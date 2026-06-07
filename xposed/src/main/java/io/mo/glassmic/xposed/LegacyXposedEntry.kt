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
 * 两条路径：
 *  1. [IXposedHookZygoteInit.initZygote] —— zygote 阶段安装 `Application.attach` hook
 *     这是和 API 101 的 onModuleLoaded 等价的"全局覆盖"路径
 *  2. [IXposedHookLoadPackage.handleLoadPackage] —— per-package 兜底
 *     若 zygote hook 因某种原因没生效，至少用户勾选的 App 还能走这条路
 *
 * 不论哪条路径，都用 [XposedHookGate.shouldSkipPackage] 跳过系统命脉，避免误杀。
 */
class LegacyXposedEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {

    @Volatile private var attachHookInstalled = false

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        installApplicationAttachHook()
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // zygote hook 没生效时的兜底
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
                            XBridge.pingModuleLoaded(ctx, pkg, api = 82)
                            LegacyAudioRecordHook.install(ctx, pkg)
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
