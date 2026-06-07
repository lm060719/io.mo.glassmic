package io.mo.glassmic.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.mo.glassmic.ui.theme.LocalGlassEnabled

/**
 * 液态玻璃容器。
 *
 * 旧实现把 RenderEffect 直接套在整个 Box 上，Compose 会把子节点也放到同一图层里，
 * 导致文字、图标和进度条一起被模糊。这里改为只绘制半透明玻璃底色、边框和高光，
 * 不再对内容图层做 blur，保证所有页面文字清晰可读。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    tintAlpha: Float = 0.18f,
    content: @Composable BoxScope.() -> Unit
) {
    val enabled = LocalGlassEnabled.current
    val shape = RoundedCornerShape(cornerRadius)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val backgroundBrush = if (enabled) {
        Brush.verticalGradient(
            listOf(
                glassTint(isDark, tintAlpha * if (isDark) 0.72f else 1.18f),
                glassTint(isDark, tintAlpha * if (isDark) 0.48f else 0.78f),
                glassTint(isDark, tintAlpha * if (isDark) 0.36f else 0.58f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceContainer,
                MaterialTheme.colorScheme.surfaceContainer
            )
        )
    }

    val borderColor = if (enabled) {
        if (isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.58f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    }

    val finalModifier = modifier
        .clip(shape)
        .background(backgroundBrush)
        .border(BorderStroke(0.7.dp, borderColor), shape)

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(modifier = finalModifier, content = content)
    }
}

private fun glassTint(isDark: Boolean, alpha: Float): Color =
    if (isDark) Color.White.copy(alpha = alpha.coerceIn(0f, 0.22f))
    else Color.White.copy(alpha = alpha.coerceIn(0f, 0.42f))

private fun Color.luminance(): Float =
    0.299f * red + 0.587f * green + 0.114f * blue
