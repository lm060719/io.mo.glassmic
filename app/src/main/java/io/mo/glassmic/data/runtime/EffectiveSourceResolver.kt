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

    private val history = DecisionHistory()

    /**
     * 返回 [callerPackage] 当前命中后实际应使用的音源类型。
     *
     * @return REAL_MIC 表示不拦截原始麦克风
     */
    fun resolve(callerPackage: String, forAudioPolicy: Boolean = false): SourceType {
        val now = System.currentTimeMillis()
        val (src, code, desc) = resolveInternal(callerPackage, forAudioPolicy)
        history.record(DecisionRecord(now, callerPackage, src, code, desc,
            backend = if (forAudioPolicy) "AUDIO_POLICY" else "LSPOSED"))
        return src
    }

    private fun resolveInternal(callerPackage: String, forAudioPolicy: Boolean): Triple<SourceType, String, String> {
        // 1. 安全模式
        if (safeMode.isActive()) {
            return Triple(SourceType.REAL_MIC, "SAFE_MODE", "安全模式已激活 (Safe mode active)")
        }
        // 2. 重启后默认关闭
        if (!bootGate.userEnabledAfterBoot()) {
            return Triple(SourceType.REAL_MIC, "BOOT_GATE_LOCKED", "设备重启后保护未解锁 (Boot gate locked)")
        }
        // 3. 首次启动门禁
        val snap = configStore.snapshotBlocking()
        // Both backends share the same gates, but only the selected backend may replace PCM.
        if (snap.audioPolicyBackend != forAudioPolicy) {
            return Triple(SourceType.REAL_MIC, "OTHER_BACKEND", "由另一注入后端处理 (Other backend selected)")
        }
        if (!snap.onboardingCompleted) {
            return Triple(SourceType.REAL_MIC, "ONBOARDING_INCOMPLETE", "首次引导未完成 (Onboarding incomplete)")
        }
        // 4. 全局总开关
        if (!snap.globalSwitch) {
            return Triple(SourceType.REAL_MIC, "GLOBAL_SWITCH_OFF", "全局总开关已关闭 (Global switch off)")
        }
        // 5. 不 hook 自己
        if (callerPackage == Constants.APP_PACKAGE) {
            return Triple(SourceType.REAL_MIC, "SELF_PACKAGE", "跳过本模块自身进程 (Self package skip)")
        }
        // 6. 生效范围
        val inScope = if (forAudioPolicy) callerPackage.isNotBlank() &&
                (snap.audioPolicyAllApps || callerPackage in snap.audioPolicyPackages)
            else ScopeMatcher.matches(callerPackage, snap)
        if (!inScope) {
            return Triple(SourceType.REAL_MIC, "SCOPE_FILTERED", "未命中生效范围/白名单 (Scope filtered)")
        }
        // 7. 前台服务 / 运行态必须仍然开启
        val rt = runtime.value
        if (!rt.enabled) {
            return Triple(SourceType.REAL_MIC, "SERVICE_DISABLED", "运行态/前台服务未启动 (Service disabled)")
        }
        // 8. 当前运行态决定的音源
        val eff = when (rt.currentSourceType) {
            SourceType.TTS, SourceType.LIVE -> SourceType.FILE
            else -> rt.currentSourceType
        }
        return Triple(eff, "INTERCEPT_OK", "已选择音源 (${eff.name})，实际替换请查看拦截统计")
    }

    fun getRecentDecisions(): List<DecisionRecord> = history.recent()

    /** 给 UI / 通知/ 悬浮窗显示用——和 resolve() 不同，这里返回是否模块整体在运行 */
    fun isModuleActiveForUi(): Boolean {
        if (safeMode.isActive()) return false
        if (!bootGate.userEnabledAfterBoot()) return false
        return runtime.value.enabled
    }

    fun configSnapshot(): ConfigSnapshot = configStore.snapshotBlocking()
}
