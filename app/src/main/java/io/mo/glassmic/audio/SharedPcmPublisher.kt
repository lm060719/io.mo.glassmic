package io.mo.glassmic.audio

import android.content.Context
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.model.PlaybackPolicy
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.service.SafeModeWatchdog
import io.mo.glassmic.proto.PlaybackPolicy as ProtoPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * PCM 数据广播器。
 *
 * 维护一个"当前音源"——可能是 FileAudioSource / SilenceSource。
 * 多个目标 App 进程通过 PcmStreamProvider 拿到不同的 pipe，
 * Publisher 把同一份 PCM 流广播给所有 consumer，共享播放进度（需求 §10.6）。
 *
 * 一个解码线程 + N 个独立写出协程，避免单个慢 consumer 阻塞全局广播。
 */
@Singleton
class SharedPcmPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtime: RuntimeStateHolder,
    private val configStore: ConfigStore,
    private val watchdog: SafeModeWatchdog
) {

    private data class Consumer(
        val id: String,
        val pkg: String,
        val sampleRate: Int,
        val channels: Int,
        val fd: ParcelFileDescriptor,
        val out: FileOutputStream,
        val queue: Channel<ByteArray>,
        val converter: Pcm16Converter
    )

    private data class AudioEffects(
        val noiseSim: Boolean = false,
        val highGain: Boolean = false,
        val limiterEnabled: Boolean = true,
        val reverb: Boolean = false,
        val reverbAmount: Float = 0f,
        val speed: Boolean = false,
        val speedFactor: Float = 1f
    )

    private val consumers = ConcurrentHashMap<String, Consumer>()
    private val consumerSeq = AtomicLong(0L)
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private companion object {
        // 当前所有路径都按 PCM_16 处理；后续如果接入 PCM_FLOAT / PCM_8 需要参数化
        const val BYTES_PER_SAMPLE = 2
        const val MASTER_SAMPLE_RATE = 48_000
        const val MASTER_CHANNELS = 1
        const val NOISE_SIM_AMPLITUDE = 6_000
        const val HIGH_GAIN_MULTIPLIER = 1.8f
        const val REVERB_DELAY_SAMPLES = 2_880   // 60ms @48k 单声道
    }

    @Volatile private var currentSource: AudioSourceProvider = SilenceSource
    @Volatile private var writerStarted = false
    @Volatile private var paused: Boolean = false
    @Volatile private var effects = AudioEffects()
    // 仅在广播协程单线程访问，无需同步
    private val reverbLine = ReverbLine(REVERB_DELAY_SAMPLES)

    init {
        scope.launch {
            configStore.flow.collect { cfg ->
                val exp = cfg.experimental
                val speedRaw = if (exp.speedFactor <= 0f) 1f else exp.speedFactor.coerceIn(0.5f, 2f)
                effects = AudioEffects(
                    noiseSim = exp.unlocked && exp.noiseSim,
                    highGain = exp.unlocked && exp.highGain,
                    limiterEnabled = exp.limiterEnabled,
                    reverb = exp.unlocked && exp.reverbEnabled,
                    reverbAmount = exp.reverbAmount.coerceIn(0f, 1f),
                    speed = exp.unlocked && exp.speedEnabled && speedRaw != 1f,
                    speedFactor = speedRaw
                )
            }
        }
    }

    val isPaused: Boolean get() = paused

    /** 暂停只影响"是否从 source 读取数据"，下游仍然收到等量静音，避免 pipe 阻塞/EOF。 */
    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        runtime.setPaused(value)
        GlassLog.b("Publisher") { "paused=$value" }
    }

    /** 把当前 FileAudioSource 跳转到指定毫秒。其他类型源忽略。 */
    suspend fun seekCurrent(positionMs: Long) {
        val src = currentSource
        if (src is FileAudioSource) src.seekTo(positionMs)
        runtime.setPosition(positionMs)
    }

    /** Xposed 进程通过 ContentProvider 调到这里 */
    fun attachConsumer(
        consumerPackage: String,
        sampleRate: Int,
        channels: Int,
        writeFd: ParcelFileDescriptor
    ) {
        val id = buildConsumerId(consumerPackage)
        val fos = FileOutputStream(writeFd.fileDescriptor)
        val queue = Channel<ByteArray>(
            capacity = 3,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val safeSampleRate = sampleRate.coerceAtLeast(8_000)
        val safeChannels = channels.coerceAtLeast(1)
        val consumer = Consumer(
            id = id,
            pkg = consumerPackage,
            sampleRate = safeSampleRate,
            channels = safeChannels,
            fd = writeFd,
            out = fos,
            queue = queue,
            converter = Pcm16Converter(
                sourceSampleRate = MASTER_SAMPLE_RATE,
                sourceChannels = MASTER_CHANNELS,
                targetSampleRate = safeSampleRate,
                targetChannels = safeChannels
            )
        )
        consumers[id] = consumer
        startConsumerWriter(consumer)
        GlassLog.b("Publisher") { "新 consumer: $id pkg=$consumerPackage sr=$sampleRate ch=$channels" }
        ensureWriterRunning()
    }

    fun setSource(
        src: AudioSourceProvider,
        groupId: String? = null,
        audioId: String? = null
    ) {
        replaceSource(src, updateRuntime = true, groupId = groupId, audioId = audioId)
    }

    private fun replaceSource(
        src: AudioSourceProvider,
        updateRuntime: Boolean,
        groupId: String? = null,
        audioId: String? = null
    ) {
        currentSource.release()
        currentSource = src
        if (updateRuntime) {
            runtime.setSource(
                type = src.type,
                groupId = groupId,
                audioId = audioId,
                durationMs = src.durationMs()
            )
        }
        GlassLog.b("Publisher") {
            "切换音源 → ${src.type}, group=$groupId audio=$audioId, updateRuntime=$updateRuntime"
        }
    }

    private fun ensureWriterRunning() {
        if (writerStarted) return
        synchronized(this) {
            if (writerStarted) return
            writerStarted = true
        }
        scope.launch {
            val frame = ByteBuffer.allocate(4096)
            // 用时间戳维持实时节奏，避免每帧 delay 累计误差
            var nextSendAt = System.currentTimeMillis()
            while (isActive) {
                if (consumers.isEmpty()) {
                    kotlinx.coroutines.delay(50)
                    nextSendAt = System.currentTimeMillis()  // 没消费者时重置基准
                    continue
                }
                frame.clear()
                val sr = MASTER_SAMPLE_RATE
                val ch = MASTER_CHANNELS
                val bytesPerSec = sr.toLong() * ch * BYTES_PER_SAMPLE

                // 暂停 → 读 SilenceSource（不动真实源的位置）；其它情况读当前源
                val readSource = if (paused) SilenceSource else currentSource

                val n = runCatching { readSource.read(frame, sr, ch) }.getOrElse {
                    watchdog.onAudioEngineFailure()
                    GlassLog.b("Publisher") { "read 失败: ${it.message}，降级静音" }
                    setSource(SilenceSource)
                    0
                }
                when {
                    n > 0 -> {
                        frame.flip()
                        if (!paused) runtime.setPosition(readSource.positionMs())
                        // 变速会改变实际广播的字节数——按广播出去的量节流，
                        // 才能让消费端以正常采样率播放时得到正确的变速节奏
                        val outBytes = broadcast(frame)

                        val frameMs = (outBytes.toLong() * 1000L + bytesPerSec - 1) / bytesPerSec
                        nextSendAt += frameMs
                        val now = System.currentTimeMillis()
                        val sleep = nextSendAt - now
                        if (sleep > 0) {
                            kotlinx.coroutines.delay(sleep)
                        } else if (sleep < -200) {
                            // 落后超过 200ms（被 GC / 系统抢占），追平基准，避免突然爆发
                            nextSendAt = now
                        }
                    }
                    n == -1 -> {
                        handleEof()
                        nextSendAt = System.currentTimeMillis()
                    }
                    else -> kotlinx.coroutines.delay(5)
                }
            }
        }
    }

    private suspend fun handleEof() = mutex.withLock {
        val policy = configStore.current().playbackPolicy.toCore()
        when (policy) {
            PlaybackPolicy.LOOP -> currentSource.reset()
            PlaybackPolicy.SILENCE -> setSource(SilenceSource)
            PlaybackPolicy.REAL_MIC -> {
                // 对外状态切回真实麦；关闭现有 pipe，避免继续向无人读取的 fd 写静音。
                runtime.setSource(SourceType.REAL_MIC)
                detachAll()
                replaceSource(SilenceSource, updateRuntime = false)
            }
        }
    }

    /** 返回实际广播出去的 master 字节数（变速后可能与输入不同）。 */
    private fun broadcast(buf: ByteBuffer): Int {
        val data = ByteArray(buf.remaining())
        buf.get(data)
        val sourceData = if (!paused && currentSource.type == SourceType.FILE) {
            applyEffects(data)
        } else {
            data
        }
        consumers.values.forEach { c ->
            val payload = c.converter.convert(sourceData)
            if (payload.isEmpty()) return@forEach
            val result = c.queue.trySend(payload)
            if (result.isFailure) {
                GlassLog.b("Publisher") { "consumer ${c.id} 队列不可用，断开" }
                detach(c.id)
            }
        }
        return sourceData.size
    }

    private fun applyEffects(input: ByteArray): ByteArray {
        val fx = effects
        // 未启用混响时清空延迟线，避免下次开启时残留旧回声
        if (!fx.reverb) reverbLine.reset()

        // 变速（变速变调）：线性重采样，改变样本数量
        val data = if (fx.speed) resample(input, fx.speedFactor) else input

        if (!fx.noiseSim && !fx.highGain && !fx.reverb) return data

        var i = 0
        while (i + 1 < data.size) {
            var mixed = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
            if (fx.highGain) {
                mixed = (mixed * HIGH_GAIN_MULTIPLIER).toInt()
            }
            if (fx.noiseSim) {
                mixed += Random.nextInt(-NOISE_SIM_AMPLITUDE, NOISE_SIM_AMPLITUDE + 1)
            }
            if (fx.reverb) {
                mixed = reverbLine.process(mixed, fx.reverbAmount)
            }
            val clipped = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            data[i] = (clipped and 0xFF).toByte()
            data[i + 1] = ((clipped ushr 8) and 0xFF).toByte()
            i += 2
        }
        return data
    }

    /** PCM16 线性重采样：speed>1 加速（样本变少、音调升高），speed<1 减速。 */
    private fun resample(input: ByteArray, speed: Float): ByteArray {
        val inSamples = input.size / 2
        if (inSamples <= 0) return input
        val outSamples = (inSamples / speed).toInt().coerceAtLeast(1)
        val out = ByteArray(outSamples * 2)
        var j = 0
        while (j < outSamples) {
            val srcPos = j * speed
            val i0 = srcPos.toInt()
            val frac = srcPos - i0
            val s0 = sampleAt(input, i0, inSamples)
            val s1 = sampleAt(input, i0 + 1, inSamples)
            val v = (s0 + (s1 - s0) * frac).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[j * 2] = (v and 0xFF).toByte()
            out[j * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
            j++
        }
        return out
    }

    private fun sampleAt(b: ByteArray, idx: Int, total: Int): Float {
        val i = idx.coerceIn(0, total - 1)
        return (((b[i * 2 + 1].toInt() shl 8) or (b[i * 2].toInt() and 0xFF)).toShort()).toFloat()
    }

    private fun startConsumerWriter(consumer: Consumer) {
        scope.launch {
            try {
                for (data in consumer.queue) {
                    consumer.out.write(data)
                    consumer.out.flush()
                }
            } catch (t: Throwable) {
                GlassLog.b("Publisher") { "consumer ${consumer.id} 写失败: ${t.message}" }
            } finally {
                detach(consumer.id)
            }
        }
    }

    fun detach(id: String) {
        consumers.remove(id)?.let { c ->
            runCatching { c.queue.close() }
            runCatching { c.out.close() }
            runCatching { c.fd.close() }
            GlassLog.b("Publisher") { "consumer 已断开: ${c.id}" }
        }
    }

    private fun detachAll() {
        consumers.keys.toList().forEach { detach(it) }
    }

    private fun buildConsumerId(pkg: String): String =
        "$pkg-${android.os.Process.myPid()}-${consumerSeq.incrementAndGet()}"
}

private fun ProtoPolicy.toCore(): PlaybackPolicy = when (this) {
    ProtoPolicy.SILENCE -> PlaybackPolicy.SILENCE
    ProtoPolicy.LOOP -> PlaybackPolicy.LOOP
    ProtoPolicy.REAL_MIC -> PlaybackPolicy.REAL_MIC
    else -> PlaybackPolicy.LOOP
}

/** 单抽头反馈延迟线，产生简单混响。仅在广播协程单线程访问。 */
private class ReverbLine(size: Int) {
    private val buf = ShortArray(size)
    private var idx = 0

    fun reset() {
        buf.fill(0)
        idx = 0
    }

    /** amount 0..1：控制湿信号比例与反馈量。返回叠加后的样本（未限幅，由调用方裁剪）。 */
    fun process(input: Int, amount: Float): Int {
        val delayed = buf[idx].toInt()
        val out = input + (delayed * amount).toInt()
        val feedback = (input + delayed * amount * 0.6f).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        buf[idx] = feedback.toShort()
        idx++
        if (idx >= buf.size) idx = 0
        return out
    }
}

private class Pcm16Converter(
    private val sourceSampleRate: Int,
    private val sourceChannels: Int,
    private val targetSampleRate: Int,
    private val targetChannels: Int
) {
    private var pending = ShortArray(0)
    private var sourcePosition = 0.0

    fun convert(bytes: ByteArray): ByteArray {
        if (sourceSampleRate == targetSampleRate && sourceChannels == targetChannels) {
            return bytes
        }

        append(bytes)
        if (pending.isEmpty()) return ByteArray(0)

        val step = sourceSampleRate.toDouble() / targetSampleRate.toDouble()
        val frames = ArrayList<ShortArray>(pending.size / sourceChannels)
        while (sourcePosition < frameCount()) {
            val sourceFrame = sourcePosition.toInt()
            frames += mixFrame(sourceFrame)
            sourcePosition += step
        }

        trimConsumedSourceFrames()
        if (frames.isEmpty()) return ByteArray(0)

        val out = ByteArray(frames.size * targetChannels * 2)
        var offset = 0
        frames.forEach { frame ->
            frame.forEach { sample ->
                out[offset++] = (sample.toInt() and 0xFF).toByte()
                out[offset++] = ((sample.toInt() ushr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun append(bytes: ByteArray) {
        val samplesToAdd = bytes.size / 2
        if (samplesToAdd <= 0) return

        val combined = ShortArray(pending.size + samplesToAdd)
        pending.copyInto(combined)
        var src = 0
        var dst = pending.size
        while (src + 1 < bytes.size) {
            val lo = bytes[src].toInt() and 0xFF
            val hi = bytes[src + 1].toInt()
            combined[dst++] = ((hi shl 8) or lo).toShort()
            src += 2
        }
        pending = combined
    }

    private fun frameCount(): Int = pending.size / sourceChannels

    private fun mixFrame(frameIndex: Int): ShortArray {
        val base = frameIndex * sourceChannels
        return ShortArray(targetChannels) { channel ->
            when {
                sourceChannels == 1 -> pending.getOrElse(base) { 0 }
                targetChannels == 1 -> {
                    val left = pending.getOrElse(base) { 0 }.toInt()
                    val right = pending.getOrElse(base + 1) { 0 }.toInt()
                    ((left + right) / 2).toShort()
                }
                channel < sourceChannels -> pending.getOrElse(base + channel) { 0 }
                else -> 0
            }
        }
    }

    private fun trimConsumedSourceFrames() {
        val frames = frameCount()
        val consumed = sourcePosition.toInt().coerceAtMost(frames)
        if (consumed <= 0) return

        val consumedSamples = consumed * sourceChannels
        pending = pending.copyOfRange(consumedSamples, pending.size)
        sourcePosition -= consumed
    }
}
