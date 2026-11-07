package io.mo.glassmic.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.audio.BufferedPcmSource
import io.mo.glassmic.audio.FileAudioSource
import io.mo.glassmic.audio.Pcm16Converter
import io.mo.glassmic.audio.SharedPcmPublisher
import io.mo.glassmic.audio.SilenceSource
import io.mo.glassmic.R
import io.mo.glassmic.audio.tts.PcmSink
import io.mo.glassmic.audio.tts.TtsProgress
import io.mo.glassmic.audio.tts.TtsRequest
import io.mo.glassmic.audio.tts.TtsSynthesizerFactory
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.config.AppLocale
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.memory.MemoryPressure
import io.mo.glassmic.memory.MemoryPressureBus
import io.mo.glassmic.memory.MemoryReleasable
import io.mo.glassmic.root.LiveCaptureController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 UI 的"设为当前音源"动作衔接到音频管线。
 *
 * - 修改 RuntimeStateHolder 当前音源
 * - 通知 SharedPcmPublisher 切换 source
 * - 持久化当前选中 ID 到 ConfigStore（用于重启后恢复显示）
 *
 * 不负责 BootGate / SafeMode 判断——这些在 EffectiveSourceResolver 里。
 */
@Singleton
class PlaybackController @Inject constructor(
    private val publisher: SharedPcmPublisher,
    private val resolver: AudioFileResolver,
    private val configStore: ConfigStore,
    private val runtime: RuntimeStateHolder,
    private val dao: AudioDao,
    private val ttsFactory: TtsSynthesizerFactory,
    private val ttsProgress: TtsProgress,
    private val liveCapture: LiveCaptureController,
    @ApplicationContext private val context: Context
) : MemoryReleasable {
    private val sourceSwitchMutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 本类持有整个进程里最大的一块可丢弃内存（TTS 生成的 PCM），登记到内存压力总线
        MemoryPressureBus.register(this)
        // 服务停止（用户关闭总开关 / 安全模式）时结束实时抓取：root 进程和 AudioPolicy 不能在服务之外常驻
        scope.launch {
            runtime.flow.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                if (enabled) return@collect
                if (runtime.value.currentSourceType == SourceType.LIVE) {
                    GlassLog.b("Playback") { "服务已停止，结束实时应用音频" }
                    setRealMic()
                }
                liveCapture.close()
            }
        }
    }

    /** Linked documents keep their records on access failure so reauthorization can recover them. */
    suspend fun setCurrentClip(clipId: String, startPaused: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        sourceSwitchMutex.withLock {
            val clip = dao.findClip(clipId) ?: return@withContext false
            if (clip.hidden) return@withContext false
            if (clip.sourceUri == null && !resolver.fileFor(clip.relativePath).exists()) {
                GlassLog.b("Playback") { "音频文件丢失，清理记录: ${clip.relativePath}" }
                dao.deleteClip(clipId)
                return@withContext false
            }
            val src = runCatching { resolver.openSource(clip) }
                .onFailure { GlassLog.b("Playback") { "FileAudioSource 创建失败: ${it.message}" } }
                .getOrNull() ?: return@withContext false

            publisher.setSource(src, groupId = clip.groupId, audioId = clip.id)
            publisher.setPaused(startPaused)
            configStore.update {
                it.setCurrentGroupId(clip.groupId)
                it.setCurrentAudioId(clip.id)
            }
            true
        }
    }

    // ============ 文字转语音（先生成后播放） ============
    enum class TtsGen { IDLE, GENERATING, READY, FAILED }

    private val _ttsGen = MutableStateFlow(TtsGen.IDLE)
    /** 生成状态：供悬浮窗决定「播放」按钮是否可用。 */
    val ttsGen: StateFlow<TtsGen> = _ttsGen.asStateFlow()

    private val _ttsPreviewing = MutableStateFlow(false)
    /** 本地试听状态：供悬浮窗显示试听/停止按钮。 */
    val ttsPreviewing: StateFlow<Boolean> = _ttsPreviewing.asStateFlow()
    private var previewJob: Job? = null
    private var previewTrack: AudioTrack? = null

    @Volatile private var generatedTtsPcm: ByteArray? = null

    /**
     * 在本机扬声器/耳机试听上次生成的语音（不占用虚拟麦克风推流管线）。
     */
    suspend fun togglePreviewTts() = withContext(Dispatchers.IO) {
        if (_ttsPreviewing.value) {
            stopPreviewTts()
        } else {
            startPreviewTts()
        }
    }

    fun stopPreviewTts() {
        previewJob?.cancel()
        previewJob = null
        previewTrack?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        previewTrack = null
        _ttsPreviewing.value = false
    }

    private fun startPreviewTts() {
        val pcm = generatedTtsPcm ?: run {
            GlassLog.b("Playback") { "TTS 试听失败：尚未生成语音" }
            return
        }
        stopPreviewTts()
        previewJob = CoroutineScope(Dispatchers.IO).launch {
            _ttsPreviewing.value = true
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    MASTER_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val track = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(MASTER_SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    maxOf(minBuf, MASTER_SAMPLE_RATE * 2),
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                previewTrack = track
                track.play()
                var written = 0
                val chunkSize = 4096
                while (isActive && written < pcm.size) {
                    val len = minOf(chunkSize, pcm.size - written)
                    val ret = track.write(pcm, written, len)
                    if (ret < 0) break
                    written += ret
                }
                // 等待缓冲音频播放完毕：计算实际播放时长
                val durationMs = pcm.size.toLong() * 1000 / (MASTER_SAMPLE_RATE * 2)
                delay(durationMs + 300)
            } catch (t: Throwable) {
                GlassLog.b("Playback") { "TTS 试听异常: ${t.message}" }
            } finally {
                previewTrack?.let {
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }
                previewTrack = null
                _ttsPreviewing.value = false
            }
        }
    }

    /**
     * 生成 [text] 的语音并缓存为主格式 PCM（不立即播放）。成功返回 true。
     * 之后调用 [playGeneratedTts] 才真正喂给目标 App，可重复播放。
     */
    suspend fun generateTts(text: String): Boolean = withContext(Dispatchers.IO) {
        stopPreviewTts()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext false
        _ttsGen.value = TtsGen.GENERATING
        generatedTtsPcm = null
        val ttsCfg = configStore.current().tts
        val synth = ttsFactory.current()
        val req = TtsRequest(
            text = trimmed,
            rate = ttsCfg.speechRate.takeIf { it > 0f } ?: 1f,
            pitch = ttsCfg.pitch.takeIf { it > 0f } ?: 1f,
            voice = ttsCfg.voice
        )
        ttsProgress.start()
        // AI TTS 可能先走一次 LLM 自动标签再合成，长文本两段加起来常超过 30s，给足余量
        val pcm = withTimeoutOrNull(TTS_GENERATE_TIMEOUT_MS) { synthesizeToMasterPcm(synth, req) }
        if (pcm == null) {
            ttsProgress.step(AppLocale.string(context, R.string.tts_step_timeout, TTS_GENERATE_TIMEOUT_MS / 1000))
        }
        if (pcm != null && pcm.isNotEmpty()) {
            generatedTtsPcm = pcm
            _ttsGen.value = TtsGen.READY
            GlassLog.b("Playback") { "TTS 已生成: ${trimmed.take(20)} (${pcm.size} B)" }
            true
        } else {
            _ttsGen.value = TtsGen.FAILED
            GlassLog.b("Playback") { "TTS 生成失败/超时: ${trimmed.take(20)}" }
            false
        }
    }

    private val _ttsDelayRemainingMs = MutableStateFlow(0L)
    /** 延时播放倒计时剩余毫秒，0 表示当前没有待播放的语音。供悬浮窗显示倒计时。 */
    val ttsDelayRemainingMs: StateFlow<Long> = _ttsDelayRemainingMs.asStateFlow()

    /**
     * 把上次生成的语音喂给目标 App，可重复调用重播。
     *
     * 配置了 tts.delay_ms 时先倒计时再出声，留出切到目标 App 的时间；
     * 倒计时期间可用 [cancelDelayedTts] 取消。返回 true 表示语音已真正开始播放
     * （被取消则返回 false）。
     */
    suspend fun playGeneratedTts(): Boolean = withContext(Dispatchers.IO) {
        stopPreviewTts()
        val pcm = generatedTtsPcm ?: return@withContext false

        val delayMs = configStore.current().tts.delayMs.toLong().coerceAtLeast(0L)
        if (delayMs > 0) {
            // 已有倒计时在跑时不叠加，交给调用方先取消
            if (_ttsDelayRemainingMs.value > 0L) return@withContext false
            val deadline = System.currentTimeMillis() + delayMs
            try {
                var remaining = delayMs
                while (remaining > 0) {
                    _ttsDelayRemainingMs.value = remaining
                    // 100ms 一档刷新，倒计时读数够跟手又不至于频繁重组
                    delay(remaining.coerceAtMost(COUNTDOWN_TICK_MS))
                    remaining = deadline - System.currentTimeMillis()
                }
            } catch (e: CancellationException) {
                // 取消后要清干净，否则下次播放会被上面的"已有倒计时"分支挡掉
                _ttsDelayRemainingMs.value = 0L
                GlassLog.b("Playback") { "TTS 延时播放已取消" }
                throw e
            }
            _ttsDelayRemainingMs.value = 0L
        }

        sourceSwitchMutex.withLock {
            publisher.setSource(BufferedPcmSource(pcm))
            publisher.setPaused(false)
            configStore.update {
                it.setCurrentGroupId("")
                it.setCurrentAudioId("")
            }
        }
        true
    }

    /** 倒计时归零，[playGeneratedTts] 的协程被取消后由调用方触发。 */
    fun clearTtsDelayCountdown() {
        _ttsDelayRemainingMs.value = 0L
    }

    /** 合成并转成主时钟格式（48kHz 单声道 PCM16）。 */
    private suspend fun synthesizeToMasterPcm(
        synth: io.mo.glassmic.audio.tts.SpeechSynthesizer,
        req: TtsRequest
    ): ByteArray? = suspendCancellableCoroutine { cont ->
        val out = ByteArrayOutputStream()
        var converter: Pcm16Converter? = null
        val sink = object : PcmSink {
            override fun onFormat(sampleRate: Int, channels: Int) {
                converter = Pcm16Converter(
                    sourceSampleRate = sampleRate.coerceAtLeast(8_000),
                    sourceChannels = channels.coerceAtLeast(1),
                    targetSampleRate = MASTER_SAMPLE_RATE,
                    targetChannels = MASTER_CHANNELS
                )
            }
            override fun onPcm(chunk: ByteArray) {
                val c = converter ?: return
                out.write(c.convert(chunk))
            }
            override fun onDone() {
                if (cont.isActive) cont.resumeWith(Result.success(out.toByteArray()))
            }
            override fun onError(message: String) {
                if (cont.isActive) cont.resumeWith(Result.success(null))
            }
        }
        synth.synthesize(req, sink)
        cont.invokeOnCancellation { synth.cancel() }
    }

    private companion object {
        const val MASTER_SAMPLE_RATE = 48_000
        const val MASTER_CHANNELS = 1
        const val COUNTDOWN_TICK_MS = 100L
        const val TTS_GENERATE_TIMEOUT_MS = 90_000L
    }

    // ============ 实时 App 音频（issue #42，实验） ============

    /**
     * 把 [packageName] 正在播放的声音实时当作虚拟麦克风音源（root 抓取）。
     * 失败返回错误信息，成功返回 null。原 App 仍在本机正常出声。
     */
    suspend fun setLiveSource(packageName: String): String? = withContext(Dispatchers.IO) {
        if (!runtime.value.enabled) return@withContext AppLocale.string(context, R.string.live_need_service)
        stopPreviewTts()
        val src = try {
            liveCapture.open(packageName)
        } catch (error: Exception) {
            return@withContext error.message ?: error.javaClass.simpleName
        }
        // root 授权可能耗时数秒，期间服务若已停止，不再接上这个会话
        if (!runtime.value.enabled) {
            liveCapture.close()
            return@withContext AppLocale.string(context, R.string.live_need_service)
        }
        publisher.setSource(src)
        publisher.setPaused(false)
        configStore.update {
            it.setCurrentGroupId("")
            it.setCurrentAudioId("")
        }
        null
    }

    /** 切回真实麦克风：释放当前 file source，运行态置 REAL_MIC，清空持久化选中。 */
    suspend fun setRealMic() = withContext(Dispatchers.IO) {
        sourceSwitchMutex.withLock {
            publisher.setPaused(false)
            publisher.setSource(SilenceSource)
            runtime.setSource(SourceType.REAL_MIC, groupId = null, audioId = null, durationMs = 0L)
            configStore.update {
                it.setCurrentGroupId("")
                it.setCurrentAudioId("")
            }
        }
    }

    suspend fun restorePersistedClip(): Boolean {
        val clipId = configStore.current().currentAudioId.takeIf { it.isNotBlank() } ?: return false
        return setCurrentClip(clipId, startPaused = true)
    }

    /** Removing a selected clip stops injection without unexpectedly enabling the real mic. */
    suspend fun clearRemovedClip() = withContext(Dispatchers.IO) {
        sourceSwitchMutex.withLock {
            val id = configStore.current().currentAudioId.takeIf { it.isNotBlank() } ?: return@withContext
            val clip = dao.findClip(id)
            if (clip != null && !clip.hidden) return@withContext
            publisher.setSource(SilenceSource)
            runtime.setSource(SourceType.SILENCE, groupId = null, audioId = null, durationMs = 0L)
            configStore.update { it.setCurrentGroupId("").setCurrentAudioId("") }
        }
    }

    val isPaused: Boolean get() = publisher.isPaused

    fun pause() {
        publisher.setPaused(true)
        GlassLog.b("Playback") { "暂停" }
    }

    fun resume() {
        publisher.setPaused(false)
        GlassLog.b("Playback") { "恢复" }
    }

    fun togglePause() {
        if (publisher.isPaused) resume() else pause()
    }

    suspend fun seekTo(positionMs: Long) = withContext(Dispatchers.IO) {
        publisher.seekCurrent(positionMs)
    }

    // ============ 公平运行内存：可回收内存 ============

    /**
     * 释放 TTS 生成缓存。
     *
     * 48kHz 单声道 PCM16 = 96KB/秒，一段一分钟的语音就是 5.6MB，是本进程最大的一块
     * 可丢弃内存。丢掉只影响「重复播放上一段语音」，用户重新生成即可恢复。
     *
     * 正在合成、或这段 PCM 正是当前音源（目标 App 正在读它）时绝不丢——那会直接打断
     * 核心功能。正在出声的音频管线在任何压力等级下都不动。
     */
    override fun onMemoryPressure(level: MemoryPressure): Long {
        val pcm = generatedTtsPcm ?: return 0L
        if (_ttsGen.value == TtsGen.GENERATING) return 0L
        if (publisher.playingBufferedPcm) return 0L
        val shouldDrop = when (level) {
            // 只是切到后台/系统轻度吃紧，用户可能马上回来重播，留着
            MemoryPressure.LIGHT -> false
            // 没有 App 在读 PCM 时才丢，避免录音过程中用户点重播落空
            MemoryPressure.MODERATE -> publisher.consumerCount == 0
            else -> true
        }
        if (!shouldDrop) return 0L
        generatedTtsPcm = null
        _ttsGen.value = TtsGen.IDLE
        GlassLog.b("Playback") { "内存压力($level)：释放 TTS 缓存 ${pcm.size / 1024}KB" }
        return pcm.size.toLong()
    }
}
