package io.mo.glassmic.root

import android.content.Context
import android.content.pm.ApplicationInfo
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.audio.LiveCaptureSource
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class LivePhase { IDLE, STARTING, ACTIVE, ERROR }

/** 应用显示名；取不到时退回包名。 */
fun Context.appLabelOf(packageName: String): String {
    if (packageName.isBlank()) return "—"
    return runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}

data class LiveStatus(
    val phase: LivePhase = LivePhase.IDLE,
    val packageName: String = "",
    val detail: String = ""
)

/**
 * 实时 App 音频（issue #42）的 root 抓取会话。
 *
 * [open] 启动 [PlaybackCaptureMain]（root app_process），把它写进私有 FIFO 的 PCM 推给返回的
 * [LiveCaptureSource]；音源被 Publisher 换掉时 [LiveCaptureSource.release] 回调到这里结束会话。
 * 同一时间只保留一个会话。
 */
@Singleton
class LiveCaptureController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(LiveStatus())
    val status: StateFlow<LiveStatus> = mutableStatus.asStateFlow()

    // 用户选好的音源 App。音频库页和悬浮窗共用，悬浮窗「开始实时」直接用它。
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutableSelected = MutableStateFlow(prefs.getString(KEY_PACKAGE, "").orEmpty())
    val selectedPackage: StateFlow<String> = mutableSelected.asStateFlow()

    fun selectPackage(packageName: String) {
        mutableSelected.value = packageName
        prefs.edit().putString(KEY_PACKAGE, packageName).apply()
    }

    private var session: Session? = null // 仅在持有 mutex 时访问

    private class Session(
        val process: Process,
        val fifo: File,
        val fd: FileDescriptor,
        val source: LiveCaptureSource
    ) {
        val ready = CompletableDeferred<Unit>()
        var reader: Job? = null
        var heartbeat: Job? = null
        var pump: Thread? = null
        @Volatile var closed = false
        @Volatile var error: String? = null
    }

    /** 当前会话的抓取统计，供诊断使用。 */
    fun stats(): String? {
        val src = session?.source ?: return null
        return "pkg=${src.packageName} pushed=${src.pushedBytes.get()} dropped=${src.droppedBytes.get()} " +
            "underruns=${src.underruns.get()} buffered=${src.bufferedMs}ms"
    }

    /** 开始抓取 [packageName] 的播放声音；失败抛异常并把状态置为 ERROR。 */
    suspend fun open(packageName: String, render: Boolean = true): LiveCaptureSource = mutex.withLock {
        closeLocked()
        publish(LivePhase.STARTING, packageName)
        try {
            val app = context.packageManager.getApplicationInfo(packageName, 0)
            require(app.uid % 100000 >= 10000 && app.uid != android.os.Process.myUid()) {
                "不支持抓取系统进程或 GlassMic 自身"
            }
            if (app.flags and ApplicationInfo.FLAG_INSTALLED == 0) throw IOException("应用未安装")
            val opened = start(app.uid, render, packageName)
            session = opened
            withTimeout(START_TIMEOUT_MS) { opened.ready.await() }
            startPump(opened)
            publish(LivePhase.ACTIVE, packageName)
            GlassLog.b("LiveCapture") { "已开始抓取 $packageName uid=${app.uid} render=$render" }
            opened.source
        } catch (error: Throwable) {
            closeLocked()
            val message = when (error) {
                is TimeoutCancellationException -> "root 抓取进程启动超时"
                else -> error.message ?: error.javaClass.simpleName
            }
            publish(LivePhase.ERROR, packageName, message)
            GlassLog.b("LiveCapture") { "启动失败 $packageName: $message" }
            throw IOException(message, error)
        }
    }

    /** 主动结束（例如用户点停止）。音源被替换时也会经 release 回调走到这里。 */
    suspend fun close() = mutex.withLock {
        closeLocked()
        publish(LivePhase.IDLE)
    }

    private fun onSourceReleased(source: LiveCaptureSource) {
        scope.launch {
            mutex.withLock {
                if (session?.source === source) {
                    closeLocked()
                    publish(LivePhase.IDLE)
                }
            }
        }
    }

    private fun start(uid: Int, render: Boolean, packageName: String): Session {
        val dir = File(context.filesDir, "live-capture").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // 上次异常退出残留的 FIFO
        val fifo = File(dir, "pcm-${UUID.randomUUID()}")
        Os.mkfifo(fifo.absolutePath, 0x180 /* 0600 */)
        // 先以非阻塞方式打开读端：root 端随后以阻塞方式打开写端时不会卡住
        val fd = try {
            Os.open(fifo.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
        } catch (error: ErrnoException) {
            fifo.delete(); throw error
        }
        val command = "CLASSPATH=${quote(context.applicationInfo.sourceDir)} exec /system/bin/app_process " +
            "/system/bin io.mo.glassmic.root.PlaybackCaptureMain $uid ${if (render) 1 else 0} ${quote(fifo.absolutePath)}"
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (error: Exception) {
            runCatching { Os.close(fd) }
            fifo.delete()
            throw error
        }
        val current = Session(process, fifo, fd, LiveCaptureSource(packageName, ::onSourceReleased))
        current.heartbeat = scope.launch {
            try {
                while (isActive && process.isAlive) {
                    process.outputStream.write("PING\n".toByteArray())
                    process.outputStream.flush()
                    delay(1000)
                }
            } catch (error: IOException) { current.error = error.message }
        }
        current.reader = scope.launch {
            val line = ByteArrayOutputStream()
            val bytes = ByteArray(1024)
            try {
                val input = process.inputStream
                while (isActive && (process.isAlive || input.available() > 0)) {
                    val count = input.available().coerceAtMost(bytes.size)
                    if (count == 0) { delay(25); continue }
                    val n = input.read(bytes, 0, count)
                    if (n < 0) break
                    for (i in 0 until n) {
                        if (bytes[i] == 10.toByte()) {
                            val text = line.toString("UTF-8").trim()
                            line.reset()
                            when {
                                text == "READY" -> current.ready.complete(Unit)
                                text.startsWith("ERROR ") -> {
                                    current.error = text.removePrefix("ERROR ").take(500)
                                    current.ready.completeExceptionally(IOException(current.error))
                                }
                                text.isNotEmpty() -> GlassLog.b("LiveCapture") { "helper: ${text.take(200)}" }
                            }
                        } else if (line.size() < 2048) line.write(bytes[i].toInt())
                    }
                }
                if (!current.ready.isCompleted) current.ready.completeExceptionally(
                    IOException("root 抓取进程已退出（未授权 root？）"))
            } catch (error: IOException) {
                current.error = error.message
                current.ready.completeExceptionally(error)
            }
            // 进程在运行中途退出：反映到状态上，音源继续输出静音
            if (!current.closed) {
                publish(LivePhase.ERROR, current.source.packageName, current.error ?: "root 抓取进程已退出")
            }
        }
        return current
    }

    /** 专用线程：poll FIFO，把 PCM 推进环形缓冲。 */
    private fun startPump(current: Session) {
        current.pump = Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
            val buf = ByteArray(4096)
            val pfd = StructPollfd().apply { fd = current.fd; events = OsConstants.POLLIN.toShort() }
            var carry = -1 // 奇数字节时暂存，保证推进去的始终是完整 16 位样本
            while (!current.closed) {
                try {
                    if (Os.poll(arrayOf(pfd), 200) <= 0) continue
                    val n = Os.read(current.fd, buf, 1, buf.size - 1)
                    if (n == 0) { Thread.sleep(20); continue } // 写端尚未打开或已关闭
                    var start = 1
                    var len = n
                    if (carry >= 0) { buf[0] = carry.toByte(); start = 0; len++ ; carry = -1 }
                    if (len % 2 != 0) { carry = buf[start + len - 1].toInt() and 0xFF; len-- }
                    if (len > 0) {
                        if (start == 1) System.arraycopy(buf, 1, buf, 0, len)
                        current.source.push(buf, len)
                    }
                } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue
                    current.error = error.message
                    break
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "glassmic-live-pump").apply { isDaemon = true; start() }
    }

    private suspend fun closeLocked() = withContext(NonCancellable) {
        val current = session ?: return@withContext
        session = null
        current.closed = true
        current.heartbeat?.cancelAndJoin()
        // 关闭 owner 通道 → root 进程退出并注销 AudioPolicy
        runCatching { current.process.outputStream.close() }
        current.pump?.join(500)
        runCatching { Os.close(current.fd) }
        withContext(Dispatchers.IO) {
            if (!current.process.waitFor(2, TimeUnit.SECONDS)) current.process.destroyForcibly()
        }
        current.reader?.cancelAndJoin()
        runCatching { current.process.inputStream.close() }
        current.fifo.delete()
        GlassLog.b("LiveCapture") { "已结束抓取 ${current.source.packageName}" }
    }

    private fun publish(phase: LivePhase, packageName: String = "", detail: String = "") {
        mutableStatus.value = LiveStatus(phase, packageName, detail)
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val START_TIMEOUT_MS = 30_000L
        // 沿用旧版音频库页的存储位置，升级后选择不丢
        const val PREFS_NAME = "live_capture"
        const val KEY_PACKAGE = "package"
    }
}
