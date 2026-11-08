package io.mo.glassmic.ui.scope

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.Constants
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.config.StaleScopeCleaner
import io.mo.glassmic.data.config.audioPolicyTargets
import io.mo.glassmic.data.runtime.LsposedServiceManager
import io.mo.glassmic.data.runtime.ScopeRequestResult
import io.mo.glassmic.proto.ScopeMode
import io.mo.glassmic.proto.InjectionBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AppItem(
    val packageName: String,
    val label: String,
    val isSystem: Boolean
)

sealed interface ScopeEvent {
    data class Prompted(val label: String) : ScopeEvent
    data class Granted(val label: String) : ScopeEvent
    data class Denied(val label: String) : ScopeEvent
    data class Unsupported(val label: String) : ScopeEvent
    data class Removed(val label: String) : ScopeEvent
    data class RemoveFailed(val label: String) : ScopeEvent
    data class Synced(val count: Int) : ScopeEvent
}

data class ScopeUiState(
    val audioPolicyBackend: Boolean = false,
    val audioPolicyAllApps: Boolean = false,
    val whitelist: Set<String> = emptySet(),
    val isLsposedServiceBound: Boolean = false,
    val showSystemApps: Boolean = false,
    val query: String = "",
    val loading: Boolean = true,
    val apps: List<AppItem> = emptyList()
) {
    val filteredApps: List<AppItem>
        get() {
            val q = query.trim().lowercase()
            return apps.asSequence()
                .filter { showSystemApps || !it.isSystem || it.packageName in whitelist }
                .filter {
                    q.isBlank() ||
                        it.label.lowercase().contains(q) ||
                        it.packageName.lowercase().contains(q)
                }
                .sortedWith(
                    compareByDescending<AppItem> { it.packageName in whitelist }
                        .thenBy { it.label.lowercase() }
                )
                .toList()
        }
}

@HiltViewModel
class ScopeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: ConfigStore,
    private val lsposedServiceManager: LsposedServiceManager,
    private val staleScopeCleaner: StaleScopeCleaner
) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _loading = MutableStateFlow(true)
    private val _apps = MutableStateFlow<List<AppItem>>(emptyList())

    private val _events = MutableSharedFlow<ScopeEvent>()
    val events: SharedFlow<ScopeEvent> = _events.asSharedFlow()

    val state: StateFlow<ScopeUiState> = combine(
        configStore.flow,
        lsposedServiceManager.isBound,
        _query,
        _loading,
        _apps
    ) { cfg, isBound, query, loading, apps ->
        ScopeUiState(
            audioPolicyBackend = cfg.injectionBackend == InjectionBackend.AUDIO_POLICY,
            audioPolicyAllApps = cfg.audioPolicyAllApps,
            whitelist = if (cfg.injectionBackend == InjectionBackend.AUDIO_POLICY)
                cfg.audioPolicyTargets() else cfg.whitelistList.toSet(),
            isLsposedServiceBound = isBound,
            showSystemApps = cfg.showSystemApps,
            query = query,
            loading = loading,
            apps = apps
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ScopeUiState())

    init {
        loadInstalledApps()
        ensureWhitelistMode()
        observeFrameworkScope()
    }

    /** 统一使用白名单模式（目标应用精确匹配） */
    private fun ensureWhitelistMode() {
        viewModelScope.launch {
            val cur = configStore.current()
            if (cur.scopeMode != ScopeMode.WHITELIST) {
                configStore.update { it.setScopeMode(ScopeMode.WHITELIST) }
            }
        }
    }

    /** 自动监听 LSPosed 服务广播的作用域列表，与 App 内白名单保持严格双向同步 */
    private fun observeFrameworkScope() {
        viewModelScope.launch {
            lsposedServiceManager.frameworkScope.collect { frameworkList ->
                if (frameworkList != null) {
                    val validFramework = withContext(Dispatchers.IO) { validScope(frameworkList) }
                    configStore.update {
                        it.clearWhitelist().addAllWhitelist(validFramework.sorted())
                        it.setScopeMode(ScopeMode.WHITELIST)
                    }
                }
            }
        }
    }

    /** 从 LSPosed 管理器拉取并精准同步最新作用域 */
    fun syncFromManager(silent: Boolean = false) {
        viewModelScope.launch {
            if (configStore.current().injectionBackend == InjectionBackend.AUDIO_POLICY) return@launch
            val list = lsposedServiceManager.syncScope()
            if (list != null) {
                val valid = withContext(Dispatchers.IO) { validScope(list) }
                configStore.update {
                    it.clearWhitelist().addAllWhitelist(valid.sorted())
                    it.setScopeMode(ScopeMode.WHITELIST)
                }
                if (!silent) {
                    _events.emit(ScopeEvent.Synced(valid.size))
                }
            } else if (!silent) {
                _events.emit(ScopeEvent.Unsupported("LSPosed"))
            }
        }
    }

    /** 框架作用域里可能残留已卸载应用（#43），同步进白名单前过滤掉。 */
    private fun validScope(list: List<String>): List<String> =
        list.filter { pkg ->
            pkg != Constants.APP_PACKAGE && pkg.isNotBlank() && staleScopeCleaner.isInstalled(pkg)
        }

    fun toggleApp(pkg: String) {
        viewModelScope.launch {
            if (configStore.current().injectionBackend == InjectionBackend.AUDIO_POLICY) {
                configStore.update {
                    val targets = it.build().audioPolicyTargets().toMutableSet()
                    if (!targets.remove(pkg)) targets.add(pkg)
                    // Migrates the legacy single-target field into the list on the first edit.
                    it.setAudioPolicyPackage("").clearAudioPolicyPackages()
                        .addAllAudioPolicyPackages(targets.sorted())
                }
                return@launch
            }
            val currentApps = _apps.value
            val appLabel = currentApps.firstOrNull { it.packageName == pkg }?.label ?: pkg
            val isCurrentlySelected = pkg in state.value.whitelist

            if (!isCurrentlySelected) {
                // 添加到生效列表
                configStore.update {
                    val current = it.whitelistList.toMutableSet()
                    current.add(pkg)
                    it.clearWhitelist().addAllWhitelist(current.sorted())
                    it.setScopeMode(ScopeMode.WHITELIST)
                }

                // 通过 libxposed:service 主动发起动态作用域申请
                lsposedServiceManager.requestScope(pkg) { result ->
                    viewModelScope.launch {
                        when (result) {
                            is ScopeRequestResult.Granted -> {
                                lsposedServiceManager.syncScope()
                                _events.emit(ScopeEvent.Granted(appLabel))
                            }
                            is ScopeRequestResult.Denied -> {
                                // 用户取消或拒绝授权时，回滚勾选状态
                                configStore.update {
                                    val current = it.whitelistList.toMutableSet()
                                    current.remove(pkg)
                                    it.clearWhitelist().addAllWhitelist(current.sorted())
                                }
                                _events.emit(ScopeEvent.Denied(appLabel))
                            }
                            is ScopeRequestResult.Prompted -> _events.emit(ScopeEvent.Prompted(appLabel))
                            is ScopeRequestResult.Unsupported -> _events.emit(ScopeEvent.Unsupported(appLabel))
                            is ScopeRequestResult.Failed -> _events.emit(ScopeEvent.Unsupported(appLabel))
                        }
                    }
                }
            } else {
                // 先从框架作用域移除；失败则保留勾选，否则下次同步会把它写回来
                if (!lsposedServiceManager.removeScope(pkg)) {
                    _events.emit(ScopeEvent.RemoveFailed(appLabel))
                    return@launch
                }
                // 从生效列表移除
                configStore.update {
                    val current = it.whitelistList.toMutableSet()
                    current.remove(pkg)
                    it.clearWhitelist().addAllWhitelist(current.sorted())
                }
                _events.emit(ScopeEvent.Removed(appLabel))
            }
        }
    }

    fun setAudioPolicyAllApps(enabled: Boolean) {
        viewModelScope.launch { configStore.update { it.setAudioPolicyAllApps(enabled) } }
    }

    fun setShowSystemApps(show: Boolean) {
        viewModelScope.launch { configStore.update { it.setShowSystemApps(show) } }
    }

    fun setQuery(q: String) { _query.value = q }

    fun refresh() {
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        _loading.value = true
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) { queryInstalledApps() }
            staleScopeCleaner.prune()
            _loading.value = false
        }
    }

    private fun queryInstalledApps(): List<AppItem> {
        val pm = context.packageManager
        val infos = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())
        return infos
            .asSequence()
            .filter { it.packageName != Constants.APP_PACKAGE }
            .map { info ->
                AppItem(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
