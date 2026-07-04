package io.mo.glassmic.data.runtime

import io.mo.glassmic.core.Constants
import io.mo.glassmic.core.model.ConfigSnapshot
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.core.util.ScopeMatcher
import io.mo.glassmic.data.config.ConfigStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §11 状态优先级唯一权威实现。
 *
 * 优先级：
 *   安全模式 > 重启后默认关闭 > 首次启动门禁 > 全局总开关 > 生效范围 > 当前音源
 *
 * 任何调用方都不允许自行拼装这一逻辑——必须走这里。
 */
@Singleton
class EffectiveSourceResolver @Inject constructor(
    private val safeMode: SafeModeRepository,
    private val bootGate: BootGateRepository,
    private val configStore: ConfigStore,
    private val runtime: RuntimeStateHolder
) {

    /**
     * 返回 [callerPackage] 当前命中后实际应使用的音源类型。
     *
     * @return REAL_MIC 表示不拦截原始麦克风
     */
    fun resolve(callerPackage: String): SourceType {
        // 1. 安全模式
        if (safeMode.isActive()) return SourceType.REAL_MIC
        // 2. 重启后默认关闭
        if (!bootGate.userEnabledAfterBoot()) return SourceType.REAL_MIC
        // 3. 首次启动门禁
        val snap = configStore.snapshotBlocking()
        if (!snap.onboardingCompleted) return SourceType.REAL_MIC
        // 4. 全局总开关 + 5. 生效范围
        if (!ScopeMatcher.matches(callerPackage, snap)) return SourceType.REAL_MIC
        // 6. 不 hook 自己
        if (callerPackage == Constants.APP_PACKAGE) return SourceType.REAL_MIC
        // 7. 前台服务 / 运行态必须仍然开启
        val rt = runtime.value
        if (!rt.enabled) return SourceType.REAL_MIC
        // 8. 当前运行态决定的音源。
        //    TTS 与 FILE 一样都是"从 PCM 管线注入"，对 Xposed 侧统一按 FILE 处理，
        //    使 hook 端保持 REAL_MIC / FILE / SILENCE 三态契约不变。
        return when (rt.currentSourceType) {
            SourceType.TTS -> SourceType.FILE
            else -> rt.currentSourceType
        }
    }

    /** 给 UI / 通知/ 悬浮窗显示用——和 resolve() 不同，这里返回是否模块整体在运行 */
    fun isModuleActiveForUi(): Boolean {
        if (safeMode.isActive()) return false
        if (!bootGate.userEnabledAfterBoot()) return false
        return runtime.value.enabled
    }

    fun configSnapshot(): ConfigSnapshot = configStore.snapshotBlocking()
}
