package io.mo.glassmic.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.mo.glassmic.core.Constants
import java.lang.reflect.Method

/**
 * 严格 ROM 包可见性放行（在 system_server 内安装）。
 *
 * 背景：偏原生 / 隐私 ROM（LineageOS、部分魅族等）严格执行 Android 11+ 的包可见性过滤
 * （compat change ENFORCE_PACKAGE_VISIBILITY_FILTERING）。这会让被注入的目标 App 进程
 * 「看不到」本模块包，从而无法通过 ContentResolver 访问 RuntimeProvider / PcmStreamProvider，
 * 拿不到决策与 PCM 数据，最终回退真实麦克风——表现为「注入成功但没效果」。
 *
 * 修复：hook `com.android.server.pm.AppsFilterBase#shouldFilterApplication`（返回 true 表示
 * 「对调用方隐藏该目标包」）。当被查询的目标包是本模块时，强制返回 false（不隐藏），
 * 让所有 App 都能看到本模块、正常访问其 Provider。
 *
 * 放行范围**只限本包**（io.mo.glassmic），不影响任何其它包的可见性；并全程异常保护，
 * hook 失败或回调出错都不会影响 system_server。仅在用户主动开启「兼容模式」后才安装。
 */
object SystemVisibilityHook {

    private const val TAG = "GlassMic-Vis"
    private val SELF = Constants.APP_PACKAGE

    @Volatile private var installed = false
    @Volatile private var getPkgNameMethod: Method? = null

    fun install(api: XposedInterface, classLoader: ClassLoader): Boolean {
        synchronized(this) {
            if (installed) return true

            val clazz = loadAppsFilterClass(classLoader)
            if (clazz == null) {
                api.log(Log.WARN, TAG, "AppsFilter class not found; skip")
                return false
            }

            val method = findShouldFilterMethod(clazz)
            if (method == null) {
                api.log(Log.WARN, TAG, "shouldFilterApplication not found on ${clazz.name}; skip")
                return false
            }

            // 找出「目标包设置」参数的位置（PackageStateInternal / PackageSetting），
            // 不硬编码方法签名，以适配不同 Android 版本。
            val targetIdx = method.parameterTypes.indexOfLast {
                it.name.contains("PackageState") || it.name.contains("PackageSetting")
            }
            if (targetIdx < 0) {
                api.log(
                    Log.WARN, TAG,
                    "target param not found, params=[${method.parameterTypes.joinToString { it.name }}]; skip"
                )
                return false
            }

            runCatching {
                api.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        // 仅在系统判定「应隐藏」(true) 时介入，且只放行本模块自己这一个包。
                        if (result == true) {
                            try {
                                val target = chain.getArg(targetIdx)
                                if (target != null && packageNameOf(target) == SELF) {
                                    return@intercept false
                                }
                            } catch (_: Throwable) {
                                // 取包名失败：保持系统原始结果，绝不影响其它包。
                            }
                        }
                        result
                    }
            }.onFailure {
                api.log(Log.WARN, TAG, "hook shouldFilterApplication failed: ${it.message}", it)
                return false
            }

            installed = true
            api.log(
                Log.INFO, TAG,
                "visibility allowlist installed: ${clazz.name}#${method.name} targetIdx=$targetIdx pkg=$SELF"
            )
            return true
        }
    }

    private fun loadAppsFilterClass(cl: ClassLoader): Class<*>? =
        sequenceOf(
            "com.android.server.pm.AppsFilterBase",  // Android 14+
            "com.android.server.pm.AppsFilterImpl",  // 兜底
            "com.android.server.pm.AppsFilter"       // Android 11–13
        ).firstNotNullOfOrNull { name ->
            runCatching { cl.loadClass(name) }.getOrNull()?.takeIf { hasShouldFilter(it) }
        }

    private fun hasShouldFilter(clazz: Class<*>): Boolean =
        clazz.declaredMethods.any { it.name == "shouldFilterApplication" }

    private fun findShouldFilterMethod(clazz: Class<*>): Method? =
        clazz.declaredMethods
            .filter { it.name == "shouldFilterApplication" && it.returnType == Boolean::class.javaPrimitiveType }
            // 多个重载时优先参数最多的（含 Computer snapshot 的新签名）。
            .maxByOrNull { it.parameterTypes.size }

    private fun packageNameOf(target: Any): String? {
        var m = getPkgNameMethod
        if (m == null) {
            m = runCatching { target.javaClass.getMethod("getPackageName") }.getOrNull()
            getPkgNameMethod = m
        }
        return runCatching { m?.invoke(target) as? String }.getOrNull()
    }
}
