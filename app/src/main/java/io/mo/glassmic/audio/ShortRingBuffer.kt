package io.mo.glassmic.audio

/**
 * 单线程使用的可增长 16-bit 环形缓冲区。
 *
 * 音频重采样时需要把「已到达但尚未消费」的源样本暂存起来，跨多次 convert 调用保留。
 * 早期实现用 ShortArray 每次追加/裁剪都整体复制，是 O(n²) 的分配模式，在广播热路径上
 * 会持续制造 GC 压力。环形缓冲把追加和消费都摊到 O(1)，仅在容量不足时才扩容。
 *
 * 非线程安全：调用方需保证串行访问（Publisher 广播协程 / 解码线程各自单线程）。
 */
internal class ShortRingBuffer(initialCapacity: Int = 4096) {

    private var buffer = ShortArray(initialCapacity.coerceAtLeast(1))
    private var head = 0
    private var count = 0

    /** 当前可读样本数。 */
    val size: Int get() = count

    fun clear() {
        head = 0
        count = 0
    }

    /** 读取相对 head 偏移 [index] 处的样本；调用方需保证 0 <= index < size。 */
    operator fun get(index: Int): Short = buffer[(head + index) % buffer.size]

    /** 从 [src] 的 [from] 起追加 [len] 个样本。 */
    fun append(src: ShortArray, from: Int = 0, len: Int = src.size - from) {
        if (len <= 0) return
        ensureCapacity(count + len)
        var tail = (head + count) % buffer.size
        for (i in 0 until len) {
            buffer[tail] = src[from + i]
            tail++
            if (tail == buffer.size) tail = 0
        }
        count += len
    }

    /** 丢弃最早的 [n] 个样本（自动裁剪到不超过当前大小）。 */
    fun discard(n: Int) {
        val d = n.coerceIn(0, count)
        head = (head + d) % buffer.size
        count -= d
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var newCapacity = buffer.size
        while (newCapacity < required) newCapacity = newCapacity shl 1
        val resized = ShortArray(newCapacity)
        for (i in 0 until count) resized[i] = buffer[(head + i) % buffer.size]
        buffer = resized
        head = 0
    }
}
