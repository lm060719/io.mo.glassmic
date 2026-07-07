package io.mo.glassmic.core.audio

import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * 舒适噪声（comfort noise）。
 *
 * 纯数字静音（全 0 的 PCM）是"死麦"的特征——真实麦克风永远带本底噪声。
 * 部分 App 的 VAD（语音活动检测，如微信语音输入）看到持续全 0 会判定"无音频输入"，
 * 从而中断录音。给虚拟静音注入极低电平、人耳不可闻的白噪声，可让麦克风看起来"活着"。
 *
 * 幅度以 PCM16 的 LSB 计。[AMPLITUDE] = 8 约 -72 dBFS，正常听感下完全不可闻，
 * 但足以让下游判定"有信号"。数值可按需微调。
 */
object ComfortNoise {

    const val AMPLITUDE = 8

    /** 生成一个 [-AMPLITUDE, AMPLITUDE] 的 PCM16 采样值。 */
    fun sample(): Int = Random.nextInt(-AMPLITUDE, AMPLITUDE + 1)

    /** 用小端 PCM16 舒适噪声填充 [buf] 的 [offset, offset+size)（size 以字节计）。 */
    fun fillBytes(buf: ByteArray, offset: Int, size: Int) {
        var i = offset
        val lastPair = offset + size - 1
        while (i < lastPair) {
            val s = sample()
            buf[i] = (s and 0xFF).toByte()
            buf[i + 1] = ((s shr 8) and 0xFF).toByte()
            i += 2
        }
        if (i < offset + size) buf[i] = 0  // 奇数尾字节兜底
    }

    /** 用舒适噪声填充 ShortArray 的 [offset, offset+count)。 */
    fun fillShorts(buf: ShortArray, offset: Int, count: Int) {
        for (i in 0 until count) buf[offset + i] = sample().toShort()
    }

    /** 用舒适噪声填充 FloatArray（归一化到 [-1, 1]）的 [offset, offset+count)。 */
    fun fillFloats(buf: FloatArray, offset: Int, count: Int) {
        for (i in 0 until count) buf[offset + i] = sample() / 32768f
    }

    /** 用小端 float32 舒适噪声填充 [buf] 的 [offset, offset+size)（size 以字节计）。 */
    fun fillFloatBytes(buf: ByteArray, offset: Int, size: Int) {
        var i = offset
        val end = offset + size
        while (i + 3 < end) {
            val bits = java.lang.Float.floatToIntBits(sample() / 32768f)
            buf[i] = (bits and 0xFF).toByte()
            buf[i + 1] = ((bits shr 8) and 0xFF).toByte()
            buf[i + 2] = ((bits shr 16) and 0xFF).toByte()
            buf[i + 3] = ((bits shr 24) and 0xFF).toByte()
            i += 4
        }
        while (i < end) { buf[i] = 0; i++ }  // 尾部不足 4 字节兜底
    }

    /** 向 [buf] 写入 [size] 字节的小端 PCM16 舒适噪声（从当前 position 起）。 */
    fun putPcm16(buf: ByteBuffer, size: Int) {
        var written = 0
        while (written + 1 < size) {
            val s = sample()
            buf.put((s and 0xFF).toByte())
            buf.put(((s shr 8) and 0xFF).toByte())
            written += 2
        }
        while (written < size) { buf.put(0.toByte()); written++ }
    }

    /** 向 [buf] 写入 [size] 字节的小端 float32 舒适噪声（从当前 position 起）。 */
    fun putFloat32(buf: ByteBuffer, size: Int) {
        var written = 0
        while (written + 3 < size) {
            val bits = java.lang.Float.floatToIntBits(sample() / 32768f)
            buf.put((bits and 0xFF).toByte())
            buf.put(((bits shr 8) and 0xFF).toByte())
            buf.put(((bits shr 16) and 0xFF).toByte())
            buf.put(((bits shr 24) and 0xFF).toByte())
            written += 4
        }
        while (written < size) { buf.put(0.toByte()); written++ }
    }
}
