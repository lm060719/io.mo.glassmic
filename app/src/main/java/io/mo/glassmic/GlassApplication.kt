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
import io.mo.glassmic.proto.AppLanguage
import io.mo.glassmic.service.SafeModeWatchdog
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class GlassApplication : Application() {

    @Inject lateinit var safeModeRepo: SafeModeRepository
    @Inject lateinit var bootGate: BootGateRepository
    @Inject lateinit var watchdog: SafeModeWatchdog
    @Inject lateinit var configStore: ConfigStore

    override fun onCreate() {
        super.onCreate()

        applyLanguagePreference()

        GlassLog.init(this)

        val sentinel = File(filesDir, Constants.RUNNING_SENTINEL)
        if (sentinel.exists()) {
            GlassLog.b("App") { "found stale running sentinel; clearing without safe mode" }
        }
        runCatching { sentinel.delete() }

        if (safeModeRepo.snapshot()?.reason == SafeModeReason.LAST_BOOT_DID_NOT_EXIT_CLEANLY) {
            safeModeRepo.exit()
            GlassLog.b("App") { "cleared legacy unclean-exit safe mode" }
        }

        bootGate.refreshBootId()
        watchdog.attach()

        // 清理遗留的 TTS 临时合成文件：正常随合成结束删除，进程被杀时可能残留。
        // 放后台线程做，避免阻塞启动。
        Thread {
            runCatching {
                cacheDir.listFiles { f -> f.name.startsWith("glass-tts-") }?.forEach { it.delete() }
            }
        }.start()

        GlassLog.b("App") { "GlassMic Application started" }
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
        AppLocale.apply(resolved)
    }
}
