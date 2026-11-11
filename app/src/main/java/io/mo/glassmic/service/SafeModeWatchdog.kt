package io.mo.glassmic.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.model.SafeModeReason
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.data.runtime.SafeModeRepository
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 安全模式监控器。
 *
 * 触发条件（任一）：
 * 1. 音频引擎连续异常 ≥ 3 次（中间只要有一次真实音源读成功就清零）
 * 2. 上次开机未正常退出（在 Application.onCreate 已处理）
 * 3. 用户手动紧急停用
 *
 * 曾经的"SystemUI 重启检测"已移除：Android 10+ 上普通应用拿不到 SystemUI 的 PID，
 * 只能靠 processesInErrorState 间接判断，同一个错误状态会被 2 秒轮询重复计数，
 * 一次崩溃就可能误触发安全模式，轮询本身还让进程常驻唤醒。
 * [SafeModeReason.SYSTEM_UI_REPEATED_CRASH] 等枚举值保留，以便读取旧版本持久化的状态。
 */
@Singleton
class SafeModeWatchdog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val safeModeRepo: SafeModeRepository,
    private val runtime: RuntimeStateHolder
) {
    private val audioEngineFailures = AtomicInteger(0)

    fun onAudioEngineFailure() {
        if (audioEngineFailures.incrementAndGet() >= 3) {
            trigger(SafeModeReason.AUDIO_ENGINE_CONTINUOUS_FAILURE)
        }
    }

    /** 真实音源读取成功。广播线程每帧都会调用，先读后写避免无谓的缓存行争用。 */
    fun onAudioEngineHealthy() {
        if (audioEngineFailures.get() != 0) audioEngineFailures.set(0)
    }

    fun emergencyStop() {
        trigger(SafeModeReason.USER_EMERGENCY_STOP)
    }

    private fun trigger(reason: SafeModeReason) {
        if (safeModeRepo.isActive()) return
        safeModeRepo.activate(reason, System.currentTimeMillis())
        runtime.setSafeMode(true)
        runtime.setEnabled(false)
        // 通知前台服务停止——通过广播或直接 stopService
        context.stopService(android.content.Intent(context, GlassForegroundService::class.java))
    }
}
