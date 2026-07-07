package io.mo.glassmic.audio

import io.mo.glassmic.core.audio.ComfortNoise
import io.mo.glassmic.core.model.SourceType
import java.nio.ByteBuffer

/**
 * 统一音频源抽象。
 *
 * 调用方告知期望的 sampleRate / channels / fmt，source 负责输出符合规格的 PCM。
 * 任何 source 都必须在异常时返回静音，绝不抛异常给调用方。
 */
interface AudioSourceProvider {
    val type: SourceType

    /** 读取最多 [out.remaining()] 字节到 out。返回实际写入字节数（0 = 没数据） */
    suspend fun read(out: ByteBuffer, sampleRate: Int, channels: Int): Int

    fun positionMs(): Long = 0L
    fun durationMs(): Long = 0L
    fun reset() {}
    fun release() {}
}

/** 静音源——任何时候都返回全 0 */
object SilenceSource : AudioSourceProvider {
    override val type = SourceType.SILENCE

    override suspend fun read(out: ByteBuffer, sampleRate: Int, channels: Int): Int {
        val n = out.remaining()
        repeat(n) { out.put(0) }
        return n
    }
}

/**
 * 舒适噪声源——对外仍是"静音"语义，但输出极低电平的本底噪声，
 * 避免下游 App（如微信语音输入）把纯 0 判定为"无音频输入"而中断录音。
 * 用于暂停 / idle 时保持虚拟麦克风"存活"。
 */
object ComfortNoiseSource : AudioSourceProvider {
    override val type = SourceType.SILENCE

    override suspend fun read(out: ByteBuffer, sampleRate: Int, channels: Int): Int {
        val n = out.remaining()
        ComfortNoise.putPcm16(out, n)
        return n
    }
}
