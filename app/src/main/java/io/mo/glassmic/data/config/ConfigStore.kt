package io.mo.glassmic.data.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.Constants
import io.mo.glassmic.core.model.ConfigSnapshot
import io.mo.glassmic.core.model.ScopeMode as ScopeModeCore
import io.mo.glassmic.proto.AppConfig
import io.mo.glassmic.proto.ScopeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val dataStore: DataStore<AppConfig> = DataStoreFactory.create(
        serializer = AppConfigSerializer,
        produceFile = { context.dataStoreFile("app_config.pb") },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    )

    val flow: Flow<AppConfig> = dataStore.data.onEach { cfg ->
        // 每次配置变更同步给 Xposed 进程
        syncToXShared(cfg)
    }

    val snapshotFlow: Flow<ConfigSnapshot> = flow.map { it.toSnapshot() }

    /** 谨慎使用——runBlocking。仅在 ContentProvider 等无法挂起的场景。 */
    fun snapshotBlocking(): ConfigSnapshot = runBlocking {
        dataStore.data.first().toSnapshot()
    }

    suspend fun current(): AppConfig = dataStore.data.first()

    suspend fun update(transform: (AppConfig.Builder) -> Unit) {
        dataStore.updateData { current ->
            current.toBuilder().also(transform).build()
        }
    }

    private fun syncToXShared(cfg: AppConfig) {
        // 关键：使用 Context.MODE_WORLD_READABLE 让 Xposed 进程能读取
        // Android 8.0+ 默认禁用，需在 application 标签下加 android:sharedUserId 或使用 LSPosed 的 XSharedPreferences
        // LSPosed 现代版本支持直接读取 App 的 SharedPreferences，无需 WORLD_READABLE
        @Suppress("DEPRECATION", "WorldReadableFiles")
        val prefs = try {
            context.getSharedPreferences(Constants.XSHARED_PREFS_NAME, Context.MODE_WORLD_READABLE)
        } catch (t: SecurityException) {
            context.getSharedPreferences(Constants.XSHARED_PREFS_NAME, Context.MODE_PRIVATE)
        }
        prefs.edit().apply {
            putBoolean("global_switch", cfg.globalSwitch)
            putString("scope_mode", cfg.scopeMode.name)
            putStringSet("whitelist", cfg.whitelistList.toSet())
            putStringSet("blacklist", cfg.blacklistList.toSet())
            putBoolean("onboarding_completed", cfg.onboardingCompleted)
            putString("current_group_id", cfg.currentGroupId)
            putString("current_audio_id", cfg.currentAudioId)
            putString("playback_policy", cfg.playbackPolicy.name)
        }.apply()
    }
}

private fun AppConfig.toSnapshot(): ConfigSnapshot = ConfigSnapshot(
    globalSwitch = globalSwitch,
    scopeMode = when (scopeMode) {
        ScopeMode.GLOBAL -> ScopeModeCore.GLOBAL
        ScopeMode.WHITELIST -> ScopeModeCore.WHITELIST
        ScopeMode.BLACKLIST -> ScopeModeCore.BLACKLIST
        else -> ScopeModeCore.GLOBAL
    },
    whitelist = whitelistList.toSet(),
    blacklist = blacklistList.toSet(),
    onboardingCompleted = onboardingCompleted
)
