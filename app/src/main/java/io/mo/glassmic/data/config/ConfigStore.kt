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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

    val flow: Flow<AppConfig> = dataStore.data

    init {
        // 可见性白名单只在这里同步一次：以前放在 flow.onEach 里，每个 collector、每次任意
        // 配置变更都会重复写 prefs、调 LSPosed 服务和 root 写文件。
        // 首个值也要同步（升级后 system_server 侧的白名单可能还是旧的）。
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            dataStore.data
                .map { cfg -> visibilityAllowlist(cfg) }
                .distinctUntilChanged()
                .collect { allowed -> runCatching { syncVisibilityAllowlist(allowed) } }
        }
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
        // 白名单变化由 init 中的常驻 collector 同步，这里不再重复。
    }

    /**
     * 把 App 内目标白名单同步给 system_server 的包可见性 Hook。
     *
     * 这份数据只决定“哪些调用方可以强制看到 GlassMic 本包”，不改变实际音频生效范围；
     * 真正是否替换麦克风仍由 RuntimeProvider / ScopeMatcher 再校验一次。
     */
    private fun visibilityAllowlist(cfg: AppConfig): Set<String> = cfg.whitelistList
        .asSequence()
        .filter { it.isNotBlank() && it != Constants.APP_PACKAGE }
        .toSet()

    private fun syncVisibilityAllowlist(allowed: Set<String>) {

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
