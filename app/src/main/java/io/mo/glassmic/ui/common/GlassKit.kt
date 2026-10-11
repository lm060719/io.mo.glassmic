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

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 13.sp,
        color = glass.ink3,
        modifier = modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 0.dp)
    )
}

@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            modifier = Modifier.weight(1f)
        )
        trailing()
    }
}

/** 二级页标题栏：44dp 圆形玻璃返回钮 + 20sp 标题。 */
@Composable
fun BackHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBackIos, null, onBack, iconOffset = 3.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        trailing()
    }
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    size: Dp = 44.dp,
    iconOffset: Dp = 0.dp
) {
    val t = glass
    Box(
        modifier = Modifier
            .liquidClickable(pressed = 0.9f, onClick = onClick)
            .size(size)
            .clip(CircleShape)
            .frosted()
            .background(t.card)
            .border(BorderStroke(1.dp, t.rim), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = t.ink, modifier = Modifier.size(18.dp).offset(x = iconOffset))
    }
}

// ============ 行 ============

/**
 * 设置行：标题 + 可选说明，右侧可放开关 / 数值 / 箭头。
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    chevron: Boolean = false,
    titleColor: Color = Color.Unspecified,
    verticalPadding: Dp = 12.dp,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val t = glass
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, color = if (titleColor == Color.Unspecified) t.ink else titleColor)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 12.sp, lineHeight = 17.sp, color = t.ink3)
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                tint = t.ink.copy(alpha = 0.4f), modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = { onChange(!checked) },
        enabled = enabled,
        verticalPadding = 8.dp
    ) {
        GlassToggle(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** 竖排：标题(+说明) 在上，自定义控件（分段 / 滑块）在下。 */
@Composable
fun StackedRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val t = glass
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 15.sp)
                if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 12.sp, lineHeight = 17.sp, color = t.ink3)
            }
            if (value != null) Text(value, fontSize = 13.sp, color = t.ink3, fontFamily = MonoFamily)
        }
        content()
    }
}

// ============ 控件 ============

/**
 * 48×28 的液态开关：按住时滑块被拉宽（像被手指压扁的水滴），松手后弹簧回到位置并略过冲。
 */
@Composable
fun GlassToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val t = glass
    val animate = liquidEnabled()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val thumbW by animateDpAsState(
        if (pressed && enabled && animate) 32.dp else 24.dp,
        if (animate) spring(dampingRatio = 0.55f, stiffness = 700f) else snap(), label = "thumbW"
    )
    // 滑块贴住所在一侧伸缩：关时左贴 2dp，开时右贴 2dp
    val x by animateDpAsState(
        if (checked) 46.dp - thumbW else 2.dp,
        if (animate) spring(dampingRatio = 0.58f, stiffness = 420f) else snap(), label = "toggleX"
    )
    val track by animateColorAsState(
        if (checked) t.primary else if (t.isDark) Color.White.copy(alpha = 0.18f) else t.ink.copy(alpha = 0.14f),
        if (animate) tween(220) else snap(), label = "toggleTrack"
    )
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .size(48.dp, 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(track)
            .clickable(interactionSource = source, indication = null, enabled = enabled) { onCheckedChange(!checked) }
    ) {
        Box(
            Modifier
                .offset(x = x, y = 2.dp)
                .size(thumbW, 24.dp)
                .shadow(3.dp, RoundedCornerShape(12.dp))
                .background(if (!checked && t.isDark) Color(0xFFE8E9EC) else Color.White, RoundedCornerShape(12.dp))
        )
    }
}

/**
 * iOS 风分段控件：填充底 + 水滴式选中块。切换时选中块沿移动方向被拉长、到位后回弹成形。
 */
@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
    fontSize: TextUnit = 13.sp,
    enabled: Boolean = true
) {
    val t = glass
    val outer = if (height >= 38.dp) 14.dp else 12.dp
    val shape = RoundedCornerShape(outer - 3.dp)
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val edges = rememberLiquidEdges(index)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(outer))
            .background(t.fill)
            .padding(3.dp)
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        val slot = maxWidth / options.size.coerceAtLeast(1)
        // 选中块：宽度随左右边缘伸缩，被拉长时纵向略收，像流动的水滴
        Box(
            Modifier
                .offset(x = slot * edges.start)
                .width(slot * (edges.end - edges.start))
                .height(height)
                .graphicsLayer { scaleY = 1f - 0.14f * edges.stretch }
                .then(if (t.segShadow) Modifier.shadow(2.dp, shape) else Modifier)
                .clip(shape)
                .background(t.segOn)
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, label) ->
                val on = i == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(height)
                        .clip(shape)
                        .clickable(enabled = enabled) { onSelect(value) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontSize = fontSize,
                        color = if (on) t.ink else t.ink2,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

/** 主操作按钮：主色胶囊 + 同色投影。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 52.dp,
    color: Color = Color.Unspecified,
    contentColor: Color = Color.White,
    leading: ImageVector? = null
) {
    val t = glass
    val bg = if (!enabled) t.fill else if (color == Color.Unspecified) t.primary else color
    val shape = RoundedCornerShape(height / 2)
    Row(
        modifier = modifier
            .liquidClickable(enabled = enabled, onClick = onClick)
            .height(height)
            .then(if (enabled && t.glass) Modifier.shadow(10.dp, shape, ambientColor = bg, spotColor = bg) else Modifier)
            .clip(shape)
            .background(bg)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        val fg = if (enabled) contentColor else t.ink3
        if (leading != null) {
            Icon(leading, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, fontSize = if (height >= 50.dp) 15.sp else 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** 次级按钮：填充色胶囊。 */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 36.dp,
    textColor: Color = Color.Unspecified,
    background: Color = Color.Unspecified
) {
    val t = glass
    Box(
        modifier = modifier
            .liquidClickable(enabled = enabled, pressed = 0.94f, onClick = onClick)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(if (background == Color.Unspecified) t.fill else background)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = if (height >= 44.dp) 14.sp else 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            color = when {
                !enabled -> t.ink3
                textColor != Color.Unspecified -> textColor
                else -> t.ink
            }
        )
    }
}

/** 描边玻璃大按钮（导出诊断包等）。 */
@Composable
fun OutlineGlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 48.dp) {
    val t = glass
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier = modifier
            .liquidClickable(enabled = enabled, onClick = onClick)
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .frosted()
            .background(t.card)
            .border(BorderStroke(1.dp, t.rim), shape),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (enabled) t.ink else t.ink3)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

enum class StatusKind { Ok, Warn, Error }

/** 健康状态胶囊：圆点 + 文字，背景为对应状态的淡色。 */
@Composable
fun StatusPill(text: String, kind: StatusKind) {
    val t = glass
    val (bg, fg, dot) = when (kind) {
        StatusKind.Ok -> Triple(t.okSoft, t.okInk, t.ok)
        StatusKind.Warn -> Triple(t.warnSoft, t.warnInk, t.warn)
        StatusKind.Error -> Triple(t.errSoft, t.err, t.err)
    }
    Row(
        Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(bg)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Dot(dot, 6.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

/** 圆形勾选框（应用勾选、协议单选）。 */
@Composable
fun CheckCircle(checked: Boolean, size: Dp = 26.dp, rounded: Dp = size / 2) {
    val t = glass
    val shape = RoundedCornerShape(rounded)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(if (checked) t.primary else Color.Transparent)
            .border(BorderStroke(1.5.dp, if (checked) t.primary else t.dash), shape),
        contentAlignment = Alignment.Center
    ) {
        if (checked) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

@Composable
fun RadioDot(selected: Boolean) {
    val t = glass
    Box(
        Modifier.size(20.dp).border(BorderStroke(2.dp, if (selected) t.primary else t.dash), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Box(Modifier.size(10.dp).background(t.primary, CircleShape))
    }
}

@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val t = glass
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = t.primary,
            inactiveTrackColor = t.fill,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent
        ),
        modifier = Modifier.fillMaxWidth().height(28.dp)
    )
}

/** 填充底输入框（替代 OutlinedTextField）。 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    mono: Boolean = false,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    suffix: String? = null,
    background: Color = Color.Unspecified
) {
    val t = glass
    val shape = RoundedCornerShape(if (singleLine) 12.dp else 16.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = t.ink, fontFamily = if (mono) MonoFamily else null),
        cursorBrush = SolidColor(t.primary),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .clip(shape)
                    .background(if (background == Color.Unspecified) t.fill else background)
                    .border(BorderStroke(1.dp, if (isError) t.err else Color.Transparent), shape)
                    .padding(horizontal = 14.dp, vertical = if (singleLine) 11.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, fontSize = 15.sp, color = t.ink3, maxLines = if (singleLine) 1 else 3)
                    }
                    inner()
                }
                if (suffix != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(suffix, fontSize = 13.sp, color = t.ink3)
                }
            }
        }
    )
}

/** 带小标题的字段（设计稿"接口"卡片里 Endpoint / API Key 的样式）。 */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    mono: Boolean = true
) {
    val t = glass
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = t.ink3)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = t.ink, fontFamily = if (mono) MonoFamily else null),
            cursorBrush = SolidColor(t.primary),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, fontSize = 14.sp, color = t.ink3, fontFamily = if (mono) MonoFamily else null, maxLines = 1)
                    }
                    inner()
                }
            }
        )
    }
}

// ============ 底部面板 / toast ============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(
    title: String,
    meta: String? = null,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val t = glass
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.sheet,
        contentColor = t.ink,
        shape = RoundedCornerShape(34.dp),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 8.dp).size(36.dp, 5.dp).background(t.dash, RoundedCornerShape(3.dp)))
        },
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 2.dp))
            if (!meta.isNullOrBlank()) {
                Text(meta, fontSize = 12.sp, color = t.ink3, fontFamily = MonoFamily, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp))
            } else Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun SheetAction(label: String, onClick: () -> Unit, color: Color = Color.Unspecified, selected: Boolean = false) {
    val t = glass
    Row(
        Modifier
            .liquidClickable(pressed = 0.97f, onClick = onClick)
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(t.fill)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 15.sp,
            color = when {
                color != Color.Unspecified -> color
                selected -> t.primaryInk
                else -> t.ink
            },
            modifier = Modifier.weight(1f)
        )
        if (selected) Icon(Icons.Rounded.Check, null, tint = t.primaryInk, modifier = Modifier.size(18.dp))
    }
}

/** 顶部深色胶囊 toast，替代 Material Snackbar。 */
@Composable
fun GlassToastHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val t = glass
    SnackbarHost(state, modifier.padding(top = 16.dp)) { data ->
        Box(
            Modifier
                .padding(horizontal = 24.dp)
                .shadow(12.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(t.ink)
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Text(
                data.visuals.message,
                color = t.bgSolid,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                style = LocalTextStyle.current
            )
        }
    }
}

/** 列表卡片里的空状态。 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 14.sp,
        color = glass.ink3,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp)
    )
}
