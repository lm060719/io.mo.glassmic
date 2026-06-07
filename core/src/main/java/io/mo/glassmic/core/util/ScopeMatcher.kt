package io.mo.glassmic.core.util

import io.mo.glassmic.core.model.ConfigSnapshot
import io.mo.glassmic.core.model.ScopeMode

/**
 * 纯函数式的生效范围判断。
 *
 * 注意：本方法被 App UI 进程和 Xposed 注入进程同时调用，必须无副作用。
 */
object ScopeMatcher {

    fun matches(callerPackage: String, config: ConfigSnapshot): Boolean {
        if (!config.globalSwitch) return false
        if (!config.onboardingCompleted) return false
        // 永远不 hook 自己——避免悬浮窗服务自录自播
        if (callerPackage == "io.mo.glassmic") return false

        return when (config.scopeMode) {
            ScopeMode.GLOBAL -> true
            ScopeMode.WHITELIST -> callerPackage in config.whitelist
            ScopeMode.BLACKLIST -> callerPackage !in config.blacklist
        }
    }
}
