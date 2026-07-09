#include "glass_audiorecord.h"
#include "glass_aaudio.h"   // glass::fill_pcm / SampleFmt / get_decision / Decision
#include "glass_log.h"

#include <shadowhook.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <sys/types.h>

namespace glass {

// ssize_t android::AudioRecord::read(void* buffer, size_t size, bool blocking)
using AudioRecord_read_t = ssize_t (*)(void* /*this*/, void*, size_t, bool);
static AudioRecord_read_t g_orig_read = nullptr;
static void* g_read_stub = nullptr;
static std::atomic<bool> g_installed{false};
static std::atomic<bool> g_dumped{false};

static const int32_t kCommonRates[] = {
    8000, 11025, 12000, 16000, 22050, 24000, 32000,
    44100, 48000, 64000, 88200, 96000, 176400, 192000
};

static bool is_common_rate(uint32_t v) {
    for (int32_t r : kCommonRates) {
        if (v == static_cast<uint32_t>(r)) return true;
    }
    return false;
}

/**
 * AudioRecord 的 getSampleRate()/channelCount()/format() 在该平台是 inline、未导出，
 * hook read 时拿不到。改为按对象内存布局直接读：
 *
 *   实测 Android 16（libaudioclient）AudioRecord 成员布局：
 *     [sr_off + 0] uint32  mSampleRate    （落在常见采样率集合内，用于定位）
 *     [sr_off + 4] uint32  mFormat        （audio_format_t：PCM_16=1, PCM_FLOAT=5 …）
 *     [sr_off + 8] uint32  mChannelCount
 *   再往后是 mAttributes（含字符串 tags[]）。
 *
 * 先扫出 mSampleRate 的偏移（值唯一可判别），再按 +4/+8 取 format / channelCount，
 * 并做范围校验。校验失败时回退到安全默认。
 *
 * FILE 模式需要精确格式；SILENCE 模式填舒适噪声，格式不准时最坏只是幅度换算略偏，
 * 仍是低电平噪声、不影响"骗过 VAD"的目的。
 */
static void detect_format(
    const void* thiz,
    int32_t* out_sr, int32_t* out_ch, SampleFmt* out_fmt, int* out_sr_off
) {
    int32_t sr = 0, ch = 0;
    SampleFmt fmt = SampleFmt::S16;
    int sr_off = -1;
    const auto* base = reinterpret_cast<const uint8_t*>(thiz);
    constexpr int kScan = 768;

    for (int off = 0; off + 12 <= kScan; off += 4) {
        uint32_t v;
        __builtin_memcpy(&v, base + off, sizeof(v));
        if (is_common_rate(v)) {
            sr = static_cast<int32_t>(v);
            sr_off = off;
            uint32_t fmt_field = 0, ch_field = 0;
            __builtin_memcpy(&fmt_field, base + off + 4, sizeof(fmt_field));
            __builtin_memcpy(&ch_field, base + off + 8, sizeof(ch_field));
            if (ch_field >= 1 && ch_field <= 8) ch = static_cast<int32_t>(ch_field);
            if (fmt_field == 5 /*AUDIO_FORMAT_PCM_FLOAT*/) fmt = SampleFmt::FLOAT;
            break;
        }
    }

    *out_sr = sr > 0 ? sr : 48'000;
    *out_ch = ch;  // 0 表示未取到，调用方据 read 字节数兜底
    *out_fmt = fmt;
    if (out_sr_off) *out_sr_off = sr_off;
}

static ssize_t my_read(void* thiz, void* buffer, size_t size, bool blocking) {
    ssize_t n = g_orig_read(thiz, buffer, size, blocking);
    if (n <= 0 || !buffer || !thiz) return n;

    if (get_decision() == Decision::REAL_MIC) return n;

    int32_t sr = 48'000, ch = 0;
    SampleFmt fmt = SampleFmt::S16;
    int sr_off = -1;
    detect_format(thiz, &sr, &ch, &fmt, &sr_off);

    int32_t bps = (fmt == SampleFmt::FLOAT) ? 4 : 2;
    if (ch <= 0) {
        // 未取到声道：按字节数兜底（frameSize 4 整除→stereo，否则 mono）。
        ch = (n % (bps * 2) == 0) ? 2 : 1;
    }

    if (!g_dumped.exchange(true)) {
        LOGI("AudioRecord::read first hit: size=%zu n=%zd sr=%d@%d ch=%d fmt=%d",
             size, n, sr, sr_off, ch, static_cast<int>(fmt));
    }

    int32_t frame_bytes = ch * bps;
    int32_t frames = static_cast<int32_t>(n) / frame_bytes;
    if (frames > 0) {
        fill_pcm(buffer, fmt, ch, sr, frames);
    }
    return n;
}

bool install_audiorecord_hook() {
    bool expected = false;
    if (!g_installed.compare_exchange_strong(expected, true)) {
        return true;
    }

    g_read_stub = shadowhook_hook_sym_name(
        "libaudioclient.so",
        "_ZN7android11AudioRecord4readEPvmb",
        reinterpret_cast<void*>(my_read),
        reinterpret_cast<void**>(&g_orig_read)
    );
    if (!g_read_stub) {
        int err = shadowhook_get_errno();
        const char* msg = shadowhook_to_errmsg(err);
        LOGW("hook AudioRecord::read failed: err=%d %s", err, msg ? msg : "?");
        g_installed.store(false);
        return false;
    }

    LOGI("AudioRecord::read hook installed");
    return true;
}

} // namespace glass
