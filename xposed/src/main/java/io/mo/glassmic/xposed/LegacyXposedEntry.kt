package io.mo.glassmic.xposed

import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * legacy Xposed API 82 入口。
 *
 * 注意：LSPosed 在应用 fork 后才按进程加载模块，`initZygote` 只是方法名，同样只会在
 * 作用域内的 App 进程里被调用，不能据此认为未勾选的 App 也会继承 hook。
 *
 * 两条路径（都在作用域内进程里执行）：
 *  1. [IXposedHookZygoteInit.initZygote] —— 进程早期安装 `Application.attach` hook，
 *     与 API 101 的 onModuleLoaded 等价
 *  2. [IXposedHookLoadPackage.handleLoadPackage] —— 兜底，若 1 没生效再装一次
 *
 * 不论哪条路径，都用 [XposedHookGate.shouldSkipPackage] 跳过系统命脉，避免误杀。
 */
class LegacyXposedEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {

    @Volatile private var attachHookInstalled = false

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        installApplicationAttachHook()
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // initZygote 路径没生效时的兜底
        if (attachHookInstalled) return
        val pkg = lpparam.packageName
        if (XposedHookGate.shouldSkipPackage(pkg)) return
        installApplicationAttachHook()
    }

    private fun installApplicationAttachHook() {
        if (attachHookInstalled) return
        synchronized(this) {
            if (attachHookInstalled) return
            attachHookInstalled = true
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val appCtx = param.args.firstOrNull() as? Context ?: return
                        val pkg = runCatching { appCtx.packageName ?: "" }.getOrDefault("")
                        if (XposedHookGate.shouldSkipPackage(pkg)) return

                        val ctx = appCtx.applicationContext ?: appCtx
                        runCatching {
                            XBridge.currentPackage = pkg
                            XBridge.apiVersion = 82
                            // 同 API 101：不再在 attach 阶段主动 ping RuntimeProvider，避免无故拉起本进程。
                            AudioRecordHook.installLegacy(ctx, pkg)
                            // 与 API 101 入口对等：接管纯音频 MediaRecorder（系统媒体服务采集的缺口）。
                            MediaRecorderHook.installLegacy(ctx, pkg)
                            val nativeOk = NativeAAudioHook.install(ctx, pkg)
                            XposedBridge.log("GlassMic-Legacy: hooks installed in $pkg native=$nativeOk")
                        }.onFailure {
                            XposedBridge.log("GlassMic-Legacy: install hooks in $pkg failed: ${it.message}")
                            XposedBridge.log(it)
                        }
                    }
                }
            )
            XposedBridge.log("GlassMic-Legacy: Application.attach hook installed")
        }.onFailure {
            XposedBridge.log("GlassMic-Legacy: failed to hook Application.attach: ${it.message}")
            XposedBridge.log(it)
        }
    }
}
