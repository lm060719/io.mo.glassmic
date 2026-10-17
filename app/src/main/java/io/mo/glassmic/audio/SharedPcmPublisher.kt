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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
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
    private val watchdog: SafeModeWatchdog,
    private val monitorPlayer: AudioMonitorPlayer
) {

    private data class Consumer(
        val id: String,
        val pkg: String,
        val sampleRate: Int,
        val channels: Int,
        val fd: ParcelFileDescriptor,
        val out: FileOutputStream,
        val queue: Channel<ByteArray>,
        val converter: Pcm16Converter,
        val framed: Boolean = false,
        val writtenBytes: AtomicLong = AtomicLong(),
        val droppedBytes: AtomicLong = AtomicLong(),
        val lastWriteMs: AtomicLong = AtomicLong(),
        val pendingDropBytes: AtomicLong = AtomicLong(),
        val lastDropEventMs: AtomicLong = AtomicLong()
    )

    private data class AudioEffects(
        val gain: Float = OutputGain.DEFAULT,
        val noiseSim: Boolean = false,
        val highGain: Boolean = false,
        val limiterEnabled: Boolean = true,
        val reverb: Boolean = false,
        val reverbAmount: Float = 0f,
        val speed: Boolean = false,
        val speedFactor: Float = 1f,
        val band: BandSettings = BandSettings()
    )


    private data class PublisherEvent(
        val time: Long,
        val type: String,
        val consumerId: String? = null,
        val packageName: String? = null,
        val detail: String? = null,
        val valueBytes: Long = 0L
    )

    private val consumers = ConcurrentHashMap<String, Consumer>()
    private val consumerSeq = AtomicLong(0L)
    private val totalDroppedBytes = AtomicLong()
    private val writeFailures = AtomicLong()
    private val integrityAnalyzedSamples = AtomicLong()
    private val integrityExactZeroSamples = AtomicLong()
    private val integrityMaxNearSilentRunSamples = AtomicLong()
    private var integrityCurrentNearSilentRunSamples = 0L
    private val eventLock = Any()
    private val recentEvents = ArrayDeque<PublisherEvent>()
    @Volatile private var lastBroadcastMs = 0L
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private companion object {
        // 当前所有路径都按 PCM_16 处理；后续如果接入 PCM_FLOAT / PCM_8 需要参数化
        const val BYTES_PER_SAMPLE = 2
        const val MASTER_SAMPLE_RATE = 48_000
        const val MASTER_CHANNELS = 1
        const val FRAME_CHUNK_BYTES = 1920 // 20ms @ 48kHz 单声道 PCM16，切片更细更平滑
        const val NOISE_SIM_AMPLITUDE = 6_000
        const val HIGH_GAIN_MULTIPLIER = 1.8f
        const val REVERB_DELAY_SAMPLES = 2_880   // 60ms @48k 单声道
        const val WAVEFORM_POINTS_PER_FRAME = 24 // 每帧下采样出的波形振幅点数
        const val PUBLISHER_EVENT_LIMIT = 160
        const val DROP_EVENT_INTERVAL_MS = 500L
        const val NEAR_SILENCE_RMS_PCM16 = 8.0
    }

    // 实时波形振幅点（0..1）。仅在有订阅者（波形悬浮窗打开）时计算并发送。
    private val _waveform = MutableSharedFlow<FloatArray>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val waveform: SharedFlow<FloatArray> = _waveform.asSharedFlow()

    @Volatile private var currentSource: AudioSourceProvider = SilenceSource
    @Volatile private var writerStarted = false
    @Volatile private var paused: Boolean = false
    @Volatile private var completed: Boolean = false
    @Volatile private var effects = AudioEffects()
    // 仅在广播协程单线程访问，无需同步
    private val reverbLine = ReverbLine(REVERB_DELAY_SAMPLES)
    private val bandFilter = StreamingBandFilter()
    private val effectsEpoch = AtomicLong()
    private val streamEpoch = AtomicLong()
    private var appliedEffectsEpoch = -1L
    private var processedSamples = 0L
    private var overRangeSamples = 0L
    private data class OutputLevels(
        val inputRms: Double = 0.0, val outputRms: Double = 0.0,
        val peak: Int = 0, val samples: Long = 0, val overRange: Long = 0,
        val time: Long = 0
    )
    @Volatile private var outputLevels = OutputLevels()

    init {
        scope.launch {
            configStore.flow.collect { cfg ->
                val exp = cfg.experimental
                val speedRaw = if (exp.speedFactor <= 0f) 1f else exp.speedFactor.coerceIn(0.5f, 2f)
                effects = AudioEffects(
                    gain = OutputGain.of(cfg),
                    noiseSim = exp.unlocked && exp.noiseSim,
                    highGain = exp.unlocked && exp.highGain,
                    limiterEnabled = exp.limiterEnabled,
                    reverb = exp.unlocked && exp.reverbEnabled,
                    reverbAmount = exp.reverbAmount.coerceIn(0f, 1f),
                    speed = exp.unlocked && exp.speedEnabled && speedRaw != 1f,
                    speedFactor = speedRaw,
                    band = BandSettings.normalized(cfg.audioBand.enabled, cfg.audioBand.lowHz, cfg.audioBand.highHz)
                )
                val mon = cfg.audioMonitor
                monitorPlayer.setEnabled(mon.enabled)
                val monVol = if (mon.volume <= 0f) 1.0f else mon.volume.coerceIn(0f, 1f)
                monitorPlayer.setVolume(monVol)
            }
        }
    }

    val isPaused: Boolean get() = paused

    /** 当前是否有注入进程在读 PCM（有 consumer = 有 App 正在"录音"）。 */
    val consumerCount: Int get() = consumers.size

    /** 当前音源是否是常驻内存的 PCM（TTS 生成结果）。内存回收时用来判断该缓冲能不能丢。 */
    val playingBufferedPcm: Boolean get() = currentSource is BufferedPcmSource

    /** 暂停只影响"是否从 source 读取数据"，下游仍然收到等量静音，避免 pipe 阻塞/EOF。 */
    fun setPaused(value: Boolean) = updatePaused(value, flushQueuedAudio = true)

    private fun updatePaused(value: Boolean, flushQueuedAudio: Boolean) {
        if (paused == value) return
        if (!value && completed) {
            // EOF 时音源已回到开头；用户主动播放才更新进度并恢复读取。
            runtime.setPosition(currentSource.positionMs())
            completed = false
        }
        paused = value
        effectsEpoch.incrementAndGet()
        if (value) {
            monitorPlayer.pauseAndFlush()
        }
        if (flushQueuedAudio) flushConsumers()
        runtime.setPaused(value)
        runtime.setStreaming(if (value) false else consumers.isNotEmpty())
        recordEvent(if (value) "playback_paused" else "playback_resumed")
        GlassLog.b("Publisher") { "paused=$value" }
    }

    /** 把当前源跳转到指定毫秒。仅 FileAudioSource / BufferedPcmSource 支持，其他类型忽略。 */
    suspend fun seekCurrent(positionMs: Long) {
        flushConsumers()
        when (val src = currentSource) {
            is FileAudioSource -> src.seekTo(positionMs)
            is BufferedPcmSource -> src.seekTo(positionMs)
            else -> return
        }
        completed = false
        runtime.setPosition(positionMs)
        effectsEpoch.incrementAndGet()
    }

    /** 清空所有 Consumer 队列中的残留数据并重置重采样状态，实现即时切换/暂停/Seek */
    private fun flushConsumers() {
        streamEpoch.incrementAndGet()
        consumers.values.forEach { c ->
            while (c.queue.tryReceive().isSuccess) {}
            c.converter.reset()
        }
    }

    /** Xposed 进程通过 ContentProvider 调到这里 */
    fun attachConsumer(
        consumerPackage: String,
        sampleRate: Int,
        channels: Int,
        writeFd: ParcelFileDescriptor,
        bufferBytes: Int = 32768,
        queueCapacity: Int = 16,
        framed: Boolean = false
    ): String {
        // 设置 Linux 内核 pipe buffer 至 32KB，兼顾低延迟与抗调度抖动
        runCatching {
            android.system.Os.fcntlInt(writeFd.fileDescriptor, 1031 /* F_SETPIPE_SZ */, bufferBytes)
        }
        val id = buildConsumerId(consumerPackage)
        val fos = FileOutputStream(writeFd.fileDescriptor)
        // ~320ms 抗调度抖动；溢出由 broadcast 显式处理并计数，不能静默丢片段。
        val queue = Channel<ByteArray>(capacity = queueCapacity)
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
            framed = framed,
            converter = Pcm16Converter(
                sourceSampleRate = MASTER_SAMPLE_RATE,
                sourceChannels = MASTER_CHANNELS,
                targetSampleRate = safeSampleRate,
                targetChannels = safeChannels
            )
        )
        consumers[id] = consumer
        startConsumerWriter(consumer)
        recordEvent(
            "consumer_attached",
            consumerId = id,
            packageName = consumerPackage,
            detail = "sr=$safeSampleRate ch=$safeChannels"
        )
        GlassLog.b("Publisher") { "新 consumer: $id pkg=$consumerPackage sr=$sampleRate ch=$channels" }
        ensureWriterRunning()
        return id
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
        monitorPlayer.pauseAndFlush()
        flushConsumers()
        currentSource.release()
        currentSource = src
        effectsEpoch.incrementAndGet()
        completed = false
        if (updateRuntime) {
            runtime.setSource(
                type = src.type,
                groupId = groupId,
                audioId = audioId,
                durationMs = src.durationMs()
            )
        }
        integrityAnalyzedSamples.set(0L)
        integrityExactZeroSamples.set(0L)
        integrityMaxNearSilentRunSamples.set(0L)
        integrityCurrentNearSilentRunSamples = 0L
        recordEvent(
            "source_changed",
            detail = "type=${src.type} group=${groupId ?: ""} audio=${audioId ?: ""} runtime=$updateRuntime"
        )
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
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            }
            val frame = ByteBuffer.allocate(FRAME_CHUNK_BYTES)
            // 用纳秒高精度时钟维持实时节奏，预置 80ms 弹性缓冲垫（cushion），杜绝下游管道欠载
            var nextSendAtNanos = System.nanoTime() - 80_000_000L
            while (isActive) {
                if (consumers.isEmpty()) {
                    runtime.setStreaming(false)
                    monitorPlayer.pauseAndFlush()
                    kotlinx.coroutines.delay(50)
                    nextSendAtNanos = System.nanoTime() - 80_000_000L  // 没消费者时重置基准并保持缓冲水位
                    continue
                }
                runtime.setStreaming(!paused)
                frame.clear()
                val sr = MASTER_SAMPLE_RATE
                val ch = MASTER_CHANNELS
                val bytesPerSec = sr.toLong() * ch * BYTES_PER_SAMPLE

                // 暂停 → 读舒适噪声源（不动真实源的位置，但保持下游有本底信号，避免录音中断）；
                // 其它情况读当前源
                val readEffectsEpoch = effectsEpoch.get()
                val readStreamEpoch = streamEpoch.get()
                val readSource = when {
                    paused && completed -> SilenceSource
                    paused -> ComfortNoiseSource
                    else -> currentSource
                }

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
                        val outBytes = broadcast(frame, readEffectsEpoch, readStreamEpoch)

                        val frameNanos = (outBytes.toLong() * 1_000_000_000L + bytesPerSec - 1) / bytesPerSec
                        nextSendAtNanos += frameNanos
                        val nowNanos = System.nanoTime()
                        val sleepNanos = nextSendAtNanos - nowNanos
                        if (sleepNanos > 0) {
                            kotlinx.coroutines.delay(sleepNanos / 1_000_000L)
                        } else if (sleepNanos < -200_000_000L) {
                            // 落后超过 200ms（被 GC / 系统抢占），追平基准，避免突然爆发
                            nextSendAtNanos = nowNanos
                        }
                    }
                    n == -1 -> {
                        monitorPlayer.pauseAndFlush()
                        handleEof(readSource)
                        // 延续时钟，避免 EOF 后突发补发静音挤掉队列中的尾音。
                    }
                    else -> {
                        monitorPlayer.pauseAndFlush()
                        kotlinx.coroutines.delay(2)
                    }
                }
            }
        }
    }

    private suspend fun handleEof(endedSource: AudioSourceProvider) = mutex.withLock {
        val policy = configStore.current().playbackPolicy.toCore()
        // 读取配置期间可能已经换曲，旧音源的 EOF 不能影响新音源。
        if (currentSource !== endedSource) return@withLock
        when (policy) {
            PlaybackPolicy.LOOP -> {
                currentSource.reset()
                effectsEpoch.incrementAndGet()
            }
            PlaybackPolicy.SILENCE -> {
                // 保留选曲与时长，让播放按钮可直接重播；等待期间继续向 pipe 发静音。
                val endPositionMs = endedSource.durationMs().takeIf { it > 0L }
                    ?: endedSource.positionMs()
                endedSource.reset()
                completed = true
                // 正常 EOF 只停止取源；已广播的尾音必须排在后续静音之前送完。
                updatePaused(true, flushQueuedAudio = false)
                runtime.setPosition(endPositionMs)
            }
            PlaybackPolicy.REAL_MIC -> {
                // 对外状态切回真实麦；关闭现有 pipe，避免继续向无人读取的 fd 写静音。
                runtime.setSource(SourceType.REAL_MIC)
                detachAll()
                replaceSource(SilenceSource, updateRuntime = false)
            }
        }
    }

    /** 返回实际广播出去的 master 字节数（变速后可能与输入不同）。 */
    private fun broadcast(buf: ByteBuffer, readEffectsEpoch: Long, readStreamEpoch: Long): Int {
        val data = ByteArray(buf.remaining())
        buf.get(data)
        val sourceData = if (!paused && (currentSource.type == SourceType.FILE || currentSource.type == SourceType.TTS)) {
            applyEffects(data, readEffectsEpoch)
        } else {
            data
        }
        if (!paused && (currentSource.type == SourceType.FILE || currentSource.type == SourceType.TTS)) {
            monitorPlayer.write(sourceData)
        } else {
            monitorPlayer.pauseAndFlush()
        }
        consumers.values.forEach { c ->
            // A seek/source change while decoding must not tag old samples as a new generation.
            if (c.framed && readStreamEpoch != streamEpoch.get()) return@forEach
            val converted = c.converter.convert(sourceData)
            if (converted.isEmpty()) return@forEach
            // AudioPolicy helper needs explicit boundaries to flush on pause/seek/source changes.
            // The epoch travels WITH the frame, so a control message cannot race new PCM.
            val payload = if (c.framed) ByteBuffer.allocate(12 + converted.size)
                .putLong(readStreamEpoch).putInt(converted.size).put(converted).array()
                else converted
            var result = c.queue.trySend(payload)
            if (result.isFailure && !result.isClosed) {
                c.queue.tryReceive().getOrNull()?.let { dropped ->
                    val droppedSize = dropped.size.toLong()
                    c.droppedBytes.addAndGet(droppedSize)
                    totalDroppedBytes.addAndGet(droppedSize)
                    c.pendingDropBytes.addAndGet(droppedSize)
                    val now = System.currentTimeMillis()
                    val last = c.lastDropEventMs.get()
                    if (now - last >= DROP_EVENT_INTERVAL_MS &&
                        c.lastDropEventMs.compareAndSet(last, now)
                    ) {
                        recordEvent(
                            "consumer_queue_drop",
                            consumerId = c.id,
                            packageName = c.pkg,
                            valueBytes = c.pendingDropBytes.getAndSet(0L)
                        )
                    }
                }
                result = c.queue.trySend(payload)
            }
            if (result.isFailure) {
                GlassLog.b("Publisher") { "consumer ${c.id} 队列不可用，断开" }
                detach(c.id)
            }
        }
        lastBroadcastMs = System.currentTimeMillis()
        // 波形窗打开时才计算振幅，避免无谓开销
        if (_waveform.subscriptionCount.value > 0) {
            _waveform.tryEmit(downsample(sourceData, WAVEFORM_POINTS_PER_FRAME))
        }
        return sourceData.size
    }

    /** 把一帧 PCM16 下采样成 [points] 个峰值振幅点（0..1），供波形显示。 */
    private fun downsample(pcm: ByteArray, points: Int): FloatArray {
        val samples = pcm.size / 2
        val out = FloatArray(points)
        if (samples <= 0) return out
        val per = (samples / points).coerceAtLeast(1)
        for (p in 0 until points) {
            val start = p * per
            if (start >= samples) break
            val end = minOf(start + per, samples)
            var peak = 0
            var i = start
            while (i < end) {
                val s = ((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xFF)).toShort().toInt()
                val a = if (s < 0) -s else s
                if (a > peak) peak = a
                i++
            }
            out[p] = (peak / 32768f).coerceIn(0f, 1f)
        }
        return out
    }

    private fun applyEffects(input: ByteArray, epoch: Long): ByteArray {
        val fx = effects
        // Use the generation captured before reading: a concurrent seek/source switch must
        // reset again for the next source, even if its generation changed during this read.
        val reset = epoch != appliedEffectsEpoch
        appliedEffectsEpoch = epoch
        bandFilter.beginFrame(fx.band, reset)
        // 未启用混响时清空延迟线，避免下次开启时残留旧回声
        if (!fx.reverb || reset) reverbLine.reset()

        // 变速（变速变调）：线性重采样，改变样本数量
        val data = if (fx.speed) resample(input, fx.speedFactor) else input

        var inputSquares = 0.0
        var outputSquares = 0.0
        var peak = 0
        var exactZeroSamples = 0L
        var i = 0
        while (i + 1 < data.size) {
            var mixed = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toFloat()
            inputSquares += mixed.toDouble() * mixed
            if (fx.gain != 1f) {
                mixed *= fx.gain
            }
            if (fx.highGain) {
                mixed *= HIGH_GAIN_MULTIPLIER
            }
            if (fx.noiseSim) {
                mixed += Random.nextInt(-NOISE_SIM_AMPLITUDE, NOISE_SIM_AMPLITUDE + 1)
            }
            if (fx.reverb) {
                mixed = reverbLine.process(mixed, fx.reverbAmount)
            }
            mixed = bandFilter.process(mixed)
            if (mixed > Short.MAX_VALUE || mixed < Short.MIN_VALUE) overRangeSamples++
            val clipped = PcmOutputLimiter.apply(mixed, fx.limiterEnabled)
            peak = maxOf(peak, kotlin.math.abs(clipped))
            if (clipped == 0) exactZeroSamples++
            outputSquares += clipped.toDouble() * clipped
            data[i] = (clipped and 0xFF).toByte()
            data[i + 1] = ((clipped ushr 8) and 0xFF).toByte()
            i += 2
        }
        val count = data.size / 2
        val inputRms = if (count > 0) kotlin.math.sqrt(inputSquares / count) else 0.0
        val outputRms = if (count > 0) kotlin.math.sqrt(outputSquares / count) else 0.0
        processedSamples += count
        integrityAnalyzedSamples.addAndGet(count.toLong())
        integrityExactZeroSamples.addAndGet(exactZeroSamples)
        if (count > 0 && outputRms <= NEAR_SILENCE_RMS_PCM16) {
            integrityCurrentNearSilentRunSamples += count
            integrityMaxNearSilentRunSamples.accumulateAndGet(integrityCurrentNearSilentRunSamples) { old, current ->
                maxOf(old, current)
            }
        } else {
            integrityCurrentNearSilentRunSamples = 0L
        }
        outputLevels = OutputLevels(
            inputRms = inputRms,
            outputRms = outputRms,
            peak = peak, samples = processedSamples, overRange = overRangeSamples,
            time = System.currentTimeMillis()
        )
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
                    consumer.writtenBytes.addAndGet(data.size.toLong())
                    consumer.lastWriteMs.set(System.currentTimeMillis())
                }
            } catch (t: Throwable) {
                writeFailures.incrementAndGet()
                recordEvent(
                    "consumer_write_failure",
                    consumerId = consumer.id,
                    packageName = consumer.pkg,
                    detail = t.message
                )
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
            recordEvent(
                "consumer_detached",
                consumerId = c.id,
                packageName = c.pkg,
                detail = "written=${c.writtenBytes.get()} dropped=${c.droppedBytes.get()}"
            )
            GlassLog.b("Publisher") { "consumer 已断开: ${c.id}" }
        }
    }

    private fun detachAll() {
        consumers.keys.toList().forEach { detach(it) }
    }

    private fun buildConsumerId(pkg: String): String =
        "$pkg-${android.os.Process.myPid()}-${consumerSeq.incrementAndGet()}"

    private fun recordEvent(
        type: String,
        consumerId: String? = null,
        packageName: String? = null,
        detail: String? = null,
        valueBytes: Long = 0L
    ) {
        synchronized(eventLock) {
            while (recentEvents.size >= PUBLISHER_EVENT_LIMIT) {
                recentEvents.removeFirst()
            }
            recentEvents.addLast(
                PublisherEvent(
                    time = System.currentTimeMillis(),
                    type = type,
                    consumerId = consumerId,
                    packageName = packageName,
                    detail = detail?.take(180),
                    valueBytes = valueBytes
                )
            )
        }
    }

    /** 仅在导出时组装 JSON；保留本进程累计丢帧数，即便故障 consumer 已断开。 */
    fun diagnostics(): JSONObject = JSONObject().apply {
        put("captured_at", System.currentTimeMillis())
        put("last_broadcast_ms", lastBroadcastMs)
        put("paused", paused)
        put("completed", completed)
        put("total_dropped_bytes", totalDroppedBytes.get())
        put("write_failures", writeFailures.get())
        put("active_consumer_count", consumers.size)
        val analyzed = integrityAnalyzedSamples.get()
        val exactZero = integrityExactZeroSamples.get()
        put("master_integrity", JSONObject().apply {
            put("analyzed_samples", analyzed)
            put("exact_zero_samples", exactZero)
            put("exact_zero_ratio", if (analyzed > 0L) exactZero.toDouble() / analyzed else 0.0)
            put(
                "max_near_silent_run_ms",
                integrityMaxNearSilentRunSamples.get() * 1000L / MASTER_SAMPLE_RATE
            )
            put("near_silence_rms_threshold_pcm16", NEAR_SILENCE_RMS_PCM16)
        })
        val fx = effects
        put("audio_band", JSONObject().apply {
            put("enabled", fx.band.enabled)
            put("low_hz", fx.band.lowHz)
            put("high_hz", fx.band.highHz)
            put("limiter_enabled", fx.limiterEnabled)
        })
        val levels = outputLevels
        put("master_levels", JSONObject().apply {
            put("time", levels.time)
            put("input_rms_pcm16", levels.inputRms)
            put("output_rms_pcm16", levels.outputRms)
            put("output_peak_pcm16", levels.peak)
            put("processed_samples", levels.samples)
            put("pre_limiter_over_range_samples", levels.overRange)
        })
        put("consumers", JSONArray().apply {
            consumers.values.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("package", c.pkg)
                    put("sample_rate", c.sampleRate)
                    put("channels", c.channels)
                    put("written_bytes", c.writtenBytes.get())
                    put("dropped_bytes", c.droppedBytes.get())
                    put("last_write_ms", c.lastWriteMs.get())
                    put("pending_drop_bytes", c.pendingDropBytes.get())
                })
            }
        })
        put("recent_events", JSONArray().apply {
            val snapshot = synchronized(eventLock) { recentEvents.toList() }
            snapshot.forEach { e ->
                put(JSONObject().apply {
                    put("time", e.time)
                    put("type", e.type)
                    if (e.consumerId != null) put("consumer_id", e.consumerId)
                    if (e.packageName != null) put("package", e.packageName)
                    if (e.detail != null) put("detail", e.detail)
                    if (e.valueBytes > 0L) put("value_bytes", e.valueBytes)
                })
            }
        })
    }
}

private fun ProtoPolicy.toCore(): PlaybackPolicy = when (this) {
    ProtoPolicy.SILENCE -> PlaybackPolicy.SILENCE
    ProtoPolicy.LOOP -> PlaybackPolicy.LOOP
    ProtoPolicy.REAL_MIC -> PlaybackPolicy.REAL_MIC
    else -> PlaybackPolicy.LOOP
}

/** 单抽头反馈延迟线，产生简单混响。仅在广播协程单线程访问。 */
private class ReverbLine(size: Int) {
    private val buf = FloatArray(size)
    private var idx = 0

    fun reset() {
        buf.fill(0f)
        idx = 0
    }

    /** amount 0..1：控制湿信号比例与反馈量。返回叠加后的样本（未限幅，由调用方裁剪）。 */
    fun process(input: Float, amount: Float): Float {
        val delayed = buf[idx]
        val out = input + delayed * amount
        buf[idx] = input + delayed * amount * 0.6f
        idx++
        if (idx >= buf.size) idx = 0
        return out
    }
}
