package io.mo.glassmic.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.mo.glassmic.data.runtime.BootGateRepository
import io.mo.glassmic.log.GlassLog
import javax.inject.Inject

/**
 * 启动广播。
 *
 * 注意：本接收器**不会**自动启动 ForegroundService、不会恢复运行态。
 * 它的唯一职责是刷新 BootGate 的 boot_id，确保设备重启后默认关闭。
 *
 * 这是和需求 §3.2 严格对齐的——不实现"开机自启虚拟麦克风"。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var bootGate: BootGateRepository

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        bootGate.refreshBootId()
        // 新 boot_id 与旧的不一致 => userEnabledAfterBoot() 自动返回 false
        GlassLog.b("Boot") { "设备启动完成，BootGate 已重置，虚拟麦克风默认关闭" }
    }
}
