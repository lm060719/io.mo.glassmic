package io.mo.glassmic.data.runtime

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 滑动窗口计数器——用于"30 秒内 SystemUI 重启 2 次"这类判断。
 */
class SlidingCounter(private val windowMs: Long) {

    private val timestamps = ConcurrentLinkedDeque<Long>()

    fun inc() {
        val now = System.currentTimeMillis()
        timestamps.addLast(now)
        trim(now)
    }

    fun count(): Int {
        trim(System.currentTimeMillis())
        return timestamps.size
    }

    fun reset() {
        timestamps.clear()
    }

    private fun trim(now: Long) {
        val threshold = now - windowMs
        while (true) {
            val head = timestamps.peekFirst() ?: return
            if (head < threshold) timestamps.pollFirst() else return
        }
    }
}
