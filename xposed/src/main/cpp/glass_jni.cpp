#include "glass_aaudio.h"
#include "glass_audio_tap.h"
#include "glass_opensl.h"
#include "glass_audiorecord.h"
#include "glass_log.h"

#include <cerrno>
#include <cstring>
#include <jni.h>
#include <unistd.h>

using namespace glass;

extern "C" {

JNIEXPORT jint JNICALL
Java_io_mo_glassmic_xposed_NativeAAudioHook_nativeInstall(JNIEnv* env, jclass) {
    // 逐路径返回安装结果位掩码：bit0=AAudio、bit1=OpenSL ES、bit2=AudioRecord::read。
    // 三条路径共用决策和 PCM 状态，任意一条装上都需要 Kotlin 侧启动轮询。
    bool aaudio_ok = install_aaudio_hook();
    bool opensl_ok = install_opensl_hook();
    bool arecord_ok = install_audiorecord_hook();
    LOGI("native hooks installed: aaudio=%d opensl=%d audiorecord=%d",
         aaudio_ok, opensl_ok, arecord_ok);
    return (aaudio_ok ? 1 : 0) | (opensl_ok ? 2 : 0) | (arecord_ok ? 4 : 0);
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

/** 调试抓取 fd（socketpair 写端），所有权交给 native；-1 关闭。 */
JNIEXPORT void JNICALL
Java_io_mo_glassmic_xposed_NativeAAudioHook_nativeSetTapFd(JNIEnv* env, jclass, jint fd) {
    set_tap_fd(fd);
}

JNIEXPORT jboolean JNICALL
Java_io_mo_glassmic_xposed_NativeAAudioHook_nativeTapActive(JNIEnv* env, jclass) {
    return tap_active() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_mo_glassmic_xposed_NativeAAudioHook_nativeWaitCapture(JNIEnv* env, jclass, jint timeout_ms) {
    return wait_for_capture(timeout_ms) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_mo_glassmic_xposed_NativeAAudioHook_nativePcmFdAlive(JNIEnv* env, jclass) {
    return pcm_fd_alive() ? JNI_TRUE : JNI_FALSE;
}

/**
 * dup() a FileDescriptor and return the new fd number.
 * Used on API < 33 where ParcelFileDescriptor.detachFd() is unavailable.
 */
JNIEXPORT jint JNICALL
Java_io_mo_glassmic_xposed_NativeAAudioHook_nativeDupFd(JNIEnv* env, jclass, jobject fdObj) {
    jclass fdClass = env->FindClass("java/io/FileDescriptor");
    jfieldID fidField = env->GetFieldID(fdClass, "descriptor", "I");
    jint rawFd = env->GetIntField(fdObj, fidField);
    jint newFd = ::dup(rawFd);
    if (newFd < 0) {
        LOGE("dup(%d) failed: %s", rawFd, strerror(errno));
    }
    return newFd;
}

/**
 * 返回 long[10]：替换统计 [0..8]，以及包含 REAL_MIC 的独立录音活动计数 [9]。
 */
JNIEXPORT jlongArray JNICALL
Java_io_mo_glassmic_xposed_NativeAAudioHook_nativeDrainStats(JNIEnv* env, jclass) {
    uint64_t reads = 0, bytes = 0;
    int32_t  sr = 0, ch = 0;
    uint64_t underruns = 0, missing = 0, requested = 0;
    int32_t path = 0;
    uint64_t skipped = 0, captures = 0;
    drain_stats(&reads, &bytes, &sr, &ch, &underruns, &missing, &requested, &path, &skipped, &captures);

    jlong out[10];
    out[0] = static_cast<jlong>(reads);
    out[1] = static_cast<jlong>(bytes);
    out[2] = static_cast<jlong>(sr);
    out[3] = static_cast<jlong>(ch);
    out[4] = static_cast<jlong>(underruns);
    out[5] = static_cast<jlong>(missing);
    out[6] = static_cast<jlong>(requested);
    out[7] = static_cast<jlong>(path);
    out[8] = static_cast<jlong>(skipped);
    out[9] = static_cast<jlong>(captures);

    jlongArray arr = env->NewLongArray(10);
    if (!arr) return nullptr;
    env->SetLongArrayRegion(arr, 0, 10, out);
    return arr;
}

} // extern "C"
