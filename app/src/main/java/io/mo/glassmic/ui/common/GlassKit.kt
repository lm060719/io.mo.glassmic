package io.mo.glassmic.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mo.glassmic.ui.theme.GlassTokens
import io.mo.glassmic.ui.theme.LocalGlassTokens
import io.mo.glassmic.ui.theme.LocalPageBackdrop
import android.os.SystemClock
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameMillis
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import io.mo.glassmic.ui.theme.LocalReduceMotion


/** 设计稿里 Geist Mono 的位置——数字、包名、时长统一走等宽字体。 */
val MonoFamily: FontFamily = FontFamily.Monospace

val glass: GlassTokens
    @Composable get() = LocalGlassTokens.current

// ============ 页面背景 ============

/**
 * 页面底：基色 + 三团径向光晕；玻璃模式下再叠几颗彩色光球，让半透明卡片有东西可透。
 * 关闭「卡片透明」时只剩纯色底。
 */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val t = glass
    val backdrop = LocalPageBackdrop.current
    val phase = rememberOrbPhase()
    val scene = remember(backdrop.image, backdrop.glowColors, phase) {
        BackdropScene(backdrop.image, backdrop.glowColors, phase)
    }
    // 背景本身保持清晰；「卡片模糊 / 卡片遮罩」只作用在卡片的磨砂层里（见 Modifier.frosted）
    Box(modifier.fillMaxSize().background(t.bgBase).sceneBackground(scene, t)) {
        if (backdrop.image != null) {
            // 图片背景上叠一层很淡的固定遮罩，保证卡片外的页面标题仍然清楚
            Box(Modifier.fillMaxSize().background((if (t.isDark) Color.Black else Color.White).copy(alpha = 0.12f)))
        }
        CompositionLocalProvider(LocalBackdropScene provides scene) { content() }
    }
}

/**
 * 为 true 时页面不再自己画背景：左右滑动的标签页共用外层一张固定背景，
 * 滑动时只有内容移动，背景（光斑 / 图片 / 壁纸）保持不动。
 */
val LocalSharedBackdrop = compositionLocalOf { false }

/**
 * 标准页面骨架：玻璃背景 + 状态栏留白 + 顶部 toast。
 */
@Composable
fun GlassPage(
    toast: SnackbarHostState? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val body: @Composable BoxScope.() -> Unit = {
        CompositionLocalProvider(LocalContentColor provides glass.ink) {
            Box(Modifier.fillMaxSize().statusBarsPadding(), content = content)
        }
        if (toast != null) GlassToastHost(toast, Modifier.align(Alignment.TopCenter).statusBarsPadding())
    }
    if (LocalSharedBackdrop.current) Box(Modifier.fillMaxSize(), content = body)
    else GlassBackground(Modifier.leadingEdgeShadow(), content = body)
}

/**
 * 独立页面（二级页）左缘外侧的一道阴影：背景按屏幕坐标对齐后，页面滑入滑出时
 * 自身边缘与底下的页面完全融在一起，看不出是一整张页面在移动；有这道阴影才像被推走的纸。
 * 页面停在原位时阴影在屏幕外，看不到。
 */
private fun Modifier.leadingEdgeShadow(): Modifier = drawBehind {
    val w = 28.dp.toPx()
    drawRect(
        Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f)), startX = -w, endX = 0f),
        topLeft = Offset(-w, 0f),
        size = Size(w, size.height)
    )
}

/** 列表页通用内边距：左右 18，顶部 12，底部给浮动底栏留 120。 */
fun pagePadding(bottom: Dp = 40.dp) = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = bottom)

// ============ 卡片 ============

/**
 * 玻璃卡片：斜向高光渐变 + 发丝白边 + 顶部 1px 内高光 + 柔和投影。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val t = glass
    val shape = RoundedCornerShape(radius)
    val elevation = if (t.glass) 14.dp else 0.dp
    Column(
        modifier = modifier
            .then(if (onClick != null) Modifier.liquidClickable(pressed = 0.98f, onClick = onClick) else Modifier)
            .shadow(elevation, shape, ambientColor = t.shadowColor, spotColor = t.shadowColor)
            .clip(shape)
            .frosted()
            .background(t.card)
            .border(BorderStroke(1.dp, t.rim), shape)
            .drawWithContent {
                drawContent()
                if (t.highlight.alpha > 0f) {
                    val inset = radius.toPx() * 0.6f
                    drawLine(
                        t.highlight, Offset(inset, 1.5f), Offset(size.width - inset, 1.5f),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
            .padding(contentPadding),
        content = content
    )
}

/**
 * 设置类分组卡片：左右 16 内边距，子项之间自动画分隔线（隐藏的子项不占线）。
 */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    content: @Composable () -> Unit
) {
    GlassCard(modifier.fillMaxWidth(), radius = radius, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        DividedColumn(content = content)
    }
}

/** 子项之间画 1px 分隔线的纵向布局。分隔线位置在摆放时写入 state，绘制阶段读取。 */
@Composable
fun DividedColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val divider = glass.divider
    val lines = remember { mutableStateOf(FloatArray(0)) }
    Layout(
        content = content,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                lines.value.forEach { y -> drawLine(divider, Offset(0f, y), Offset(size.width, y), 1f) }
            }
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val visible = placeables.filter { it.height > 0 }
        val height = visible.sumOf { it.height }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else (placeables.maxOfOrNull { it.width } ?: 0)
        layout(width, height) {
            var y = 0
            val ys = ArrayList<Float>()
            visible.forEachIndexed { i, p ->
                if (i > 0) ys += y.toFloat()
                p.placeRelative(0, y)
                y += p.height
            }
            val arr = ys.toFloatArray()
            if (!arr.contentEquals(lines.value)) lines.value = arr
        }
    }
}

// ============ 文本 / 标题 ============
