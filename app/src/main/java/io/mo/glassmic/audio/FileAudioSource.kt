package io.mo.glassmic.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import io.mo.glassmic.core.model.AudioClip
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.core.util.Pcm16
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes an imported audio file to signed 16-bit little-endian PCM and converts it to the
 * requested sample rate/channel layout. The conversion is intentionally small and dependency-free:
 * linear-interpolation resampling plus channel mix/copy. That is enough to keep voice-recording
 * clients such as WeChat on the correct clock even when they request 16 kHz while the imported
 * file is 44.1/48 kHz.
 */
class FileAudioSource(
    private val clip: AudioClip,
    private val filePath: String
) : AudioSourceProvider {

    override val type = SourceType.FILE

    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val mutex = Mutex()
    private val info = MediaCodec.BufferInfo()

    private var inputDone = false
    private var outputDone = false
    private var positionUs = 0L

    private var sourceSampleRate = clip.sampleRate.takeIf { it > 0 } ?: 48_000
    private var sourceChannels = clip.channels.takeIf { it > 0 } ?: 1
    private var pcmEncoding = PCM_ENCODING_16BIT

    private var pendingSamples = ShortArray(0)
    private var pendingFrameOffset = 0
    private var pendingFrameCount = 0

    // 线性插值重采样：curFrame/nextFrame 为相邻两个源帧，frac 是它们之间的相位 [0,1)。
    private var curFrame: ShortArray? = null
    private var nextFrame: ShortArray? = null
    private var frac = 0.0
    private var resampleScratch = ShortArray(sourceChannels)
    private var lastTargetSampleRate = 0
    private var lastTargetChannels = 0
    private var generatedTargetFrames = 0L

    init {
        extractor.setDataSource(filePath)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No decodable audio track in ${clip.fileName}")

        extractor.selectTrack(trackIndex)
        val fmt = extractor.getTrackFormat(trackIndex)
        sourceSampleRate = fmt.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sourceSampleRate)
        sourceChannels = fmt.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, sourceChannels)

        codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
    }

    override suspend fun read(out: ByteBuffer, sampleRate: Int, channels: Int): Int = mutex.withLock {
        val targetSampleRate = sampleRate.takeIf { it > 0 } ?: 48_000
        val targetChannels = channels.takeIf { it > 0 } ?: 1
        val bytesPerTargetFrame = targetChannels * BYTES_PER_SAMPLE
        if (out.remaining() < bytesPerTargetFrame) return 0

        if (targetSampleRate != lastTargetSampleRate || targetChannels != lastTargetChannels) {
            resetResampler(targetSampleRate, targetChannels)
        }

        var written = 0
        while (out.remaining() >= bytesPerTargetFrame) {
            val frame = nextResampledFrame(targetSampleRate) ?: break
            writeFrame(out, frame, targetChannels)
            written += bytesPerTargetFrame
            generatedTargetFrames++
        }

        if (written > 0) {
            positionUs = generatedTargetFrames * 1_000_000L / targetSampleRate
            written
        } else if (outputDone && curFrame == null && pendingFrameOffset >= pendingFrameCount) {
            -1
        } else {
            0
        }
    }

    private fun nextResampledFrame(targetSampleRate: Int): ShortArray? {
        // 左端点。整个流首次无数据时返回 null（read() 会回 0 等待解码）。
        if (curFrame == null) {
            curFrame = readSourceFrame() ?: return null
            frac = 0.0
            nextFrame = null
        }

        // 先把相位归一化到 [0,1) 再插值——绝不能带着 frac >= 1.0 去 lerp，否则会外插出越界样本。
        // 推进沿 left <- right 移位消费源帧，保证不跳帧。
        while (frac >= 1.0) {
            if (nextFrame == null) nextFrame = readSourceFrame()
            val successor = nextFrame
            if (successor == null) {
                // 需要推进却取不到后继帧。
                if (outputDone) { curFrame = null; return null } // 流已结束
                return lerpFrame(curFrame!!, curFrame!!, 0.0)     // 解码暂时欠载：零阶保持，保留相位待下次追上
            }
            curFrame = successor
            nextFrame = readSourceFrame()
            frac -= 1.0
        }

        val left = curFrame!!
        if (nextFrame == null) nextFrame = readSourceFrame()
        val right = nextFrame
        if (right == null) {
            // 无右端点：文件末尾吐出末帧一次后结束；否则解码欠载，零阶保持避免消费端断流。
            if (outputDone) curFrame = null
            return lerpFrame(left, left, 0.0)
        }

        val out = lerpFrame(left, right, frac)
        frac += sourceSampleRate.toDouble() / targetSampleRate.toDouble()
        return out
    }

    /** 在两个源帧之间按相位 [t] (0..1) 线性插值，写入可复用的 scratch 后返回。 */
    private fun lerpFrame(a: ShortArray, b: ShortArray, t: Double): ShortArray {
        if (resampleScratch.size != sourceChannels) resampleScratch = ShortArray(sourceChannels)
        val scratch = resampleScratch
        for (i in 0 until sourceChannels) {
            scratch[i] = Pcm16.lerp(a.getOrElse(i) { 0 }.toInt(), b.getOrElse(i) { 0 }.toInt(), t)
        }
        return scratch
    }

    private fun readSourceFrame(): ShortArray? {
        while (pendingFrameOffset >= pendingFrameCount) {
            if (!loadNextDecodedBuffer()) return null
        }

        val frame = ShortArray(sourceChannels)
        val sampleOffset = pendingFrameOffset * sourceChannels
        for (i in 0 until sourceChannels) {
            frame[i] = pendingSamples.getOrElse(sampleOffset + i) { 0 }
        }
        pendingFrameOffset++
        return frame
    }

    private fun loadNextDecodedBuffer(): Boolean {
        pendingSamples = ShortArray(0)
        pendingFrameOffset = 0
        pendingFrameCount = 0

        while (!outputDone) {
            if (!inputDone) feedInput()

            when (val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = codec.outputFormat
                    sourceSampleRate = fmt.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sourceSampleRate)
                    sourceChannels = fmt.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, sourceChannels)
                    pcmEncoding = fmt.getIntegerOrDefault("pcm-encoding", PCM_ENCODING_16BIT)
                    // 声道数可能随格式变化，只重建 scratch。不要在此清空 curFrame/nextFrame：
                    // 本函数是从 nextResampledFrame 深层调用的，置空会与调用点的重新赋值竞态、
                    // 导致帧序颠倒甚至 NPE。旧维度的残留帧会在相位推进中被新帧自然替换，
                    // lerpFrame 的 getOrElse 兜底可安全跨越这一两帧的声道数过渡。
                    resampleScratch = ShortArray(sourceChannels)
                }
                else -> {
                    if (outIdx < 0) return false
                    val decoded = codec.getOutputBuffer(outIdx)
                    val hasData = decoded != null && info.size > 0
                    if (hasData) {
                        decoded!!.position(info.offset)
                        decoded.limit(info.offset + info.size)
                        pendingSamples = decodePcm16(decoded)
                        pendingFrameCount = pendingSamples.size / sourceChannels
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (pendingFrameCount > 0) return true
                }
            }
        }
        return false
    }

    private fun feedInput() {
        val idx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (idx < 0) return
        val input = codec.getInputBuffer(idx) ?: return
        input.clear()
        val sampleSize = extractor.readSampleData(input, 0)
        if (sampleSize < 0) {
            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(idx, 0, sampleSize, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun decodePcm16(buffer: ByteBuffer): ShortArray {
        val duplicate = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        return when (pcmEncoding) {
            PCM_ENCODING_FLOAT -> {
                val out = ShortArray(duplicate.remaining() / 4)
                var i = 0
                while (duplicate.remaining() >= 4) {
                    val v = duplicate.float.coerceIn(-1f, 1f)
                    out[i++] = (v * Short.MAX_VALUE).toInt().toShort()
                }
                out
            }
            else -> {
                val out = ShortArray(duplicate.remaining() / 2)
                var i = 0
                while (duplicate.remaining() >= 2) {
                    out[i++] = duplicate.short
                }
                out
            }
        }
    }

    private fun writeFrame(out: ByteBuffer, src: ShortArray, targetChannels: Int) {
        for (channel in 0 until targetChannels) {
            val sample = when {
                sourceChannels == 1 -> src[0]
                targetChannels == 1 -> (((src.getOrElse(0) { 0 }.toInt()) + src.getOrElse(1) { 0 }.toInt()) / 2).toShort()
                channel < sourceChannels -> src[channel]
                else -> 0
            }
            out.put((sample.toInt() and 0xFF).toByte())
            out.put(((sample.toInt() ushr 8) and 0xFF).toByte())
        }
    }

    private fun resetResampler(targetSampleRate: Int, targetChannels: Int) {
        lastTargetSampleRate = targetSampleRate
        lastTargetChannels = targetChannels
        frac = 0.0
        curFrame = null
        nextFrame = null
        generatedTargetFrames = positionUs * targetSampleRate / 1_000_000L
    }

    override fun positionMs(): Long = positionUs / 1000
    override fun durationMs(): Long = clip.durationMs

    override fun reset() {
        runCatching {
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            codec.flush()
            inputDone = false
            outputDone = false
            positionUs = 0L
            pendingSamples = ShortArray(0)
            pendingFrameOffset = 0
            pendingFrameCount = 0
            frac = 0.0
            curFrame = null
            nextFrame = null
            generatedTargetFrames = 0L
        }.onFailure { GlassLog.b("FileAudio") { "reset failed: ${it.message}" } }
    }

    suspend fun seekTo(positionMs: Long) = mutex.withLock {
        runCatching {
            val safe = positionMs.coerceAtLeast(0L).coerceAtMost(clip.durationMs)
            extractor.seekTo(safe * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            codec.flush()
            inputDone = false
            outputDone = false
            positionUs = safe * 1000
            pendingSamples = ShortArray(0)
            pendingFrameOffset = 0
            pendingFrameCount = 0
            frac = 0.0
            curFrame = null
            nextFrame = null
            generatedTargetFrames = positionUs * (lastTargetSampleRate.takeIf { it > 0 } ?: 48_000) / 1_000_000L
        }.onFailure { GlassLog.b("FileAudio") { "seekTo failed: ${it.message}" } }
    }

    override fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val PCM_ENCODING_16BIT = 2
        const val PCM_ENCODING_FLOAT = 4
    }
}

private fun MediaFormat.getIntegerOrDefault(key: String, defaultValue: Int): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(defaultValue) else defaultValue
