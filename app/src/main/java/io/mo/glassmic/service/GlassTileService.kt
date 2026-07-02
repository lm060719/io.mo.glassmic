package io.mo.glassmic.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import io.mo.glassmic.R
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.runtime.BootGateRepository
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.data.runtime.SafeModeRepository
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 系统快捷设置磁贴：一键切换 GlassMic 主开关（等价于主界面的「开启GlassMix」）。
 *
 * 切换逻辑与 [io.mo.glassmic.ui.home.HomeViewModel] 的 toggleMaster 保持一致：
 * - 开：BootGate 标记 + global_switch=true + 启动前台服务（安全模式下拒绝）
 * - 关：global_switch=false + 清 BootGate + 停前台服务与悬浮窗
 */
@AndroidEntryPoint
class GlassTileService : TileService() {

    @Inject lateinit var runtime: RuntimeStateHolder
    @Inject lateinit var configStore: ConfigStore
    @Inject lateinit var bootGate: BootGateRepository
    @Inject lateinit var safeModeRepo: SafeModeRepository

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val turnOn = !isRunning()
        // 立即回显，避免等待写盘期间磁贴无反馈
        setTileState(turnOn)
        scope.launch {
            if (turnOn) enableMaster() else disableMaster()
            updateTile()
        }
    }

    private fun isRunning(): Boolean {
        val rt = runtime.value
        return rt.enabled && !rt.safeMode && bootGate.userEnabledAfterBoot()
    }

    private suspend fun enableMaster() {
        if (safeModeRepo.isActive()) {
            GlassLog.b("Tile") { "安全模式下拒绝开启" }
            updateTile()
            return
        }
        bootGate.markEnabledForThisBoot()
        configStore.update { it.setGlobalSwitch(true) }
        GlassForegroundService.start(applicationContext)
    }

    private suspend fun disableMaster() {
        configStore.update { it.setGlobalSwitch(false) }
        bootGate.clear()
        GlassForegroundService.stop(applicationContext)
        FloatingWindowService.stop(applicationContext)
    }

    private fun updateTile() = setTileState(isRunning())

    private fun setTileState(running: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (running) R.string.status_running else R.string.status_off
            )
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
