package io.mo.glassmic.xposed

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.mo.glassmic.core.Constants

/**
 * libxposed API 101 入口。
 *
 * 关键设计：**zygote 级 hook**。
 *
 * 我们在 [onModuleLoaded] 阶段（zygote 进程）就 hook 住 `Application.attach`。
 * zygote 之后 fork 出来的每一个 App 进程会通过 COW 内存继承这个 hook——
 * 因此即使用户没在 LSPosed 管理器里勾选某个 App，只要勾选了「android」（= zygote），
 * 那个 App 启动时也会跑我们的 hook 回调，从而装上 AudioRecord 和 AAudio 拦截。
 *
 * 代价：一个 hook bug 会让所有 App 一起出问题。所以下面有一份"保护进程"列表，
 * 关键系统进程（systemui、launcher、settings、GMS 等）直接跳过，绝不动它们。
 */
class GlassMicXposedModule : XposedModule() {

    private companion object {
        const val TAG = "GlassMic-X"
        const val SELF_PKG = Constants.APP_PACKAGE
    }

    @Volatile private var attachHookInstalled = false

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(
            Log.INFO,
            TAG,
            "module loaded process=${param.processName} system=${param.isSystemServer} api=$apiVersion framework=$frameworkName/$frameworkVersion"
        )
        // zygote 阶段安装 Application.attach hook——所有 fork 出来的 App 自动继承
        // 即使在 non-zygote 进程里调用，hook 也是 method 级的，全局生效
        installApplicationAttachHook()
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        log(Log.INFO, TAG, "loaded in system_server (build=viscompat-r2)")
        // 不在 system_server 里装 audio hook——保护核心进程。
        // 仅当用户开启「严格 ROM 兼容」时，安装"包可见性放行"hook（只放行本模块包）。
        runCatching {
            if (isVisibilityCompatEnabled()) {
                val ok = SystemVisibilityHook.install(this, param.classLoader)
                log(Log.INFO, TAG, "visibility compat ON, hook installed=$ok")
            } else {
                log(Log.INFO, TAG, "visibility compat OFF (default)")
            }
        }.onFailure {
            log(Log.WARN, TAG, "visibility compat init error: ${it.message}", it)
        }
    }

    /**
     * 读取「严格 ROM 兼容」开关。两个来源（任一为真即开启）：
     *  1. system property（高级用户/排查用，见 Constants.PROP_VISIBILITY_COMPAT）
     *  2. 模块 remote preferences（App 设置里的开关写入，默认关闭）
     * 全程异常保护，读不到一律按关闭处理。
     */
    private fun isVisibilityCompatEnabled(): Boolean {
        val propVal = runCatching {
            @Suppress("PrivateApi")
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java, String::class.java)
            get.invoke(null, Constants.PROP_VISIBILITY_COMPAT, "") as? String
        }.getOrNull()
        val prefVal = runCatching {
            getRemotePreferences(Constants.REMOTE_PREFS)
                .getBoolean(Constants.KEY_VISIBILITY_COMPAT, false)
        }.getOrNull()
        // 诊断：直接打印两条来源的实际读数，便于远程定位「开关没生效」卡在哪一步。
        log(
            Log.INFO, TAG,
            "visibility compat read: prop='${propVal ?: "<null>"}' remotePref=${prefVal ?: "<null>"}"
        )
        return propVal == "1" || prefVal == true
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // 现在用 zygote 继承路径，per-package 回调只用来记日志
        val pkg = param.packageName ?: return
        if (pkg == SELF_PKG) return
        log(Log.INFO, TAG, "onPackageReady $pkg firstPackage=${param.isFirstPackage}")
    }

    private fun installApplicationAttachHook() {
        if (attachHookInstalled) return
        synchronized(this) {
            if (attachHookInstalled) return
            attachHookInstalled = true
        }

        val attach = runCatching {
            Application::class.java.getDeclaredMethod("attach", Context::class.java)
        }.onFailure {
            log(Log.WARN, TAG, "find Application.attach failed: ${it.message}", it)
        }.getOrNull() ?: return

        hook(attach)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val appCtx = chain.getArg(0) as? Context ?: return@intercept result

                val pkg = runCatching { appCtx.packageName ?: "" }.getOrDefault("")
                if (XposedHookGate.shouldSkipPackage(pkg)) {
                    return@intercept result
                }

                // 真正的 audio hook 安装在 App 进程上下文里
                val ctx = appCtx.applicationContext ?: appCtx
                runCatching {
                    XBridge.currentPackage = pkg
                    XBridge.pingModuleLoaded(ctx, pkg)
                    AudioRecordHook.install(this@GlassMicXposedModule, ctx, pkg)
                    val nativeOk = NativeAAudioHook.install(ctx, pkg)
                    log(Log.INFO, TAG, "hooks installed in $pkg native=$nativeOk")
                }.onFailure {
                    log(Log.WARN, TAG, "install hooks in $pkg failed: ${it.message}", it)
                }
                result
            }
        log(Log.INFO, TAG, "Application.attach hook installed (zygote-level)")
    }
}
