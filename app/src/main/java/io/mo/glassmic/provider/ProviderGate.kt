package io.mo.glassmic.provider

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.mo.glassmic.log.GlassLog

/**
 * 控制 [RuntimeProvider] / [PcmStreamProvider] 两个跨进程 ContentProvider 的启用状态。
 *
 * 背景（「杀掉不到 2 秒又自动复活」的根因）：
 * 被注入的目标 App 进程（微信等）在启动或录音时会访问这两个 Provider。只要 Provider 处于
 * enabled，Android AMS 就会在 GlassMic 进程被杀后**强制拉起宿主进程**来响应这次跨进程访问
 * ——系统把它当作「别的正常 App 在主动调用 GlassMic 的 ContentProvider」，STOPPED 标记也拦不住。
 *
 * 对策：**仅在前台服务真正运行时启用 Provider；服务停止 / 未运行时禁用**。
 * 被禁用的 Provider 无法被 AMS 解析（resolveContentProvider 返回 null），跨进程访问直接拿到
 * null 而**不会拉起进程**。这一个开关就同时覆盖了 API 101 / API 82 / native AAudio 全部注入路径，
 * 无需在注入侧读任何哨兵文件（私有目录文件受 SELinux 隔离，注入进程本就读不到）。
 */
object ProviderGate {

    private val PROVIDERS = arrayOf(
        RuntimeProvider::class.java,
        PcmStreamProvider::class.java
    )

    /** 前台服务启动时调用：放开 Provider，让注入进程能正常查询决策 / 读 PCM。 */
    fun enable(ctx: Context) = setEnabled(ctx, true)

    /** 前台服务停止 / 未运行时调用：禁用 Provider，切断 AMS 强制拉起链路。 */
    fun disable(ctx: Context) = setEnabled(ctx, false)

    private fun setEnabled(ctx: Context, enabled: Boolean) {
        val target = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val pm = ctx.packageManager
        for (cls in PROVIDERS) {
            runCatching {
                val cn = ComponentName(ctx, cls)
                // 已是目标状态就跳过，避免每次都写 package-restrictions.xml
                if (pm.getComponentEnabledSetting(cn) == target) return@runCatching
                pm.setComponentEnabledSetting(
                    cn,
                    target,
                    // 关键：不带 DONT_KILL_APP，系统会在改组件状态时把本进程杀掉
                    PackageManager.DONT_KILL_APP
                )
                GlassLog.b("ProviderGate") {
                    "${cls.simpleName} -> ${if (enabled) "ENABLED" else "DISABLED"}"
                }
            }.onFailure {
                GlassLog.b("ProviderGate") { "toggle ${cls.simpleName} failed: ${it.message}" }
            }
        }
    }
}
