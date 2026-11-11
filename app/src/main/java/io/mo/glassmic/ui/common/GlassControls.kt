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
