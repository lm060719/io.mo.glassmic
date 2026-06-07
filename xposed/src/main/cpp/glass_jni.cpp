#include "glass_aaudio.h"
#include "glass_log.h"

#include <jni.h>
#include <unistd.h>

using namespace glass;

extern "C" {

JNIEXPORT jint JNICALL
Java_io_mo_glassmic_xposed_NativeAAudioHook_nativeInstall(JNIEnv* env, jclass) {
    return install_aaudio_hook() ? 0 : -1;
}

JNIEXPORT void JNICALL
Java_io_mo_glassmic_xposed_NativeAAudioHook_nativeSetDecision(JNIEnv* env, jclass, jint d) {
    Decision dec = Decision::REAL_MIC;
    switch (d) {
        case 0: dec = Decision::REAL_MIC; break;
        case 1: dec = Decision::FILE; break;
        case 2: dec = Decision::SILENCE; break;
        default: dec = Decision::REAL_MIC; break;
    }
    set_decision(dec);
}

/**
 * Kotlin 侧打开 ContentProvider 的 pipe 后把 fd 通过 ParcelFileDescriptor.detachFd() 取出来再传给我们。
 * native 持有该 fd 的所有权——上一次设置的 fd 会在这里被 close。
 * 传 -1 == 清空。
 */
JNIEXPORT void JNICALL
Java_io_mo_glassmic_xposed_NativeAAudioHook_nativeSetPcmFd(
    JNIEnv* env, jclass, jint fd, jint sample_rate, jint channels
) {
    set_pcm_fd(fd, sample_rate, channels);
}

/**
 * 返回 long[4] = { reads, bytes, last_sample_rate, last_channels }
 */
JNIEXPORT jlongArray JNICALL
Java_io_mo_glassmic_xposed_NativeAAudioHook_nativeDrainStats(JNIEnv* env, jclass) {
    uint64_t reads = 0, bytes = 0;
    int32_t  sr = 0, ch = 0;
    drain_stats(&reads, &bytes, &sr, &ch);

    jlong out[4];
    out[0] = static_cast<jlong>(reads);
    out[1] = static_cast<jlong>(bytes);
    out[2] = static_cast<jlong>(sr);
    out[3] = static_cast<jlong>(ch);

    jlongArray arr = env->NewLongArray(4);
    if (!arr) return nullptr;
    env->SetLongArrayRegion(arr, 0, 4, out);
    return arr;
}

} // extern "C"
