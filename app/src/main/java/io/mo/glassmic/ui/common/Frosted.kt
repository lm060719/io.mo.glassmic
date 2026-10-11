package io.mo.glassmic.ui.common

import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import io.mo.glassmic.ui.theme.GlassTokens
import io.mo.glassmic.ui.theme.LocalPageBackdrop
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 磨砂玻璃（毛玻璃）。
 *
 * 页面背景（光斑或图片）始终按「屏幕坐标」绘制在最底层，本身保持清晰。
 * 卡片在自己的背景层里按同样的屏幕坐标把身后那一块重新画进离屏图层，
 * 只对这个图层做模糊、再叠一层底色遮罩——于是卡片透出的是模糊后的背景，
 * 卡片外面的背景不受影响。模糊强度与遮罩深浅来自外观设置（[LocalPageBackdrop]）。
 */
@Stable
class BackdropScene(
    val image: ImageBitmap?,
    val tint: List<Color>,
    private val phaseState: State<Float>,
) {
    /** 光球漂移相位（弧度）。 */
    val phase: Float get() = phaseState.value
}

/** 当前页面背景场景，由 [GlassBackground] 提供，卡片据此重画身后的背景。 */
val LocalBackdropScene = compositionLocalOf<BackdropScene?> { null }

private const val ORB_PERIOD_MS = 28_000L

/**
 * 光球漂移相位：按统一时钟计算（各层背景位置完全一致），每 100ms 更新一次。
 * 一步只移动约 0.6dp，肉眼看是连续的；卡片的模糊层也只需 10 帧/秒重画，省电。
 */
@Composable
fun rememberOrbPhase(): State<Float> {
    val phase = remember { mutableFloatStateOf(currentOrbPhase()) }
    // 光斑只在「卡片透明」开启时绘制；关闭时或减少动画时不刷新相位
    if (liquidEnabled() && glass.glass) {
        LaunchedEffect(Unit) {
            while (true) {
                phase.floatValue = currentOrbPhase()
                delay(100)
            }
        }
    }
    return phase
}

private fun currentOrbPhase(): Float =
    (SystemClock.uptimeMillis() % ORB_PERIOD_MS) / ORB_PERIOD_MS.toFloat() * (2 * PI).toFloat()

/**
 * 按屏幕坐标绘制背景场景：图片（等同 ContentScale.Crop）或默认光斑。
 * [root] 为整个窗口的尺寸；调用方负责把画布平移到屏幕原点。
 * [dp] 为 1dp 对应的像素数，由调用方传入：在 GraphicsLayer 录制块里读取 DrawScope.density
 * 会无限递归（StackOverflowError），所以这里不读 density。
 */
fun DrawScope.drawScene(scene: BackdropScene, t: GlassTokens, root: Size, dp: Float) {
    val image = scene.image
    if (image != null) {
        val scale = maxOf(root.width / image.width, root.height / image.height)
        val dw = image.width * scale
        val dh = image.height * scale
        drawImage(
            image,
            dstOffset = IntOffset(((root.width - dw) / 2f).roundToInt(), ((root.height - dh) / 2f).roundToInt()),
            dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
            filterQuality = FilterQuality.Medium
        )
        return
    }
    if (!t.glass) return
    val tint = scene.tint
    fun pick(i: Int, fallback: Color) = if (tint.isEmpty()) fallback else tint[i % tint.size]
    val w = root.width
    val h = root.height
    val ph = scene.phase
    fun glow(c: Color, cx: Float, cy: Float, r: Float) = drawCircle(
        Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(cx, cy), radius = r),
        radius = r, center = Offset(cx, cy)
    )
    glow(pick(0, t.glowBlue).copy(alpha = t.glowBlue.alpha), 0f, 0f, w * 0.95f)
    glow(pick(1, t.glowOrange).copy(alpha = t.glowOrange.alpha), w, h * 0.42f, w * 0.8f)
    glow(pick(2, t.glowPurple).copy(alpha = t.glowPurple.alpha), w * 0.15f, h, w * 1.0f)
    // 彩色光球：中心偏亮、边缘淡出
    fun orb(edge: Color, cx: Float, cy: Float, r: Float) {
        val core = lerp(edge, Color.White, 0.55f)
        drawCircle(
            Brush.radialGradient(
                0f to core.copy(alpha = t.orbAlpha),
                0.55f to edge.copy(alpha = t.orbAlpha),
                0.71f to edge.copy(alpha = 0f),
                center = Offset(cx - r * 0.3f, cy - r * 0.4f),
                radius = r * 1.4f
            ),
            radius = r, center = Offset(cx, cy)
        )
    }
    val d = dp
    val a = 26f * d
    orb(pick(0, Color(0xFF3D7AFF)), 55f * d + a * sin(ph), 185f * d + a * cos(ph * 2), 125f * d)
    orb(pick(1, Color(0xFFFF8A3D)), w - 45f * d + a * cos(ph), 345f * d + a * sin(ph * 2), 95f * d)
    orb(pick(2, Color(0xFF7B5CFF)), 130f * d - a * sin(ph * 2), 620f * d + a * cos(ph), 100f * d)
    orb(pick(3, Color(0xFF22B5C4)), w - 60f * d - a * cos(ph * 2), 790f * d - a * sin(ph), 90f * d)
}

/**
 * 背景层：按屏幕坐标绘制场景（自身在屏幕上的偏移会被抵消），
 * 这样无论页面怎么滑、怎么推入，背景都和卡片里的磨砂层严格对齐。
 */
fun Modifier.sceneBackground(scene: BackdropScene, t: GlassTokens): Modifier =
    this then SceneElement(scene, t, frosted = false, blurPx = 0f, dim = Color.Transparent)

/**
 * 磨砂玻璃填充：放在卡片的 clip 之后、卡片自身底色之前。
 * 把身后的背景画进离屏图层并模糊（Android 12+），再叠底色遮罩；
 * 关闭「卡片透明」时不生效（卡片为实色）。
 */
fun Modifier.frosted(): Modifier = composed {
    val t = glass
    val scene = LocalBackdropScene.current
    if (!t.glass || scene == null) return@composed this
    val backdrop = LocalPageBackdrop.current
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val blurPx = with(LocalDensity.current) { (backdrop.blur * 40f).dp.toPx() }
    // 不支持模糊的系统用更重的遮罩补偿可读性
    val dim = (backdrop.dim + if (!canBlur && backdrop.blur > 0f) 0.15f else 0f).coerceIn(0f, 0.95f)
    this then SceneElement(
        scene, t, frosted = true,
        blurPx = if (canBlur) blurPx else 0f,
        dim = t.bgBase.copy(alpha = dim)
    )
}

private data class SceneElement(
    val scene: BackdropScene,
    val t: GlassTokens,
    val frosted: Boolean,
    val blurPx: Float,
    val dim: Color,
) : ModifierNodeElement<SceneNode>() {
    override fun create() = SceneNode(scene, t, frosted, blurPx, dim)
    override fun update(node: SceneNode) {
        node.scene = scene; node.t = t; node.frosted = frosted; node.blurPx = blurPx; node.dim = dim
        node.invalidateDraw()
    }
}

/**
 * 按屏幕坐标画背景（[frosted] 为 true 时画进离屏图层并模糊、再叠遮罩）。
 *
 * 位置在布局阶段（onGloballyPositioned）一变化就直接 invalidateDraw，同一帧内重画；
 * 以前写进 State 再由绘制读取，会晚一帧，页面滑动时卡片里的磨砂背景跟不上、看起来在"游"。
 */
private class SceneNode(
    var scene: BackdropScene,
    var t: GlassTokens,
    var frosted: Boolean,
    var blurPx: Float,
    var dim: Color,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var pos = Offset.Zero
    private var root = Size.Zero
    private var layer: GraphicsLayer? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInRoot()
        val r = coordinates.findRootCoordinates().size.toSize()
        if (p != pos || r != root) {
            pos = p
            root = r
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        if (root.width > 0f) {
            // 先在录制块外取好密度：录制块内读 DrawScope.density 会无限递归
            val dp = density
            if (frosted) {
                val l = layer ?: requireGraphicsContext().createGraphicsLayer().also { layer = it }
                l.renderEffect = if (blurPx >= 1f) BlurEffect(blurPx, blurPx, TileMode.Clamp) else null
                l.record(Density(dp, fontScale), layoutDirection, IntSize(size.width.roundToInt(), size.height.roundToInt())) {
                    translate(-pos.x, -pos.y) { drawScene(scene, t, root, dp) }
                }
                drawLayer(l)
            } else {
                translate(-pos.x, -pos.y) { drawScene(scene, t, root, dp) }
            }
        }
        if (frosted) drawRect(dim)
        drawContent()
    }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
    }
}
