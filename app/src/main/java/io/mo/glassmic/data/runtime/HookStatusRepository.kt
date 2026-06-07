package io.mo.glassmic.data.runtime

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.Constants
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

enum class HookActivity {
    /** 从未收到过任何 ping，可能未启用模块 / 未重启 / 作用域未生效 */
    NEVER_PINGED,
    /** 最近 5 分钟内收到过 ping，认为注入活跃 */
    ACTIVE,
    /** 收到过 ping 但已超过 5 分钟，可能模块仍在但目标 App 没在录音 */
    STALE
}

data class HookStatus(
    val activity: HookActivity,
    val lastPingMs: Long,
    val lastPackage: String?,
    val api: Int
)

/**
 * 读取 XPOSED_STATUS_PREFS——XBridge.pingModuleLoaded 写入的 last_ping 时间戳。
 *
 * Ping 来源：每个被注入的 App 进程在 Application.attach 阶段会调一次
 * RuntimeProvider#call("xposed_ping")，由 RuntimeProvider 写到这里。
 */
@Singleton
class HookStatusRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(Constants.XPOSED_STATUS_PREFS, Context.MODE_PRIVATE)
    }

    private val activeWindowMs = 5 * 60 * 1000L  // 5 分钟

    val flow: Flow<HookStatus> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(snapshot())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.onStart { emit(snapshot()) }.distinctUntilChanged()

    fun snapshot(): HookStatus {
        val lastPing = prefs.getLong(Constants.XPOSED_STATUS_LAST_PING, 0L)
        val lastPkg = prefs.getString(Constants.XPOSED_STATUS_LAST_PACKAGE, null)
        val api = prefs.getInt(Constants.XPOSED_STATUS_API, 0)
        val now = System.currentTimeMillis()
        val activity = when {
            lastPing == 0L -> HookActivity.NEVER_PINGED
            now - lastPing <= activeWindowMs -> HookActivity.ACTIVE
            else -> HookActivity.STALE
        }
        return HookStatus(
            activity = activity,
            lastPingMs = lastPing,
            lastPackage = lastPkg?.takeIf { it.isNotBlank() },
            api = api
        )
    }
}
