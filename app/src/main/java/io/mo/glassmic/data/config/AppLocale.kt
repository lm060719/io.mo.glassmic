package io.mo.glassmic.data.config

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.mo.glassmic.proto.AppLanguage

/** 把用户选择的 [AppLanguage] 应用为进程当前的 Locale。 */
object AppLocale {
    fun apply(language: AppLanguage) {
        val locales = when (language) {
            AppLanguage.ZH -> LocaleListCompat.forLanguageTags("zh-CN")
            AppLanguage.EN -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
