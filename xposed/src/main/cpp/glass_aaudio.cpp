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
static void* g_hook_stub = nullptr;

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

    Decision decision = static_cast<Decision>(g_decision.load(std::memory_order_relaxed));
    if (decision == Decision::REAL_MIC) {
        return g_orig_AAudioStream_read(stream, buffer, numFrames, timeoutNanos);
    }

    int32_t stream_channels = AAudioStream_getChannelCount(stream);
    if (stream_channels <= 0) stream_channels = 1;
    int32_t stream_sample_rate = AAudioStream_getSampleRate(stream);
    if (stream_sample_rate <= 0) stream_sample_rate = 48'000;
    aaudio_format_t stream_fmt = AAudioStream_getFormat(stream);

    if (decision == Decision::SILENCE) {
        std::memset(
            buffer,
            0,
            static_cast<size_t>(numFrames) * stream_channels * bytes_per_dst_sample(stream_fmt)
        );
        g_pending_reads.fetch_add(1, std::memory_order_relaxed);
        g_pending_bytes.fetch_add(static_cast<uint64_t>(numFrames) * stream_channels * 2, std::memory_order_relaxed);
        g_last_sr.store(stream_sample_rate, std::memory_order_relaxed);
        g_last_ch.store(stream_channels, std::memory_order_relaxed);
        return numFrames;
    }

    int fd = -1;
    int32_t src_channels = 1;
    int32_t src_sample_rate = 48'000;
    {
        std::lock_guard<std::mutex> lock(g_fd_mutex);
        fd = g_fd_state.fd;
        src_channels = g_fd_state.channels > 0 ? g_fd_state.channels : 1;
        src_sample_rate = g_fd_state.sample_rate > 0 ? g_fd_state.sample_rate : 48'000;
    }

    if (fd < 0) {
        return g_orig_AAudioStream_read(stream, buffer, numFrames, timeoutNanos);
    }

    int32_t need_src_frames = static_cast<int32_t>(
        (static_cast<int64_t>(numFrames) * src_sample_rate + stream_sample_rate - 1) / stream_sample_rate
    );
    if (need_src_frames <= 0) need_src_frames = numFrames;

    int need_bytes = need_src_frames * src_channels * 2;
    if (need_bytes > 32 * 1024) {
        return g_orig_AAudioStream_read(stream, buffer, numFrames, timeoutNanos);
    }

    uint8_t tmp[32 * 1024];
    int got = read_full(fd, tmp, need_bytes);
    if (got <= 0) {
        return g_orig_AAudioStream_read(stream, buffer, numFrames, timeoutNanos);
    }

    int got_frames = got / (src_channels * 2);
    convert_and_write(
        buffer,
        stream_fmt,
        stream_channels,
        reinterpret_cast<int16_t*>(tmp),
        got_frames,
        src_channels,
        numFrames
    );

    g_pending_reads.fetch_add(1, std::memory_order_relaxed);
    g_pending_bytes.fetch_add(static_cast<uint64_t>(got), std::memory_order_relaxed);
    g_last_sr.store(stream_sample_rate, std::memory_order_relaxed);
    g_last_ch.store(stream_channels, std::memory_order_relaxed);

    return numFrames;
}

bool install_aaudio_hook() {
    bool expected = false;
    if (!g_hook_installed.compare_exchange_strong(expected, true)) {
        return true;
    }

    g_hook_stub = shadowhook_hook_sym_name(
        "libaaudio.so",
        "AAudioStream_read",
        reinterpret_cast<void*>(my_AAudioStream_read),
        reinterpret_cast<void**>(&g_orig_AAudioStream_read)
    );
    if (!g_hook_stub) {
        int err = shadowhook_get_errno();
        const char* msg = shadowhook_to_errmsg(err);
        LOGE("hook AAudioStream_read failed: err=%d %s", err, msg ? msg : "?");
        g_hook_installed.store(false);
        return false;
    }

    LOGI("AAudioStream_read hook installed");
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
