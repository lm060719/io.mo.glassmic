package io.mo.glassmic.data.runtime

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.Constants
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「严格 ROM 兼容」开关的存取。
 *
 * 关键：用 [Context.MODE_WORLD_READABLE] 写入，让 LSPosed 把这份 SharedPreferences 暴露为
 * 模块 remote preferences——这样 system_server 里的 [io.mo.glassmic.xposed.SystemVisibilityHook]
 * 才能通过 `getRemotePreferences` 读到开关状态（普通 MODE_PRIVATE 的 prefs / DataStore
 * system_server 都读不到）。
 *
 * 非 LSPosed 环境（或框架未放行 WORLD_READABLE）下会回退到 MODE_PRIVATE：此时开关仍能在
 * App 内保存与显示，只是 system_server 读不到（也就用不上兼容 hook），但不会崩。
 */
@Singleton
class VisibilityCompatRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        @Suppress("DEPRECATION", "WorldReadableFiles")
        runCatching {
            context.getSharedPreferences(Constants.REMOTE_PREFS, Context.MODE_WORLD_READABLE)
        }.getOrElse {
            context.getSharedPreferences(Constants.REMOTE_PREFS, Context.MODE_PRIVATE)
        }
    }

    fun isEnabled(): Boolean =
        runCatching { prefs.getBoolean(Constants.KEY_VISIBILITY_COMPAT, false) }.getOrDefault(false)

    fun setEnabled(enabled: Boolean) {
        runCatching { prefs.edit().putBoolean(Constants.KEY_VISIBILITY_COMPAT, enabled).apply() }
    }
}
