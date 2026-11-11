package io.mo.glassmic.xposed

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import io.mo.glassmic.core.Constants
import io.mo.glassmic.core.model.SourceType
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 注入到目标 App 进程后，只通过 GlassMic 自己的 ContentProvider 读取实时决策。
 *
 * API 101 不再依赖旧版 XSharedPreferences；黑白名单、首次启动、BootGate、运行态等
 * 统一由 app 进程里的 EffectiveSourceResolver 计算，Xposed 侧只缓存短时间结果。
 */
object XBridge {

    // Diagnostics must not add Binder/disk latency to the target app's audio read thread.
    // Bounded best-effort queue: a stalled provider cannot accumulate unbounded snapshots.
    private val pcmReportExecutor by lazy {
        ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8),
            { task -> Thread(task, "GlassMic-PcmStats").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardOldestPolicy()
        ).apply { allowCoreThreadTimeOut(true) }
    }

    private val queryPolicy = SourceQueryPolicy()
    @Volatile private var cachedSource: SourceType? = null
    @Volatile private var cachedPkg: String = ""
    @Volatile private var lastReachable: Boolean = true

    private data class ProviderFailure(
        val reason: String, val firstAt: Long, val lastAt: Long,
        val count: Long, val lastLogAt: Long
    )
    private val providerFailures = LinkedHashMap<String, ProviderFailure>()

    @Volatile var currentPackage: String = ""

    /** 注入入口设置：101 = libxposed API 101，82 = legacy。仅用于诊断状态展示。 */
    @Volatile var apiVersion: Int = 101

    // ============== 拦截统计累计 + 节流上报 ==============
    private const val REPORT_EVERY_READS = 50
    private const val REPORT_EVERY_MS = 1500L
    private val pendingReads = AtomicInteger(0)
    private val pendingBytes = AtomicLong(0L)
    @Volatile private var lastReportMs: Long = 0L

    /**
     * 任意一个 hook 拦截成功时调用——累计到内存计数器，
     * 达到阈值或超过节流时间后批量写到 RuntimeProvider。
     */
    fun recordInterception(ctx: Context, pkg: String, bytes: Int, sampleRate: Int, channels: Int) {
        if (bytes <= 0) return
        pendingReads.incrementAndGet()
        pendingBytes.addAndGet(bytes.toLong())
        val now = System.currentTimeMillis()
        if (pendingReads.get() >= REPORT_EVERY_READS || now - lastReportMs >= REPORT_EVERY_MS) {
            val r = pendingReads.getAndSet(0)
            val b = pendingBytes.getAndSet(0L)
            lastReportMs = now
            if (r > 0) reportInterceptStats(ctx, pkg, r, b, sampleRate, channels)
        }
    }

    /**
     * 直接批量上报——给 native AAudio hook 用：它已经在 native 侧聚合好了 deltaReads/deltaBytes，
     * 这里跳过 Java 端的节流，直接写到 ContentProvider。
     */
    fun reportInterceptBatch(
        ctx: Context,
        callerPackage: String,
        deltaReads: Int,
        deltaBytes: Long,
        sampleRate: Int,
        channels: Int,
        nativeDiagnostics: Bundle? = null
    ) {
        if (deltaReads <= 0 || deltaBytes < 0) return
        reportInterceptStats(ctx, callerPackage, deltaReads, deltaBytes, sampleRate, channels, nativeDiagnostics)
    }

    private fun reportInterceptStats(
        ctx: Context,
        callerPackage: String,
        deltaReads: Int,
        deltaBytes: Long,
        sampleRate: Int,
        channels: Int,
        nativeDiagnostics: Bundle? = null
    ) {
        runCatching {
            val extras = Bundle().apply {
                putString("package", callerPackage)
                putLong("time", System.currentTimeMillis())
                putInt("delta_reads", deltaReads)
                putLong("delta_bytes", deltaBytes)
                putInt("sample_rate", sampleRate)
                putInt("channels", channels)
                if (nativeDiagnostics != null) putBundle("native_stats", nativeDiagnostics)
            }
            ctx.contentResolver.call(
                Uri.parse("content://${Constants.PROVIDER_RUNTIME}"),
                Constants.METHOD_AUDIO_INTERCEPT,
                callerPackage,
                extras
            )
        }
    }

    /**
     * 上报低频诊断事件。只用于状态切换/异常，不在实时音频线程直接做 Binder 调用。
     * fields 只接受基础类型；RuntimeProvider 会再次挑选允许写入诊断包的字段。
     */
    fun reportDiagnosticEvent(
        ctx: Context,
        callerPackage: String,
        event: String,
        fields: Bundle = Bundle()
    ) {
        if (event.isBlank()) return
        runCatching {
            pcmReportExecutor.execute {
                runCatching {
                    fields.putString("event", event)
                    fields.putString("package", callerPackage)
                    fields.putLong("time", System.currentTimeMillis())
                    fields.putInt("pid", android.os.Process.myPid())
                    ctx.contentResolver.call(
                        Uri.parse("content://${Constants.PROVIDER_RUNTIME}"),
                        Constants.METHOD_AUDIO_DIAG_EVENT,
                        callerPackage,
                        fields
                    )
                }
            }
        }
    }

    fun resolveSource(ctx: Context, callerPackage: String, captureObserved: Boolean = true): SourceType {
        val queryDue = queryPolicy.shouldQuery(SystemClock.uptimeMillis(), captureObserved)
        val cached = cachedSource
        if (cachedPkg == callerPackage && cached != null && !queryDue) return cached

        val src = queryProvider(ctx, callerPackage)
        cachedSource = src
        cachedPkg = callerPackage
        queryPolicy.onQuery(SystemClock.uptimeMillis(), lastReachable)
        return src
    }

    /** Keep evidence locally: trying to report a failed Provider through itself would recurse. */
    fun reportProviderFailure(callerPackage: String, operation: String, reason: String) {
        val now = SystemClock.uptimeMillis()
        val shouldLog = synchronized(providerFailures) {
            val old = providerFailures[operation]
            val log = old == null || old.reason != reason || now - old.lastLogAt >= 30_000L
            providerFailures[operation] = ProviderFailure(
                reason, old?.firstAt ?: System.currentTimeMillis(), System.currentTimeMillis(),
                (old?.count ?: 0L) + 1L, if (log) now else old!!.lastLogAt
            )
            if (providerFailures.size > 8) providerFailures.remove(providerFailures.keys.first())
            log
        }
        if (shouldLog) Log.w("GlassMic-Provider",
            "package=$callerPackage operation=$operation reason=$reason; " +
                "check service state, package visibility and Hide My Applist rules")
    }

    fun flushProviderFailures(ctx: Context, callerPackage: String, operationPrefix: String) {
        val pending = synchronized(providerFailures) {
            providerFailures.filterKeys { it.startsWith(operationPrefix) }.also {
                it.keys.forEach(providerFailures::remove)
            }
        }
        pending.forEach { (operation, failure) ->
            reportDiagnosticEvent(ctx, callerPackage, "provider_access_recovered", Bundle().apply {
                putString("path", operation)
                putString("reason", failure.reason)
                putString("detail", "failures=${failure.count} first=${failure.firstAt} last=${failure.lastAt}")
            })
        }
    }

    /** Pipe data metrics are separate from requested hook bytes (which may include zero padding). */
    fun reportPcmReadStats(ctx: Context, stats: Bundle) {
        runCatching {
            pcmReportExecutor.execute {
                runCatching {
                    ctx.contentResolver.call(
                        Uri.parse("content://${Constants.PROVIDER_RUNTIME}"),
                        Constants.METHOD_PCM_READ_STATS, ctx.packageName, stats
                    )
                }
            }
        }
    }

    private fun queryProvider(ctx: Context, pkg: String): SourceType {
        val uri = Uri.parse("content://${Constants.PROVIDER_RUNTIME}/resolve")
        // selectionArgs[1] 带上 api 版本，供 RuntimeProvider 顺手更新「模块已激活」诊断状态，
        // 免去在注入进程 attach 阶段单独 ping（那是复活的主要来源）。
        return runCatching {
            val c = ctx.contentResolver.query(uri, null, null, arrayOf(pkg, apiVersion.toString()), null)
            if (c == null) {
                lastReachable = false
                reportProviderFailure(pkg, "runtime.query", "NULL_CURSOR")
                return SourceType.REAL_MIC
            }
            c.use {
                if (!it.moveToFirst()) {
                    lastReachable = false
                    reportProviderFailure(pkg, "runtime.query", "EMPTY_CURSOR")
                    return SourceType.REAL_MIC
                }
                val source = SourceType.valueOf(it.getString(0))
                if (!lastReachable) Log.i("GlassMic-Provider", "runtime access recovered package=$pkg")
                lastReachable = true
                flushProviderFailures(ctx, pkg, "runtime.")
                source
            }
        }.getOrElse {
            lastReachable = false
            reportProviderFailure(pkg, "runtime.query", it.javaClass.simpleName)
            SourceType.REAL_MIC
        }
    }
}
