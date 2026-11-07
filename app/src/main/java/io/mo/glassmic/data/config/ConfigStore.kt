package io.mo.glassmic.data.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.Constants
import io.mo.glassmic.core.model.ConfigSnapshot
import io.mo.glassmic.data.runtime.LsposedServiceManager
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
    @ApplicationContext private val context: Context,
    private val lsposedServiceManager: LsposedServiceManager
) {

    private val dataStore: DataStore<AppConfig> = DataStoreFactory.create(
        serializer = AppConfigSerializer,
        produceFile = { context.dataStoreFile("app_config.pb") },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    )

    val flow: Flow<AppConfig> = dataStore.data.onEach { cfg ->
        // 每次配置变更同步给 Xposed 进程
        syncToXShared(cfg)
        syncVisibilityAllowlist(cfg)
    }

    val snapshotFlow: Flow<ConfigSnapshot> = flow.map { it.toSnapshot() }

    /** 谨慎使用——runBlocking。仅在 ContentProvider 等无法挂起的场景。 */
    fun snapshotBlocking(): ConfigSnapshot = runBlocking {
        dataStore.data.first().toSnapshot()
    }

    suspend fun current(): AppConfig = dataStore.data.first()

    suspend fun update(transform: (AppConfig.Builder) -> Unit) {
        val updated = dataStore.updateData { current ->
            current.toBuilder().also(transform).build()
        }
        // DataStore 的 flow 不一定此刻正被收集；作用域变化后主动同步一次，
        // 避免 system_server 在下一次 UI 订阅前仍拿到旧白名单。
        syncVisibilityAllowlist(updated)
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
    /**
     * 把 App 内目标白名单同步给 system_server 的包可见性 Hook。
     *
     * 这份数据只决定“哪些调用方可以强制看到 GlassMic 本包”，不改变实际音频生效范围；
     * 真正是否替换麦克风仍由 RuntimeProvider / ScopeMatcher 再校验一次。
     */
    private fun syncVisibilityAllowlist(cfg: AppConfig) {
        val allowed = cfg.whitelistList
            .asSequence()
            .filter { it.isNotBlank() && it != Constants.APP_PACKAGE }
            .toSet()

        @Suppress("DEPRECATION", "WorldReadableFiles")
        val prefs = runCatching {
            context.getSharedPreferences(Constants.REMOTE_PREFS, Context.MODE_WORLD_READABLE)
        }.getOrElse {
            // 与现有 remote preferences 路径保持一致。若当前框架无法暴露该文件，
            // system_server 将读不到白名单并按“拒绝额外放行”处理，不会扩大可见性。
            context.getSharedPreferences(Constants.REMOTE_PREFS, Context.MODE_PRIVATE)
        }

        runCatching {
            prefs.edit()
                .putStringSet(Constants.KEY_VISIBILITY_ALLOWLIST, allowed)
                .apply()
        }
        // 本地文件对 system_server 不可见，必须再经 XposedService 写进 LSPosed remote preferences。
        lsposedServiceManager.syncRemotePrefs()
        // remote preferences 在 system_server 里只在开机时加载；再用 root 写一份文件，改范围立即生效。
        io.mo.glassmic.data.runtime.VisibilityAllowlistFile.sync(allowed)
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
    onboardingCompleted = onboardingCompleted,
    audioPolicyBackend = injectionBackend == io.mo.glassmic.proto.InjectionBackend.AUDIO_POLICY,
    audioPolicyPackages = audioPolicyTargets(),
    audioPolicyAllApps = audioPolicyAllApps
)

/** AudioPolicy 选中的目标应用；兼容旧版单选字段 `audio_policy_package`。 */
fun AppConfig.audioPolicyTargets(): Set<String> =
    (audioPolicyPackagesList + audioPolicyPackage).filter { it.isNotBlank() }.toSet()
