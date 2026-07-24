package io.mo.glassmic.xposed

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import io.mo.glassmic.core.Constants
import io.mo.glassmic.core.model.SourceType
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 注入到目标 App 进程后，只通过 GlassMic 自己的 ContentProvider 读取实时决策。
 *
 * API 101 不再依赖旧版 XSharedPreferences；黑白名单、首次启动、BootGate、运行态等
 * 统一由 app 进程里的 EffectiveSourceResolver 计算，Xposed 侧只缓存短时间结果。
 */
object XBridge {

    private const val CACHE_TTL_MS = 50L
    // Provider 被禁用/不可达（= 前台服务未运行）时的回退缓存时长。
    // 拉长它，避免服务关闭期间某个 App 仍在录音时，每 50ms 就对已禁用的 Provider 空查询一次。
    private const val UNREACHABLE_TTL_MS = 2000L

    private var cachedSource: SourceType? = null
    private var cachedAt: Long = 0L
    private var cachedPkg: String = ""
    // 上次查询 Provider 是否可达（false = 服务未运行 / Provider 被禁用）
    @Volatile private var lastReachable: Boolean = true

    @Volatile var currentPackage: String = ""

    /** 注入入口设置：101 = libxposed API 101，82 = legacy。仅用于诊断状态展示。 */
    @Volatile var apiVersion: Int = 101

    fun pingModuleLoaded(ctx: Context, callerPackage: String, api: Int = 101) {
        runCatching {
            val extras = Bundle().apply {
                putString("package", callerPackage)
                putLong("time", System.currentTimeMillis())
                putInt("api", api)
            }
            ctx.contentResolver.call(
                Uri.parse("content://${Constants.PROVIDER_RUNTIME}"),
                Constants.METHOD_XPOSED_PING,
                callerPackage,
                extras
            )
        }
    }

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
        channels: Int
    ) {
        if (deltaReads <= 0 || deltaBytes <= 0) return
        reportInterceptStats(ctx, callerPackage, deltaReads, deltaBytes, sampleRate, channels)
    }

    private fun reportInterceptStats(
        ctx: Context,
        callerPackage: String,
        deltaReads: Int,
        deltaBytes: Long,
        sampleRate: Int,
        channels: Int
    ) {
        runCatching {
            val extras = Bundle().apply {
                putString("package", callerPackage)
                putLong("time", System.currentTimeMillis())
                putInt("delta_reads", deltaReads)
                putLong("delta_bytes", deltaBytes)
                putInt("sample_rate", sampleRate)
                putInt("channels", channels)
            }
            ctx.contentResolver.call(
                Uri.parse("content://${Constants.PROVIDER_RUNTIME}"),
                Constants.METHOD_AUDIO_INTERCEPT,
                callerPackage,
                extras
            )
        }
    }

    fun resolveSource(ctx: Context, callerPackage: String): SourceType {
        val now = System.currentTimeMillis()
        val ttl = if (lastReachable) CACHE_TTL_MS else UNREACHABLE_TTL_MS
        if (cachedPkg == callerPackage && cachedSource != null && now - cachedAt < ttl) {
            return cachedSource!!
        }

        val src = queryProvider(ctx.contentResolver, callerPackage)
        cachedSource = src
        cachedAt = now
        cachedPkg = callerPackage
        return src
    }

    private fun queryProvider(cr: ContentResolver, pkg: String): SourceType {
        val uri = Uri.parse("content://${Constants.PROVIDER_RUNTIME}/resolve")
        // selectionArgs[1] 带上 api 版本，供 RuntimeProvider 顺手更新「模块已激活」诊断状态，
        // 免去在注入进程 attach 阶段单独 ping（那是复活的主要来源）。
        return runCatching {
            val c = cr.query(uri, null, null, arrayOf(pkg, apiVersion.toString()), null)
            if (c == null) {
                // Provider 被禁用（服务未运行）→ AMS 不解析、不拉起本进程，直接走真麦
                lastReachable = false
                return SourceType.REAL_MIC
            }
            c.use {
                lastReachable = true
                if (it.moveToFirst()) SourceType.valueOf(it.getString(0)) else SourceType.REAL_MIC
            }
        }.getOrElse {
            lastReachable = false
            SourceType.REAL_MIC
        }
    }
}
