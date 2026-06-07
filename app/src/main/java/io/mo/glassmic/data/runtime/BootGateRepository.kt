package io.mo.glassmic.data.runtime

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.Constants
import io.mo.glassmic.log.GlassLog
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BootGate 保证"设备重启后虚拟麦克风默认关闭"。
 *
 * 原理：
 * - 每次设备冷启动，`/proc/sys/kernel/random/boot_id` 会变。
 * - 用户在 UI 上手动开启虚拟麦克风时，调用 [markEnabledForThisBoot]，
 *   把当前 boot_id 写入 SharedPreferences。
 * - 任何环节判断是否"用户已在本次启动后手动开启"都走 [userEnabledAfterBoot]。
 *
 * 这样即使 AppConfig.global_switch == true，重启后仍然不会自动恢复。
 */
@Singleton
class BootGateRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs by lazy {
        context.getSharedPreferences(Constants.BOOT_GATE_PREFS, Context.MODE_PRIVATE)
    }

    @Volatile
    private var cachedBootId: String = readBootId()

    fun refreshBootId() {
        cachedBootId = readBootId()
        GlassLog.b("BootGate") { "boot_id=$cachedBootId" }
    }

    fun userEnabledAfterBoot(): Boolean {
        val stored = prefs.getString(Constants.BOOT_GATE_KEY_ENABLED, null) ?: return false
        return stored == cachedBootId
    }

    fun markEnabledForThisBoot() {
        prefs.edit()
            .putString(Constants.BOOT_GATE_KEY_ENABLED, cachedBootId)
            .commit()  // 用 commit 而不是 apply——保证写盘
        GlassLog.b("BootGate") { "用户已在本次启动手动开启" }
    }

    fun clear() {
        prefs.edit().remove(Constants.BOOT_GATE_KEY_ENABLED).commit()
        GlassLog.b("BootGate") { "BootGate 已清除" }
    }

    private fun readBootId(): String = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    } catch (t: Throwable) {
        // 兜底：用 elapsedRealtime 起点（也能区分开机）
        GlassLog.b("BootGate") { "boot_id 读取失败，使用兜底值: ${t.message}" }
        "fallback-${System.currentTimeMillis() - SystemClock.elapsedRealtime()}"
    }
}
