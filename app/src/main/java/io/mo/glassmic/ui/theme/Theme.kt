package io.mo.glassmic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.proto.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 当前是否启用液态玻璃效果——给所有 GlassSurface 用 */
val LocalGlassEnabled = compositionLocalOf { true }

/** 当前是否降低动画——给过渡/转场用 */
val LocalReduceMotion = compositionLocalOf { false }

private val GlassTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
)

data class GlassThemeState(
    val theme: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    val glassEffect: Boolean = true,
    val reduceMotion: Boolean = false
)

@HiltViewModel
class ThemeViewModel @Inject constructor(
    configStore: ConfigStore
) : ViewModel() {
    val state: StateFlow<GlassThemeState> = configStore.flow
        .map { cfg ->
            GlassThemeState(
                theme = cfg.appearance.theme,
                glassEffect = cfg.appearance.glassEffect,
                reduceMotion = cfg.appearance.reduceMotion
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, GlassThemeState())
}

@Composable
fun GlassMicTheme(content: @Composable () -> Unit) {
    val vm: ThemeViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    val isDark = when (state.theme) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    val scheme = if (isDark) GlassDarkScheme else GlassLightScheme

    MaterialTheme(colorScheme = scheme, typography = GlassTypography) {
        CompositionLocalProvider(
            LocalGlassEnabled provides state.glassEffect,
            LocalReduceMotion provides state.reduceMotion
        ) { content() }
    }
}
