package io.mo.glassmic

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.mo.glassmic.core.Constants
import io.mo.glassmic.core.model.SafeModeReason
import io.mo.glassmic.data.runtime.BootGateRepository
import io.mo.glassmic.data.runtime.SafeModeRepository
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.service.SafeModeWatchdog
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class GlassApplication : Application() {

    @Inject lateinit var safeModeRepo: SafeModeRepository
    @Inject lateinit var bootGate: BootGateRepository
    @Inject lateinit var watchdog: SafeModeWatchdog

    override fun onCreate() {
        super.onCreate()

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

        GlassLog.b("App") { "GlassMic Application started" }
    }
}
