package io.mo.glassmic.ui.ksu

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.runtime.BootGateRepository
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.data.runtime.SafeModeRepository
import io.mo.glassmic.proto.InjectionBackend
import io.mo.glassmic.root.KsuController
import io.mo.glassmic.service.FloatingWindowService
import io.mo.glassmic.service.GlassForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class KsuApp(val packageName: String, val label: String)

@HiltViewModel
class KsuViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val controller: KsuController,
    private val config: ConfigStore,
    private val runtime: RuntimeStateHolder,
    private val boot: BootGateRepository,
    private val safeMode: SafeModeRepository
) : ViewModel() {
    val backend = config.flow.map { it.injectionBackend }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val state = controller.state
    val runtimeState = runtime.flow
    private val mutableApps = MutableStateFlow<List<KsuApp>>(emptyList())
    val apps = mutableApps.asStateFlow()
    private val mutableSourceApps = MutableStateFlow<List<KsuApp>>(emptyList())
    val sourceApps = mutableSourceApps.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableSwitching = MutableStateFlow(false)
    val switching = mutableSwitching.asStateFlow()

    fun toggleEnabled() {
        if (mutableSwitching.value) return
        mutableSwitching.value = true
        viewModelScope.launch {
            try {
                if (config.current().injectionBackend != InjectionBackend.KERNEL_SU) return@launch
                if (runtime.value.enabled) {
                    // Keep the foreground service alive until the module acknowledges REAL.
                    if (!controller.release()) return@launch
                    config.update { it.setGlobalSwitch(false) }
                    boot.clear()
                    GlassForegroundService.stop(context)
                    FloatingWindowService.stop(context)
                } else {
                    if (safeMode.isActive()) return@launch
                    boot.markEnabledForThisBoot()
                    config.update { it.setGlobalSwitch(true) }
                    GlassForegroundService.start(context)
                }
            } catch (error: Exception) { mutableError.value = error.message
            } finally { mutableSwitching.value = false }
        }
    }

    fun toggleFloating() {
        if (runtime.value.floatingWindowVisible) FloatingWindowService.stop(context)
        else FloatingWindowService.start(context)
    }

    fun loadApps() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val installed = context.packageManager.getInstalledApplications(0)
                .filter { it.uid != android.os.Process.myUid() && it.uid % 100000 >= 10000 }
            val labeled = installed.associate { it.packageName to KsuApp(it.packageName,
                it.loadLabel(context.packageManager).toString()) }
            mutableSourceApps.value = labeled.values.sortedBy { it.label.lowercase() }
            mutableApps.value = installed.filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { labeled.getValue(it.packageName) }.sortedBy { it.label.lowercase() }
        }.onFailure { mutableError.value = it.message }
    }

    fun toggleTarget(packageName: String) {
        val targets = controller.state.value.snapshot?.targets ?: return
        val next = if (packageName in targets) targets - packageName else targets + packageName
        controller.command("targets", next.sorted())
    }

    fun importAudio(uri: Uri) = viewModelScope.launch {
        try {
            val name = withContext(Dispatchers.IO) {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "Audio.wav"
            }
            controller.importAudio(uri, name)
        } catch (error: Exception) { mutableError.value = error.message }
    }
}
