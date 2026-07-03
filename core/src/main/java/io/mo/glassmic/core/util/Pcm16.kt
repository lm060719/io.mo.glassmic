package io.mo.glassmic.core.util

import kotlin.math.roundToInt

/**
 * PCM 16-bit 共享采样运算。
 *
 * app 内有多条重采样路径（文件解码、消费者格式转换、变速效果），
 * 舍入与限幅语义必须一致，否则同一段音频经不同路径会产生可闻差异。
 */
object Pcm16 {

    /** 两个样本间按相位 [t] 线性插值，四舍五入并限幅到 16-bit 范围。 */
    fun lerp(a: Int, b: Int, t: Double): Short =
        (a + (b - a) * t).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
}
