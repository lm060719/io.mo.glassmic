package io.mo.glassmic.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

/**
 * 重设计（通透玻璃方案 A）的语义色 token。
 *
 * 与设计稿 `tok(dark, A, glass)` 一一对应：ink 三档文字、fill 填充、segOn 分段选中块、
 * ok / warn / err 三组状态色，以及卡片 / 底栏 / 底部面板的玻璃材质。
 * 关闭「卡片透明」时退化为不透明实色卡片，背景去掉彩色光斑。
 */
@Immutable
data class GlassTokens(
    val isDark: Boolean,
    val glass: Boolean,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val bgSolid: Color,
    val bgBase: Color,
    val fill: Color,
    val segOn: Color,
    val segShadow: Boolean,
    val primary: Color,
    val primarySoft: Color,
    val primaryInk: Color,
    val divider: Color,
    val dash: Color,
    val ok: Color,
    val okSoft: Color,
    val okInk: Color,
    val warn: Color,
    val warnSoft: Color,
    val warnInk: Color,
    val err: Color,
    val errSoft: Color,
    val ringBorder: Color,
    /** 卡片材质：玻璃模式为 135° 斜向高光渐变。 */
    val card: Brush,
    /** 未选中但需要"卡片底"的小元素（分组 chip 等）用的近似纯色。 */
    val cardFlat: Color,
    val border: Color,
    /** 玻璃边缘受光：左上亮、右下淡，营造玻璃厚度。 */
    val rim: Brush,
    /** 卡片顶部 1px 内高光。 */
    val highlight: Color,
    val shadowColor: Color,
    val bar: Brush,
    val barBorder: Color,
    val sheet: Color,
    /** 背景三团径向光晕（左上蓝 / 右中橙 / 左下紫）的颜色。 */
    val glowBlue: Color,
    val glowOrange: Color,
    val glowPurple: Color,
    val orbAlpha: Float,
)

val GlassPrimary = Color(0xFF3D7AFF)

fun glassTokens(dark: Boolean, glass: Boolean): GlassTokens {
    val ink = if (dark) Color(0xFFF2F3F5) else Color(0xFF15161A)
    val base = GlassTokens(
        isDark = dark,
        glass = glass,
        ink = ink,
        ink2 = ink.copy(alpha = if (dark) 0.68f else 0.66f),
        ink3 = ink.copy(alpha = 0.5f),
        bgSolid = if (dark) Color(0xFF0E0F13) else Color.White,
        bgBase = if (dark) Color(0xFF0B0C11) else Color(0xFFEEF0F5),
        fill = if (dark) Color.White.copy(alpha = 0.09f) else ink.copy(alpha = 0.055f),
        segOn = if (dark) Color.White.copy(alpha = 0.2f) else Color.White,
        segShadow = !dark,
        primary = GlassPrimary,
        primarySoft = GlassPrimary.copy(alpha = if (dark) 0.24f else 0.12f),
        primaryInk = if (dark) Color(0xFF9CBBFF) else Color(0xFF2A62E6),
        divider = if (dark) Color.White.copy(alpha = 0.08f) else ink.copy(alpha = 0.07f),
        dash = if (dark) Color.White.copy(alpha = 0.25f) else ink.copy(alpha = 0.2f),
        ok = Color(0xFF22A559),
        okSoft = Color(0xFF22A559).copy(alpha = if (dark) 0.16f else 0.1f),
        okInk = if (dark) Color(0xFF7BDCA2) else Color(0xFF16723D),
        warn = Color(0xFFD98E04),
        warnSoft = Color(0xFFD98E04).copy(alpha = if (dark) 0.18f else 0.12f),
        warnInk = if (dark) Color(0xFFF3C063) else Color(0xFF8A5A00),
        err = Color(0xFFE5484D),
        errSoft = Color(0xFFE5484D).copy(alpha = if (dark) 0.18f else 0.1f),
        ringBorder = if (dark) Color(0xFF1A1B20) else Color.White,
        card = if (dark) Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.16f),
            0.55f to Color.White.copy(alpha = 0.05f),
            1f to Color.White.copy(alpha = 0.09f)
        ) else Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.62f),
            0.55f to Color.White.copy(alpha = 0.22f),
            1f to Color.White.copy(alpha = 0.38f)
        ),
        cardFlat = if (dark) Color.White.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.5f),
        border = if (dark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.9f),
        rim = if (dark) Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.42f),
            0.45f to Color.White.copy(alpha = 0.08f),
            1f to Color.White.copy(alpha = 0.22f)
        ) else Brush.linearGradient(
            0f to Color.White,
            0.45f to Color.White.copy(alpha = 0.45f),
            1f to Color.White.copy(alpha = 0.85f)
        ),
        highlight = if (dark) Color.White.copy(alpha = 0.22f) else Color.White,
        shadowColor = if (dark) Color.Black.copy(alpha = 0.35f) else Color(0xFF283278).copy(alpha = 0.14f),
        bar = if (dark) Brush.verticalGradient(
            listOf(Color(0xFF2A2C36).copy(alpha = 0.92f), Color(0xFF1C1D24).copy(alpha = 0.9f))
        ) else Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0.78f))
        ),
        barBorder = if (dark) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.95f),
        sheet = if (dark) Color(0xFF1E1F26).copy(alpha = 0.97f) else Color.White.copy(alpha = 0.97f),
        glowBlue = GlassPrimary.copy(alpha = if (dark) 0.42f else 0.32f),
        glowOrange = Color(0xFFFF9A5A).copy(alpha = if (dark) 0.22f else 0.28f),
        glowPurple = Color(0xFF8C6EFF).copy(alpha = if (dark) 0.32f else 0.24f),
        orbAlpha = if (dark) 0.7f else 0.85f,
    )
    if (glass) return base
    // 关闭「卡片透明」：不透明实色卡片 + 纯色背景
    val solid = if (dark) Color(0xFF1C1C20) else Color.White
    return base.copy(
        bgBase = if (dark) Color(0xFF111114) else Color(0xFFF8F8F6),
        card = SolidColor(solid),
        cardFlat = solid,
        border = if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.06f),
        rim = SolidColor(if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.06f)),
        highlight = Color.Transparent,
        shadowColor = Color.Transparent,
        bar = SolidColor(solid),
        barBorder = if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.06f),
        sheet = solid,
        orbAlpha = 0f,
    )
}

val LocalGlassTokens = staticCompositionLocalOf { glassTokens(dark = false, glass = true) }
