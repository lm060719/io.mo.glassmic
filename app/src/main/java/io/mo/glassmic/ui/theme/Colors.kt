package io.mo.glassmic.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * GlassMic 设计 token——类 iOS / 极简 / 液态玻璃
 *
 * 不直接使用 Material 默认色板——避免"工具感太重"的灰蓝调。
 * 采用偏暖的中性灰 + 海蓝主色，与液态玻璃模糊背景搭配呈现"高级测试工具"质感。
 */
object GlassColors {
    // 主色——偏冷的海蓝
    val Primary = Color(0xFF3D7AFF)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFD6E4FF)
    val OnPrimaryContainer = Color(0xFF001A4D)

    // 次色——偏暖的米
    val Secondary = Color(0xFF7C7560)
    val OnSecondary = Color(0xFFFFFFFF)

    // 错误（安全模式 banner）
    val Error = Color(0xFFE5484D)
    val OnError = Color(0xFFFFFFFF)

    // 表面（浅色）
    val SurfaceLight = Color(0xFFF8F8F6)
    val OnSurfaceLight = Color(0xFF1A1A1A)
    val SurfaceContainerLight = Color(0xFFFFFFFF)
    val SurfaceVariantLight = Color(0xFFEDEDEA)

    // 表面（深色）
    val SurfaceDark = Color(0xFF111114)
    val OnSurfaceDark = Color(0xFFF5F5F5)
    val SurfaceContainerDark = Color(0xFF1C1C20)
    val SurfaceVariantDark = Color(0xFF2A2A2E)

    // 液态玻璃 tint
    val GlassTintLight = Color(0x33FFFFFF)
    val GlassTintDark = Color(0x33181820)
    val GlassBorderLight = Color(0x40FFFFFF)
    val GlassBorderDark = Color(0x40FFFFFF)
}

val GlassLightScheme: ColorScheme = lightColorScheme(
    primary = GlassColors.Primary,
    onPrimary = GlassColors.OnPrimary,
    primaryContainer = GlassColors.PrimaryContainer,
    onPrimaryContainer = GlassColors.OnPrimaryContainer,
    secondary = GlassColors.Secondary,
    onSecondary = GlassColors.OnSecondary,
    error = GlassColors.Error,
    onError = GlassColors.OnError,
    background = GlassColors.SurfaceLight,
    onBackground = GlassColors.OnSurfaceLight,
    surface = GlassColors.SurfaceLight,
    onSurface = GlassColors.OnSurfaceLight,
    surfaceContainer = GlassColors.SurfaceContainerLight,
    surfaceVariant = GlassColors.SurfaceVariantLight
)

val GlassDarkScheme: ColorScheme = darkColorScheme(
    primary = GlassColors.Primary,
    onPrimary = GlassColors.OnPrimary,
    secondary = GlassColors.Secondary,
    onSecondary = GlassColors.OnSecondary,
    error = GlassColors.Error,
    onError = GlassColors.OnError,
    background = GlassColors.SurfaceDark,
    onBackground = GlassColors.OnSurfaceDark,
    surface = GlassColors.SurfaceDark,
    onSurface = GlassColors.OnSurfaceDark,
    surfaceContainer = GlassColors.SurfaceContainerDark,
    surfaceVariant = GlassColors.SurfaceVariantDark
)
