package io.mo.glassmic.data.audio

import io.mo.glassmic.audio.BufferedPcmSource
import io.mo.glassmic.audio.FileAudioSource
import io.mo.glassmic.audio.Pcm16Converter
import io.mo.glassmic.audio.SharedPcmPublisher
import io.mo.glassmic.audio.SilenceSource
import io.mo.glassmic.audio.tts.PcmSink
import io.mo.glassmic.audio.tts.TtsRequest
import io.mo.glassmic.audio.tts.TtsSynthesizerFactory
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val ttsFactory: TtsSynthesizerFactory
) {

    /** 设置某个片段为当前虚拟麦克风音源。文件不存在则自动清掉数据库记录并返回 false。 */
    suspend fun setCurrentClip(clipId: String, startPaused: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val clip = dao.findClip(clipId) ?: return@withContext false
        val file = resolver.fileFor(clip.relativePath)
        if (!file.exists()) {
            GlassLog.b("Playback") { "音频文件丢失，清理记录: ${clip.relativePath}" }
            dao.deleteClip(clipId)
            return@withContext false
        }
        val src = runCatching { FileAudioSource(clip.toModel(), file.absolutePath) }
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

    // ============ 文字转语音（先生成后播放） ============
    enum class TtsGen { IDLE, GENERATING, READY, FAILED }

    private val _ttsGen = MutableStateFlow(TtsGen.IDLE)
    /** 生成状态：供悬浮窗决定「播放」按钮是否可用。 */
    val ttsGen: StateFlow<TtsGen> = _ttsGen.asStateFlow()

    @Volatile private var generatedTtsPcm: ByteArray? = null

    /**
     * 生成 [text] 的语音并缓存为主格式 PCM（不立即播放）。成功返回 true。
     * 之后调用 [playGeneratedTts] 才真正喂给目标 App，可重复播放。
     */
    suspend fun generateTts(text: String): Boolean = withContext(Dispatchers.IO) {
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
        val pcm = withTimeoutOrNull(30_000L) { synthesizeToMasterPcm(synth, req) }
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

        publisher.setSource(BufferedPcmSource(pcm))
        publisher.setPaused(false)
        configStore.update {
            it.setCurrentGroupId("")
            it.setCurrentAudioId("")
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
    }

    /** 切回真实麦克风：释放当前 file source，运行态置 REAL_MIC，清空持久化选中。 */
    suspend fun setRealMic() = withContext(Dispatchers.IO) {
        publisher.setPaused(false)
        publisher.setSource(SilenceSource)
        runtime.setSource(SourceType.REAL_MIC, groupId = null, audioId = null, durationMs = 0L)
        configStore.update {
            it.setCurrentGroupId("")
            it.setCurrentAudioId("")
        }
    }

    suspend fun restorePersistedClip(): Boolean {
        val clipId = configStore.current().currentAudioId.takeIf { it.isNotBlank() } ?: return false
        return setCurrentClip(clipId, startPaused = true)
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
}
