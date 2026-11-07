package io.mo.glassmic.xposed

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.bytedance.shadowhook.ShadowHook
import io.mo.glassmic.core.Constants
import io.mo.glassmic.core.model.SourceType
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * AAudio 原生 hook 入口。
 *
 * 调用顺序：
 *   1. install(ctx, pkg)  ——
 *      a) ShadowHook.init（加载 libshadowhook.so）
 *      b) System.loadLibrary("glassmic_native")
 *      c) nativeInstall 安装 AAudioStream_read / callback 等 hook
 *      d) 启动 80ms 轮询线程：同步决策、读取 native 统计，并只在真实录音活跃时维护 PCM pipe
 *
 * 设计要点：
 * - audio thread 完全不调 Java，只读 native 端原子；避免 RT 线程跨 JNI/Binder 引发卡顿
 * - nativeDrainStats() 的 reads 是真实录音回调心跳；没有回调的子进程不应打开 PCM Provider
 * - 停止录音一段时间后主动关闭 fd，让 Publisher 及时移除无效 consumer
 */
object NativeAAudioHook {

    private const val TAG = "GlassMic-NativeAAudio"
    private const val POLL_INTERVAL_MS = 80L
    private const val PCM_OPEN_ACTIVITY_WINDOW_MS = 500L
    private const val PCM_CLOSE_IDLE_MS = 1_500L
    private const val TAP_RETRY_MS = 15_000L

    private val installed = AtomicBoolean(false)
    @Volatile private var pollerStarted = false

    // detachFd 后 PFD 句柄已转交 native，只能用这些字段标记 native 当前持有的配置。
    @Volatile private var pushedFdSr: Int = 0
    @Volatile private var pushedFdCh: Int = 0
    @Volatile private var hasPushedFd: Boolean = false
    private var pcmRetryAtMs = 0L

    // 调试抓取：GlassMic 仅在日志级别为 DEBUG 时接受 /tap，其余情况按退避间隔重试。
    @Volatile private var hasTapFd: Boolean = false
    private var tapRetryAtMs = 0L

    fun install(ctx: Context, callerPackage: String): Boolean {
        if (!installed.compareAndSet(false, true)) return true

        // 1. shadowhook 初始化（含 dlopen 自身 .so）
        val initOk = runCatching {
            ShadowHook.init(
                ShadowHook.ConfigBuilder()
                    .setMode(ShadowHook.Mode.UNIQUE)
                    .setDebuggable(false)
                    .build()
            )
            true
        }.onFailure { android.util.Log.e(TAG, "ShadowHook.init failed: ${it.message}", it) }
            .getOrDefault(false)

        if (!initOk) {
            installed.set(false)
            return false
        }

        // 2. 加载我们自己的 native lib
        val libOk = runCatching { System.loadLibrary("glassmic_native") }
            .onFailure { android.util.Log.e(TAG, "loadLibrary glassmic_native failed: ${it.message}", it) }
            .isSuccess
        if (!libOk) {
            installed.set(false)
            return false
        }

        // 3. 安装 hook
        // 返回值是逐路径位掩码；任一路径装上就要启动轮询，否则它拿不到决策和 PCM。
        val mask = runCatching { nativeInstall() }
            .onFailure { android.util.Log.e(TAG, "nativeInstall throw: ${it.message}", it) }
            .getOrDefault(0)
        val aaudioOk = mask and 1 != 0
        val openslOk = mask and 2 != 0
        val audioRecordOk = mask and 4 != 0
        val detail = "aaudio=$aaudioOk opensl=$openslOk audiorecord=$audioRecordOk"
        if (mask and 7 == 0) {
            android.util.Log.e(TAG, "no native hook installed in $callerPackage")
            XBridge.reportDiagnosticEvent(
                ctx,
                callerPackage,
                "native_hooks_failed",
                Bundle().apply { putString("detail", detail) }
            )
            installed.set(false)
            return false
        }
        android.util.Log.i(TAG, "native hooks installed in $callerPackage: $detail")
        XBridge.reportDiagnosticEvent(
            ctx,
            callerPackage,
            "native_hooks_installed",
            Bundle().apply { putString("detail", detail) }
        )

        // 4. 启动轮询线程
        startPoller(ctx, callerPackage)
        return true
    }

    private fun startPoller(ctx: Context, callerPackage: String) {
        if (pollerStarted) return
        pollerStarted = true
        thread(name = "GlassMic-AAudioPoller", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            var lastNativeCaptureAtMs = 0L
            var lastSource: SourceType? = null
            var lastPath = 0
            var lastGapEventAtMs = 0L

            while (true) {
                try {
                    // 心跳包含 REAL_MIC，空闲时减少 IPC，开始录音后下一轮立即刷新决策。
                    // 保留 80ms 的本地原子统计采样，避免长时间休眠延迟录音起始响应。
                    val stats = nativeDrainStats()
                    val captureReads = stats?.getOrNull(9) ?: 0L
                    val src = XBridge.resolveSource(ctx, callerPackage, captureObserved = captureReads > 0L)
                    val decisionCode = when (src) {
                        SourceType.REAL_MIC -> 0
                        SourceType.FILE, SourceType.TTS, SourceType.LIVE -> 1
                        SourceType.SILENCE -> 2
                    }
                    nativeSetDecision(decisionCode)
                    if (lastSource != src) {
                        XBridge.reportDiagnosticEvent(
                            ctx,
                            callerPackage,
                            "source_decision_changed",
                            Bundle().apply {
                                putString("source", src.name)
                                putString("detail", "previous=${lastSource?.name ?: "none"}")
                            }
                        )
                        lastSource = src
                    }

                    // 替换次数与录音活动分开，真麦采集不能计为成功替换。
                    val now = SystemClock.uptimeMillis()
                    val reads = stats?.getOrNull(0)?.toInt() ?: 0
                    val inactiveBeforeRead = lastNativeCaptureAtMs == 0L ||
                        now - lastNativeCaptureAtMs > PCM_OPEN_ACTIVITY_WINDOW_MS
                    if (captureReads > 0L) {
                        lastNativeCaptureAtMs = now
                    }
                    val captureAgeMs = if (lastNativeCaptureAtMs > 0L) {
                        (now - lastNativeCaptureAtMs).coerceAtLeast(0L)
                    } else {
                        Long.MAX_VALUE
                    }
                    val pathCode = stats?.getOrNull(7)?.toInt() ?: 0
                    val pathName = pathName(pathCode)

                    if (captureReads > 0L && inactiveBeforeRead) {
                        XBridge.reportDiagnosticEvent(
                            ctx,
                            callerPackage,
                            "native_capture_active",
                            Bundle().apply {
                                putLong("reads", captureReads)
                                putLong("capture_age_ms", 0L)
                                putString("path", pathName)
                                putLong("sample_rate", stats?.getOrNull(2) ?: 0L)
                                putLong("channels", stats?.getOrNull(3) ?: 0L)
                            }
                        )
                    }
                    if (pathCode != 0 && pathCode != lastPath) {
                        XBridge.reportDiagnosticEvent(
                            ctx,
                            callerPackage,
                            "native_path_changed",
                            Bundle().apply {
                                putString("path", pathName)
                                putString("detail", "previous=${pathName(lastPath)}")
                            }
                        )
                        lastPath = pathCode
                    }
                    if (stats != null && stats.size >= 9) {
                        val underruns = stats[4]
                        val missing = stats[5]
                        val skipped = stats[8]
                        if ((underruns > 0L || skipped > 0L) && now - lastGapEventAtMs >= 500L) {
                            lastGapEventAtMs = now
                            XBridge.reportDiagnosticEvent(
                                ctx,
                                callerPackage,
                                "native_gap_detected",
                                Bundle().apply {
                                    putString("path", pathName)
                                    putLong("reads", reads.toLong())
                                    putLong("missing_frames", missing)
                                    putLong("requested_frames", stats[6])
                                    putLong("skipped_source_frames", skipped)
                                    putLong("capture_age_ms", captureAgeMs)
                                    putBoolean("pcm_fd_active", hasPushedFd)
                                }
                            )
                        }
                    }

                    // 4.3 只有目标进程最近确实发生过 native 录音回调，才创建 PCM Provider consumer。
                    // 旧逻辑仅凭 FILE 决策就打开 fd，会让王者等多进程应用产生长期空闲 consumer，
                    // Publisher 持续向没人读取的 pipe 写入并大量丢弃队列数据。
                    val needsPcm = src == SourceType.FILE || src == SourceType.TTS || src == SourceType.LIVE
                    when {
                        needsPcm && captureAgeMs <= PCM_OPEN_ACTIVITY_WINDOW_MS -> ensurePcmFd(ctx, callerPackage)
                        hasPushedFd && (!needsPcm || captureAgeMs > PCM_CLOSE_IDLE_MS) -> {
                            closePcmFd(
                                ctx,
                                callerPackage,
                                if (!needsPcm) "source=$src" else "capture idle ${captureAgeMs}ms"
                            )
                        }
                    }
                    // 抓取跟随 FILE 决策而非录音心跳，才能录到录音刚开始、PCM fd 尚未打开时的补零。
                    if (needsPcm) ensureTapFd(ctx, now) else closeTapFd()

                    // 4.4 上报统计——全欠载时 bytes=0 也必须上报，否则诊断会停留在旧数据。
                    if (stats != null && stats.size >= 4 && reads > 0) {
                        val bytes = stats[1]
                        val sampleRate = stats[2].toInt()
                        val channels = stats[3].toInt()
                        val diagnostics = if (stats.size >= 8) Bundle().apply {
                            putLong("underrun_reads", stats[4])
                            putLong("missing_frames", stats[5])
                            putLong("requested_frames", stats[6])
                            putLong("skipped_source_frames", stats.getOrElse(8) { 0L })
                            putLong("native_capture_age_ms", captureAgeMs)
                            putBoolean("pcm_fd_active", hasPushedFd)
                            putString("path", pathName(stats[7].toInt()))
                        } else null
                        XBridge.reportInterceptBatch(
                            ctx,
                            callerPackage,
                            deltaReads = reads,
                            deltaBytes = bytes,
                            sampleRate = sampleRate,
                            channels = channels,
                            nativeDiagnostics = diagnostics
                        )
                    }
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "poller iter error: ${t.message}")
                }

                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    closePcmFd(ctx, callerPackage, "poller interrupted")
                    closeTapFd()
                    return@thread
                }
            }
        }
    }

    private fun ensurePcmFd(ctx: Context, callerPackage: String) {
        // native 广播缓冲统一使用 48kHz mono；各录音 stream 再在 native 侧按目标格式转换。
        val wantSr = 48_000
        val wantCh = 1
        if (hasPushedFd && pushedFdSr == wantSr && pushedFdCh == wantCh) return
        val now = SystemClock.uptimeMillis()
        if (now < pcmRetryAtMs) return
        pcmRetryAtMs = now + 2_000L

        val uri = Uri.parse("content://${Constants.PROVIDER_PCM}/stream?sr=$wantSr&ch=$wantCh")
        var openFailure = "NO_FILE_DESCRIPTOR"
        val pfd = runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "r")
        }.onFailure {
            openFailure = it.javaClass.simpleName
        }.getOrNull() ?: run {
            XBridge.reportProviderFailure(callerPackage, "pcm.open", openFailure)
            XBridge.reportDiagnosticEvent(
                ctx,
                callerPackage,
                "pcm_fd_open_failed",
                Bundle().apply { putString("reason", "open provider failed") }
            )
            closePcmFd(ctx, callerPackage, "open provider failed")
            return
        }

        val fd = takeFd(pfd)
        if (fd < 0) {
            XBridge.reportProviderFailure(callerPackage, "pcm.detach", "INVALID_FD")
            XBridge.reportDiagnosticEvent(
                ctx,
                callerPackage,
                "pcm_fd_open_failed",
                Bundle().apply { putString("reason", "detach/dup failed") }
            )
            closePcmFd(ctx, callerPackage, "detach/dup failed")
            return
        }

        nativeSetPcmFd(fd, wantSr, wantCh)
        pcmRetryAtMs = 0L
        XBridge.flushProviderFailures(ctx, callerPackage, "pcm.open")
        XBridge.flushProviderFailures(ctx, callerPackage, "pcm.detach")
        hasPushedFd = true
        pushedFdSr = wantSr
        pushedFdCh = wantCh
        android.util.Log.i(TAG, "pcm fd opened on active capture: fd=$fd sr=$wantSr ch=$wantCh")
        XBridge.reportDiagnosticEvent(
            ctx,
            callerPackage,
            "pcm_fd_opened",
            Bundle().apply {
                putBoolean("pcm_fd_active", true)
                putLong("sample_rate", wantSr.toLong())
                putLong("channels", wantCh.toLong())
            }
        )
    }

    private fun closePcmFd(ctx: Context, callerPackage: String, reason: String) {
        if (!hasPushedFd) {
            pushedFdSr = 0
            pushedFdCh = 0
            return
        }
        nativeSetPcmFd(-1, 0, 0)
        hasPushedFd = false
        pushedFdSr = 0
        pushedFdCh = 0
        android.util.Log.i(TAG, "pcm fd closed: $reason")
        XBridge.reportDiagnosticEvent(
            ctx,
            callerPackage,
            "pcm_fd_closed",
            Bundle().apply {
                putBoolean("pcm_fd_active", false)
                putString("reason", reason)
            }
        )
    }

    private fun ensureTapFd(ctx: Context, now: Long) {
        if (hasTapFd) {
            if (nativeTapActive()) return
            // native 写端出错（GlassMic 侧已关闭或达到上限），按退避重新申请。
            hasTapFd = false
            tapRetryAtMs = now + TAP_RETRY_MS
            return
        }
        if (now < tapRetryAtMs) return
        tapRetryAtMs = now + TAP_RETRY_MS
        val uri = Uri.parse("content://${Constants.PROVIDER_PCM}/tap?pid=${android.os.Process.myPid()}")
        val pfd = runCatching { ctx.contentResolver.openFileDescriptor(uri, "w") }.getOrNull() ?: return
        val fd = takeFd(pfd)
        if (fd < 0) return
        nativeSetTapFd(fd)
        hasTapFd = true
        android.util.Log.i(TAG, "audio tap opened: fd=$fd")
    }

    private fun closeTapFd() {
        if (!hasTapFd) return
        nativeSetTapFd(-1)
        hasTapFd = false
        tapRetryAtMs = 0L
    }

    /** detachFd 后 PFD 不再持有 fd 所有权，由 native 负责 close。 */
    private fun takeFd(pfd: ParcelFileDescriptor): Int {
        val fd = if (Build.VERSION.SDK_INT >= 33) {
            runCatching { pfd.detachFd() }.getOrDefault(-1)
        } else {
            runCatching { nativeDupFd(pfd.fileDescriptor) }.getOrDefault(-1)
        }
        runCatching { pfd.close() }
        return fd
    }

    private fun pathName(code: Int): String = when (code) {
        1 -> "AAudio.read"
        2 -> "AAudio.callback"
        3 -> "AudioRecord.native"
        4 -> "OpenSL.callback"
        else -> "unknown"
    }

    // =================== JNI ===================
    @JvmStatic private external fun nativeInstall(): Int
    @JvmStatic private external fun nativeSetDecision(decision: Int)
    @JvmStatic private external fun nativeSetPcmFd(fd: Int, sampleRate: Int, channels: Int)
    @JvmStatic private external fun nativeDrainStats(): LongArray?
    @JvmStatic private external fun nativeDupFd(fd: java.io.FileDescriptor): Int
    @JvmStatic private external fun nativeSetTapFd(fd: Int)
    @JvmStatic private external fun nativeTapActive(): Boolean
}
