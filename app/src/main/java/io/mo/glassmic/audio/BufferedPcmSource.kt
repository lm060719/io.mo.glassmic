package io.mo.glassmic.audio

import io.mo.glassmic.core.model.SourceType
import java.nio.ByteBuffer

/**
 * 播放一段已经生成好的、主时钟格式（48kHz 单声道 PCM16）的音频。
 *
 * 用于「先生成后播放」：TTS 合成结果先转成主格式缓存，用户点播放时用本 source 喂给管线，
 * 可重复播放（每次重新 setSource 或 [reset] 从头开始）。播完返回 -1（EOF）交由播放策略处理。
 */
class BufferedPcmSource(private val pcm: ByteArray) : AudioSourceProvider {

    override val type = SourceType.TTS

    private var pos = 0

    override suspend fun read(out: ByteBuffer, sampleRate: Int, channels: Int): Int {
        if (pos >= pcm.size) return -1
        val remaining = pcm.size - pos
        // 按 2 字节（PCM16 单声道帧）对齐写出
        val toWrite = minOf(out.remaining(), remaining).let { it - (it % BYTES_PER_FRAME) }
        if (toWrite <= 0) return if (remaining < BYTES_PER_FRAME) -1 else 0
        out.put(pcm, pos, toWrite)
        pos += toWrite
        return toWrite
    }

    override fun positionMs(): Long = pos.toLong() * 1000 / (MASTER_SAMPLE_RATE * BYTES_PER_FRAME)
    override fun durationMs(): Long = pcm.size.toLong() * 1000 / (MASTER_SAMPLE_RATE * BYTES_PER_FRAME)

    /** 重播：回到开头。 */
    override fun reset() { pos = 0 }

    private companion object {
        const val MASTER_SAMPLE_RATE = 48_000
        const val BYTES_PER_FRAME = 2   // 48k 单声道 PCM16
    }
}
