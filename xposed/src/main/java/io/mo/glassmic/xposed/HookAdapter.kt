package io.mo.glassmic.xposed

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * libxposed API 101 与 legacy API 82 的最小公共面。
 *
 * 音频 hook 的拦截逻辑只写一份（[AudioRecordHook]、[MediaRecorderHook]），通过 [MethodHooker]
 * 装到任一套 API 上。以前两套入口各抄一份，legacy 那份已经漏掉了 SILENCE 实时节流等修复。
 *
 * 语义：handler 在原方法执行前做决定——[HookDecision.Proceed] 放行原方法，
 * [HookDecision.Replace] 跳过原方法并以给定值返回。两套 API 对此都有等价实现。
 */
internal sealed interface HookDecision {
    data object Proceed : HookDecision
    data class Replace(val value: Any?) : HookDecision
}

/** 一次被拦截的调用。 */
internal interface HookCall {
    val thisObject: Any?
    fun arg(index: Int): Any?
}

internal interface MethodHooker {
    fun hook(method: Method, handler: (HookCall) -> HookDecision)
    fun log(level: Int, message: String, error: Throwable? = null)
}

internal fun findMethod(cls: Class<*>, name: String, vararg paramTypes: Class<*>?): Method? =
    runCatching { cls.getMethod(name, *paramTypes) }
        .recoverCatching { cls.getDeclaredMethod(name, *paramTypes) }
        .getOrNull()

/** handler 抛异常一律按放行处理：hook 出错时宁可用真麦，也不能让目标 App 崩。 */
private inline fun decide(
    hooker: MethodHooker,
    tag: String,
    handler: (HookCall) -> HookDecision,
    call: HookCall
): HookDecision = try {
    handler(call)
} catch (t: Throwable) {
    hooker.log(Log.WARN, "$tag: hook error: ${t.message}", t)
    HookDecision.Proceed
}

internal class ModernHooker(
    private val api: XposedInterface,
    private val tag: String
) : MethodHooker {
    override fun hook(method: Method, handler: (HookCall) -> HookDecision) {
        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val call = object : HookCall {
                    override val thisObject: Any? get() = chain.getThisObject()
                    override fun arg(index: Int): Any? = chain.getArg(index)
                }
                when (val decision = decide(this, tag, handler, call)) {
                    HookDecision.Proceed -> chain.proceed()
                    is HookDecision.Replace -> decision.value
                }
            }
    }

    override fun log(level: Int, message: String, error: Throwable?) {
        if (error != null) api.log(level, tag, message, error) else api.log(level, tag, message)
    }
}

internal class LegacyHooker(private val tag: String) : MethodHooker {
    override fun hook(method: Method, handler: (HookCall) -> HookDecision) {
        runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val call = object : HookCall {
                        override val thisObject: Any? get() = param.thisObject
                        override fun arg(index: Int): Any? = param.args[index]
                    }
                    val decision = decide(this@LegacyHooker, tag, handler, call)
                    // before 阶段设置 result 即跳过原方法，与 API 101 不调用 chain.proceed() 等价。
                    if (decision is HookDecision.Replace) param.result = decision.value
                }
            })
        }.onFailure { log(Log.WARN, "$tag: hook ${method.name} failed: ${it.message}", it) }
    }

    override fun log(level: Int, message: String, error: Throwable?) {
        XposedBridge.log(message)
        if (error != null) XposedBridge.log(error)
    }
}
