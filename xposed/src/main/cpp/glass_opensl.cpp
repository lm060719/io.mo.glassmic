#include "glass_opensl.h"
#include "glass_aaudio.h"   // glass::fill_pcm / SampleFmt
#include "glass_log.h"

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <shadowhook.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <deque>
#include <map>
#include <mutex>
#include <set>
#include <sys/mman.h>
#include <unistd.h>
#include <utility>

namespace glass {

// =================== vtable 方法签名（与 OpenSLES 头里的结构体成员一致） ===================
using GetInterface_t = SLresult (*)(SLObjectItf, const SLInterfaceID, void*);
using CreateAudioRecorder_t = SLresult (*)(
    SLEngineItf, SLObjectItf*, SLDataSource*, SLDataSink*,
    SLuint32, const SLInterfaceID*, const SLboolean*);
using Enqueue_t = SLresult (*)(SLAndroidSimpleBufferQueueItf, const void*, SLuint32);
using RegisterCallback_t = SLresult (*)(
    SLAndroidSimpleBufferQueueItf, slAndroidSimpleBufferQueueCallback, void*);

// wilhelm 实现里每个接口类型只有一张全局方法表，所有实例共享——每个原始指针存一份即可。
static GetInterface_t        g_orig_GetInterface        = nullptr;
static CreateAudioRecorder_t g_orig_CreateAudioRecorder = nullptr;
static Enqueue_t             g_orig_Enqueue             = nullptr;
static RegisterCallback_t    g_orig_RegisterCallback    = nullptr;

// =================== 录音 buffer-queue 簿记 ===================
struct BqInfo {
    slAndroidSimpleBufferQueueCallback app_cb = nullptr;
    void* app_ctx = nullptr;
    SampleFmt fmt = SampleFmt::S16;
    int32_t channels = 1;
    int32_t sample_rate = 48'000;
    std::deque<std::pair<const void*, uint32_t>> fifo;  // app Enqueue 的待填缓冲，FIFO
};

struct RecFmt {
    SampleFmt fmt = SampleFmt::S16;
    int32_t channels = 1;
    int32_t sample_rate = 48'000;
};

static std::mutex g_mutex;
static std::set<const void*> g_patched_vtables;    // 已 patch 过的 vtable 指针（去重）
static std::set<const void*> g_recorder_objs;      // CreateAudioRecorder 产出的 recorder object
static std::map<const void*, RecFmt> g_rec_fmt;    // recorder object -> 录音 PCM 格式
static std::map<const void*, BqInfo> g_bq_map;     // 录音 bq itf -> 状态

static std::atomic<bool> g_installed{false};
static void* g_slCreateEngine_stub = nullptr;

// =================== 工具 ===================

static bool make_writable(void* addr, size_t len) {
    static const uintptr_t ps = static_cast<uintptr_t>(sysconf(_SC_PAGESIZE));
    uintptr_t a = reinterpret_cast<uintptr_t>(addr);
    uintptr_t start = a & ~(ps - 1);
    uintptr_t end = (a + len + ps - 1) & ~(ps - 1);
    return ::mprotect(reinterpret_cast<void*>(start), end - start,
                      PROT_READ | PROT_WRITE) == 0;
}

// 取一个 OpenSL 接口实例的 vtable（去掉 const）。SLObjectItf 等都是
// `const struct X_ * const *`，*itf 即方法表指针。
template <typename VT, typename ITF>
static VT* vtable_of(ITF itf) {
    // itf 形如 `const VT* const*`，*itf 是方法表指针；读出后去 const 以便改写。
    return const_cast<VT*>(*reinterpret_cast<const VT* const*>(itf));
}

// 把 vtable 的某个方法槽改写成我们的函数，并保存原始指针。调用方持有 g_mutex。
template <typename FN>
static void patch_slot(FN* slot, FN replacement, FN* save_orig) {
    if (save_orig && *save_orig == nullptr) {
        *save_orig = *slot;
    }
    if (!make_writable(slot, sizeof(FN))) {
        LOGW("mprotect vtable slot failed: %s", strerror(errno));
        return;
    }
    *slot = replacement;
}

static bool iid_eq(const SLInterfaceID a, const SLInterfaceID b) {
    if (a == b) return true;
    if (!a || !b) return false;
    return std::memcmp(a, b, sizeof(struct SLInterfaceID_)) == 0;
}

// =================== 录音 buffer-queue 回调 trampoline ===================

static void my_bq_callback(SLAndroidSimpleBufferQueueItf caller, void* pContext) {
    slAndroidSimpleBufferQueueCallback app_cb = nullptr;
    const void* buf = nullptr;
    uint32_t size = 0;
    SampleFmt fmt = SampleFmt::S16;
    int32_t ch = 1, sr = 48'000;
    bool have_buf = false;

    {
        std::lock_guard<std::mutex> lk(g_mutex);
        auto it = g_bq_map.find(reinterpret_cast<const void*>(caller));
        if (it != g_bq_map.end()) {
            BqInfo& info = it->second;
            app_cb = info.app_cb;
            fmt = info.fmt;
            ch = info.channels;
            sr = info.sample_rate;
            if (!info.fifo.empty()) {
                auto front = info.fifo.front();
                info.fifo.pop_front();
                buf = front.first;
                size = front.second;
                have_buf = true;
            }
        }
    }

    // 此刻队首缓冲已被录音引擎填满麦克风数据——在转交 app 之前覆盖成虚拟音源。
    if (have_buf && buf && size > 0) {
        int32_t bps = (fmt == SampleFmt::FLOAT) ? 4 : 2;
        int32_t frames = static_cast<int32_t>(size / (static_cast<uint32_t>(ch) * bps));
        if (frames > 0) {
            fill_pcm(const_cast<void*>(buf), fmt, ch, sr, frames);
        }
    }

    if (app_cb) {
        app_cb(caller, pContext);
    }
}

// =================== hook 后的 vtable 方法 ===================

static SLresult my_Enqueue(SLAndroidSimpleBufferQueueItf self, const void* pBuffer, SLuint32 size) {
    {
        std::lock_guard<std::mutex> lk(g_mutex);
        auto it = g_bq_map.find(reinterpret_cast<const void*>(self));
        if (it != g_bq_map.end()) {
            it->second.fifo.emplace_back(pBuffer, size);
        }
    }
    return g_orig_Enqueue(self, pBuffer, size);
}

static SLresult my_RegisterCallback(
    SLAndroidSimpleBufferQueueItf self,
    slAndroidSimpleBufferQueueCallback callback,
    void* pContext
) {
    bool is_record = false;
    {
        std::lock_guard<std::mutex> lk(g_mutex);
        auto it = g_bq_map.find(reinterpret_cast<const void*>(self));
        if (it != g_bq_map.end()) {
            it->second.app_cb = callback;
            it->second.app_ctx = pContext;
            is_record = true;
        }
    }
    if (is_record) {
        // 保持 pContext 不变，只把 callback 换成我们的 trampoline。
        return g_orig_RegisterCallback(self, &my_bq_callback, pContext);
    }
    return g_orig_RegisterCallback(self, callback, pContext);
}

static void ensure_bq_vtable_patched(SLAndroidSimpleBufferQueueItf bq) {
    auto* vt = vtable_of<struct SLAndroidSimpleBufferQueueItf_>(bq);
    void* vtp = reinterpret_cast<void*>(vt);
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_patched_vtables.count(vtp)) return;
    patch_slot(&vt->Enqueue, &my_Enqueue, &g_orig_Enqueue);
    patch_slot(&vt->RegisterCallback, &my_RegisterCallback, &g_orig_RegisterCallback);
    g_patched_vtables.insert(vtp);
    LOGI("OpenSL bufferqueue vtable patched");
}

static SLresult my_CreateAudioRecorder(
    SLEngineItf self,
    SLObjectItf* pRecorder,
    SLDataSource* pAudioSrc,
    SLDataSink* pAudioSnk,
    SLuint32 numInterfaces,
    const SLInterfaceID* pInterfaceIds,
    const SLboolean* pInterfaceRequired
) {
    SLresult r = g_orig_CreateAudioRecorder(
        self, pRecorder, pAudioSrc, pAudioSnk,
        numInterfaces, pInterfaceIds, pInterfaceRequired);
    if (r != SL_RESULT_SUCCESS || !pRecorder || !*pRecorder) return r;

    // 解析录音 sink 的 PCM 格式（sink = 录到哪，含采样率/声道/位深）。
    RecFmt rf;
    if (pAudioSnk && pAudioSnk->pFormat) {
        auto* pcm = reinterpret_cast<SLDataFormat_PCM*>(pAudioSnk->pFormat);
        if (pcm->formatType == SL_DATAFORMAT_PCM ||
            pcm->formatType == SL_ANDROID_DATAFORMAT_PCM_EX) {
            rf.channels = pcm->numChannels > 0 ? static_cast<int32_t>(pcm->numChannels) : 1;
            // samplesPerSec 单位是 milliHz。
            rf.sample_rate = static_cast<int32_t>(pcm->samplesPerSec / 1000);
            if (rf.sample_rate <= 0) rf.sample_rate = 48'000;
            rf.fmt = SampleFmt::S16;
            if (pcm->formatType == SL_ANDROID_DATAFORMAT_PCM_EX) {
                auto* ex = reinterpret_cast<SLAndroidDataFormat_PCM_EX*>(pAudioSnk->pFormat);
                if (ex->representation == SL_ANDROID_PCM_REPRESENTATION_FLOAT) {
                    rf.fmt = SampleFmt::FLOAT;
                }
            }
        }
    }

    {
        std::lock_guard<std::mutex> lk(g_mutex);
        g_recorder_objs.insert(reinterpret_cast<const void*>(*pRecorder));
        g_rec_fmt[reinterpret_cast<const void*>(*pRecorder)] = rf;
    }
    LOGI("OpenSL AudioRecorder created: ch=%d sr=%d fmt=%d",
         rf.channels, rf.sample_rate, static_cast<int>(rf.fmt));
    return r;
}

static void ensure_engine_vtable_patched(SLEngineItf eng) {
    auto* vt = vtable_of<struct SLEngineItf_>(eng);
    void* vtp = reinterpret_cast<void*>(vt);
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_patched_vtables.count(vtp)) return;
    patch_slot(&vt->CreateAudioRecorder, &my_CreateAudioRecorder, &g_orig_CreateAudioRecorder);
    g_patched_vtables.insert(vtp);
    LOGI("OpenSL engine vtable patched");
}

// 前置声明：GetInterface 与 object vtable patch 互相引用。
static void ensure_object_vtable_patched(SLObjectItf obj);

static SLresult my_GetInterface(SLObjectItf self, const SLInterfaceID iid, void* pInterface) {
    SLresult r = g_orig_GetInterface(self, iid, pInterface);
    if (r != SL_RESULT_SUCCESS || !pInterface) return r;

    void* itf = *reinterpret_cast<void**>(pInterface);
    if (!itf) return r;

    if (iid_eq(iid, SL_IID_ENGINE)) {
        ensure_engine_vtable_patched(reinterpret_cast<SLEngineItf>(itf));
        return r;
    }

    if (iid_eq(iid, SL_IID_ANDROIDSIMPLEBUFFERQUEUE)) {
        bool is_recorder = false;
        RecFmt rf;
        {
            std::lock_guard<std::mutex> lk(g_mutex);
            if (g_recorder_objs.count(reinterpret_cast<const void*>(self))) {
                is_recorder = true;
                auto it = g_rec_fmt.find(reinterpret_cast<const void*>(self));
                if (it != g_rec_fmt.end()) rf = it->second;
            }
        }
        if (is_recorder) {
            ensure_bq_vtable_patched(reinterpret_cast<SLAndroidSimpleBufferQueueItf>(itf));
            std::lock_guard<std::mutex> lk(g_mutex);
            BqInfo info;
            info.fmt = rf.fmt;
            info.channels = rf.channels;
            info.sample_rate = rf.sample_rate;
            g_bq_map[itf] = std::move(info);
            LOGI("OpenSL record bufferqueue tracked");
        }
    }
    return r;
}

static void ensure_object_vtable_patched(SLObjectItf obj) {
    auto* vt = vtable_of<struct SLObjectItf_>(obj);
    void* vtp = reinterpret_cast<void*>(vt);
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_patched_vtables.count(vtp)) return;
    patch_slot(&vt->GetInterface, &my_GetInterface, &g_orig_GetInterface);
    g_patched_vtables.insert(vtp);
    LOGI("OpenSL object vtable patched");
}

// =================== slCreateEngine hook（入口） ===================
using slCreateEngine_t = SLresult (*)(
    SLObjectItf*, SLuint32, const SLEngineOption*,
    SLuint32, const SLInterfaceID*, const SLboolean*);
static slCreateEngine_t g_orig_slCreateEngine = nullptr;

static SLresult my_slCreateEngine(
    SLObjectItf* pEngine,
    SLuint32 numOptions,
    const SLEngineOption* pEngineOptions,
    SLuint32 numInterfaces,
    const SLInterfaceID* pInterfaceIds,
    const SLboolean* pInterfaceRequired
) {
    SLresult r = g_orig_slCreateEngine(
        pEngine, numOptions, pEngineOptions,
        numInterfaces, pInterfaceIds, pInterfaceRequired);
    if (r == SL_RESULT_SUCCESS && pEngine && *pEngine) {
        ensure_object_vtable_patched(*pEngine);
    }
    return r;
}

bool install_opensl_hook() {
    bool expected = false;
    if (!g_installed.compare_exchange_strong(expected, true)) {
        return true;
    }

    g_slCreateEngine_stub = shadowhook_hook_sym_name(
        "libOpenSLES.so",
        "slCreateEngine",
        reinterpret_cast<void*>(my_slCreateEngine),
        reinterpret_cast<void**>(&g_orig_slCreateEngine)
    );
    if (!g_slCreateEngine_stub) {
        int err = shadowhook_get_errno();
        const char* msg = shadowhook_to_errmsg(err);
        LOGW("hook slCreateEngine failed: err=%d %s", err, msg ? msg : "?");
        g_installed.store(false);
        return false;
    }

    LOGI("slCreateEngine hook installed");
    return true;
}

} // namespace glass
