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
import io.mo.glassmic.proto.ScopeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

data class ScopeUiState(
    val mode: ScopeMode = ScopeMode.GLOBAL,
    val whitelist: Set<String> = emptySet(),
    val showSystemApps: Boolean = false,
    val query: String = "",
    val loading: Boolean = true,
    val apps: List<AppItem> = emptyList()
) {
    val filteredApps: List<AppItem>
        get() {
            val q = query.trim().lowercase()
            return apps.asSequence()
                .filter { showSystemApps || !it.isSystem }
                .filter {
                    q.isBlank() ||
                        it.label.lowercase().contains(q) ||
                        it.packageName.lowercase().contains(q)
                }
                .toList()
        }
}

@HiltViewModel
class ScopeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: ConfigStore
) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _loading = MutableStateFlow(true)
    private val _apps = MutableStateFlow<List<AppItem>>(emptyList())

    val state: StateFlow<ScopeUiState> = combine(
        configStore.flow, _query, _loading, _apps
    ) { cfg, query, loading, apps ->
        ScopeUiState(
            mode = cfg.scopeMode,
            whitelist = cfg.whitelistList.toSet(),
            showSystemApps = cfg.showSystemApps,
            query = query,
            loading = loading,
            apps = apps
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ScopeUiState())

    init {
        loadInstalledApps()
        migrateLegacyMode()
    }

    /** UI 不再暴露 BLACKLIST；如果旧配置是黑名单，自动改回 GLOBAL 以避免出现"两个 RadioButton 都不选"的状态。 */
    private fun migrateLegacyMode() {
        viewModelScope.launch {
            val cur = configStore.current()
            if (cur.scopeMode != ScopeMode.GLOBAL && cur.scopeMode != ScopeMode.WHITELIST) {
                configStore.update { it.setScopeMode(ScopeMode.GLOBAL) }
            }
        }
    }

    fun setMode(mode: ScopeMode) {
        viewModelScope.launch { configStore.update { it.setScopeMode(mode) } }
    }

    fun toggleApp(pkg: String) {
        viewModelScope.launch {
            configStore.update {
                val current = it.whitelistList.toMutableSet()
                if (pkg in current) current.remove(pkg) else current.add(pkg)
                it.clearWhitelist().addAllWhitelist(current.sorted())
            }
        }
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
