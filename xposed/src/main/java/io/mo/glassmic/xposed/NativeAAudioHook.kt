package io.mo.glassmic.xposed

import android.content.Context
import android.net.Uri
import android.os.Build
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
 *      c) nativeInstall 安装 AAudioStream_read hook
 *      d) 启动一个低频轮询线程，250ms 一次：
 *         - 通过 RuntimeProvider 拿当前 Decision（REAL_MIC / FILE / SILENCE）
 *         - Decision=FILE 时确保已为该 Stream 打开 PCM pipe fd，并下传给 native
 *         - 把 native 累积的劫持次数/字节通过 RuntimeProvider 上报
 *
 * 设计要点：
 * - audio thread 完全不调 Java，只读 native 端原子；避免 RT 线程跨 JNI/Binder 引发的卡顿
 * - 上报统计也走轮询线程，audio thread 只 atomic.add
 */
object NativeAAudioHook {

    private const val TAG = "GlassMic-NativeAAudio"
    private val installed = AtomicBoolean(false)
    @Volatile private var pollerStarted = false
    // 我们 detach fd 后 PFD 句柄就没了，只能用这两个值标记"native 端已经持有过哪种配置的 fd"
    @Volatile private var pushedFdSr: Int = 0
    @Volatile private var pushedFdCh: Int = 0
    @Volatile private var hasPushedFd: Boolean = false

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

        // 3. 装 hook
        val rc = runCatching { nativeInstall() }
            .onFailure { android.util.Log.e(TAG, "nativeInstall throw: ${it.message}", it) }
            .getOrDefault(-1)
        if (rc != 0) {
            installed.set(false)
            return false
        }
        android.util.Log.i(TAG, "AAudio native hook installed in $callerPackage")

        // 4. 启动轮询线程
        startPoller(ctx, callerPackage)
        return true
    }

    private fun startPoller(ctx: Context, callerPackage: String) {
        if (pollerStarted) return
        pollerStarted = true
        thread(name = "GlassMic-AAudioPoller", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            val pollIntervalMs = 250L
            while (true) {
                try {
                    // 4.1 决策
                    val src = XBridge.resolveSource(ctx, callerPackage)
                    val decisionCode = when (src) {
                        SourceType.REAL_MIC -> 0
                        SourceType.FILE     -> 1
                        SourceType.SILENCE  -> 2
                    }
                    nativeSetDecision(decisionCode)

                    // 4.2 PCM fd
                    if (src == SourceType.FILE) {
                        ensurePcmFd(ctx)
                    } else if (hasPushedFd) {
                        // native 端已经有 fd 了，但当前不需要——通知 native 关掉
                        nativeSetPcmFd(-1, 0, 0)
                        hasPushedFd = false
                        pushedFdSr = 0
                        pushedFdCh = 0
                    }

                    // 4.3 上报统计——native 已经聚合好了，走 batch 接口
                    val stats = nativeDrainStats()  // [reads, bytes, sr, ch]
                    if (stats != null && stats.size >= 4) {
                        val reads = stats[0].toInt()
                        val bytes = stats[1]
                        val sr = stats[2].toInt()
                        val ch = stats[3].toInt()
                        if (reads > 0 && bytes > 0) {
                            XBridge.reportInterceptBatch(
                                ctx, callerPackage,
                                deltaReads = reads,
                                deltaBytes = bytes,
                                sampleRate = sr, channels = ch
                            )
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "poller iter error: ${t.message}")
                }

                try {
                    Thread.sleep(pollIntervalMs)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    private fun ensurePcmFd(ctx: Context) {
        // 默认请求 48000Hz mono——publisher 端按 consumer 采样率从 currentSource 读取
        val wantSr = 48000
        val wantCh = 1
        if (hasPushedFd && pushedFdSr == wantSr && pushedFdCh == wantCh) return

        val uri = Uri.parse("content://${Constants.PROVIDER_PCM}/stream?sr=$wantSr&ch=$wantCh")
        val pfd = runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "r")
        }.onFailure {
            android.util.Log.w(TAG, "open pcm pipe failed: ${it.message}")
        }.getOrNull() ?: run {
            nativeSetPcmFd(-1, 0, 0)
            hasPushedFd = false
            return
        }

        // detachFd 后 PFD 不再持有 fd 所有权，由 native 负责 close
        val fd = if (Build.VERSION.SDK_INT >= 33) {
            runCatching { pfd.detachFd() }.getOrDefault(-1)
        } else {
            // API < 33: detachFd() 不存在，用 native dup() 复制 fd
            runCatching {
                val rawFd = pfd.fileDescriptor
                nativeDupFd(rawFd)
            }.getOrDefault(-1)
        }
        runCatching { pfd.close() }
        if (fd < 0) {
            nativeSetPcmFd(-1, 0, 0)
            hasPushedFd = false
            return
        }
        nativeSetPcmFd(fd, wantSr, wantCh)
        hasPushedFd = true
        pushedFdSr = wantSr
        pushedFdCh = wantCh
        android.util.Log.i(TAG, "pcm fd opened: fd=$fd sr=$wantSr ch=$wantCh")
    }

    // =================== JNI ===================
    @JvmStatic private external fun nativeInstall(): Int
    @JvmStatic private external fun nativeSetDecision(decision: Int)
    @JvmStatic private external fun nativeSetPcmFd(fd: Int, sampleRate: Int, channels: Int)
    @JvmStatic private external fun nativeDrainStats(): LongArray?
    @JvmStatic private external fun nativeDupFd(fd: java.io.FileDescriptor): Int
}
