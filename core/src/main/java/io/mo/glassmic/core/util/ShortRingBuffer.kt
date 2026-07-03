package io.mo.glassmic.core.util

/**
 * 单线程使用的可增长 16-bit 环形缓冲区。
 *
 * 音频重采样时需要把「已到达但尚未消费」的源样本暂存起来，跨多次 convert 调用保留。
 * 追加和消费都摊到 O(1)，仅在容量不足时才扩容；容量恒为 2 的幂，
 * 索引用 and mask 代替取模，追加/扩容用至多两段 copyInto 代替逐元素拷贝。
 *
 * 非线程安全：调用方需保证串行访问（Publisher 广播协程 / 解码线程各自单线程）。
 */
class ShortRingBuffer(initialCapacity: Int = 4096) {

    private var buffer = ShortArray(nextPowerOfTwo(initialCapacity.coerceAtLeast(1)))
    private var mask = buffer.size - 1
    private var head = 0
    private var count = 0

    /** 当前可读样本数。 */
    val size: Int get() = count

    /** 读取相对 head 偏移 [index] 处的样本；调用方需保证 0 <= index < size。 */
    operator fun get(index: Int): Short = buffer[(head + index) and mask]

    /** 从 [src] 的 [from] 起追加 [len] 个样本。 */
    fun append(src: ShortArray, from: Int = 0, len: Int = src.size - from) {
        if (len <= 0) return
        ensureCapacity(count + len)
        val tail = (head + count) and mask
        val first = minOf(len, buffer.size - tail)
        src.copyInto(buffer, tail, from, from + first)
        if (first < len) src.copyInto(buffer, 0, from + first, from + len)
        count += len
    }

    /** 丢弃最早的 [n] 个样本（自动裁剪到不超过当前大小）。 */
    fun discard(n: Int) {
        val d = n.coerceIn(0, count)
        head = (head + d) and mask
        count -= d
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var newCapacity = buffer.size
        while (newCapacity < required) newCapacity = newCapacity shl 1
        val resized = ShortArray(newCapacity)
        val first = minOf(count, buffer.size - head)
        buffer.copyInto(resized, 0, head, head + first)
        if (first < count) buffer.copyInto(resized, first, 0, count - first)
        buffer = resized
        mask = newCapacity - 1
        head = 0
    }

    private companion object {
        fun nextPowerOfTwo(v: Int): Int {
            var c = 1
            while (c < v) c = c shl 1
            return c
        }
    }
}
