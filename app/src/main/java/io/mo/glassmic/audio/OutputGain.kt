package io.mo.glassmic.audio

import io.mo.glassmic.proto.AppConfig
import kotlin.math.roundToInt

/** 注入音量倍率：导入音频与 TTS 共用，在 [SharedPcmPublisher] 的效果链最前端生效。 */
object OutputGain {
    const val MIN = 0f
    const val MAX = 3f
    const val DEFAULT = 1f

    fun of(cfg: AppConfig): Float =
        if (cfg.hasOutputGain()) normalize(cfg.outputGain) else DEFAULT

    /** 夹到合法范围并按 5% 取整，滑块拖动时更容易停在 100%。 */
    fun normalize(value: Float): Float {
        if (!value.isFinite()) return DEFAULT
        return ((value.coerceIn(MIN, MAX) * 20f).roundToInt() / 20f)
    }
}
