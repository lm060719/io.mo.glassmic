package io.mo.glassmic.audio

import io.mo.glassmic.core.model.SourceType
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * 实时音源：另一个 App 正在播放的声音（issue #42），由 root 抓取进程推入 [push]。
 *
 * 抓取端按音频 HAL 的节奏推数据，Publisher 按自己的 nanoTime 时钟拉数据，两个时钟会有漂移与抖动，
 * 所以中间放一个小环形缓冲：
 * - 欠载时补静音并进入「预充」状态，攒够 [PRIME_BYTES] 再出声，避免逐帧断续；
 * - 积压超过 [HIGH_BYTES]（暂停期间或时钟漂移）时丢掉最旧的数据回到 [TARGET_BYTES]，把延迟钉住。
 *
 * [read] 总是写满请求的字节数：实时源没有 EOF，Publisher 靠返回量维持实时节奏。
 * 格式固定为主时钟格式 48kHz 单声道 PCM16。
 */
class LiveCaptureSource(
    val packageName: String,
    private val onRelease: (LiveCaptureSource) -> Unit
) : AudioSourceProvider {

    override val type = SourceType.LIVE

    private val lock = Any()
    private val ring = ByteArray(CAPACITY_BYTES)
    private var head = 0   // 下一个读位置
    private var size = 0
    private var priming = true
    private val startedAt = System.currentTimeMillis()

    val pushedBytes = AtomicLong()
    val droppedBytes = AtomicLong()
    val underruns = AtomicLong()

    /** 抓取线程调用。溢出时覆盖最旧的数据。 */
    fun push(data: ByteArray, length: Int) {
        if (length <= 0) return
        synchronized(lock) {
            var off = 0
            var n = length
            if (n > CAPACITY_BYTES) { off = n - CAPACITY_BYTES; n = CAPACITY_BYTES }
            val overflow = size + n - CAPACITY_BYTES
            if (overflow > 0) skipLocked(overflow)
            var tail = (head + size) % CAPACITY_BYTES
            var remaining = n
            while (remaining > 0) {
                val chunk = minOf(remaining, CAPACITY_BYTES - tail)
                System.arraycopy(data, off, ring, tail, chunk)
                off += chunk; remaining -= chunk
                tail = (tail + chunk) % CAPACITY_BYTES
            }
            size += n
        }
        pushedBytes.addAndGet(length.toLong())
    }

    override suspend fun read(out: ByteBuffer, sampleRate: Int, channels: Int): Int {
        val want = alignDown(out.remaining())
        if (want <= 0) return 0
        synchronized(lock) {
            if (priming && size >= PRIME_BYTES) priming = false
            if (!priming) {
                if (size > HIGH_BYTES) skipLocked(alignDown(size - TARGET_BYTES))
                var copied = 0
                while (copied < want && size > 0) {
                    val chunk = minOf(want - copied, size, CAPACITY_BYTES - head)
                    out.put(ring, head, chunk)
                    head = (head + chunk) % CAPACITY_BYTES
                    size -= chunk
                    copied += chunk
                }
                if (copied < want) {
                    underruns.incrementAndGet()
                    priming = true
                    repeat(want - copied) { out.put(0) }
                }
            } else {
                repeat(want) { out.put(0) }
            }
        }
        return want
    }

    val bufferedMs: Long get() = synchronized(lock) { size.toLong() * 1000 / BYTES_PER_SECOND }

    /** 没有时长；位置用已运行时间，仅供 UI 显示。 */
    override fun positionMs(): Long = System.currentTimeMillis() - startedAt

    override fun release() = onRelease(this)

    private fun skipLocked(bytes: Int) {
        val n = minOf(bytes, size)
        head = (head + n) % CAPACITY_BYTES
        size -= n
        droppedBytes.addAndGet(n.toLong())
    }

    private fun alignDown(n: Int) = n - (n % BYTES_PER_FRAME)

    private companion object {
        const val BYTES_PER_FRAME = 2
        const val BYTES_PER_SECOND = 48_000 * BYTES_PER_FRAME
        const val CAPACITY_BYTES = BYTES_PER_SECOND          // 1 s
        const val PRIME_BYTES = BYTES_PER_SECOND * 40 / 1000  // 40 ms
        const val TARGET_BYTES = BYTES_PER_SECOND * 60 / 1000 // 60 ms
        const val HIGH_BYTES = BYTES_PER_SECOND * 200 / 1000  // 200 ms
    }
}
