package io.mo.glassmic.service

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.model.SafeModeReason
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.data.runtime.SafeModeRepository
import io.mo.glassmic.data.runtime.SlidingCounter
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 安全模式监控器。
 *
 * 触发条件（任一）：
 * 1. 30 秒内 SystemUI 重启 ≥ 2 次
 * 2. 30 秒内关键系统进程异常 ≥ 2 次
 * 3. 模块初始化连续失败 ≥ 2 次
 * 4. 音频引擎连续异常 ≥ 3 次
 * 5. 上次开机未正常退出（在 Application.onCreate 已处理）
 * 6. 用户手动紧急停用
 */
@Singleton
class SafeModeWatchdog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val safeModeRepo: SafeModeRepository,
    private val runtime: RuntimeStateHolder
) {
    private val window30s = 30_000L
    private val systemUiCounter = SlidingCounter(window30s)
    private val keySysProcCounter = SlidingCounter(window30s)
    private val initFailures = AtomicInteger(0)
    private val audioEngineFailures = AtomicInteger(0)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastSystemUiPid: Int = -1

    fun attach() {
        scope.launch {
            // 周期检查 SystemUI 进程的 PID 变化
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            lastSystemUiPid = findSystemUiPid(am)
            while (isActive) {
                delay(2_000)
                val pid = findSystemUiPid(am)
                if (pid != -1 && lastSystemUiPid != -1 && pid != lastSystemUiPid) {
                    GlassLog.b("Watchdog") { "SystemUI 重启 ($lastSystemUiPid -> $pid)" }
                    onSystemUiCrash()
                }
                lastSystemUiPid = pid
            }
        }
    }

    fun onSystemUiCrash() {
        systemUiCounter.inc()
        if (systemUiCounter.count() >= 2) {
            trigger(SafeModeReason.SYSTEM_UI_REPEATED_CRASH)
        }
    }

    fun onKeySystemProcessCrash() {
        keySysProcCounter.inc()
        if (keySysProcCounter.count() >= 2) {
            trigger(SafeModeReason.KEY_SYSTEM_PROC_CRASH)
        }
    }

    fun onModuleInitFailure() {
        if (initFailures.incrementAndGet() >= 2) {
            trigger(SafeModeReason.MODULE_INIT_FAILURE)
        }
    }

    fun onAudioEngineFailure() {
        if (audioEngineFailures.incrementAndGet() >= 3) {
            trigger(SafeModeReason.AUDIO_ENGINE_CONTINUOUS_FAILURE)
        }
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

    @Suppress("DEPRECATION")
    private fun findSystemUiPid(am: ActivityManager): Int {
        // Android 15/16 上 getRunningAppProcesses 只返回自身——这是预期的限制。
        // 我们只能通过 ActivityManager.getProcessesInErrorState() 间接侦测
        val errs = am.processesInErrorState ?: return -1
        errs.forEach { err ->
            if (err.processName == "com.android.systemui") {
                GlassLog.b("Watchdog") { "SystemUI 处于错误状态: ${err.condition}" }
                onSystemUiCrash()
            }
        }
        return -1  // 实际 PID 不可达；通过错误状态触发
    }
}
