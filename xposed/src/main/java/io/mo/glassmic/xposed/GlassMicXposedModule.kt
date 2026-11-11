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
 * LSPosed 在应用 fork **之后**按进程加载框架和模块，只有 LSPosed 作用域内的进程才会
 * 回调 [onModuleLoaded]；并不存在“勾选 android/system 就从父 zygote 继承到所有 App”的路径。
 * 因此目标 App 必须出现在 LSPosed 作用域中（作用域页勾选时会经 libxposed:service 自动申请），
 * 并在授权后完全重启该 App。
 *
 * 进程内我们在 [onModuleLoaded] 时 hook `Application.attach`，在拿到 App Context 后
 * 装上 AudioRecord 与 native（AAudio/OpenSL/AudioRecord::read）拦截。
 * 关键系统进程（systemui、launcher、GMS、输入法等）即使被勾进作用域也直接跳过。
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
        // 当前进程已在作用域内：hook Application.attach，等 App Context 就绪再装音频 hook
        installApplicationAttachHook()
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        log(Log.INFO, TAG, "loaded in system_server")
        // 跨进程包可见性兼容：只对 GlassMic 当前目标白名单里的调用方解除本包可见性过滤。
        // 这样 Telegram/微信等严格执行 Android 11+ 包可见性的应用仍能访问 Provider；
        // 未授权应用则完全保留系统/HMA 的原始过滤结果，不再把 GlassMic 全局暴露出去。
        runCatching {
            val ok = SystemVisibilityHook.install(this, param.classLoader) {
                runCatching { getRemotePreferences(Constants.REMOTE_PREFS) }.getOrNull()
            }
            log(Log.INFO, TAG, "scoped visibility allowlist hook installed=$ok")
        }.onFailure {
            log(Log.WARN, TAG, "visibility compat init error: ${it.message}", it)
        }

        // 音量键双击快捷操作。这里**无条件**安装，实际是否拦截由 App 侧写入的「布防」标志
        // 决定（设置开关打开 && 悬浮窗正在运行）。之所以不像上面那样在安装前判开关：
        // onSystemServerStarting 是本模块进入 system_server 的唯一时机，若按开关决定装不装，
        // 用户开启开关后就必须重启设备才生效。未布防时回调只做一次布尔判断即返回，代价可忽略。
        runCatching {
            val ok = VolumeKeyHook.install(this, param.classLoader) {
                runCatching { getRemotePreferences(Constants.REMOTE_PREFS) }.getOrNull()
            }
            log(Log.INFO, TAG, "volume key hook installed=$ok")
        }.onFailure {
            log(Log.WARN, TAG, "volume key hook init error: ${it.message}", it)
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // 音频 hook 由 Application.attach 安装，per-package 回调只用来记日志
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
                    XBridge.apiVersion = 101
                    // 注意：这里**不再**主动 pingModuleLoaded / 发起任何 ContentResolver 调用。
                    // 之前每个 App 启动都 ping 一次 RuntimeProvider，是「被杀又秒复活」的主要来源之一。
                    // 「模块已激活」的诊断状态改由 RuntimeProvider.query 在真正被查询时更新。
                    AudioRecordHook.install(this@GlassMicXposedModule, ctx, pkg)
                    // MediaRecorder 服务端采集缺口：拦截纯音频 MediaRecorder，接管为应用内
                    // AudioRecord(已 Hook)+MediaCodec+MediaMuxer，覆盖聊天语音类路径。
                    MediaRecorderHook.install(this@GlassMicXposedModule, ctx, pkg)
                    val nativeOk = NativeAAudioHook.install(ctx, pkg)
                    log(Log.INFO, TAG, "hooks installed in $pkg native=$nativeOk")
                }.onFailure {
                    log(Log.WARN, TAG, "install hooks in $pkg failed: ${it.message}", it)
                }
                result
            }
        log(Log.INFO, TAG, "Application.attach hook installed")
    }
}
