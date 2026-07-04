package io.mo.glassmic.audio.tts

import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.log.GlassLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按当前配置选择语音合成引擎。
 *
 * AI TTS 开启且填了 endpoint → 走 [AiTtsSynthesizer]；否则回退系统 [SystemTtsSynthesizer]。
 * AI 相关配置目前隐藏在设置里，普通用户始终走系统 TTS。
 */
@Singleton
class TtsSynthesizerFactory @Inject constructor(
    private val configStore: ConfigStore,
    private val system: SystemTtsSynthesizer,
    private val ai: AiTtsSynthesizer
) {
    /** 返回当前应使用的合成器（不持有状态，可直接调用 synthesize）。 */
    suspend fun current(): SpeechSynthesizer {
        val aiCfg = runCatching { configStore.current().tts.ai }.getOrNull()
        // 开启即走 AI（endpoint/model 可留空按 provider 用默认）；否则系统 TTS。
        return if (aiCfg != null && aiCfg.enabled) {
            GlassLog.b("Tts") { "使用 AI TTS: ${aiCfg.provider}" }
            ai
        } else {
            system
        }
    }

    fun releaseAll() {
        runCatching { system.release() }
        runCatching { ai.cancel() }
    }
}
