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
 * 开关状态要被 **system_server**（在开机极早期的 onSystemServerStarting 阶段）读到。经实测，
 * 依赖 LSPosed remote preferences（MODE_WORLD_READABLE）在那个时刻并不可靠——可能尚未就绪
 * 或写入机制不匹配，导致读到默认值。
 *
 * 因此**主路径改用 `persist` 系统属性**：GlassMic 面向 Root 用户，开关时用 `su setprop` 写入
 * [Constants.PROP_VISIBILITY_COMPAT]，system_server 开机早期即可稳定读取（见 SystemVisibilityHook
 * 与 GlassMicXposedModule.isVisibilityCompatEnabled，prop 优先）。
 *
 * 同时仍写一份本地 MODE_PRIVATE prefs，仅用于 App 内 UI 回显开关状态。
 */
@Singleton
class VisibilityCompatRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // 后备路径：MODE_WORLD_READABLE，供 LSPosed remote preferences 暴露给 system_server
    // （当 prop 路径在某些 ROM 上不可用时兜底）；失败则退回 MODE_PRIVATE（仅本地回显）。
    private val prefs: SharedPreferences by lazy {
        @Suppress("DEPRECATION", "WorldReadableFiles")
        runCatching {
            context.getSharedPreferences(Constants.REMOTE_PREFS, Context.MODE_WORLD_READABLE)
        }.getOrElse {
            context.getSharedPreferences(Constants.REMOTE_PREFS, Context.MODE_PRIVATE)
        }
    }

    /** UI 回显：优先以系统属性为准（真正决定 system_server 行为的来源），回退本地 prefs。 */
    fun isEnabled(): Boolean {
        readPropOrNull()?.let { return it }
        return runCatching { prefs.getBoolean(Constants.KEY_VISIBILITY_COMPAT, false) }.getOrDefault(false)
    }

    /**
     * 写开关。**阻塞**（含 su 调用），调用方需放在 IO 线程。
     * @return 是否成功写入系统属性（false 多半是 Root 未授权）。
     */
    fun setEnabled(enabled: Boolean): Boolean {
        runCatching { prefs.edit().putBoolean(Constants.KEY_VISIBILITY_COMPAT, enabled).apply() }
        val v = if (enabled) "1" else "0"
        runCatching {
            // 用登录 shell 执行，兼容不同 su 实现；setprop 后立刻在同一条命令里读回。
            Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "setprop ${Constants.PROP_VISIBILITY_COMPAT} $v"))
                .waitFor()
        }
        // 关键：以「读回值是否等于期望」作为成功判据——setprop 即便返回 0，也可能被
        // SELinux 静默拒绝；只有读回一致才算真的写进去了。
        return readPropOrNull() == enabled
    }

    private fun readPropOrNull(): Boolean? = runCatching {
        @Suppress("PrivateApi")
        val sp = Class.forName("android.os.SystemProperties")
        val get = sp.getMethod("get", String::class.java, String::class.java)
        when ((get.invoke(null, Constants.PROP_VISIBILITY_COMPAT, "") as? String)) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }.getOrNull()
}
