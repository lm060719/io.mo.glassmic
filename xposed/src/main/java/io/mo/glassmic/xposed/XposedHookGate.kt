package io.mo.glassmic.xposed

import io.mo.glassmic.core.Constants
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 防止同一个进程里 API 101 与 legacy API 82 双入口同时生效时重复安装 AudioRecord hook。
 *
 * 同时承载"哪些进程绝不安装 audio hook"的共享白名单——zygote 级 hook 会覆盖所有 App，
 * 但系统命脉（systemui / launcher / GMS / 输入法）必须放过，否则一个 bug 就是系统级崩。
 */
object XposedHookGate {
    private val audioHookInstalled = AtomicBoolean(false)

    fun tryMarkAudioHookInstalled(): Boolean =
        audioHookInstalled.compareAndSet(false, true)

    /** 任意 Xposed 入口都应该在装 hook 前调一次。返回 true 表示该跳过此包。 */
    fun shouldSkipPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true
        return pkg in PROTECTED_PROCESSES
    }

    private val PROTECTED_PROCESSES: Set<String> = setOf(
        // Android 系统进程
        "android",
        "system",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.providers.settings",
        "com.android.providers.media.module",
        "com.android.providers.downloads",
        "com.android.providers.contacts",
        "com.android.providers.telephony",
        "com.android.providers.calendar",
        "com.android.shell",
        "com.android.externalstorage",
        "com.android.documentsui",
        "com.android.printspooler",

        // Launcher
        "com.android.launcher3",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.coloros.launcher",
        "com.vivo.launcher",
        "com.sec.android.app.launcher",
        "com.realme.launcher",
        "com.google.android.apps.nexuslauncher",

        // GMS / Google
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.gms.unstable",
        "com.android.vending",

        // 输入法
        "com.baidu.input",
        "com.sohu.inputmethod.sogou",
        "com.iflytek.inputmethod",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",

        // 自己
        Constants.APP_PACKAGE
    )
}
