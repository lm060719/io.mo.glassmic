package io.mo.glassmic.audio

import io.mo.glassmic.audio.tts.PcmSink
import io.mo.glassmic.audio.tts.SpeechSynthesizer
import io.mo.glassmic.audio.tts.TtsRequest
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.log.GlassLog
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * 把语音合成结果当作虚拟麦克风音源。
 *
 * [synthesizer] 异步产出源采样率的 PCM16，本类缓冲后用 [Pcm16Converter] 转换到调用方
 * 要求的采样率/声道（管线固定 48k 单声道），再逐帧写出。合成结束且缓冲排空后返回
 * -1（EOF），交由 SharedPcmPublisher 按播放策略处理（LOOP 会触发 [reset] 重新合成）。
 *
 * 线程：合成回调来自任意线程，读取来自广播协程；仅共享的输入队列/格式加锁保护。
 */
class TtsAudioSource(
    private val synthesizer: SpeechSynthesizer,
    private val request: TtsRequest
) : AudioSourceProvider {

    override val type = SourceType.TTS

    private val lock = Any()
    private val srcQueue = ArrayDeque<ByteArray>()   // 源格式 PCM16，锁保护
    @Volatile private var srcSampleRate = 0
    @Volatile private var srcChannels = 0
    @Volatile private var done = false
    @Volatile private var errored = false
    // 延迟到首次 read 才启动合成：确保上一个音源已被 release（其 cancel 不会误清本源回调）。
    @Volatile private var started = false

    // 仅广播协程访问
    private var converter: Pcm16Converter? = null
    private var convSrcSr = 0
    private var convSrcCh = 0
    private var convTgtSr = 0
    private var convTgtCh = 0
    private var outPending = ByteArray(0)
    private var outOffset = 0
    private var generatedTargetFrames = 0L

    private val sink = object : PcmSink {
        override fun onFormat(sampleRate: Int, channels: Int) {
            srcSampleRate = sampleRate.coerceAtLeast(8_000)
            srcChannels = channels.coerceAtLeast(1)
        }

        override fun onPcm(chunk: ByteArray) {
            if (chunk.isEmpty()) return
            synchronized(lock) { srcQueue.addLast(chunk) }
        }

        override fun onDone() {
            done = true
            GlassLog.b("Tts") { "TTS 合成完成" }
        }

        override fun onError(message: String) {
            errored = true
            done = true
            GlassLog.b("Tts") { "TTS 合成出错: $message" }
        }
    }

    override suspend fun read(out: ByteBuffer, sampleRate: Int, channels: Int): Int {
        if (!started) {
            started = true
            synthesizer.synthesize(request, sink)
        }
        val targetSr = sampleRate.takeIf { it > 0 } ?: 48_000
        val targetCh = channels.takeIf { it > 0 } ?: 1
        val bytesPerFrame = targetCh * 2
        if (out.remaining() < bytesPerFrame) return 0

        // 尚未收到格式（合成还没开始产出）
        val sr = srcSampleRate
        val ch = srcChannels
        if (sr == 0 || ch == 0) {
            return if (isFullyDrained()) -1 else 0
        }

        ensureConverter(sr, ch, targetSr, targetCh)
        drainSourceIntoOutput()

        var written = 0
        while (out.remaining() >= bytesPerFrame && outOffset + bytesPerFrame <= outPending.size) {
            out.put(outPending, outOffset, bytesPerFrame)
            outOffset += bytesPerFrame
            written += bytesPerFrame
            generatedTargetFrames++
        }
        compactOutput()

        return when {
            written > 0 -> written
            isFullyDrained() -> -1
            else -> 0
        }
    }

    private fun ensureConverter(srcSr: Int, srcCh: Int, tgtSr: Int, tgtCh: Int) {
        if (converter != null && srcSr == convSrcSr && srcCh == convSrcCh &&
            tgtSr == convTgtSr && tgtCh == convTgtCh
        ) return
        converter = Pcm16Converter(srcSr, srcCh, tgtSr, tgtCh)
        convSrcSr = srcSr; convSrcCh = srcCh; convTgtSr = tgtSr; convTgtCh = tgtCh
    }

    private fun drainSourceIntoOutput() {
        val conv = converter ?: return
        val chunks = synchronized(lock) {
            if (srcQueue.isEmpty()) return
            ArrayList<ByteArray>(srcQueue.size).also { list ->
                while (srcQueue.isNotEmpty()) list.add(srcQueue.pollFirst()!!)
            }
        }
        val converted = ArrayList<ByteArray>(chunks.size)
        var extra = 0
        chunks.forEach { c ->
            val bytes = conv.convert(c)
            if (bytes.isNotEmpty()) { converted.add(bytes); extra += bytes.size }
        }
        if (extra == 0) return

        val remaining = outPending.size - outOffset
        val merged = ByteArray(remaining + extra)
        System.arraycopy(outPending, outOffset, merged, 0, remaining)
        var pos = remaining
        converted.forEach { b -> System.arraycopy(b, 0, merged, pos, b.size); pos += b.size }
        outPending = merged
        outOffset = 0
    }

    private fun compactOutput() {
        if (outOffset > 0 && outOffset == outPending.size) {
            outPending = ByteArray(0)
            outOffset = 0
        }
    }

    private fun isFullyDrained(): Boolean =
        done && outOffset >= outPending.size && synchronized(lock) { srcQueue.isEmpty() }

    override fun positionMs(): Long {
        val sr = convTgtSr.takeIf { it > 0 } ?: 48_000
        return generatedTargetFrames * 1000L / sr
    }

    override fun durationMs(): Long = 0L

    /** LOOP 策略下重播：清空缓冲并在下次 read 时重新合成同一段文字。 */
    override fun reset() {
        synchronized(lock) { srcQueue.clear() }
        outPending = ByteArray(0)
        outOffset = 0
        converter = null
        convSrcSr = 0; convSrcCh = 0; convTgtSr = 0; convTgtCh = 0
        srcSampleRate = 0
        srcChannels = 0
        generatedTargetFrames = 0L
        done = false
        errored = false
        started = false
    }

    override fun release() {
        runCatching { synthesizer.cancel() }
        synchronized(lock) { srcQueue.clear() }
        outPending = ByteArray(0)
        outOffset = 0
    }
}
