package io.mo.glassmic.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.mo.glassmic.core.Constants
import io.mo.glassmic.data.runtime.EffectiveSourceResolver
import org.json.JSONArray
import org.json.JSONObject

/**
 * 给 Xposed 进程查询"针对某个调用方包名，应该用什么音源"。
 *
 * URI: content://io.mo.glassmic.provider.runtime/resolve
 * selectionArgs[0] = 调用方自报包名（仅作兜底；以 Binder 校验过的 callingPackage 为准）
 * 返回单行游标 (sourceType, groupId, audioId, globalSwitch)
 *
 * 安全：所有入口都以 [callerPackage]（AppOps 按调用方 UID 校验过的包名）为准，
 * 自报的包名只在系统给不出 callingPackage 时兜底，防止任意 App 冒充别的包查决策或写诊断。
 *
 * Hilt 限制：不能直接 @AndroidEntryPoint。
 * 用 EntryPointAccessors 在 query 时按需解析依赖——这样无论 Provider.onCreate 是否
 * 在 Application.onCreate 之前都不会 NPE。
 */
class RuntimeProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RuntimeEntryPoint {
        fun resolver(): EffectiveSourceResolver
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val pkg = callerPackage(selectionArgs?.firstOrNull())
        val resolver = entryPoint().resolver()
        val src = resolver.resolve(pkg)
        // 查询只证明模块可以通信；空闲 native poller 也会查询，不能视为成功替换。
        recordPackageState(pkg, "last_query_ms", System.currentTimeMillis(), src.name)
        recordPing(pkg, selectionArgs?.getOrNull(1)?.toIntOrNull() ?: 0)
        val snap = resolver.configSnapshot()
        return MatrixCursor(arrayOf("source", "group_id", "audio_id", "global_switch"))
            .apply { addRow(arrayOf<Any>(src.name, "", "", if (snap.globalSwitch) 1 else 0)) }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        when (method) {
            Constants.METHOD_AUDIO_DIAG_EVENT -> {
                if (extras == null) return null
                val prefs = context?.getSharedPreferences(Constants.AUDIO_STATS_PREFS, Context.MODE_PRIVATE)
                val now = extras.getLong("time").takeIf { it > 0L } ?: System.currentTimeMillis()
                val event = extras.getString("event").orEmpty().take(64)
                if (event.isBlank()) return null
                val pkg = callerPackage(extras.getString("package") ?: arg)
                if (event == "native_hooks_installed" || event == "native_hooks_failed") {
                    recordPackageState(pkg, "native_hook_installed_ms", now,
                        extras.getString("detail") ?: if (event == "native_hooks_failed") "none" else null)
                }
                if (event == "native_capture_active") recordPackageState(pkg, "last_capture_ms", now)
                if (event == "provider_access_recovered") {
                    recordPackageState(pkg, "provider_recovered_ms", now,
                        "${extras.getString("path")}: ${extras.getString("reason")}")
                }

                synchronized(statsLock) {
                    val timeline = runCatching {
                        JSONArray(prefs?.getString(Constants.AUDIO_STATS_TIMELINE, null) ?: "[]")
                    }.getOrElse { JSONArray() }

                    val item = JSONObject().apply {
                        put("time", now)
                        put("event", event)
                        put("package", pkg)
                        put("pid", extras.getInt("pid", -1))
                        for (key in arrayOf(
                            "source", "path", "reason", "detail"
                        )) {
                            extras.getString(key)?.takeIf { it.isNotBlank() }?.let { put(key, it.take(160)) }
                        }
                        for (key in arrayOf(
                            "reads", "missing_frames", "requested_frames", "skipped_source_frames",
                            "capture_age_ms", "value_bytes", "sample_rate", "channels"
                        )) {
                            if (extras.containsKey(key)) put(key, extras.getLong(key))
                        }
                        if (extras.containsKey("pcm_fd_active")) {
                            put("pcm_fd_active", extras.getBoolean("pcm_fd_active"))
                        }
                    }

                    val trimmed = JSONArray()
                    val start = (timeline.length() - DIAG_TIMELINE_LIMIT + 1).coerceAtLeast(0)
                    for (i in start until timeline.length()) {
                        trimmed.put(timeline.opt(i))
                    }
                    trimmed.put(item)
                    prefs?.edit()?.putString(Constants.AUDIO_STATS_TIMELINE, trimmed.toString())?.apply()
                }
                return Bundle().apply { putBoolean("ok", true) }
            }
            Constants.METHOD_PCM_READ_STATS -> {
                if (extras == null) return null
                val pkg = callerPackage(arg)
                val prefs = context?.getSharedPreferences(Constants.AUDIO_STATS_PREFS, Context.MODE_PRIVATE)
                synchronized(statsLock) {
                    val previous = runCatching {
                        JSONObject(prefs?.getString(Constants.AUDIO_STATS_PCM_DIAGNOSTICS, null) ?: "{}")
                    }.getOrElse { JSONObject() }
                    val window = JSONObject().apply {
                        put("time", System.currentTimeMillis())
                        put("package", pkg)
                        put("pid", extras.getInt("pid"))
                        put("reader_id", extras.getInt("reader_id"))
                        put("sample_rate", extras.getInt("sample_rate"))
                        put("channels", extras.getInt("channels"))
                        put("path", "AudioRecord.pipe.PCM16")
                        for (key in arrayOf("reads", "requested_pcm16_bytes", "source_pcm16_bytes",
                            "zero_fill_pcm16_bytes", "short_reads", "errors")) put(key, extras.getLong(key))
                        put("rms_pcm16", extras.getDouble("rms_pcm16"))
                        put("peak_pcm16", extras.getInt("peak_pcm16"))
                    }
                    val lastShort = if (extras.getLong("short_reads") > 0) window else previous.optJSONObject("last_short_read")
                    val lastError = if (extras.getLong("errors") > 0) window else previous.optJSONObject("last_error")
                    prefs?.edit()?.putString(Constants.AUDIO_STATS_PCM_DIAGNOSTICS, JSONObject().apply {
                        put("latest", window)
                        if (lastShort != null) put("last_short_read", lastShort)
                        if (lastError != null) put("last_error", lastError)
                    }.toString())?.apply()
                }
                return Bundle().apply { putBoolean("ok", true) }
            }
            Constants.METHOD_XPOSED_PING -> {
                val pkg = callerPackage(extras?.getString("package") ?: arg)
                val now = extras?.getLong("time")?.takeIf { it > 0L } ?: System.currentTimeMillis()
                val prefs = context?.getSharedPreferences(Constants.XPOSED_STATUS_PREFS, Context.MODE_PRIVATE)
                val api = extras?.getInt("api") ?: 101
                val oldApi = prefs?.getInt(Constants.XPOSED_STATUS_API, 0) ?: 0
                prefs?.edit()
                    ?.putLong(Constants.XPOSED_STATUS_LAST_PING, now)
                    ?.putString(Constants.XPOSED_STATUS_LAST_PACKAGE, pkg)
                    ?.putInt(Constants.XPOSED_STATUS_API, maxOf(oldApi, api))
                    ?.apply()
                return Bundle().apply { putBoolean("ok", true) }
            }
            Constants.METHOD_AUDIO_INTERCEPT -> {
                val pkg = callerPackage(extras?.getString("package") ?: arg)
                val now = extras?.getLong("time")?.takeIf { it > 0L } ?: System.currentTimeMillis()
                val deltaReads = extras?.getInt("delta_reads") ?: 0
                val deltaBytes = extras?.getLong("delta_bytes") ?: 0L
                val sampleRate = extras?.getInt("sample_rate") ?: 0
                val channels = extras?.getInt("channels") ?: 0
                if (deltaReads > 0) recordPackageState(pkg, "last_intercept_ms", now)
                // native 轮询录音期间每 80ms 上报一次。累计值先放内存，最多每秒落一次盘——
                // 每次都 apply() 会把整个 prefs 文件（含 160 条时间线 JSON）重写一遍。
                val flushNow = synchronized(statsLock) {
                    pendingIntercept.add(pkg, now, deltaReads, deltaBytes, sampleRate, channels)
                    extras?.getBundle("native_stats")?.let { native ->
                        pendingIntercept.addNativeWindow(JSONObject().apply {
                            put("time", now)
                            put("package", pkg)
                            put("path", native.getString("path") ?: "unknown")
                            put("sample_rate", sampleRate)
                            put("channels", channels)
                            put("reads", deltaReads)
                            put("source_bytes", deltaBytes)
                            put("underrun_reads", native.getLong("underrun_reads"))
                            put("missing_frames", native.getLong("missing_frames"))
                            put("requested_frames", native.getLong("requested_frames"))
                            put("skipped_source_frames", native.getLong("skipped_source_frames"))
                            put("native_capture_age_ms", native.getLong("native_capture_age_ms", -1L))
                            put("pcm_fd_active", native.getBoolean("pcm_fd_active", false))
                        }, underrun = native.getLong("underrun_reads") > 0,
                            overrun = native.getLong("skipped_source_frames") > 0)
                    }
                    SystemClock.uptimeMillis() - lastStatsFlushAt >= STATS_FLUSH_INTERVAL_MS
                }
                if (flushNow) flushInterceptStats() else scheduleInterceptFlush()
                return Bundle().apply { putBoolean("ok", true) }
            }
        }
        return super.call(method, arg, extras)
    }

    /** Binder 校验过的调用方包名优先；系统给不出时才用自报值。 */
    private fun callerPackage(claimed: String?): String {
        // 包名与调用方 UID 对不上时 getCallingPackage 抛 SecurityException——这正是冒充，
        // 不能退回自报值。
        val verified = try { callingPackage } catch (_: SecurityException) { return "unknown" }
        val safeClaimed = claimed?.takeIf { it.isNotBlank() }?.take(MAX_PACKAGE_LENGTH)
        // 本进程自己的诊断工具可以代查任意包。
        if (verified != null && verified == context?.packageName) return safeClaimed ?: verified
        return verified ?: safeClaimed ?: "unknown"
    }

    private fun scheduleInterceptFlush() {
        synchronized(statsLock) {
            if (flushScheduled) return
            flushScheduled = true
        }
        // 录音停止后不会再有上报，必须有一次延迟落盘把最后一段累计值写进去。
        flushHandler.postDelayed({ flushInterceptStats() }, STATS_FLUSH_INTERVAL_MS)
    }

    private fun flushInterceptStats() {
        val prefs = context?.getSharedPreferences(Constants.AUDIO_STATS_PREFS, Context.MODE_PRIVATE)
            ?: return
        synchronized(statsLock) {
            flushScheduled = false
            lastStatsFlushAt = SystemClock.uptimeMillis()
            val pending = pendingIntercept
            if (pending.isEmpty()) return
            pendingIntercept = PendingIntercept()
            val editor = prefs.edit()
                .putLong(Constants.AUDIO_STATS_TOTAL_READS,
                    prefs.getLong(Constants.AUDIO_STATS_TOTAL_READS, 0L) + pending.reads)
                .putLong(Constants.AUDIO_STATS_TOTAL_BYTES,
                    prefs.getLong(Constants.AUDIO_STATS_TOTAL_BYTES, 0L) + pending.bytes)
                .putLong(Constants.AUDIO_STATS_LAST_INTERCEPT, pending.lastTime)
                .putString(Constants.AUDIO_STATS_LAST_PACKAGE, pending.lastPackage)
                .putInt(Constants.AUDIO_STATS_LAST_SAMPLE_RATE, pending.lastSampleRate)
                .putInt(Constants.AUDIO_STATS_LAST_CHANNELS, pending.lastChannels)
            pending.latestNative?.let { latest ->
                // 保留最近有欠载/溢出的窗口，暂停或随后正常读取不会抹掉故障证据。
                val previous = runCatching {
                    JSONObject(prefs.getString(Constants.AUDIO_STATS_NATIVE_DIAGNOSTICS, null) ?: "{}")
                }.getOrElse { JSONObject() }
                val lastUnderrun = pending.lastUnderrun ?: previous.optJSONObject("last_underrun")
                val lastOverrun = pending.lastOverrun ?: previous.optJSONObject("last_overrun")
                editor.putString(Constants.AUDIO_STATS_NATIVE_DIAGNOSTICS, JSONObject().apply {
                    put("latest", latest)
                    if (lastUnderrun != null) put("last_underrun", lastUnderrun)
                    if (lastOverrun != null) put("last_overrun", lastOverrun)
                }.toString())
            }
            editor.apply()
        }
    }

    /** 两次落盘之间累计的拦截统计。只在 [statsLock] 内访问。 */
    private class PendingIntercept {
        var reads = 0L
        var bytes = 0L
        var lastTime = 0L
        var lastPackage: String? = null
        var lastSampleRate = 0
        var lastChannels = 0
        var latestNative: JSONObject? = null
        var lastUnderrun: JSONObject? = null
        var lastOverrun: JSONObject? = null

        fun isEmpty() = lastPackage == null

        fun add(pkg: String, now: Long, deltaReads: Int, deltaBytes: Long, sampleRate: Int, channels: Int) {
            reads += deltaReads
            bytes += deltaBytes
            lastTime = now
            lastPackage = pkg
            lastSampleRate = sampleRate
            lastChannels = channels
        }

        fun addNativeWindow(window: JSONObject, underrun: Boolean, overrun: Boolean) {
            latestNative = window
            if (underrun) lastUnderrun = window
            if (overrun) lastOverrun = window
        }
    }

    /** 按包保留查询、安装、录音活动与实际替换证据，字段分别更新，不推导不存在的阶段。 */
    private fun recordPackageState(pkg: String, field: String, now: Long, detail: String? = null) {
        if (pkg.isBlank()) return
        synchronized(statsLock) {
            val key = pkg to field
            val previous = lastPackageWrites[key]
            if (previous != null && now - previous.first < PING_WRITE_THROTTLE_MS && previous.second == detail) return
            runCatching {
                val prefs = context?.getSharedPreferences(Constants.XPOSED_STATUS_PREFS, Context.MODE_PRIVATE) ?: return
                val states = JSONObject(prefs.getString(Constants.XPOSED_STATUS_PACKAGES, "{}") ?: "{}")
                val state = states.optJSONObject(pkg) ?: JSONObject()
                state.put(field, now)
                if (detail != null) state.put("${field}_detail", detail.take(160))
                state.put("updated_at", now)
                states.put(pkg, state)
                if (states.length() > 64) {
                    val oldest = states.keys().asSequence().minByOrNull {
                        states.optJSONObject(it)?.optLong("updated_at") ?: 0L
                    }
                    if (oldest != null) states.remove(oldest)
                }
                prefs.edit().putString(Constants.XPOSED_STATUS_PACKAGES, states.toString()).apply()
                lastPackageWrites.remove(key)
                lastPackageWrites[key] = now to detail
                if (lastPackageWrites.size > 256) lastPackageWrites.remove(lastPackageWrites.keys.first())
            }
        }
    }

    /** 全局通信心跳只用于说明 Provider 可达，不表示当前 App 正在录音。 */
    private fun recordPing(pkg: String, api: Int) {
        val now = System.currentTimeMillis()
        if (now - lastPingWrite < PING_WRITE_THROTTLE_MS) return
        lastPingWrite = now
        runCatching {
            val prefs = context?.getSharedPreferences(
                Constants.XPOSED_STATUS_PREFS, Context.MODE_PRIVATE
            ) ?: return
            val oldApi = prefs.getInt(Constants.XPOSED_STATUS_API, 0)
            prefs.edit()
                .putLong(Constants.XPOSED_STATUS_LAST_PING, now)
                .putString(Constants.XPOSED_STATUS_LAST_PACKAGE, pkg)
                .putInt(Constants.XPOSED_STATUS_API, maxOf(oldApi, api))
                .apply()
        }
    }

    private fun entryPoint(): RuntimeEntryPoint =
        EntryPointAccessors.fromApplication(
            context!!.applicationContext, RuntimeEntryPoint::class.java
        )

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/glassmic.runtime"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        private val statsLock = Any()
        private val flushHandler by lazy { Handler(Looper.getMainLooper()) }
        private var pendingIntercept = PendingIntercept()
        private var lastStatsFlushAt = 0L
        private var flushScheduled = false
        private const val STATS_FLUSH_INTERVAL_MS = 1_000L
        private const val MAX_PACKAGE_LENGTH = 256
        private val lastPackageWrites = LinkedHashMap<Pair<String, String>, Pair<Long, String?>>()
        @Volatile private var lastPingWrite = 0L
        private const val PING_WRITE_THROTTLE_MS = 3000L
        private const val DIAG_TIMELINE_LIMIT = 160
    }
}
