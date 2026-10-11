package io.mo.glassmic.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import io.mo.glassmic.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch

/**
 * 「液态」动效的基础件。
 *
 * 液态玻璃的"液体"感主要来自运动：选中指示块像水滴一样被拉长再回弹、
 * 控件按下时被挤压、松手后带一点过冲弹回。这里集中提供这些弹簧参数与工具，
 * 关闭「减少动画」或液态玻璃时全部退化为瞬时变化。
 */
object LiquidSpec {
    /** 指示块领先边：快而略带回弹。 */
    val lead = spring<Float>(dampingRatio = 0.62f, stiffness = 900f)
    /** 指示块拖尾边：慢一拍，形成拉伸。 */
    val trail = spring<Float>(dampingRatio = 0.78f, stiffness = 240f)
    /** 按压回弹：松手后过冲一点再落定。 */
    val press = spring<Float>(dampingRatio = 0.42f, stiffness = 520f)
    val soft = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
}

/** 动效是否启用：减少动画或关闭液态玻璃时禁用。 */
@Composable
fun liquidEnabled(): Boolean = !LocalReduceMotion.current && glass.glass

/**
 * 水滴式选中指示块的左右边缘（单位：槽位序号，左边缘 = index，右边缘 = index + 1）。
 * 移动方向上的领先边用快弹簧、拖尾边用慢弹簧，中途指示块被拉长，到位后回缩成原形。
 */
@Stable
class LiquidEdges internal constructor(
    private val left: Animatable<Float, *>,
    private val right: Animatable<Float, *>,
) {
    val start: Float get() = left.value
    val end: Float get() = right.value
    /** 被拉伸的程度（0 = 原形，1 = 拉长了一整个槽位），用于纵向轻微收缩。 */
    val stretch: Float get() = ((right.value - left.value) - 1f).coerceIn(0f, 1f)
}

@Composable
fun rememberLiquidEdges(index: Int): LiquidEdges {
    val left = remember { Animatable(index.toFloat()) }
    val right = remember { Animatable(index + 1f) }
    val animate = liquidEnabled()
    LaunchedEffect(index, animate) {
        val target = index.toFloat()
        if (!animate) {
            left.snapTo(target); right.snapTo(target + 1f)
            return@LaunchedEffect
        }
        val movingRight = target > left.value
        launch { left.animateTo(target, if (movingRight) LiquidSpec.trail else LiquidSpec.lead) }
        launch { right.animateTo(target + 1f, if (movingRight) LiquidSpec.lead else LiquidSpec.trail) }
    }
    return remember(left, right) { LiquidEdges(left, right) }
}

/** 连续位置版本的水滴指示块（跟随分页拖动），单位同 [LiquidEdges]。 */
@Stable
class LiquidSpan(val start: Float, val end: Float) {
    val stretch: Float get() = ((end - start) - 1f).coerceIn(0f, 1f)
}

/**
 * 跟随连续位置（如分页的 currentPage + offsetFraction）的水滴指示块：
 * 领先边紧跟手指，拖尾边略慢一拍，拖得越快拉得越长，停下后回缩成原形。
 */
@Composable
fun rememberLiquidSpan(position: Float): LiquidSpan {
    val animate = liquidEnabled()
    val prev = remember { floatArrayOf(position) }
    val movingRight = position >= prev[0]
    SideEffect { prev[0] = position }
    val lead = spring<Float>(dampingRatio = 0.8f, stiffness = 1400f)
    val trail = spring<Float>(dampingRatio = 0.85f, stiffness = 380f)
    val start by animateFloatAsState(
        position, if (!animate) snap() else if (movingRight) trail else lead, label = "spanStart"
    )
    val end by animateFloatAsState(
        position + 1f, if (!animate) snap() else if (movingRight) lead else trail, label = "spanEnd"
    )
    return LiquidSpan(start, end)
}

/**
 * 按压挤压 + 回弹缩放。需与使用同一 [source] 的 clickable 配合，放在修饰链最前面，
 * 这样背景、描边和内容会一起被挤压。
 */
fun Modifier.pressScale(source: MutableInteractionSource, enabled: Boolean = true, pressed: Float = 0.96f): Modifier = composed {
    val isPressed by source.collectIsPressedAsState()
    val animate = liquidEnabled()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) pressed else 1f,
        animationSpec = if (animate) LiquidSpec.press else snap(),
        label = "pressScale"
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * 一步到位的液态点击：挤压回弹 + 无涟漪点击。注意它会缩放整个组件，
 * 因此应放在修饰链最前面（在 background / clip 之前）。
 */
fun Modifier.liquidClickable(
    enabled: Boolean = true,
    pressed: Float = 0.96f,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    this
        .pressScale(source, enabled, pressed)
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}
