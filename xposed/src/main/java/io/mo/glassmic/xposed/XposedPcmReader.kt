package io.mo.glassmic.xposed

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.os.SystemClock
import io.mo.glassmic.core.Constants
import java.io.FileInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * 在目标 App 进程内打开 PcmStreamProvider 的 pipe 读端，
 * 持续从中读取 PCM 字节填充到 AudioRecord 的输出 buffer。
 */
class XposedPcmReader(
    private val context: Context,
    private val sampleRate: Int,
    private val channels: Int
) {

    private data class OpenStream(
        val pfd: ParcelFileDescriptor,
        val input: InputStream
    )

    private val streamRef = AtomicReference<OpenStream?>()
    private val tempBuf = ByteArray(4096)
    private val metrics = PcmReadMetrics()
    private var lastMetricsReport = SystemClock.elapsedRealtime()
    @Volatile private var retryAtMs = 0L
    private var readFailurePending = false

    private fun recordRead(wanted: Int, actual: Int, failed: Boolean = false) {
        if (wanted <= 0) return
        if (!failed && actual > 0 && readFailurePending) {
            readFailurePending = false
            XBridge.flushProviderFailures(context, context.packageName, "pcm.read")
        }
        val reads = metrics.read(wanted, actual, failed)
        if (reads >= 50 || SystemClock.elapsedRealtime() - lastMetricsReport >= 1500L) reportMetrics()
    }

    private fun reportMetrics() {
        val snapshot = metrics.drain() ?: return
        lastMetricsReport = SystemClock.elapsedRealtime()
        XBridge.reportPcmReadStats(context, Bundle().apply {
            putInt("pid", android.os.Process.myPid())
            putInt("reader_id", System.identityHashCode(this@XposedPcmReader))
            putInt("sample_rate", sampleRate)
            putInt("channels", channels)
            putLong("reads", snapshot.reads)
            putLong("requested_pcm16_bytes", snapshot.requested)
            putLong("source_pcm16_bytes", snapshot.source)
            putLong("zero_fill_pcm16_bytes", snapshot.zeroFill)
            putLong("short_reads", snapshot.shortReads)
            putLong("errors", snapshot.errors)
            putDouble("rms_pcm16", snapshot.rms)
            putInt("peak_pcm16", snapshot.peak)
        })
    }

    fun read(out: ByteBuffer, size: Int): Int {
        val stream = ensureStream()?.input ?: run { recordRead(size, 0, failed = true); return -1 }
        var totalWritten = 0
        var remaining = size
        try {
            while (remaining > 0) {
                val want = minOf(remaining, tempBuf.size)
                val n = stream.read(tempBuf, 0, want)
                if (n <= 0) { closeStream(); break }
                out.put(tempBuf, 0, n)
                metrics.samples(tempBuf, 0, n)
                totalWritten += n
                remaining -= n
            }
        } catch (e: InterruptedIOException) {
            return onInterrupted(size, totalWritten)
        } catch (t: Throwable) {
            readFailurePending = true
            XBridge.reportProviderFailure(context.packageName, "pcm.read", t.javaClass.simpleName)
            retryAtMs = SystemClock.elapsedRealtime() + 2_000L
            closeStream()
            recordRead(size, totalWritten, failed = true)
            return -1
        }
        recordRead(size, totalWritten)
        return totalWritten
    }

    /**
     * 目标 App 停止录音时常会中断自己的读取线程。这不是 Provider 故障：pipe 仍然有效，
     * 不上报、不关流（关流会让下一次 read 重开一个多余的 consumer），也不返回 -1
     * （-1 会让 Hook 放行到真实麦克风）。恢复中断标志，交还已读到的部分，其余由调用方补零。
     */
    private fun onInterrupted(size: Int, done: Int): Int {
        Thread.currentThread().interrupt()
        recordRead(size, done)
        return done
    }

    fun read(out: ByteArray, offset: Int, size: Int): Int {
        val stream = ensureStream()?.input ?: run { recordRead(size, 0, failed = true); return -1 }
        var totalRead = 0
        return try {
            while (totalRead < size) {
                val n = stream.read(out, offset + totalRead, size - totalRead)
                if (n <= 0) { closeStream(); break }
                metrics.samples(out, offset + totalRead, n)
                totalRead += n
            }
            recordRead(size, totalRead)
            totalRead
        } catch (e: InterruptedIOException) {
            onInterrupted(size, totalRead)
        } catch (t: Throwable) {
            readFailurePending = true
            XBridge.reportProviderFailure(context.packageName, "pcm.read", t.javaClass.simpleName)
            retryAtMs = SystemClock.elapsedRealtime() + 2_000L
            closeStream()
            recordRead(size, totalRead, failed = true)
            -1
        }
    }

    fun readFloatBytes(out: ByteArray, offset: Int, size: Int): Int {
        val sampleCount = size / 4
        if (sampleCount <= 0) return 0

        val pcm16 = ByteArray(sampleCount * 2)
        val n = read(pcm16, 0, pcm16.size)
        if (n < 0) return -1

        val src = ByteBuffer.wrap(pcm16, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val dst = ByteBuffer.wrap(out, offset, size).order(ByteOrder.LITTLE_ENDIAN)
        val floats = n / 2
        repeat(floats) {
            dst.putFloat(src.get(it) / 32768f)
        }
        return floats * 4
    }

    fun readFloatBuffer(out: ByteBuffer, size: Int): Int {
        val sampleCount = size / 4
        if (sampleCount <= 0) return 0

        val pcm16 = ByteArray(sampleCount * 2)
        val n = read(pcm16, 0, pcm16.size)
        if (n < 0) return -1

        val src = ByteBuffer.wrap(pcm16, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        out.order(ByteOrder.LITTLE_ENDIAN)
        val floats = n / 2
        repeat(floats) {
            out.putFloat(src.get(it) / 32768f)
        }
        return floats * 4
    }

    private fun ensureStream(): OpenStream? {
        streamRef.get()?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (now < retryAtMs) return null
        val uri = Uri.parse(
            "content://${Constants.PROVIDER_PCM}/stream?sr=$sampleRate&ch=$channels"
        )
        var openFailure = "NO_FILE_DESCRIPTOR"
        val pfd = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")
        }.onFailure {
            openFailure = it.javaClass.simpleName
        }.getOrNull() ?: run {
            XBridge.reportProviderFailure(context.packageName, "pcm.open", openFailure)
            retryAtMs = now + 2_000L
            return null
        }
        retryAtMs = 0L
        XBridge.flushProviderFailures(context, context.packageName, "pcm.open")
        val open = OpenStream(pfd, FileInputStream(pfd.fileDescriptor))
        if (streamRef.compareAndSet(null, open)) return open
        runCatching { open.input.close() }
        runCatching { open.pfd.close() }
        return streamRef.get()
    }

    fun release() {
        closeStream()
        reportMetrics()
    }

    private fun closeStream() {
        streamRef.getAndSet(null)?.let {
            runCatching { it.input.close() }
            runCatching { it.pfd.close() }
        }
    }
}
