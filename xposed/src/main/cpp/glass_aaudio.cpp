#include "glass_aaudio.h"
#include "glass_audio_tap.h"
#include "glass_pcm_broadcast.h"
#include "glass_log.h"

#include <aaudio/AAudio.h>
#include <shadowhook.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <numeric>
#include <poll.h>
#include <sys/eventfd.h>
#include <thread>
#include <unistd.h>
#include <pthread.h>
#include <vector>

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

// fd 锁只负责 read/close；音频线程不与系统调用争锁。
// 缓冲锁只覆盖内存复制/游标更新，格式转换在锁外完成。
static std::mutex g_buffer_mutex;
static PcmBroadcastBuffer<65536> g_ring_buffer;
static uint64_t g_pcm_generation = 0;
static std::atomic<bool> g_worker_running{false};

// 唤醒用 eventfd。被注入进程里不能有常驻的毫秒级轮询：没 fd、没数据时线程必须真正睡眠。
// worker：set_pcm_fd 换 fd 时唤醒；capture：空闲的 Kotlin 轮询线程等录音开始。
static std::once_flag g_wake_fds_once;
static std::atomic<int> g_worker_wake_fd{-1};
static std::atomic<int> g_capture_wake_fd{-1};
static std::atomic<bool> g_capture_waiter{false};
// 当前 fd 已读到 EOF/错误（GlassMic 侧关闭了写端）。worker 不再 poll 它，Kotlin 侧据此重开。
static std::atomic<bool> g_fd_dead{false};

static std::atomic<uint64_t> g_pending_reads{0};
static std::atomic<uint64_t> g_pending_capture_reads{0};
static std::atomic<uint64_t> g_pending_bytes{0};
static std::atomic<int32_t> g_last_sr{0};
static std::atomic<int32_t> g_last_ch{0};
static std::atomic<uint64_t> g_pending_underruns{0};
static std::atomic<uint64_t> g_pending_missing_frames{0};
static std::atomic<uint64_t> g_pending_requested_frames{0};
static std::atomic<uint64_t> g_pending_skipped_source_frames{0};
static std::atomic<int32_t> g_last_path{0};

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

// 舒适噪声幅度：±8 LSB ≈ -72 dBFS，人耳不可闻，但足以让下游 App 的 VAD 判定"有信号"。
static constexpr int32_t kComfortAmplitude = 8;

// 轻量 xorshift32，仅用于生成极低电平噪声，无需密码学质量。每线程独立种子。
static inline int32_t next_comfort_sample() {
    static thread_local uint32_t s =
        0x9E3779B9u ^ static_cast<uint32_t>(reinterpret_cast<uintptr_t>(&s));
    s ^= s << 13; s ^= s >> 17; s ^= s << 5;
    return static_cast<int32_t>(s % (2u * kComfortAmplitude + 1u)) - kComfortAmplitude;
}

// 用舒适噪声填满输入缓冲，替代纯 0 静音
static void fill_comfort_noise(
    void* buffer, aaudio_format_t fmt, int32_t channels, int32_t numFrames
) {
    const int64_t samples = static_cast<int64_t>(numFrames) * channels;
    switch (fmt) {
        case AAUDIO_FORMAT_PCM_I16: {
            auto* d = static_cast<int16_t*>(buffer);
            for (int64_t i = 0; i < samples; ++i) *d++ = static_cast<int16_t>(next_comfort_sample());
            break;
        }
        case AAUDIO_FORMAT_PCM_FLOAT: {
            auto* d = static_cast<float*>(buffer);
            constexpr float kScale = 1.0f / 32768.0f;
            for (int64_t i = 0; i < samples; ++i) *d++ = next_comfort_sample() * kScale;
            break;
        }
        case AAUDIO_FORMAT_PCM_I32: {
            auto* d = static_cast<int32_t*>(buffer);
            for (int64_t i = 0; i < samples; ++i) *d++ = next_comfort_sample() << 16;
            break;
        }
        case AAUDIO_FORMAT_PCM_I24_PACKED: {
            auto* d = static_cast<uint8_t*>(buffer);
            for (int64_t i = 0; i < samples; ++i) {
                int32_t v = next_comfort_sample() << 8;
                *d++ = static_cast<uint8_t>(v & 0xFF);
                *d++ = static_cast<uint8_t>((v >> 8) & 0xFF);
                *d++ = static_cast<uint8_t>((v >> 16) & 0xFF);
            }
            break;
        }
        default:
            std::memset(buffer, 0,
                        static_cast<size_t>(numFrames) * channels * bytes_per_dst_sample(fmt));
            break;
    }
}

static inline float soft_limit_f(float sample) {
    constexpr float threshold = 30000.0f;
    constexpr float max_val = 32767.0f;
    float abs_val = sample < 0.0f ? -sample : sample;
    if (abs_val <= threshold) return sample;
    float over = abs_val - threshold;
    float range = max_val - threshold;
    float norm = over / range;
    float soft = (norm / (1.0f + norm)) * range;
    float res = threshold + soft;
    return sample < 0.0f ? -res : res;
}

// 抗混叠：降采样前对源 PCM 做 4 阶 Butterworth 低通（两级 biquad，截止频率 0.45×目标采样率）。
// 纯线性插值抽取会把高于目标 Nyquist 的成分折叠回可听频段（16k 语音类 App 播放音乐时尤其明显）。
// 滤波器状态按 (stream, path) 跨回调保存：每块从零开始会在块边界产生咔哒，也等于没有低通。
struct Biquad {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
};

static Biquad lowpass_biquad(double cutoff_hz, double sample_rate, double q) {
    constexpr double kPi = 3.14159265358979323846;
    const double w0 = 2.0 * kPi * cutoff_hz / sample_rate;
    const double cs = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double a0 = 1.0 + alpha;
    Biquad bq;
    bq.b0 = static_cast<float>((1.0 - cs) / 2.0 / a0);
    bq.b1 = static_cast<float>((1.0 - cs) / a0);
    bq.b2 = bq.b0;
    bq.a1 = static_cast<float>(-2.0 * cs / a0);
    bq.a2 = static_cast<float>((1.0 - alpha) / a0);
    return bq;
}

/**
 * 每条录音流（stream, path）的降采样状态：抗混叠滤波器，以及跨回调的插值相位。
 *
 * 相位：[phase] 是下一个输出样本在“下一块源数据”中的位置（单位：源帧），取值 ≥ -1；
 * -1 表示正好落在上一块的最后一帧 [prev] 上。每块只读取当前相位真正需要的源帧，
 * 采样率不成整数比（如 48k→44.1k）、回调帧数又不对齐时，不会再每块多吞一点源数据
 * 造成轻微加速，也不会在块边界产生相位跳变。
 */
struct StreamResampleState {
    const void* stream = nullptr;
    int32_t path = 0;
    int32_t src_rate = 0;
    int32_t dst_rate = 0;
    int32_t channels = 0;
    Biquad sections[2];
    float z[2][2][2] = {};  // [声道][级][延迟单元]，只处理前两个声道（转换只用到 L/R）
    bool primed = false;
    double phase = 0.0;
    int16_t prev[2] = {0, 0};
    uint64_t touched = 0;
    bool used = false;
};

// 由 g_buffer_mutex 保护；与环形缓冲的读游标同时清空（换决策 / 换 fd）。
static std::array<StreamResampleState, 32> g_resample_states{};
static uint64_t g_resample_sequence = 0;

static void reset_resample_states_locked() {
    g_resample_states = {};
    g_resample_sequence = 0;
}

static StreamResampleState& resample_state_locked(
    const void* stream, int32_t path, int32_t src_rate, int32_t dst_rate, int32_t channels
) {
    StreamResampleState* st = nullptr;
    for (auto& candidate : g_resample_states) {
        if (candidate.used && candidate.stream == stream && candidate.path == path) {
            st = &candidate;
            break;
        }
    }
    if (!st) {
        st = &*std::min_element(g_resample_states.begin(), g_resample_states.end(),
            [](const StreamResampleState& a, const StreamResampleState& b) { return a.touched < b.touched; });
        *st = StreamResampleState{};
        st->stream = stream;
        st->path = path;
        st->used = true;
    }
    if (st->src_rate != src_rate || st->dst_rate != dst_rate || st->channels != channels) {
        st->src_rate = src_rate;
        st->dst_rate = dst_rate;
        st->channels = channels;
        const double cutoff = 0.45 * dst_rate;
        // 4 阶 Butterworth 拆成两级二阶节的 Q 值。
        st->sections[0] = lowpass_biquad(cutoff, src_rate, 0.54119610);
        st->sections[1] = lowpass_biquad(cutoff, src_rate, 1.30656296);
        st->primed = false;
        st->phase = 0.0;
    }
    st->touched = ++g_resample_sequence;
    return *st;
}

/** 原地低通 [frames] 帧交织 PCM16。转置直接 II 型，每样本两级共 10 次乘加。 */
static void anti_alias_in_place(StreamResampleState& st, int16_t* pcm, int32_t frames, int32_t channels) {
    if (!pcm || frames <= 0 || channels <= 0) return;
    const int32_t filtered_channels = std::min<int32_t>(channels, 2);
    if (!st.primed) {
        // 用首帧把延迟单元预置为直流稳态，避免录音开头从 0 爬升产生的咔哒。
        for (int32_t c = 0; c < filtered_channels; ++c) {
            const float x0 = static_cast<float>(pcm[c]);
            for (int s = 0; s < 2; ++s) {
                const Biquad& bq = st.sections[s];
                st.z[c][s][1] = (bq.b2 - bq.a2) * x0;
                st.z[c][s][0] = (bq.b1 - bq.a1) * x0 + st.z[c][s][1];
            }
        }
        st.primed = true;
    }
    for (int32_t f = 0; f < frames; ++f) {
        for (int32_t c = 0; c < filtered_channels; ++c) {
            int16_t& sample = pcm[f * channels + c];
            float x = static_cast<float>(sample);
            for (int s = 0; s < 2; ++s) {
                const Biquad& bq = st.sections[s];
                float* z = st.z[c][s];
                const float y = bq.b0 * x + z[0];
                z[0] = bq.b1 * x - bq.a1 * y + z[1];
                z[1] = bq.b2 * x - bq.a2 * y;
                x = y;
            }
            const float rounded = x < 0.0f ? x - 0.5f : x + 0.5f;
            sample = static_cast<int16_t>(std::max(-32768.0f, std::min(32767.0f, rounded)));
        }
    }
}

/**
 * 按目标采样率线性插值（降采样时调用方已先做抗混叠低通），逐帧交给 [emit] 写成目标格式。
 * 第 f 个输出位于源位置 start_pos + f×src/dst；位置落在 [-1, 0) 时与上一块末帧 [prev] 插值。
 * 不足部分补零；不能把短读数据拉伸到整块目标时长。
 * [limit] 为 true 时对整数格式做软限幅；浮点格式保留原值。
 */
template <typename Emit>
static void convert_frames(
    uint8_t* d,
    int32_t dst_channels,
    int32_t dst_sample_rate,
    const int16_t* src,
    int32_t src_frames,
    int32_t src_channels,
    int32_t src_sample_rate,
    int32_t valid_dst_frames,
    int32_t dst_frames,
    bool limit,
    double start_pos,
    const int16_t* prev,
    Emit emit
) {
    // 索引 -1 指上一块的末帧；没有 prev 时退回本块首帧（start_pos 此时不会为负）。
    const auto at = [&](int32_t i, int32_t c) -> float {
        if (i < 0) return static_cast<float>(prev ? prev[c] : src[c]);
        return static_cast<float>(src[i * src_channels + c]);
    };
    for (int32_t f = 0; f < valid_dst_frames; ++f) {
        const double src_pos = start_pos + (static_cast<double>(f) * src_sample_rate) / dst_sample_rate;
        int32_t i0 = static_cast<int32_t>(std::floor(src_pos));
        const float frac = static_cast<float>(src_pos - i0);
        if (i0 >= src_frames) i0 = src_frames - 1;
        if (i0 < -1) i0 = -1;
        const int32_t i1 = (i0 + 1 < src_frames) ? i0 + 1 : i0;

        const float lv0 = at(i0, 0);
        const float lv1 = at(i1, 0);
        float lv = lv0 + (lv1 - lv0) * frac;
        float rv = lv;
        if (src_channels > 1) {
            const float rv0 = at(i0, 1);
            const float rv1 = at(i1, 1);
            rv = rv0 + (rv1 - rv0) * frac;
        }
        if (limit) {
            lv = soft_limit_f(lv);
            rv = soft_limit_f(rv);
        }
        // 欠载时在有效末尾极短淡出（16 帧），消除硬截断导致的咔哒杂音
        if (valid_dst_frames < dst_frames && valid_dst_frames - f <= 16) {
            const float fade = static_cast<float>(valid_dst_frames - f) / 16.0f;
            lv *= fade;
            rv *= fade;
        }
        for (int32_t c = 0; c < dst_channels; ++c) {
            emit(d, c == 0 ? lv : (c == 1 ? rv : 0.0f));
        }
    }
    // 欠载不足部分静音填充，避免拼接白噪声切片
    for (int32_t f = valid_dst_frames; f < dst_frames; ++f) {
        for (int32_t c = 0; c < dst_channels; ++c) emit(d, 0.0f);
    }
}

/** 把 PCM16 源数据转换为目标格式写入 dst；样本值以 PCM16 刻度的 float 传给各格式的写出函数。 */
static void convert_and_write(
    void* dst,
    aaudio_format_t fmt,
    int32_t dst_channels,
    int32_t dst_sample_rate,
    const int16_t* src,
    int32_t src_frames,
    int32_t src_channels,
    int32_t src_sample_rate,
    int32_t dst_frames,
    double start_pos = 0.0,
    const int16_t* prev = nullptr,
    int32_t valid_override = -1
) {
    if (!dst || dst_frames <= 0 || dst_channels <= 0) return;

    // 计算实际可转换的有效目标帧数（带相位的降采样路径由调用方算好传入）
    int32_t valid = 0;
    if (src && src_frames > 0 && src_channels > 0 && src_sample_rate > 0 && dst_sample_rate > 0) {
        valid = valid_override >= 0 ? valid_override : static_cast<int32_t>(
            (static_cast<int64_t>(src_frames) * dst_sample_rate) / src_sample_rate
        );
        if (valid > dst_frames) valid = dst_frames;
    }

    auto* d = static_cast<uint8_t*>(dst);
    switch (fmt) {
        case AAUDIO_FORMAT_PCM_I16:
            convert_frames(d, dst_channels, dst_sample_rate, src, src_frames, src_channels,
                src_sample_rate, valid, dst_frames, true, start_pos, prev, [](uint8_t*& out, float v) {
                    const int16_t s = static_cast<int16_t>(v);
                    std::memcpy(out, &s, sizeof(s));
                    out += sizeof(s);
                });
            break;
        case AAUDIO_FORMAT_PCM_FLOAT:
            convert_frames(d, dst_channels, dst_sample_rate, src, src_frames, src_channels,
                src_sample_rate, valid, dst_frames, false, start_pos, prev, [](uint8_t*& out, float v) {
                    const float s = v * (1.0f / 32768.0f);
                    std::memcpy(out, &s, sizeof(s));
                    out += sizeof(s);
                });
            break;
        case AAUDIO_FORMAT_PCM_I32:
            convert_frames(d, dst_channels, dst_sample_rate, src, src_frames, src_channels,
                src_sample_rate, valid, dst_frames, true, start_pos, prev, [](uint8_t*& out, float v) {
                    const int32_t s = static_cast<int32_t>(v) * 65536;
                    std::memcpy(out, &s, sizeof(s));
                    out += sizeof(s);
                });
            break;
        case AAUDIO_FORMAT_PCM_I24_PACKED:
            convert_frames(d, dst_channels, dst_sample_rate, src, src_frames, src_channels,
                src_sample_rate, valid, dst_frames, true, start_pos, prev, [](uint8_t*& out, float v) {
                    const int32_t s = static_cast<int32_t>(v) * 256;
                    *out++ = static_cast<uint8_t>(s & 0xFF);
                    *out++ = static_cast<uint8_t>((s >> 8) & 0xFF);
                    *out++ = static_cast<uint8_t>((s >> 16) & 0xFF);
                });
            break;
        default:
            std::memset(dst, 0, static_cast<size_t>(dst_frames) * dst_channels * bytes_per_dst_sample(fmt));
            break;
    }
}

static void init_wake_fds() {
    std::call_once(g_wake_fds_once, [] {
        g_worker_wake_fd.store(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK));
        g_capture_wake_fd.store(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK));
        if (g_worker_wake_fd.load() < 0 || g_capture_wake_fd.load() < 0)
            LOGW("eventfd failed: %s; falling back to timed polling", strerror(errno));
    });
}

static inline void signal_eventfd(int fd) {
    if (fd < 0) return;
    const uint64_t one = 1;
    (void)::write(fd, &one, sizeof(one));
}

static inline void drain_eventfd(int fd) {
    if (fd < 0) return;
    uint64_t value = 0;
    while (::read(fd, &value, sizeof(value)) > 0) {}
}

// 后台异步管道读取循环：poll 阻塞等数据或换 fd 的通知，空闲时零唤醒。
static void pcm_reader_worker_loop() {
    pthread_setname_np(pthread_self(), "GlassPcmWorker");
    uint8_t chunk[2048];
    const int wake_fd = g_worker_wake_fd.load();
    // 没有 eventfd 时换 fd 无法唤醒 poll，只能退化为 20ms 超时（仍远低于原先的 1ms 轮询）。
    const int timeout_ms = wake_fd >= 0 ? -1 : 20;
    while (g_worker_running.load(std::memory_order_relaxed)) {
        int fd = -1;
        {
            std::lock_guard<std::mutex> lock(g_fd_mutex);
            if (!g_fd_dead.load(std::memory_order_relaxed)) fd = g_fd_state.fd;
        }
        // poll 忽略负数 fd，因此两项可以固定传入。
        pollfd fds[2] = {{wake_fd, POLLIN, 0}, {fd, POLLIN, 0}};
        const int ready = ::poll(fds, 2, timeout_ms);
        if (ready < 0) {
            if (errno != EINTR) usleep(20'000);  // 防御：poll 异常时也不能退化成忙等
            continue;
        }
        if (fds[0].revents & POLLIN) drain_eventfd(wake_fd);
        if (fd < 0 || fds[1].revents == 0) continue;

        // 持 fd 锁读：set_pcm_fd 不会 close/reuse 一个正在 read 的 fd；fd 是非阻塞的。
        std::lock_guard<std::mutex> lock(g_fd_mutex);
        if (g_fd_state.fd != fd) continue;  // poll 期间已换 fd，回到循环头用新 fd
        while (true) {
            const ssize_t r = ::read(fd, chunk, sizeof(chunk));
            if (r > 0) {
                std::lock_guard<std::mutex> buffer_lock(g_buffer_mutex);
                g_ring_buffer.write(chunk, static_cast<size_t>(r));
                if (static_cast<size_t>(r) < sizeof(chunk)) break;  // 管道已读空
                continue;
            }
            if (r < 0 && errno == EINTR) continue;
            if (r < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) break;
            // EOF（写端已关闭）或读错误：停止 poll 这个 fd，否则 POLLHUP 会让 poll 立即返回、
            // 线程空转。Kotlin 轮询通过 pcm_fd_alive() 发现后关闭并按需重开。
            g_fd_dead.store(true, std::memory_order_relaxed);
            LOGI("PCM fd=%d closed by provider (r=%zd)", fd, r);
            break;
        }
    }
}

static void ensure_worker_started() {
    init_wake_fds();
    bool expected = false;
    if (g_worker_running.compare_exchange_strong(expected, true)) {
        std::thread t(pcm_reader_worker_loop);
        t.detach();
    }
}

/**
 * 把虚拟音源填进一个已知格式的输入缓冲。
 *
 * 返回值：
 *   FillResult::FILLED   —— buffer 已被虚拟数据覆盖
 *   FillResult::PASS     —— 当前应放行真实麦克风（REAL_MIC）
 */
enum class FillResult { FILLED, PASS };

static FillResult fill_pcm_impl(
    void* buffer,
    aaudio_format_t dst_fmt,
    int32_t dst_channels,
    int32_t dst_sample_rate,
    int32_t numFrames,
    CapturePath path,
    const void* stream_id = nullptr
) {
    if (!buffer || numFrames <= 0 || dst_channels <= 0) return FillResult::PASS;

    note_capture(path);
    Decision decision = static_cast<Decision>(g_decision.load(std::memory_order_relaxed));
    if (decision == Decision::REAL_MIC) return FillResult::PASS;

    if (dst_sample_rate <= 0) dst_sample_rate = 48'000;
    g_last_path.store(static_cast<int32_t>(path), std::memory_order_relaxed);

    if (decision == Decision::SILENCE) {
        fill_comfort_noise(buffer, dst_fmt, dst_channels, numFrames);
        g_pending_reads.fetch_add(1, std::memory_order_relaxed);
        g_pending_bytes.fetch_add(static_cast<uint64_t>(numFrames) * dst_channels * 2, std::memory_order_relaxed);
        g_last_sr.store(dst_sample_rate, std::memory_order_relaxed);
        g_last_ch.store(dst_channels, std::memory_order_relaxed);
        return FillResult::FILLED;
    }

    // decision == FILE: 回调不得等待 worker / 切源线程持有的锁。
    std::unique_lock<std::mutex> lock(g_buffer_mutex, std::try_to_lock);
    g_pending_requested_frames.fetch_add(numFrames, std::memory_order_relaxed);
    int32_t src_channels = 1;
    int32_t src_sample_rate = 48'000;
    uint64_t generation = 0;
    if (lock.owns_lock()) {
        src_channels = g_fd_state.channels > 0 ? g_fd_state.channels : 1;
        src_sample_rate = g_fd_state.sample_rate > 0 ? g_fd_state.sample_rate : 48'000;
        generation = g_pcm_generation;
    }

    // 大请求分块转换，不能把超过栈缓冲容量的整段音频直接补零。
    // 降采样：按每条流保存的相位读取恰好需要的源帧（见 StreamResampleState），块间连续；
    // 为相位进位预留 2 帧余量。其余情况：中间块按采样率公约数对齐，避免每块向上取整多吞源帧。
    const bool downsample = dst_sample_rate < src_sample_rate;
    const double ratio = static_cast<double>(src_sample_rate) / dst_sample_rate;
    int16_t tmp[8192];
    const int32_t max_src_frames = 8192 / src_channels;
    const int32_t quantum = dst_sample_rate / std::gcd(src_sample_rate, dst_sample_rate);
    const int32_t max_dst_frames = static_cast<int32_t>(
        static_cast<int64_t>(downsample ? max_src_frames - 2 : max_src_frames) * dst_sample_rate / src_sample_rate);
    const int32_t block_frames = downsample ? max_dst_frames : (max_dst_frames / quantum) * quantum;
    uint64_t got = 0;
    int32_t missing = 0;
    int32_t offset = 0;
    while (offset < numFrames) {
        const int32_t count = std::min(numFrames - offset,
            block_frames > 0 ? block_frames : max_dst_frames);
        if (count <= 0) break;
        int32_t need = static_cast<int32_t>(
            (static_cast<int64_t>(count) * src_sample_rate + dst_sample_rate - 1) / dst_sample_rate);
        const size_t frame_bytes = static_cast<size_t>(src_channels) * 2;
        size_t bytes = 0;
        // 相位路径的插值参数：在锁内取出并推进状态，锁外只用这份拷贝做格式转换。
        bool phased = false;
        double start_pos = 0.0;
        int16_t prev[2] = {0, 0};
        int32_t phased_valid = 0;
        if (lock.owns_lock()) {
            if (generation == g_pcm_generation) {
                StreamResampleState* rs = nullptr;
                if (downsample) {
                    rs = &resample_state_locked(stream_id, static_cast<int32_t>(path),
                        src_sample_rate, dst_sample_rate, src_channels);
                    // 输出位置 phase + k×ratio（k < count）都要落在 [-1, need-1) 内。
                    need = static_cast<int32_t>(std::floor(rs->phase + count * ratio)) + 1;
                    if (need < 0) need = 0;
                }
                // 新录音流保留至少一个完整请求或 80ms；现有流保留独立进度。
                const size_t request_frames = static_cast<size_t>(
                    (static_cast<int64_t>(numFrames) * src_sample_rate + dst_sample_rate - 1) / dst_sample_rate);
                const size_t join_bytes = std::max(request_frames,
                    static_cast<size_t>(src_sample_rate) * 80 / 1000) * frame_bytes;
                uint64_t skipped_bytes = 0;
                bytes = g_ring_buffer.read(reinterpret_cast<uint8_t*>(tmp),
                    static_cast<size_t>(need) * frame_bytes, stream_id,
                    static_cast<int32_t>(path), frame_bytes, join_bytes, &skipped_bytes);
                if (skipped_bytes > 0) g_pending_skipped_source_frames.fetch_add(
                    skipped_bytes / frame_bytes, std::memory_order_relaxed);
                // 状态表与读游标同受此锁保护；单块最多 8192 个样本，耗时在微秒级。
                if (rs) {
                    const int32_t m = static_cast<int32_t>(bytes / frame_bytes);
                    if (m > 0) anti_alias_in_place(*rs, tmp, m, src_channels);
                    phased = true;
                    start_pos = rs->phase;
                    prev[0] = rs->prev[0];
                    prev[1] = rs->prev[1];
                    // 能产出的帧：位置 < m-1（右侧插值点必须已读到）。读满时恰为 count。
                    const double span = (m - 1) - start_pos;
                    phased_valid = span > 0.0
                        ? static_cast<int32_t>(std::min<double>(count, std::ceil(span / ratio)))
                        : 0;
                    rs->phase = start_pos + phased_valid * ratio - m;
                    if (rs->phase < -1.0) rs->phase = -1.0;  // 浮点误差兜底
                    if (m > 0) {
                        for (int32_t c = 0; c < std::min<int32_t>(src_channels, 2); ++c)
                            rs->prev[c] = tmp[(m - 1) * src_channels + c];
                    }
                }
            }
            lock.unlock();
        }
        const int32_t frames = static_cast<int32_t>(bytes / frame_bytes);
        auto* dst = static_cast<uint8_t*>(buffer) +
            static_cast<size_t>(offset) * dst_channels * bytes_per_dst_sample(dst_fmt);
        convert_and_write(dst, dst_fmt, dst_channels, dst_sample_rate,
            tmp, frames, src_channels, src_sample_rate, count,
            start_pos, phased ? prev : nullptr, phased ? phased_valid : -1);
        const int32_t valid = phased ? phased_valid : static_cast<int32_t>(std::min<int64_t>(count,
            static_cast<int64_t>(frames) * dst_sample_rate / src_sample_rate));
        missing += count - valid;
        got += bytes;
        offset += count;
        if (offset < numFrames) (void)lock.try_lock();
    }
    if (offset < numFrames) {
        auto* dst = static_cast<uint8_t*>(buffer) +
            static_cast<size_t>(offset) * dst_channels * bytes_per_dst_sample(dst_fmt);
        std::memset(dst, 0, static_cast<size_t>(numFrames - offset) * dst_channels * bytes_per_dst_sample(dst_fmt));
        missing += numFrames - offset;
    }
    if (missing > 0) {
        g_pending_underruns.fetch_add(1, std::memory_order_relaxed);
        g_pending_missing_frames.fetch_add(missing, std::memory_order_relaxed);
    }

    tap_capture(buffer, dst_fmt, dst_channels, dst_sample_rate, numFrames,
        static_cast<int32_t>(path), stream_id, missing);

    g_pending_reads.fetch_add(1, std::memory_order_relaxed);
    g_pending_bytes.fetch_add(static_cast<uint64_t>(got), std::memory_order_relaxed);
    g_last_sr.store(dst_sample_rate, std::memory_order_relaxed);
    g_last_ch.store(dst_channels, std::memory_order_relaxed);
    return FillResult::FILLED;
}

static FillResult fill_input_buffer(AAudioStream* stream, void* buffer, int32_t numFrames, CapturePath path) {
    if (!stream) return FillResult::PASS;
    int32_t ch = AAudioStream_getChannelCount(stream);
    if (ch <= 0) ch = 1;
    int32_t sr = AAudioStream_getSampleRate(stream);
    if (sr <= 0) sr = 48'000;
    aaudio_format_t fmt = AAudioStream_getFormat(stream);
    return fill_pcm_impl(buffer, fmt, ch, sr, numFrames, path, stream);
}

// 导出给 OpenSL ES / AudioRecord 路径用
bool fill_pcm(void* buffer, SampleFmt sf, int32_t channels, int32_t sample_rate, int32_t frames, CapturePath path, const void* stream_id) {
    aaudio_format_t fmt = (sf == SampleFmt::FLOAT)
        ? AAUDIO_FORMAT_PCM_FLOAT
        : AAUDIO_FORMAT_PCM_I16;
    return fill_pcm_impl(buffer, fmt, channels, sample_rate, frames, path, stream_id) == FillResult::FILLED;
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

    // 保留系统录音时钟、超时、短读和错误语义，只覆盖实际返回的帧。
    // 否则紧循环会耗尽实时 PCM，并把大量补零帧发送给语音引擎。
    const bool was_reading = aaudio_read_in_progress;
    aaudio_read_in_progress = true;
    const aaudio_result_t frames = g_orig_AAudioStream_read(stream, buffer, numFrames, timeoutNanos);
    aaudio_read_in_progress = was_reading;
    if (frames > 0) fill_input_buffer(stream, buffer, frames, CapturePath::AAUDIO_READ);
    return frames;
}

// =================== data-callback 路径 ===================

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

    if (stream && audioData && numFrames > 0 &&
        AAudioStream_getDirection(stream) == AAUDIO_DIRECTION_INPUT) {
        fill_input_buffer(stream, audioData, numFrames, CapturePath::AAUDIO_CALLBACK);
    }

    if (w && w->orig_cb) {
        return w->orig_cb(stream, w->orig_ud, audioData, numFrames);
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

// wrapper 的生命周期必须覆盖由该 builder 打开的所有 stream，而 AAudio 不告诉我们 stream
// 何时关闭，所以无法安全释放。按 (callback, userData) 复用：同一对组合只分配一次，
// 反复建流的 App 不会无界泄漏。
static std::mutex g_cb_wrappers_mutex;
static std::vector<CbWrapper*> g_cb_wrappers;

static CbWrapper* wrapper_for(AAudioStream_dataCallback callback, void* userData) {
    std::lock_guard<std::mutex> lock(g_cb_wrappers_mutex);
    for (auto* w : g_cb_wrappers) {
        if (w->orig_cb == callback && w->orig_ud == userData) return w;
    }
    auto* w = new CbWrapper{callback, userData};
    g_cb_wrappers.push_back(w);
    return w;
}

static void my_setDataCallback(
    AAudioStreamBuilder* builder,
    AAudioStream_dataCallback callback,
    void* userData
) {
    if (callback == nullptr) {
        g_orig_setDataCallback(builder, nullptr, userData);
        return;
    }

    g_orig_setDataCallback(builder, &my_data_callback, wrapper_for(callback, userData));
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

    ensure_worker_started();
    return true;
}

void set_decision(Decision decision) {
    // 相同决策的轮询无需争用音频缓冲锁。
    if (g_decision.load(std::memory_order_relaxed) == static_cast<int32_t>(decision)) return;
    int32_t new_d = static_cast<int32_t>(decision);
    int32_t old_d;
    {
        std::lock_guard<std::mutex> lock(g_buffer_mutex);
        old_d = g_decision.exchange(new_d, std::memory_order_relaxed);
        if (old_d != new_d) {
            g_ring_buffer.clear();
            reset_resample_states_locked();
            ++g_pcm_generation;
        }
    }
    if (old_d != new_d) LOGI("decision changed %d -> %d, ring buffer cleared", old_d, new_d);
}

Decision get_decision() {
    return static_cast<Decision>(g_decision.load(std::memory_order_relaxed));
}

void note_capture(CapturePath path) {
    g_last_path.store(static_cast<int32_t>(path), std::memory_order_relaxed);
    // seq_cst 与 wait_for_capture 构成 Dekker 式配对：要么等待方看到计数，要么这里看到等待标志。
    g_pending_capture_reads.fetch_add(1, std::memory_order_seq_cst);
    // 只有空闲等待中的轮询线程才需要唤醒；录音期间这里只是一次原子读，不做系统调用。
    if (g_capture_waiter.load(std::memory_order_seq_cst) &&
        g_capture_waiter.exchange(false, std::memory_order_seq_cst)) {
        signal_eventfd(g_capture_wake_fd.load(std::memory_order_relaxed));
    }
}

bool wait_for_capture(int32_t timeout_ms) {
    init_wake_fds();
    const int fd = g_capture_wake_fd.load();
    if (fd < 0) {
        usleep(static_cast<useconds_t>(std::min<int32_t>(timeout_ms, 80)) * 1000);
        return false;
    }
    drain_eventfd(fd);
    g_capture_waiter.store(true, std::memory_order_seq_cst);
    // 置位前录音可能已经开始，此时不能睡。
    if (g_pending_capture_reads.load(std::memory_order_seq_cst) > 0) {
        g_capture_waiter.store(false, std::memory_order_relaxed);
        return true;
    }
    pollfd p{fd, POLLIN, 0};
    const int ready = ::poll(&p, 1, timeout_ms);
    g_capture_waiter.store(false, std::memory_order_relaxed);
    drain_eventfd(fd);
    return ready > 0;
}

bool pcm_fd_alive() {
    std::lock_guard<std::mutex> lock(g_fd_mutex);
    return g_fd_state.fd >= 0 && !g_fd_dead.load(std::memory_order_relaxed);
}

void set_pcm_fd(int fd, int32_t sample_rate, int32_t channels) {
    ensure_worker_started();
    if (fd >= 0) {
        const int flags = fcntl(fd, F_GETFL);
        if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
            LOGW("cannot make PCM fd nonblocking: %s", strerror(errno));
            ::close(fd);
            fd = -1;
        }
    }
    if (fd >= 0) {
#if defined(F_SETPIPE_SZ)
        int ret = fcntl(fd, F_SETPIPE_SZ, 8192);
        if (ret < 0) {
            LOGW("fcntl F_SETPIPE_SZ 8192 failed on fd=%d: %s", fd, strerror(errno));
        } else {
            LOGI("pipe buffer size resized to %d on fd=%d", ret, fd);
        }
#endif
    }

    int old_fd = -1;
    {
        std::lock_guard<std::mutex> lock(g_fd_mutex);
        {
            std::lock_guard<std::mutex> buffer_lock(g_buffer_mutex);
            old_fd = g_fd_state.fd;
            g_fd_state.fd = fd;
            g_fd_state.sample_rate = sample_rate;
            g_fd_state.channels = channels > 0 ? channels : 1;
            g_ring_buffer.clear();
            reset_resample_states_locked();
            ++g_pcm_generation;
        }
        g_fd_dead.store(false, std::memory_order_relaxed);
        if (old_fd >= 0 && old_fd != fd) ::close(old_fd);
    }
    // worker 可能正阻塞在旧 fd（或无 fd）的 poll 上，唤醒它改用新 fd。
    signal_eventfd(g_worker_wake_fd.load());
    LOGI("set_pcm_fd fd=%d sr=%d ch=%d (ring buffer cleared)", fd, sample_rate, channels);
}

void drain_stats(
    uint64_t* out_reads,
    uint64_t* out_bytes,
    int32_t* out_last_sr,
    int32_t* out_last_ch,
    uint64_t* out_underruns,
    uint64_t* out_missing_frames,
    uint64_t* out_requested_frames,
    int32_t* out_path,
    uint64_t* out_skipped_source_frames,
    uint64_t* out_capture_reads
) {
    if (out_reads) *out_reads = g_pending_reads.exchange(0, std::memory_order_relaxed);
    if (out_bytes) *out_bytes = g_pending_bytes.exchange(0, std::memory_order_relaxed);
    if (out_last_sr) *out_last_sr = g_last_sr.load(std::memory_order_relaxed);
    if (out_last_ch) *out_last_ch = g_last_ch.load(std::memory_order_relaxed);
    if (out_underruns) *out_underruns = g_pending_underruns.exchange(0, std::memory_order_relaxed);
    if (out_missing_frames) *out_missing_frames = g_pending_missing_frames.exchange(0, std::memory_order_relaxed);
    if (out_requested_frames) *out_requested_frames = g_pending_requested_frames.exchange(0, std::memory_order_relaxed);
    if (out_path) *out_path = g_last_path.load(std::memory_order_relaxed);
    const auto skipped = g_pending_skipped_source_frames.exchange(0, std::memory_order_relaxed);
    if (out_skipped_source_frames) *out_skipped_source_frames = skipped;
    const auto captures = g_pending_capture_reads.exchange(0, std::memory_order_relaxed);
    if (out_capture_reads) *out_capture_reads = captures;
}

} // namespace glass
