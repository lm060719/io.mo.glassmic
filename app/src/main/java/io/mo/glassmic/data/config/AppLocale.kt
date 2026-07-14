package io.mo.glassmic.data.config

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.mo.glassmic.proto.AppLanguage
import java.util.Locale

/**
 * 把用户选择的 [AppLanguage] 应用为进程当前的 Locale。
 *
 * [MainActivity] 是普通 ComponentActivity 而非 AppCompatActivity，Android 13 以下
 * [AppCompatDelegate.setApplicationLocales] 不会自动改写它的 Resources（那套自动生效机制依赖
 * AppCompatActivity#attachBaseContext 的包装）。因此这里额外把语言同步写入一份 SharedPreferences
 * 缓存，供 [wrap] 在 attachBaseContext() 阶段同步读取并手动包一层 Locale Context，
 * 保证 Android 10~12 也能正确生效并在重启后保留。
 */
object AppLocale {
    private const val PREFS_NAME = "app_locale"
    private const val KEY_LANGUAGE = "language"

    fun apply(context: Context, language: AppLanguage) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language.name)
            .apply()
        AppCompatDelegate.setApplicationLocales(toLocaleList(language))
    }

    /** 在 [android.app.Activity.attachBaseContext] 里调用，返回按已保存语言包装过的 Context。 */
    fun wrap(base: Context): Context {
        val locale = toLocale(restore(base)) ?: return base
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    /**
     * 供 ViewModel / Service 等非 Composable 场景使用的语言安全取字符串。
     * 不能直接用注入的 [android.content.Context] 调 getString()——Android 13 以下它不会
     * 跟随应用内切换的语言，这里统一走 [wrap] 保证跟 UI 显示的语言一致。
     */
    fun string(context: Context, resId: Int, vararg formatArgs: Any): String =
        if (formatArgs.isEmpty()) wrap(context).getString(resId)
        else wrap(context).getString(resId, *formatArgs)

    private fun restore(context: Context): AppLanguage {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null) ?: return AppLanguage.SYSTEM
        return runCatching { AppLanguage.valueOf(name) }.getOrDefault(AppLanguage.SYSTEM)
    }

    private fun toLocaleList(language: AppLanguage): LocaleListCompat = when (language) {
        AppLanguage.ZH -> LocaleListCompat.forLanguageTags("zh-CN")
        AppLanguage.EN -> LocaleListCompat.forLanguageTags("en")
        else -> LocaleListCompat.getEmptyLocaleList()
    }

    private fun toLocale(language: AppLanguage): Locale? = when (language) {
        AppLanguage.ZH -> Locale.SIMPLIFIED_CHINESE
        AppLanguage.EN -> Locale.ENGLISH
        else -> null
    }
}
