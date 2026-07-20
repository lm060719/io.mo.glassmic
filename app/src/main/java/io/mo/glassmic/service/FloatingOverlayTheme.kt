package io.mo.glassmic.service

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mo.glassmic.ui.theme.GlassColors
import io.mo.glassmic.ui.theme.LocalGlassEnabled
import io.mo.glassmic.ui.theme.LocalReduceMotion

/**
 * 悬浮窗设计系统。
 *
 * 与主 App 的 [io.mo.glassmic.ui.common.GlassSurface] 分开是有原因的：GlassSurface 只画半透明 tint，
 * 假定身后是 App 自己的不透明背景；悬浮窗浮在任意第三方 App 画面之上，纯 tint 会几乎看不见。
 * 这里的 [GlassPanel] 自带一层不透明底色，再叠玻璃高光与描边，保证在任何背景上文字都可读。
 *
 * 色值统一引用 [GlassColors]，不再像以前那样在各个悬浮窗文件里手抄一遍。
 */
object OverlayColors {
    /** 面板不透明底。基色与 GlassColors.SurfaceContainerDark 同源，留一点透明透出背景层次。 */
    val PanelBase = GlassColors.SurfaceContainerDark.copy(alpha = 0.94f)
    /** 悬浮球底色，比面板更透一些。 */
    val BallBase = GlassColors.SurfaceContainerDark.copy(alpha = 0.82f)

    val Accent = Color(0xFF34C759)
    val Danger = Color(0xFFFF6B6B)
    val Idle = Color(0xFF9E9EA0)

    val OnDark = Color.White
    val OnDarkDim = Color(0xB3FFFFFF)
    val OnDarkFaint = Color(0x66FFFFFF)

    /** 三档白色填充，取代散落各处的 0x33/0x22/0x1A FFFFFF 魔法值。 */
    val FillStrong = Color(0x33FFFFFF)
    val Fill = Color(0x22FFFFFF)
    val FillWeak = Color(0x14FFFFFF)

    val Border = Color(0x24FFFFFF)
    /** 选中行的淡色底，配合绿色 ✓ 让当前项一眼可见。 */
    val SelectedRow = Accent.copy(alpha = 0.14f)
}

object OverlayShapes {
    val Panel = 22.dp
    val Card = 14.dp
    val Pill = 12.dp
    val Chip = 9.dp
}

/**
 * 悬浮窗专用配色方案。
 *
 * 以前悬浮窗只包了一个裸 `MaterialTheme {}`，Slider / Switch 会用 Material3 默认紫色，
 * 和这里的绿色 [OverlayColors.Accent] 直接打架。挂上这个 scheme 后它们自动变绿。
 */
val OverlayColorScheme: ColorScheme = darkColorScheme(
    primary = OverlayColors.Accent,
    onPrimary = Color.Black,
    surface = OverlayColors.PanelBase,
    onSurface = OverlayColors.OnDark,
    surfaceVariant = OverlayColors.Fill,
    onSurfaceVariant = OverlayColors.OnDarkDim,
    background = OverlayColors.PanelBase,
    onBackground = OverlayColors.OnDark,
    error = OverlayColors.Danger,
    outline = OverlayColors.Border
)

/**
 * 液态玻璃容器：不透明底 + 自上而下的高光渐变 + 发丝描边。
 * 设置里关掉「玻璃效果」时退化为纯色底，不画渐变与描边。
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    corner: Dp = OverlayShapes.Panel,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = LocalGlassEnabled.current
    val shape = RoundedCornerShape(corner)

    var m = modifier.clip(shape).background(OverlayColors.PanelBase)
    if (glass) {
        m = m
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.04f),
                        Color.White.copy(alpha = 0.02f)
                    )
                )
            )
            .border(BorderStroke(0.7.dp, OverlayColors.Border), shape)
    }
    Box(modifier = m, content = content)
}

/** 按钮语义档位。生成 / 播放 / 倒计时取消三个按钮统一走这套，不再各自手搓背景。 */
enum class PillStyle { Primary, Secondary, Danger, Disabled }

@Composable
fun PillButton(
    label: String,
    style: PillStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = when (style) {
        PillStyle.Primary -> OverlayColors.Accent
        PillStyle.Secondary -> OverlayColors.FillStrong
        PillStyle.Danger -> OverlayColors.Danger
        PillStyle.Disabled -> OverlayColors.FillWeak
    }
    val fg = when (style) {
        PillStyle.Primary -> Color.Black
        PillStyle.Disabled -> OverlayColors.OnDarkFaint
        else -> OverlayColors.OnDark
    }
    Text(
        text = label,
        color = fg,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(OverlayShapes.Pill))
            .background(bg)
            .clickable(enabled = style != PillStyle.Disabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp)
    )
}

/**
 * 横向分段切换器（iOS 风）。悬浮窗展开态顶部用它在「文字转语音 / 音频库」之间就地切换。
 *
 * 选中指示块是一个独立的滑块，位置用 [animateDpAsState] 平移；等分宽度靠 BoxWithConstraints
 * 拿到实际可用宽度算出来，标签层再叠在上面，保证指示块与文字始终对齐。
 */
@Composable
fun SegmentedSwitch(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val reduceMotion = LocalReduceMotion.current
    val trackPad = 3.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OverlayShapes.Pill))
            .background(OverlayColors.FillWeak)
            .padding(trackPad)
    ) {
        BoxWithConstraints {
            val segW = maxWidth / options.size
            val indicatorX by animateDpAsState(
                targetValue = segW * selectedIndex.coerceIn(0, options.lastIndex),
                animationSpec = if (reduceMotion) snap() else tween(220, easing = FastOutSlowInEasing),
                label = "segIndicator"
            )
            Box(
                modifier = Modifier
                    .offset(x = indicatorX)
                    .width(segW)
                    .height(30.dp)
                    .clip(RoundedCornerShape(OverlayShapes.Pill - trackPad))
                    .background(OverlayColors.Accent)
            )
            Row(modifier = Modifier.fillMaxWidth().height(30.dp)) {
                options.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    val color by animateColorAsState(
                        targetValue = if (selected) Color.Black else OverlayColors.OnDarkDim,
                        animationSpec = if (reduceMotion) snap() else tween(220),
                        label = "segLabel"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(OverlayShapes.Pill - trackPad))
                            .clickable { onSelect(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = color,
                            fontSize = 12.sp,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
