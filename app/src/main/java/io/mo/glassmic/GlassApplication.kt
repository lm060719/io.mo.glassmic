package io.mo.glassmic

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.mo.glassmic.core.Constants
import io.mo.glassmic.core.model.SafeModeReason
import io.mo.glassmic.data.config.AppLocale
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.runtime.BootGateRepository
import io.mo.glassmic.data.runtime.SafeModeRepository
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.memory.FairMemoryController
import io.mo.glassmic.provider.ProviderGate
import io.mo.glassmic.proto.AppLanguage
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class GlassApplication : Application() {

    @Inject lateinit var safeModeRepo: SafeModeRepository
    @Inject lateinit var bootGate: BootGateRepository
    @Inject lateinit var configStore: ConfigStore
    @Inject lateinit var fairMemory: FairMemoryController

    override fun onCreate() {
        super.onCreate()

        applyLanguagePreference()

        GlassLog.init(this)

        val sentinel = File(filesDir, Constants.RUNNING_SENTINEL)
        if (sentinel.exists()) {
            GlassLog.b("App") { "found stale running sentinel; clearing without safe mode" }
        }
        runCatching { sentinel.delete() }

        // 进程刚起，前台服务尚未运行——先把跨进程 Provider 禁用掉，维持
        // 「Provider enabled ⇔ 服务运行」的不变量。若本进程是被某次 Provider 访问
        // 强制拉起的（服务并没开），这一步会立刻切断链路，让复活循环自终止；
        // 用户随后正常开启服务时 GlassForegroundService.onCreate 会重新放开。
        runCatching { ProviderGate.disable(this) }

        if (safeModeRepo.snapshot()?.reason == SafeModeReason.LAST_BOOT_DID_NOT_EXIT_CLEANLY) {
            safeModeRepo.exit()
            GlassLog.b("App") { "cleared legacy unclean-exit safe mode" }
        }

        bootGate.refreshBootId()

        // 公平运行内存（ITGSA / HyperOS）：监听系统预警与查杀广播，3 秒内回执
        fairMemory.initialize()

        // 清理遗留的 TTS 临时合成文件：正常随合成结束删除，进程被杀时可能残留。
        // 放后台线程做，避免阻塞启动。
        Thread {
            runCatching {
                cacheDir.listFiles { f -> f.name.startsWith("glass-tts-") }?.forEach { it.delete() }
            }
        }.start()

        GlassLog.b("App") { "GlassMic Application started" }
    }

    /** 系统标准的内存回收回调，与公平运行内存广播共用同一条回收链路。 */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        fairMemory.onSystemTrim(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        fairMemory.onSystemLowMemory()
    }

    // 首次启动按系统语言自动决定默认语言（中文→中文，其余→英文），之后由用户在设置里显式选择。
    private fun applyLanguagePreference() {
        val appearance = runBlocking { configStore.current() }.appearance
        val resolved = if (appearance.languageResolved) {
            appearance.language
        } else {
            val systemIsChinese = Locale.getDefault().language == "zh"
            val detected = if (systemIsChinese) AppLanguage.ZH else AppLanguage.EN
            runBlocking {
                configStore.update {
                    it.setAppearance(it.appearance.toBuilder().setLanguage(detected).setLanguageResolved(true))
                }
            }
            detected
        }
        AppLocale.apply(this, resolved)
    }
}
