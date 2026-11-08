package io.mo.glassmic.data.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.data.runtime.LsposedServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 清理生效范围里已被卸载的应用（issue #43）。
 *
 * 勾选过的应用被卸载后，包名仍留在白名单 / AudioPolicy 目标里：首页显示空白图标，
 * 而作用域列表只列出已安装应用，用户无从取消勾选。这里在打开 App、进入作用域页、
 * 同步 LSPosed 作用域以及进程存活期间收到卸载广播时，把这些包名剔除，并尽力从
 * LSPosed 框架作用域里一并移除，避免下一次同步又被写回来。
 *
 * 卸载广播只在进程存活时动态注册，不在 Manifest 里静态声明，不会因卸载别的应用把
 * GlassMic 拉起来。
 */
@Singleton
class StaleScopeCleaner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: ConfigStore,
    private val lsposedServiceManager: LsposedServiceManager
) {
    private val tag = "StaleScopeCleaner"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val receiverRegistered = AtomicBoolean(false)

    private val removedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 覆盖安装（更新）也会先发 PACKAGE_REMOVED，带 EXTRA_REPLACING。
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            scope.launch { prune() }
        }
    }

    /** 注册卸载监听并立即清理一次。可重复调用。 */
    fun start() {
        if (receiverRegistered.compareAndSet(false, true)) {
            runCatching {
                val filter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED).apply { addDataScheme("package") }
                ContextCompat.registerReceiver(
                    context, removedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }.onFailure {
                receiverRegistered.set(false)
                Log.w(tag, "register package receiver failed: ${it.message}")
            }
        }
        scope.launch { prune() }
    }

    fun isInstalled(pkg: String): Boolean {
        if (pkg in PSEUDO_PACKAGES) return true
        return try {
            context.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (t: Throwable) {
            // 查询本身出错时不当作已卸载，宁可留着也不误删用户的勾选。
            Log.w(tag, "query $pkg failed: ${t.message}")
            true
        }
    }

    /**
     * 从配置中剔除已卸载应用，并尽力从 LSPosed 框架作用域中移除。
     * @return 被剔除的包名
     */
    suspend fun prune(): Set<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cfg = configStore.current()
            val candidates = (cfg.whitelistList + cfg.audioPolicyTargets()).filter { it.isNotBlank() }.toSet()
            val stale = candidates.filterNot { isInstalled(it) }.toSet()

            // 框架作用域里可能也残留着，即使本地白名单已经干净。
            val frameworkStale = lsposedServiceManager.frameworkScope.value
                .orEmpty()
                .filter { it.isNotBlank() && !isInstalled(it) }
                .toSet()

            if (stale.isNotEmpty()) {
                configStore.update { b ->
                    val whitelist = b.whitelistList.filterNot { it in stale }
                    if (whitelist.size != b.whitelistCount) {
                        b.clearWhitelist().addAllWhitelist(whitelist)
                    }
                    val targets = b.audioPolicyPackagesList.filterNot { it in stale }
                    if (targets.size != b.audioPolicyPackagesCount) {
                        b.clearAudioPolicyPackages().addAllAudioPolicyPackages(targets)
                    }
                    if (b.audioPolicyPackage in stale) b.setAudioPolicyPackage("")
                }
                Log.i(tag, "pruned uninstalled packages from scope: $stale")
            }

            for (pkg in stale + frameworkStale) {
                // 失败也无妨：同步框架作用域时会再按是否安装过滤一遍。
                lsposedServiceManager.removeScope(pkg)
            }
            stale + frameworkStale
        }
    }

    private companion object {
        /** LSPosed 用来代表 system_server 的伪包名，PackageManager 查不到但必须保留。 */
        val PSEUDO_PACKAGES = setOf("system")
    }
}
