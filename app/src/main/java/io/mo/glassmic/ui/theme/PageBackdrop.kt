package io.mo.glassmic.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

/**
 * 页面背景（外观设置里的「页面背景」）。
 *
 * - [image] 非空：铺满页面的背景图（自选图片或手机壁纸），本身保持清晰。
 * - [image] 为空但 [glowColors] 非空：读不到壁纸图片时，用壁纸主色给默认光晕上色。
 * - 都为空：默认彩色光斑背景。
 */
@Immutable
data class PageBackdrop(
    val image: ImageBitmap? = null,
    /** 卡片磨砂层的模糊强度 0..1，映射到 0..40dp 的模糊半径。 */
    val blur: Float = DEFAULT_BLUR,
    /** 卡片磨砂层的遮罩不透明度 0..1：深色模式压暗、浅色模式提亮，保证卡片文字可读。 */
    val dim: Float = DEFAULT_DIM,
    val glowColors: List<Color> = emptyList(),
) {
    companion object {
        const val DEFAULT_BLUR = 0.5f
        const val DEFAULT_DIM = 0.35f
    }
}

val LocalPageBackdrop = staticCompositionLocalOf { PageBackdrop() }
