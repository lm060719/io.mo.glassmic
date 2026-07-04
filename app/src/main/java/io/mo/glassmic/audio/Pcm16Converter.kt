package io.mo.glassmic.audio

/**
 * 流式 PCM16（小端、交错）重采样 + 声道混合。
 *
 * 把 [sourceSampleRate]/[sourceChannels] 的输入增量转换到
 * [targetSampleRate]/[targetChannels]。内部保留跨调用的样本游标与未消费样本，
 * 因此可以一小块一小块地喂数据。源/目标一致时零拷贝直接返回。
 *
 * 由 SharedPcmPublisher（每 consumer 一份）与 TtsAudioSource 共用。
 */
internal class Pcm16Converter(
    private val sourceSampleRate: Int,
    private val sourceChannels: Int,
    private val targetSampleRate: Int,
    private val targetChannels: Int
) {
    private var pending = ShortArray(0)
    private var sourcePosition = 0.0

    fun convert(bytes: ByteArray): ByteArray {
        if (sourceSampleRate == targetSampleRate && sourceChannels == targetChannels) {
            return bytes
        }

        append(bytes)
        if (pending.isEmpty()) return ByteArray(0)

        val step = sourceSampleRate.toDouble() / targetSampleRate.toDouble()
        val frames = ArrayList<ShortArray>(pending.size / sourceChannels)
        while (sourcePosition < frameCount()) {
            val sourceFrame = sourcePosition.toInt()
            frames += mixFrame(sourceFrame)
            sourcePosition += step
        }

        trimConsumedSourceFrames()
        if (frames.isEmpty()) return ByteArray(0)

        val out = ByteArray(frames.size * targetChannels * 2)
        var offset = 0
        frames.forEach { frame ->
            frame.forEach { sample ->
                out[offset++] = (sample.toInt() and 0xFF).toByte()
                out[offset++] = ((sample.toInt() ushr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun append(bytes: ByteArray) {
        val samplesToAdd = bytes.size / 2
        if (samplesToAdd <= 0) return

        val combined = ShortArray(pending.size + samplesToAdd)
        pending.copyInto(combined)
        var src = 0
        var dst = pending.size
        while (src + 1 < bytes.size) {
            val lo = bytes[src].toInt() and 0xFF
            val hi = bytes[src + 1].toInt()
            combined[dst++] = ((hi shl 8) or lo).toShort()
            src += 2
        }
        pending = combined
    }

    private fun frameCount(): Int = pending.size / sourceChannels

    private fun mixFrame(frameIndex: Int): ShortArray {
        val base = frameIndex * sourceChannels
        return ShortArray(targetChannels) { channel ->
            when {
                sourceChannels == 1 -> pending.getOrElse(base) { 0 }
                targetChannels == 1 -> {
                    val left = pending.getOrElse(base) { 0 }.toInt()
                    val right = pending.getOrElse(base + 1) { 0 }.toInt()
                    ((left + right) / 2).toShort()
                }
                channel < sourceChannels -> pending.getOrElse(base + channel) { 0 }
                else -> 0
            }
        }
    }

    private fun trimConsumedSourceFrames() {
        val frames = frameCount()
        val consumed = sourcePosition.toInt().coerceAtMost(frames)
        if (consumed <= 0) return

        val consumedSamples = consumed * sourceChannels
        pending = pending.copyOfRange(consumedSamples, pending.size)
        sourcePosition -= consumed
    }
}
