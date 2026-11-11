// Compile the actual implementation against inert host platform declarations.
// No Android device, hook installation, or third-party process is used.
#include "../../xposed/src/main/cpp/glass_aaudio.cpp"
#include <cassert>
#include <chrono>
#include <cmath>
#include <functional>
#include <iostream>
#include <vector>

using namespace glass;

// This host harness tests PCM delivery; debug tap transport has no host socket implementation.
namespace glass {
void tap_capture(const void*, int32_t, int32_t, int32_t, int32_t, int32_t, const void*, int32_t) {}
}

static void prepare(int frames, int channels = 1, const void* stream = nullptr) {
    set_decision(Decision::FILE);
    std::lock_guard<std::mutex> lock(g_buffer_mutex);
    g_fd_state.sample_rate = 48000;
    g_fd_state.channels = channels;
    g_ring_buffer.clear();
    reset_resample_states_locked();
    // Register before producing, so large fixtures model an already running stream.
    uint8_t unused = 0;
    for (int path = 1; path <= 4; ++path)
        g_ring_buffer.read(&unused, 0, stream, path, channels * 2, 0);
    std::vector<int16_t> pcm(frames * channels);
    for (int f = 0; f < frames; ++f)
        for (int ch = 0; ch < channels; ++ch) pcm[f * channels + ch] = 1000 + f % 1000;
    assert(g_ring_buffer.write(reinterpret_cast<uint8_t*>(pcm.data()), pcm.size() * 2) == pcm.size() * 2);
}

// Like prepare(), but with an arbitrary mono waveform.
static void prepare_wave(int frames, const std::function<int16_t(int)>& sample) {
    set_decision(Decision::FILE);
    std::lock_guard<std::mutex> lock(g_buffer_mutex);
    g_fd_state.sample_rate = 48000;
    g_fd_state.channels = 1;
    g_ring_buffer.clear();
    reset_resample_states_locked();
    uint8_t unused = 0;
    for (int path = 1; path <= 4; ++path) g_ring_buffer.read(&unused, 0, nullptr, path, 2, 0);
    std::vector<int16_t> pcm(frames);
    for (int f = 0; f < frames; ++f) pcm[f] = sample(f);
    assert(g_ring_buffer.write(reinterpret_cast<uint8_t*>(pcm.data()), pcm.size() * 2) == pcm.size() * 2);
}

static int readCalls = 0, readResult = 0;
static int64_t seenTimeout = -1;
static aaudio_result_t system_read(AAudioStream*, void*, int32_t, int64_t timeout) {
    ++readCalls;
    seenTimeout = timeout;
    assert(aaudio_read_in_progress);
    if (timeout > 0) std::this_thread::sleep_for(std::chrono::milliseconds(5));
    return readResult;
}

static void read_contract() {
    AAudioStream stream;
    g_orig_AAudioStream_read = system_read;
    for (int result : {0, -899, 120, 480}) {
        prepare(480, 1, &stream);
        readResult = result;
        std::vector<int16_t> out(481, -123);
        const int before = readCalls;
        assert(my_AAudioStream_read(&stream, out.data(), 480, 0) == result);
        assert(readCalls == before + 1 && seenTimeout == 0);
        assert(!aaudio_read_in_progress);
        const int filled = std::max(result, 0);
        for (int i = 0; i < filled; ++i) assert(out[i] == 1000 + i);
        for (int i = filled; i <= 480; ++i) assert(out[i] == -123);
        assert(g_ring_buffer.available_read(&stream, 1) == static_cast<size_t>(480 - filled) * 2);
    }
    prepare(480, 1, &stream);
    readResult = 480;
    int16_t out[480];
    const auto start = std::chrono::steady_clock::now();
    assert(my_AAudioStream_read(&stream, out, 480, 50000000) == 480);
    assert(seenTimeout == 50000000);
    assert(std::chrono::steady_clock::now() - start >= std::chrono::milliseconds(5));
    std::cout << "PASS: system read, timeout, short/zero/error results and buffer boundaries\n";
}

static void large_buffers() {
    for (int sr : {8000, 16000, 44100, 48000, 96000}) {
        for (int channels : {1, 2}) {
            const int frames = sr / 4; // 250 ms needs 24 KB of mono source, above old 16 KB cap.
            prepare(12000, channels);
            std::vector<int16_t> out(frames * channels + 1, -123);
            assert(fill_pcm(out.data(), SampleFmt::S16, channels, sr, frames, CapturePath::OPENSL));
            if (sr < 48000) {
                // Downsampling runs the anti-aliasing low-pass first: the 1000-step sawtooth
                // fixture rings a little at each wrap, but must stay near its 1000..1999 range.
                for (int i = 0; i < frames * channels; ++i) assert(out[i] > 800 && out[i] < 2200);
                assert(out.back() == -123);
                assert(g_ring_buffer.available_read(nullptr, 4) == 0);
                continue;
            }
            for (int f = 0; f < frames; ++f) {
                const double pos = static_cast<double>(f) * 48000 / sr;
                const int i = static_cast<int>(pos);
                const double frac = pos - i;
                const int s0 = 1000 + i % 1000;
                const int s1 = 1000 + std::min(i + 1, 11999) % 1000;
                const int expected = static_cast<int>(s0 + (s1 - s0) * frac);
                for (int ch = 0; ch < channels; ++ch)
                    assert(std::abs(out[f * channels + ch] - expected) <= 1);
            }
            assert(out.back() == -123);
            assert(g_ring_buffer.available_read(nullptr, 4) == 0);
        }
    }
    for (auto format : {AAUDIO_FORMAT_PCM_FLOAT, AAUDIO_FORMAT_PCM_I32, AAUDIO_FORMAT_PCM_I24_PACKED}) {
        prepare(12000);
        const int bps = bytes_per_dst_sample(format);
        std::vector<uint32_t> aligned((12000 * bps + 7) / 4, 0);
        auto* bytes = reinterpret_cast<uint8_t*>(aligned.data());
        bytes[12000 * bps] = 0x7e;
        assert(fill_pcm_impl(bytes, format, 1, 48000, 12000, CapturePath::AAUDIO_CALLBACK) == FillResult::FILLED);
        assert(bytes[12000 * bps] == 0x7e);
        assert(std::any_of(bytes + 10000 * bps, bytes + 12000 * bps, [](uint8_t v) { return v != 0; }));
        assert(g_ring_buffer.available_read(nullptr, 2) == 0);
    }
    std::cout << "PASS: 250 ms buffers, five rates, mono/stereo and four PCM formats\n";
}

static void underruns() {
    uint64_t reads, bytes, missing, requested, underrun;
    int32_t sr, ch, path;
    auto drain = [&] { drain_stats(&reads, &bytes, &sr, &ch, &underrun, &missing, &requested, &path); };
    drain();
    prepare(120);
    int16_t out[480];
    fill_pcm(out, SampleFmt::S16, 1, 48000, 480, CapturePath::AUDIORECORD);
    drain();
    assert(reads == 1 && bytes == 240 && underrun == 1 && missing == 360 && requested == 480 && path == 3);
    assert(std::all_of(out + 120, out + 480, [](int16_t v) { return v == 0; }));
    fill_pcm(out, SampleFmt::S16, 1, 48000, 480, CapturePath::AUDIORECORD);
    drain();
    assert(reads == 1 && bytes == 0 && underrun == 1 && missing == 480 && requested == 480);
    drain();
    assert(reads == 0 && bytes == 0 && underrun == 0 && missing == 0 && requested == 0);
    std::cout << "PASS: partial/complete underrun accounting, including zero source bytes\n";
}

static void contention_and_reset() {
    prepare(480);
    std::atomic<bool> locked{false}, done{false};
    std::thread holder([&] {
        std::lock_guard<std::mutex> lock(g_buffer_mutex);
        locked.store(true);
        while (!done.load()) std::this_thread::yield();
    });
    while (!locked.load()) std::this_thread::yield();
    int16_t out[480];
    const auto start = std::chrono::steady_clock::now();
    fill_pcm(out, SampleFmt::S16, 1, 48000, 480, CapturePath::OPENSL);
    const auto elapsed = std::chrono::steady_clock::now() - start;
    done.store(true);
    holder.join();
    assert(elapsed < std::chrono::milliseconds(100));
    assert(std::all_of(out, out + 480, [](int16_t v) { return v == 0; }));
    assert(g_ring_buffer.available_read(nullptr, 4) == 960);
    std::thread writer([] {
        const uint8_t data[1920] = {};
        for (int i = 0; i < 3000; ++i) {
            std::lock_guard<std::mutex> lock(g_buffer_mutex);
            g_ring_buffer.write(data, sizeof(data));
        }
    });
    std::thread resetter([] {
        for (int i = 0; i < 3000; ++i) {
            set_decision(Decision::REAL_MIC);
            set_decision(Decision::FILE);
        }
    });
    for (int i = 0; i < 3000; ++i) {
        fill_pcm(out, SampleFmt::S16, 1, 48000, 480, CapturePath::OPENSL);
        std::lock_guard<std::mutex> lock(g_buffer_mutex);
        assert(g_ring_buffer.available_read(nullptr, 4) <= 65536);
    }
    writer.join();
    resetter.join();
    std::cout << "PASS: nonblocking contention and concurrent decision/reset/read/write\n";
}

static void simultaneous_recorders() {
    prepare(960);
    AAudioStream voice, background;
    int16_t first[480], second[480], opensl[160];
    for (int block = 0; block < 2; ++block) {
        assert(fill_input_buffer(&voice, first, 480, CapturePath::AAUDIO_CALLBACK) == FillResult::FILLED);
        assert(fill_input_buffer(&background, second, 480, CapturePath::AAUDIO_CALLBACK) == FillResult::FILLED);
        assert(fill_pcm(opensl, SampleFmt::S16, 1, 16000, 160, CapturePath::OPENSL));
        for (int i = 0; i < 480; ++i) {
            assert(first[i] == 1000 + block * 480 + i);
            assert(second[i] == first[i]);
        }
        // 16 kHz output is low-passed before decimation: a ramp comes out delayed by a few samples.
        for (int i = 0; i < 160; ++i) assert(std::abs(opensl[i] - first[i * 3]) <= 8);
    }
    // A new recorder's first request can exceed the normal 80 ms join window.
    prepare(12000);
    std::vector<int16_t> large(12000);
    assert(fill_input_buffer(&voice, large.data(), 12000, CapturePath::AAUDIO_READ) == FillResult::FILLED);
    for (int i = 0; i < 12000; ++i) assert(large[i] == 1000 + i % 1000);
    // A pipe syscall/close in the worker must not silence a callback with buffered PCM.
    prepare(480);
    std::lock_guard<std::mutex> fd_lock(g_fd_mutex);
    fill_pcm(first, SampleFmt::S16, 1, 48000, 480, CapturePath::OPENSL);
    for (int i = 0; i < 480; ++i) assert(first[i] == 1000 + i);
    std::cout << "PASS: independent same-path streams, mixed APIs/rates and fd-lock isolation\n";
}

static double rms_16k_after_warmup(int16_t (*wave)(int)) {
    prepare_wave(4800, [wave](int f) { return wave(f); });
    std::vector<int16_t> out(1600);
    for (int block = 0; block < 10; ++block)
        assert(fill_pcm(out.data() + block * 160, SampleFmt::S16, 1, 16000, 160, CapturePath::OPENSL));
    double sum = 0;
    for (int i = 320; i < 1600; ++i) sum += static_cast<double>(out[i]) * out[i];
    return std::sqrt(sum / 1280);
}

static void anti_aliasing() {
    constexpr double kPi = 3.14159265358979323846;
    // 20 kHz would alias to 4 kHz at full amplitude without the low-pass (stateful across 10 blocks).
    const double alias = rms_16k_after_warmup([](int f) {
        return static_cast<int16_t>(10000 * std::sin(2 * kPi * 20000.0 * f / 48000.0));
    });
    // 1 kHz is well inside the 7.2 kHz passband and must survive (RMS of a 10000 sine is ~7071).
    const double pass = rms_16k_after_warmup([](int f) {
        return static_cast<int16_t>(10000 * std::sin(2 * kPi * 1000.0 * f / 48000.0));
    });
    assert(alias < 500);
    assert(pass > 6500 && pass < 7600);
    // 48 kHz output takes no filter: samples pass through bit-exact.
    prepare(480);
    int16_t out[480];
    assert(fill_pcm(out, SampleFmt::S16, 1, 48000, 480, CapturePath::OPENSL));
    for (int i = 0; i < 480; ++i) assert(out[i] == 1000 + i);
    std::cout << "PASS: anti-aliasing low-pass (20 kHz alias rms=" << alias << ", 1 kHz rms=" << pass << ")\n";
}

static void phase_continuity() {
    constexpr double kPi = 3.14159265358979323846;
    // 48k -> 44.1k is not an integer ratio and 160 is not a multiple of 147 (the rate quantum):
    // reading ceil(160 * 48000 / 44100) = 175 source frames per callback used to drop ~0.85 frame
    // every block (a 0.5% speed-up plus a phase jump at each boundary).
    constexpr int kSource = 24000;
    prepare_wave(kSource, [](int f) {
        return static_cast<int16_t>(10000 * std::sin(2 * kPi * 440.0 * f / 48000.0));
    });
    constexpr int kBlock = 160, kBlocks = 130;
    std::vector<int16_t> out(kBlock * kBlocks);
    for (int b = 0; b < kBlocks; ++b)
        assert(fill_pcm(out.data() + b * kBlock, SampleFmt::S16, 1, 44100, kBlock, CapturePath::OPENSL));
    // Source consumption follows the exact ratio (+1 frame held for the next interpolation).
    size_t left = 0;
    {
        std::lock_guard<std::mutex> lock(g_buffer_mutex);
        left = g_ring_buffer.available_read(nullptr, 4) / 2;
    }
    const double consumed = static_cast<double>(kSource - left);
    const double ideal = out.size() * 48000.0 / 44100.0;
    assert(std::abs(consumed - ideal) <= 2.0);
    // A smooth 440 Hz sine has a second difference of at most ~A*(2*pi*440/44100)^2 ~ 40;
    // a 0.85-frame phase jump at a block boundary shows up as several hundred.
    int worst = 0;
    for (size_t i = kBlock; i + 1 < out.size(); ++i)
        worst = std::max(worst, std::abs(out[i + 1] - 2 * out[i] + out[i - 1]));
    assert(worst < 100);
    std::cout << "PASS: phase-continuous 48k->44.1k over 160-frame callbacks (consumed " << consumed
              << " vs ideal " << ideal << ", max 2nd diff " << worst << ")\n";
}

static void broadcast_history() {
    PcmBroadcastBuffer<64, 4> history;
    int fast, slow, newcomer;
    uint8_t input[48], out[48];
    for (int i = 0; i < 48; ++i) input[i] = static_cast<uint8_t>(i);
    history.write(input, 48);
    assert(history.read(out, 24, &fast, 1, 2, 48) == 24);
    assert(std::equal(out, out + 24, input));
    assert(history.read(out, 12, &slow, 1, 2, 48) == 12);
    history.write(input, 48); // wrap and overwrite the stopped reader's position
    uint64_t skipped = 0;
    assert(history.read(out, 24, &slow, 1, 2, 24, &skipped) == 24);
    assert(skipped == 60);
    assert(std::equal(out, out + 24, input + 24));
    assert(history.read(out, 24, &newcomer, 1, 2, 24) == 24);
    assert(std::equal(out, out + 24, input + 24));
    history.clear();
    assert(history.read(out, 48, &fast, 1, 2, 48) == 0);
    // Byte-sized pipe reads must not split a multi-channel PCM frame.
    history.write(input, 5);
    assert(history.read(out, 48, &fast, 1, 6, 48) == 0);
    history.write(input + 5, 7);
    assert(history.read(out, 48, &fast, 1, 6, 48) == 12);
    assert(std::equal(out, out + 12, input));
    // More historical identities than slots must remain bounded and reuse slots.
    int identities[100];
    for (auto& identity : identities) {
        assert(history.read(out, 12, &identity, 1, 6, 48) == 12);
        assert(std::equal(out, out + 12, input));
    }
    std::cout << "PASS: bounded history, wraparound, slow/new readers, reset and frame alignment\n";
}

static void capture_heartbeat() {
    set_decision(Decision::REAL_MIC);
    uint64_t reads = 0, captures = 0;
    auto drain = [&] {
        drain_stats(&reads, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, &captures);
    };
    drain(); // Discard earlier fixtures.
    int16_t real_pcm[] = {321, -456};
    assert(!fill_pcm(real_pcm, SampleFmt::S16, 1, 48000, 2, CapturePath::AAUDIO_CALLBACK));
    assert(real_pcm[0] == 321 && real_pcm[1] == -456);
    drain();
    assert(captures == 1 && reads == 0); // Activity must not masquerade as replacement.
    drain();
    assert(captures == 0 && reads == 0);
    assert(!fill_pcm(nullptr, SampleFmt::S16, 1, 48000, 2, CapturePath::AAUDIO_READ));
    drain();
    assert(captures == 0);
    set_decision(Decision::SILENCE);
    assert(fill_pcm(real_pcm, SampleFmt::S16, 1, 48000, 2, CapturePath::OPENSL));
    drain();
    assert(captures == 1 && reads == 1);
    std::cout << "PASS: real-mic capture heartbeat, unchanged PCM and independent replacement counters\n";
}

int main() {
    read_contract();
    large_buffers();
    underruns();
    contention_and_reset();
    simultaneous_recorders();
    anti_aliasing();
    phase_continuity();
    broadcast_history();
    capture_heartbeat();
}
