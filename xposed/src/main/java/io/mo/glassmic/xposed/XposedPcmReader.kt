package io.mo.glassmic.xposed

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.mo.glassmic.core.Constants
import java.io.FileInputStream
import java.io.InputStream
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

    fun read(out: ByteBuffer, size: Int): Int {
        val stream = ensureStream()?.input ?: return -1
        var totalWritten = 0
        var remaining = size
        try {
            while (remaining > 0) {
                val want = minOf(remaining, tempBuf.size)
                val n = stream.read(tempBuf, 0, want)
                if (n <= 0) break
                out.put(tempBuf, 0, n)
                totalWritten += n
                remaining -= n
            }
        } catch (_: Throwable) {
            closeStream()
            return -1
        }
        return totalWritten
    }

    fun read(out: ByteArray, offset: Int, size: Int): Int {
        val stream = ensureStream()?.input ?: return -1
        return try {
            var totalRead = 0
            while (totalRead < size) {
                val n = stream.read(out, offset + totalRead, size - totalRead)
                if (n <= 0) break
                totalRead += n
            }
            totalRead
        } catch (_: Throwable) {
            closeStream()
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
        val uri = Uri.parse(
            "content://${Constants.PROVIDER_PCM}/stream?sr=$sampleRate&ch=$channels"
        )
        val pfd = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")
        }.getOrNull() ?: return null
        val open = OpenStream(pfd, FileInputStream(pfd.fileDescriptor))
        if (streamRef.compareAndSet(null, open)) return open
        runCatching { open.input.close() }
        runCatching { open.pfd.close() }
        return streamRef.get()
    }

    fun release() = closeStream()

    private fun closeStream() {
        streamRef.getAndSet(null)?.let {
            runCatching { it.input.close() }
            runCatching { it.pfd.close() }
        }
    }
}
