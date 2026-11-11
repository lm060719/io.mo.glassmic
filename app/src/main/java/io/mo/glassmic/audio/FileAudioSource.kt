package io.mo.glassmic.audio

import android.content.Context
import android.net.Uri
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import io.mo.glassmic.core.model.AudioClip
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.log.GlassLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 将导入的音频文件解码并通过 [Pcm16Converter] 高保真重采样/混音为请求的 PCM 格式。
 *
 * 线程模型：广播线程调用 [read]，切歌/Seek/EOF 处理可能在其他线程调用 [release] /
 * [reset] / [seekTo]。四者共用同一把锁——否则切歌时在解码中途释放 MediaCodec 会让
 * read 抛异常，被广播循环当成音频引擎故障，把刚换上的新音源顶成静音。
 * read 体内没有挂起点，用阻塞锁即可；释放最多等一次 read（几十毫秒）。
 */
class FileAudioSource(
    private val clip: AudioClip,
    context: Context,
    uri: Uri
) : AudioSourceProvider {

    override val type = SourceType.FILE

    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val lock = ReentrantLock()
    private var released = false
    private val info = MediaCodec.BufferInfo()

    private var inputDone = false
    private var outputDone = false
    private var positionUs = 0L

    private var sourceSampleRate = clip.sampleRate.takeIf { it > 0 } ?: 48_000
    private var sourceChannels = clip.channels.takeIf { it > 0 } ?: 1
    private var pcmEncoding = PCM_ENCODING_16BIT

    private var lastTargetSampleRate = 0
    private var lastTargetChannels = 0
    private var generatedTargetFrames = 0L

    private var converter: Pcm16Converter? = null
    private var convertedBuffer = ByteArray(0)
    private var convertedOffset = 0

    init {
        var pendingCodec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No decodable audio track in ${clip.fileName}")

            extractor.selectTrack(trackIndex)
            val fmt = extractor.getTrackFormat(trackIndex)
            sourceSampleRate = fmt.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sourceSampleRate)
            sourceChannels = fmt.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, sourceChannels)

            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            pendingCodec = codec
            codec.configure(fmt, null, null, 0)
            codec.start()
        } catch (failure: Exception) {
            runCatching { pendingCodec?.release() }
            extractor.release()
            throw failure
        }
    }

    override suspend fun read(out: ByteBuffer, sampleRate: Int, channels: Int): Int = lock.withLock {
        // 已被切走并释放：codec 不可再用，返回"暂无数据"，广播循环下一轮会读新音源。
        if (released) return 0
        val targetSampleRate = sampleRate.takeIf { it > 0 } ?: 48_000
        val targetChannels = channels.takeIf { it > 0 } ?: 1
        val bytesPerTargetFrame = targetChannels * BYTES_PER_SAMPLE
        if (out.remaining() < bytesPerTargetFrame) return 0

        if (targetSampleRate != lastTargetSampleRate || targetChannels != lastTargetChannels || converter == null) {
            lastTargetSampleRate = targetSampleRate
            lastTargetChannels = targetChannels
            converter = Pcm16Converter(
                sourceSampleRate = sourceSampleRate,
                sourceChannels = sourceChannels,
                targetSampleRate = targetSampleRate,
                targetChannels = targetChannels
            )
            convertedBuffer = ByteArray(0)
            convertedOffset = 0
            generatedTargetFrames = positionUs * targetSampleRate / 1_000_000L
        }

        var written = 0
        while (out.remaining() >= bytesPerTargetFrame) {
            // 如果缓冲区内还有转换好的数据，先输出
            val availableConverted = convertedBuffer.size - convertedOffset
            if (availableConverted >= bytesPerTargetFrame) {
                val framesToTake = minOf(availableConverted / bytesPerTargetFrame, out.remaining() / bytesPerTargetFrame)
                val bytesToTake = framesToTake * bytesPerTargetFrame
                out.put(convertedBuffer, convertedOffset, bytesToTake)
                convertedOffset += bytesToTake
                written += bytesToTake
                generatedTargetFrames += framesToTake
                continue
            }

            // 缓冲区不足，从解码器读取下一批原始 PCM 数据并转换
            val rawBytes = loadNextDecodedPcm16Bytes()
            if (rawBytes != null && rawBytes.isNotEmpty()) {
                val conv = converter!!.convert(rawBytes)
                if (conv.isNotEmpty()) {
                    if (availableConverted > 0) {
                        val merged = ByteArray(availableConverted + conv.size)
                        System.arraycopy(convertedBuffer, convertedOffset, merged, 0, availableConverted)
                        System.arraycopy(conv, 0, merged, availableConverted, conv.size)
                        convertedBuffer = merged
                        convertedOffset = 0
                    } else {
                        convertedBuffer = conv
                        convertedOffset = 0
                    }
                }
            } else {
                // 没有更多解码数据产生
                break
            }
        }

        if (written > 0) {
            positionUs = generatedTargetFrames * 1_000_000L / targetSampleRate
            written
        } else if (outputDone && (convertedBuffer.size - convertedOffset) < bytesPerTargetFrame) {
            -1
        } else {
            0
        }
    }

    private fun loadNextDecodedPcm16Bytes(): ByteArray? {
        while (!outputDone) {
            if (!inputDone) feedInput()

            when (val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return null
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = codec.outputFormat
                    val newSr = fmt.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sourceSampleRate)
                    val newCh = fmt.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, sourceChannels)
                    pcmEncoding = fmt.getIntegerOrDefault("pcm-encoding", PCM_ENCODING_16BIT)
                    if (newSr != sourceSampleRate || newCh != sourceChannels) {
                        sourceSampleRate = newSr
                        sourceChannels = newCh
                        converter = Pcm16Converter(
                            sourceSampleRate = sourceSampleRate,
                            sourceChannels = sourceChannels,
                            targetSampleRate = lastTargetSampleRate.takeIf { it > 0 } ?: 48_000,
                            targetChannels = lastTargetChannels.takeIf { it > 0 } ?: 1
                        )
                    }
                }
                else -> {
                    if (outIdx < 0) return null
                    val decoded = codec.getOutputBuffer(outIdx)
                    var pcmBytes: ByteArray? = null
                    if (decoded != null && info.size > 0) {
                        decoded.position(info.offset)
                        decoded.limit(info.offset + info.size)
                        pcmBytes = decodeToPcm16Bytes(decoded)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (pcmBytes != null && pcmBytes.isNotEmpty()) {
                        return pcmBytes
                    }
                }
            }
        }
        return null
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

    private fun decodeToPcm16Bytes(buffer: ByteBuffer): ByteArray {
        val duplicate = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        return when (pcmEncoding) {
            PCM_ENCODING_FLOAT -> {
                val sampleCount = duplicate.remaining() / 4
                val out = ByteArray(sampleCount * 2)
                var outIdx = 0
                while (duplicate.remaining() >= 4) {
                    val v = duplicate.float
                    val limited = Pcm16Converter.softLimit(v * 32767f)
                    val s = limited.toInt().toShort()
                    out[outIdx++] = (s.toInt() and 0xFF).toByte()
                    out[outIdx++] = ((s.toInt() ushr 8) and 0xFF).toByte()
                }
                out
            }
            else -> {
                val out = ByteArray(duplicate.remaining())
                duplicate.get(out)
                out
            }
        }
    }

    override fun positionMs(): Long = positionUs / 1000
    override fun durationMs(): Long = clip.durationMs

    override fun reset() {
        lock.withLock { resetLocked() }
    }

    private fun resetLocked() {
        if (released) return
        runCatching {
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            codec.flush()
            inputDone = false
            outputDone = false
            positionUs = 0L
            converter?.reset()
            convertedBuffer = ByteArray(0)
            convertedOffset = 0
            generatedTargetFrames = 0L
        }.onFailure { GlassLog.b("FileAudio") { "reset failed: ${it.message}" } }
    }

    suspend fun seekTo(positionMs: Long) {
        lock.withLock { seekLocked(positionMs) }
    }

    private fun seekLocked(positionMs: Long) {
        if (released) return
        runCatching {
            val safe = positionMs.coerceAtLeast(0L).coerceAtMost(clip.durationMs)
            extractor.seekTo(safe * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            codec.flush()
            inputDone = false
            outputDone = false
            positionUs = safe * 1000
            converter?.reset()
            convertedBuffer = ByteArray(0)
            convertedOffset = 0
            generatedTargetFrames = positionUs * (lastTargetSampleRate.takeIf { it > 0 } ?: 48_000) / 1_000_000L
        }.onFailure { GlassLog.b("FileAudio") { "seekTo failed: ${it.message}" } }
    }

    override fun release() {
        lock.withLock {
            if (released) return
            released = true
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
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

