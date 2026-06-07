package io.mo.glassmic.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.mo.glassmic.core.Constants
import io.mo.glassmic.data.runtime.EffectiveSourceResolver

/**
 * 给 Xposed 进程查询"针对某个调用方包名，应该用什么音源"。
 *
 * URI: content://io.mo.glassmic.provider.runtime/resolve
 * selectionArgs[0] = 调用方包名
 * 返回单行游标 (sourceType, groupId, audioId, globalSwitch)
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
        val pkg = selectionArgs?.firstOrNull() ?: ""
        val resolver = entryPoint().resolver()
        val src = resolver.resolve(pkg)
        val snap = resolver.configSnapshot()
        return MatrixCursor(arrayOf("source", "group_id", "audio_id", "global_switch"))
            .apply { addRow(arrayOf(src.name, "", "", if (snap.globalSwitch) 1 else 0)) }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        when (method) {
            Constants.METHOD_XPOSED_PING -> {
                val pkg = extras?.getString("package") ?: arg ?: callingPackage ?: "unknown"
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
                val pkg = extras?.getString("package") ?: arg ?: callingPackage ?: "unknown"
                val now = extras?.getLong("time")?.takeIf { it > 0L } ?: System.currentTimeMillis()
                val deltaReads = extras?.getInt("delta_reads") ?: 0
                val deltaBytes = extras?.getLong("delta_bytes") ?: 0L
                val sampleRate = extras?.getInt("sample_rate") ?: 0
                val channels = extras?.getInt("channels") ?: 0
                val prefs = context?.getSharedPreferences(Constants.AUDIO_STATS_PREFS, Context.MODE_PRIVATE)
                val oldReads = prefs?.getLong(Constants.AUDIO_STATS_TOTAL_READS, 0L) ?: 0L
                val oldBytes = prefs?.getLong(Constants.AUDIO_STATS_TOTAL_BYTES, 0L) ?: 0L
                prefs?.edit()
                    ?.putLong(Constants.AUDIO_STATS_TOTAL_READS, oldReads + deltaReads)
                    ?.putLong(Constants.AUDIO_STATS_TOTAL_BYTES, oldBytes + deltaBytes)
                    ?.putLong(Constants.AUDIO_STATS_LAST_INTERCEPT, now)
                    ?.putString(Constants.AUDIO_STATS_LAST_PACKAGE, pkg)
                    ?.putInt(Constants.AUDIO_STATS_LAST_SAMPLE_RATE, sampleRate)
                    ?.putInt(Constants.AUDIO_STATS_LAST_CHANNELS, channels)
                    ?.apply()
                return Bundle().apply { putBoolean("ok", true) }
            }
        }
        return super.call(method, arg, extras)
    }

    private fun entryPoint(): RuntimeEntryPoint =
        EntryPointAccessors.fromApplication(
            context!!.applicationContext, RuntimeEntryPoint::class.java
        )

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/glassmic.runtime"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
