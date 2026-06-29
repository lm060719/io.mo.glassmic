#include "glass_aaudio.h"
#include "glass_log.h"

#include <aaudio/AAudio.h>
#include <shadowhook.h>

#include <atomic>
#include <cerrno>
#include <cstring>
#include <mutex>
#include <unistd.h>

namespace glass {

static std::atomic<int32_t> g_decision{static_cast<int32_t>(Decision::REAL_MIC)};
static std::atomic<bool> g_hook_installed{false};

struct PcmFdState {
    int fd = -1;
    int32_t sample_rate = 0;
    int32_t channels = 0;
};

static std::mutex g_fd_mutex;
static PcmFdState g_fd_state;

static std::atomic<uint64_t> g_pending_reads{0};
static std::atomic<uint64_t> g_pending_bytes{0};
static std::atomic<int32_t> g_last_sr{0};
static std::atomic<int32_t> g_last_ch{0};

using AAudioStream_read_fn = aaudio_result_t(*)(AAudioStream*, void*, int32_t, int64_t);
static AAudioStream_read_fn g_orig_AAudioStream_read = nullptr;
static void* g_read_hook_stub = nullptr;

// ---- callback 模式 hook 用 ----
using AAudioStreamBuilder_setDataCallback_fn =
    void(*)(AAudioStreamBuilder*, AAudioStream_dataCallback, void*);
static AAudioStreamBuilder_setDataCallback_fn g_orig_setDataCallback = nullptr;
static void* g_setcb_hook_stub = nullptr;

static int32_t bytes_per_dst_sample(aaudio_format_t fmt) {
    switch (fmt) {
        case AAUDIO_FORMAT_PCM_I16:        return 2;
        case AAUDIO_FORMAT_PCM_FLOAT:      return 4;
        case AAUDIO_FORMAT_PCM_I32:        return 4;
        case AAUDIO_FORMAT_PCM_I24_PACKED: return 3;
        default:                           return 2;
    }
}

static int32_t map_src_frame(int32_t dst_frame, int32_t src_frames, int32_t dst_frames) {
    if (src_frames <= 1 || dst_frames <= 1) return 0;
    int32_t mapped = static_cast<int32_t>(
        (static_cast<int64_t>(dst_frame) * src_frames) / dst_frames
    );
    return mapped >= src_frames ? src_frames - 1 : mapped;
}

static void convert_and_write(
    void* dst,
    aaudio_format_t fmt,
    int32_t dst_channels,
    const int16_t* src,
    int32_t src_frames,
    int32_t src_channels,
    int32_t dst_frames
) {
    if (!dst || !src || src_frames <= 0 || dst_frames <= 0 || src_channels <= 0 || dst_channels <= 0) {
        return;
    }

    switch (fmt) {
        case AAUDIO_FORMAT_PCM_I16: {
            auto* d = static_cast<int16_t*>(dst);
            for (int32_t f = 0; f < dst_frames; ++f) {
                int32_t si = map_src_frame(f, src_frames, dst_frames);
                int16_t lv = src[si * src_channels];
                int16_t rv = src_channels > 1 ? src[si * src_channels + 1] : lv;
                for (int32_t c = 0; c < dst_channels; ++c) {
                    *d++ = c == 0 ? lv : (c == 1 ? rv : 0);
                }
            }
            break;
        }
        case AAUDIO_FORMAT_PCM_FLOAT: {
            auto* d = static_cast<float*>(dst);
            constexpr float kScale = 1.0f / 32768.0f;
            for (int32_t f = 0; f < dst_frames; ++f) {
                int32_t si = map_src_frame(f, src_frames, dst_frames);
                float lv = src[si * src_channels] * kScale;
                float rv = src_channels > 1 ? src[si * src_channels + 1] * kScale : lv;
                for (int32_t c = 0; c < dst_channels; ++c) {
                    *d++ = c == 0 ? lv : (c == 1 ? rv : 0.0f);
                }
            }
            break;
        }
        case AAUDIO_FORMAT_PCM_I32: {
            auto* d = static_cast<int32_t*>(dst);
            for (int32_t f = 0; f < dst_frames; ++f) {
                int32_t si = map_src_frame(f, src_frames, dst_frames);
                int32_t lv = static_cast<int32_t>(src[si * src_channels]) << 16;
                int32_t rv = src_channels > 1
                    ? static_cast<int32_t>(src[si * src_channels + 1]) << 16
                    : lv;
                for (int32_t c = 0; c < dst_channels; ++c) {
                    *d++ = c == 0 ? lv : (c == 1 ? rv : 0);
                }
            }
            break;
        }
        case AAUDIO_FORMAT_PCM_I24_PACKED: {
            auto* d = static_cast<uint8_t*>(dst);
            for (int32_t f = 0; f < dst_frames; ++f) {
                int32_t si = map_src_frame(f, src_frames, dst_frames);
                int32_t lv = static_cast<int32_t>(src[si * src_channels]) << 8;
                int32_t rv = src_channels > 1
                    ? static_cast<int32_t>(src[si * src_channels + 1]) << 8
                    : lv;
                for (int32_t c = 0; c < dst_channels; ++c) {
                    int32_t v = c == 0 ? lv : (c == 1 ? rv : 0);
                    *d++ = static_cast<uint8_t>(v & 0xFF);
                    *d++ = static_cast<uint8_t>((v >> 8) & 0xFF);
                    *d++ = static_cast<uint8_t>((v >> 16) & 0xFF);
                }
            }
            break;
        }
        default:
            std::memset(dst, 0, static_cast<size_t>(dst_frames) * dst_channels * bytes_per_dst_sample(fmt));
            break;
    }
}

static int read_full(int fd, uint8_t* buf, int n) {
    int got = 0;
    while (got < n) {
        ssize_t r = ::read(fd, buf + got, n - got);
        if (r > 0) {
            got += static_cast<int>(r);
        } else if (r == 0) {
            break;
        } else {
            if (errno == EINTR) continue;
            return got > 0 ? got : -1;
        }
    }
    return got;
}

/**
 * 把虚拟音源填进一个已知格式的输入缓冲。
 *
 * 返回值：
 *   FillResult::FILLED   —— buffer 已被虚拟数据覆盖
 *   FillResult::PASS     —— 当前应放行真实麦克风（REAL_MIC，或 FILE 但暂无可读数据）
 *
 * 同时累计劫持统计。read() 路径与 data-callback 路径共用本函数。
 */
enum class FillResult { FILLED, PASS };

/**
 * 不依赖 AAudioStream 的底层填充：把虚拟音源按给定 PCM 格式写进 buffer。
 * AAudio（read / data-callback）与 OpenSL ES 两条路径共用本函数。
 */
static FillResult fill_pcm_impl(
    void* buffer,
    aaudio_format_t dst_fmt,
    int32_t dst_channels,
    int32_t dst_sample_rate,
    int32_t numFrames
) {
    if (!buffer || numFrames <= 0 || dst_channels <= 0) return FillResult::PASS;

    Decision decision = static_cast<Decision>(g_decision.load(std::memory_order_relaxed));
    if (decision == Decision::REAL_MIC) return FillResult::PASS;

    if (dst_sample_rate <= 0) dst_sample_rate = 48'000;

    if (decision == Decision::SILENCE) {
        std::memset(
            buffer,
            0,
            static_cast<size_t>(numFrames) * dst_channels * bytes_per_dst_sample(dst_fmt)
        );
        g_pending_reads.fetch_add(1, std::memory_order_relaxed);
        g_pending_bytes.fetch_add(static_cast<uint64_t>(numFrames) * dst_channels * 2, std::memory_order_relaxed);
        g_last_sr.store(dst_sample_rate, std::memory_order_relaxed);
        g_last_ch.store(dst_channels, std::memory_order_relaxed);
        return FillResult::FILLED;
    }

    // decision == FILE
    int fd = -1;
    int32_t src_channels = 1;
    int32_t src_sample_rate = 48'000;
    {
        std::lock_guard<std::mutex> lock(g_fd_mutex);
        fd = g_fd_state.fd;
        src_channels = g_fd_state.channels > 0 ? g_fd_state.channels : 1;
        src_sample_rate = g_fd_state.sample_rate > 0 ? g_fd_state.sample_rate : 48'000;
    }
    if (fd < 0) return FillResult::PASS;

    int32_t need_src_frames = static_cast<int32_t>(
        (static_cast<int64_t>(numFrames) * src_sample_rate + dst_sample_rate - 1) / dst_sample_rate
    );
    if (need_src_frames <= 0) need_src_frames = numFrames;

    int need_bytes = need_src_frames * src_channels * 2;
    if (need_bytes > 32 * 1024) return FillResult::PASS;

    uint8_t tmp[32 * 1024];
    int got = read_full(fd, tmp, need_bytes);
    if (got <= 0) return FillResult::PASS;

    int got_frames = got / (src_channels * 2);
    convert_and_write(
        buffer,
        dst_fmt,
        dst_channels,
        reinterpret_cast<int16_t*>(tmp),
        got_frames,
        src_channels,
        numFrames
    );

    g_pending_reads.fetch_add(1, std::memory_order_relaxed);
    g_pending_bytes.fetch_add(static_cast<uint64_t>(got), std::memory_order_relaxed);
    g_last_sr.store(dst_sample_rate, std::memory_order_relaxed);
    g_last_ch.store(dst_channels, std::memory_order_relaxed);
    return FillResult::FILLED;
}

static FillResult fill_input_buffer(AAudioStream* stream, void* buffer, int32_t numFrames) {
    if (!stream) return FillResult::PASS;
    int32_t ch = AAudioStream_getChannelCount(stream);
    if (ch <= 0) ch = 1;
    int32_t sr = AAudioStream_getSampleRate(stream);
    if (sr <= 0) sr = 48'000;
    aaudio_format_t fmt = AAudioStream_getFormat(stream);
    return fill_pcm_impl(buffer, fmt, ch, sr, numFrames);
}

// 导出给 OpenSL ES 路径用（见 glass_aaudio.h）。
bool fill_pcm(void* buffer, SampleFmt sf, int32_t channels, int32_t sample_rate, int32_t frames) {
    aaudio_format_t fmt = (sf == SampleFmt::FLOAT)
        ? AAUDIO_FORMAT_PCM_FLOAT
        : AAUDIO_FORMAT_PCM_I16;
    return fill_pcm_impl(buffer, fmt, channels, sample_rate, frames) == FillResult::FILLED;
}

// =================== 阻塞 read 路径 ===================

static aaudio_result_t my_AAudioStream_read(
    AAudioStream* stream,
    void* buffer,
    int32_t numFrames,
    int64_t timeoutNanos
) {
    if (!stream || !buffer || numFrames <= 0) {
        return g_orig_AAudioStream_read(stream, buffer, numFrames, timeoutNanos);
    }

    if (AAudioStream_getDirection(stream) != AAUDIO_DIRECTION_INPUT) {
        return g_orig_AAudioStream_read(stream, buffer, numFrames, timeoutNanos);
    }

    if (fill_input_buffer(stream, buffer, numFrames) == FillResult::FILLED) {
        return numFrames;
    }
    return g_orig_AAudioStream_read(stream, buffer, numFrames, timeoutNanos);
}

// =================== data-callback 路径 ===================
//
// 抖音等使用 AAudio 的回调采集模式（AAudioStreamBuilder_setDataCallback +
// LOW_LATENCY）。回调模式下数据由框架回调线程直接送进 app 的 callback，
// 完全不经过 AAudioStream_read。我们 hook setDataCallback，把 app 的 callback
// 包进 trampoline：在转交给 app 之前，把输入缓冲覆盖成虚拟音源。

struct CbWrapper {
    AAudioStream_dataCallback orig_cb;
    void* orig_ud;
};

static aaudio_data_callback_result_t my_data_callback(
    AAudioStream* stream,
    void* userData,
    void* audioData,
    int32_t numFrames
) {
    auto* w = static_cast<CbWrapper*>(userData);

    // 仅对输入流改写；输出流（播放）原样转交。
    if (stream && audioData && numFrames > 0 &&
        AAudioStream_getDirection(stream) == AAUDIO_DIRECTION_INPUT) {
        fill_input_buffer(stream, audioData, numFrames);
    }

    if (w && w->orig_cb) {
        return w->orig_cb(stream, w->orig_ud, audioData, numFrames);
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void my_setDataCallback(
    AAudioStreamBuilder* builder,
    AAudioStream_dataCallback callback,
    void* userData
) {
    // app 传 nullptr 表示改用阻塞 read 模式——必须原样转交，否则会被我们
    // 误转成 callback 模式。
    if (callback == nullptr) {
        g_orig_setDataCallback(builder, nullptr, userData);
        return;
    }

    // 每个录音会话分配一个 wrapper。wrapper 与 stream 生命周期绑定，但 AAudio
    // 不暴露释放时机，这里故意不回收——每个流仅泄漏 sizeof(CbWrapper) 字节，
    // 现实中录音流数量极少，可忽略；换取回调线程零锁、零竞争。
    auto* w = new CbWrapper{callback, userData};
    g_orig_setDataCallback(builder, &my_data_callback, w);
}

bool install_aaudio_hook() {
    bool expected = false;
    if (!g_hook_installed.compare_exchange_strong(expected, true)) {
        return true;
    }

    g_read_hook_stub = shadowhook_hook_sym_name(
        "libaaudio.so",
        "AAudioStream_read",
        reinterpret_cast<void*>(my_AAudioStream_read),
        reinterpret_cast<void**>(&g_orig_AAudioStream_read)
    );
    if (!g_read_hook_stub) {
        int err = shadowhook_get_errno();
        const char* msg = shadowhook_to_errmsg(err);
        LOGE("hook AAudioStream_read failed: err=%d %s", err, msg ? msg : "?");
        g_hook_installed.store(false);
        return false;
    }
    LOGI("AAudioStream_read hook installed");

    // data-callback 路径——失败不致命，阻塞 read 路径仍可用。
    g_setcb_hook_stub = shadowhook_hook_sym_name(
        "libaaudio.so",
        "AAudioStreamBuilder_setDataCallback",
        reinterpret_cast<void*>(my_setDataCallback),
        reinterpret_cast<void**>(&g_orig_setDataCallback)
    );
    if (!g_setcb_hook_stub) {
        int err = shadowhook_get_errno();
        const char* msg = shadowhook_to_errmsg(err);
        LOGW("hook AAudioStreamBuilder_setDataCallback failed: err=%d %s", err, msg ? msg : "?");
    } else {
        LOGI("AAudioStreamBuilder_setDataCallback hook installed");
    }

    return true;
}

void set_decision(Decision decision) {
    g_decision.store(static_cast<int32_t>(decision), std::memory_order_relaxed);
}

Decision get_decision() {
    return static_cast<Decision>(g_decision.load(std::memory_order_relaxed));
}

void set_pcm_fd(int fd, int32_t sample_rate, int32_t channels) {
    int old_fd = -1;
    {
        std::lock_guard<std::mutex> lock(g_fd_mutex);
        old_fd = g_fd_state.fd;
        g_fd_state.fd = fd;
        g_fd_state.sample_rate = sample_rate;
        g_fd_state.channels = channels > 0 ? channels : 1;
    }

    if (old_fd >= 0 && old_fd != fd) {
        ::close(old_fd);
    }
    LOGI("set_pcm_fd fd=%d sr=%d ch=%d", fd, sample_rate, channels);
}

void drain_stats(
    uint64_t* out_reads,
    uint64_t* out_bytes,
    int32_t* out_last_sr,
    int32_t* out_last_ch
) {
    if (out_reads) *out_reads = g_pending_reads.exchange(0, std::memory_order_relaxed);
    if (out_bytes) *out_bytes = g_pending_bytes.exchange(0, std::memory_order_relaxed);
    if (out_last_sr) *out_last_sr = g_last_sr.load(std::memory_order_relaxed);
    if (out_last_ch) *out_last_ch = g_last_ch.load(std::memory_order_relaxed);
}

} // namespace glass
