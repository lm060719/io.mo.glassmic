package io.mo.glassmic.ui.settings

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.R
import io.mo.glassmic.audio.tts.AiTtsSynthesizer
import io.mo.glassmic.audio.tts.PcmSink
import io.mo.glassmic.audio.tts.TtsRequest
import io.mo.glassmic.data.audio.SampleImport
import io.mo.glassmic.data.audio.TtsCloneSampleStore
import io.mo.glassmic.data.config.AppLocale
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.proto.AppConfig
import io.mo.glassmic.proto.TtsAiConfig
import io.mo.glassmic.proto.TtsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

/** AI TTS「测试连接」状态。 */
sealed interface TtsTestState {
    data object Idle : TtsTestState
    data object Testing : TtsTestState
    data class Result(val ok: Boolean, val message: String) : TtsTestState
}

/** AI TTS「获取模型」状态。 */
sealed interface TtsModelsState {
    data object Idle : TtsModelsState
    data object Loading : TtsModelsState
    data class Loaded(val models: List<String>) : TtsModelsState
    data class Error(val message: String) : TtsModelsState
}

/** AI TTS「效果试听」状态（先生成后播放，可重复播放）。 */
sealed interface TtsPreviewState {
    data object Idle : TtsPreviewState
    data object Generating : TtsPreviewState
    data class Ready(val bytes: Int) : TtsPreviewState   // 已生成，可播放/重播
    data object Playing : TtsPreviewState
    data class Error(val message: String) : TtsPreviewState
}

/** AI 供应商（TTS）配置页的 ViewModel。 */
@HiltViewModel
class AiTtsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: ConfigStore,
    private val aiTts: AiTtsSynthesizer,
    private val cloneSampleStore: TtsCloneSampleStore
) : ViewModel() {

    /** 当前 AI 配置，UI collect。 */
    val ai: StateFlow<TtsAiConfig> = configStore.flow
        .map { it.tts.ai }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.getDefaultInstance().tts.ai)

    private fun updateAi(transform: (TtsAiConfig.Builder) -> Unit) = viewModelScope.launch {
        configStore.update { it.setTts(it.tts.toBuilder().setAi(it.tts.ai.toBuilder().also(transform))) }
    }

    fun setEnabled(v: Boolean) = updateAi { it.enabled = v }
    fun setProvider(p: TtsProvider) = updateAi { it.provider = p }
    fun setEndpoint(v: String) = updateAi { it.endpoint = v }
    fun setApiKey(v: String) = updateAi { it.apiKey = v }
    fun setModel(v: String) = updateAi { it.model = v }
    fun setVoice(v: String) = updateAi { it.voice = v }
    fun setFormat(v: String) = updateAi { it.format = v }
    fun setStylePrompt(v: String) = updateAi { it.stylePrompt = v }

    // ---- 参考音频（voiceclone）----
    private val _sampleError = MutableStateFlow<String?>(null)
    val sampleError: StateFlow<String?> = _sampleError.asStateFlow()
    fun consumeSampleError() { _sampleError.value = null }

    fun setCloneSample(uri: Uri?) = viewModelScope.launch {
        if (uri == null) {
            cloneSampleStore.clear()
            updateAi { it.cloneSamplePath = "" }
            return@launch
        }
        when (val r = cloneSampleStore.importSample(uri)) {
            is SampleImport.Ok -> updateAi { it.cloneSamplePath = r.relativePath }
            is SampleImport.Err -> _sampleError.value = r.message
        }
    }

    // ---- 测试连接 ----
    private val _test = MutableStateFlow<TtsTestState>(TtsTestState.Idle)
    val test: StateFlow<TtsTestState> = _test.asStateFlow()

    fun testConnection() = viewModelScope.launch {
        if (_test.value is TtsTestState.Testing) return@launch
        _test.value = TtsTestState.Testing
        val result = withTimeoutOrNull(25_000L) {
            suspendCancellableCoroutine { cont ->
                var bytes = 0
                val sink = object : PcmSink {
                    override fun onFormat(sampleRate: Int, channels: Int) {}
                    override fun onPcm(chunk: ByteArray) { bytes += chunk.size }
                    override fun onDone() {
                        if (cont.isActive) {
                            cont.resumeWith(Result.success(true to AppLocale.string(context, R.string.ai_tts_connected_ok, bytes / 1024)))
                        }
                    }
                    override fun onError(message: String) {
                        if (cont.isActive) cont.resumeWith(Result.success(false to message))
                    }
                }
                aiTts.synthesize(TtsRequest(AppLocale.string(context, R.string.ai_tts_test_phrase)), sink)
                cont.invokeOnCancellation { aiTts.cancel() }
            }
        } ?: (false to AppLocale.string(context, R.string.ai_tts_test_timeout))
        _test.value = TtsTestState.Result(result.first, result.second)
    }

    // ---- 获取模型 ----
    private val _models = MutableStateFlow<TtsModelsState>(TtsModelsState.Idle)
    val models: StateFlow<TtsModelsState> = _models.asStateFlow()

    fun fetchModels() = viewModelScope.launch {
        if (_models.value is TtsModelsState.Loading) return@launch
        _models.value = TtsModelsState.Loading
        _models.value = runCatching { aiTts.fetchModels() }.fold(
            onSuccess = {
                if (it.isEmpty()) TtsModelsState.Error(AppLocale.string(context, R.string.ai_tts_no_models_returned))
                else TtsModelsState.Loaded(it)
            },
            onFailure = { TtsModelsState.Error(it.message ?: AppLocale.string(context, R.string.ai_tts_fetch_models_failed)) }
        )
    }

    // ---- 效果试听：先生成，再点播放（可重复播放）----
    private val _preview = MutableStateFlow<TtsPreviewState>(TtsPreviewState.Idle)
    val preview: StateFlow<TtsPreviewState> = _preview.asStateFlow()

    @Volatile private var previewTrack: AudioTrack? = null
    private var previewJob: Job? = null
    @Volatile private var generatedPcm: ByteArray? = null
    @Volatile private var generatedSr = 24_000
    @Volatile private var generatedCh = 1

    /** 生成 [text] 的语音并缓存（不自动播放）。 */
    fun generatePreview(text: String) {
        val t = text.trim()
        if (t.isEmpty()) {
            _preview.value = TtsPreviewState.Error(AppLocale.string(context, R.string.ai_tts_enter_preview_text))
            return
        }
        stopPlayback()
        generatedPcm = null
        _preview.value = TtsPreviewState.Generating
        val out = ByteArrayOutputStream()
        var sr = 24_000
        var ch = 1
        val sink = object : PcmSink {
            override fun onFormat(sampleRate: Int, channels: Int) {
                sr = sampleRate.coerceAtLeast(8_000)
                ch = channels.coerceAtLeast(1)
            }
            override fun onPcm(chunk: ByteArray) { out.write(chunk) }
            override fun onDone() {
                val pcm = out.toByteArray()
                if (pcm.isEmpty()) {
                    _preview.value = TtsPreviewState.Error(AppLocale.string(context, R.string.ai_tts_no_audio_received))
                } else {
                    generatedPcm = pcm; generatedSr = sr; generatedCh = ch
                    _preview.value = TtsPreviewState.Ready(pcm.size)
                }
            }
            override fun onError(message: String) { _preview.value = TtsPreviewState.Error(message) }
        }
        aiTts.synthesize(TtsRequest(t), sink)
    }

    /** 播放上次生成的语音（可重复调用重播）。 */
    fun playPreview() {
        val pcm = generatedPcm ?: run {
            _preview.value = TtsPreviewState.Error(AppLocale.string(context, R.string.ai_tts_generate_first))
            return
        }
        startPlayback(pcm, generatedSr, generatedCh)
    }

    private fun startPlayback(pcm: ByteArray, sampleRate: Int, channels: Int) {
        stopPlayback()
        previewJob = viewModelScope.launch(Dispatchers.IO) {
            val chMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(chMask)
                    .build(),
                maxOf(minBuf, sampleRate * channels * 2),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            previewTrack = track
            _preview.value = TtsPreviewState.Playing
            try {
                track.play()
                track.write(pcm, 0, pcm.size)
                val durationMs = pcm.size.toLong() * 1000 / (sampleRate.toLong() * channels * 2)
                delay(durationMs + 300)
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
                previewTrack = null
                // 播完回到「已生成」，可再次播放
                if (_preview.value is TtsPreviewState.Playing) _preview.value = TtsPreviewState.Ready(pcm.size)
            }
        }
    }

    private fun stopPlayback() {
        previewJob?.cancel()
        previewJob = null
        previewTrack?.let { runCatching { it.stop() }; runCatching { it.release() } }
        previewTrack = null
    }

    /** 停止播放（保留已生成音频，仍可再次播放）。 */
    fun stopPreview() {
        stopPlayback()
        _preview.value = generatedPcm?.let { TtsPreviewState.Ready(it.size) } ?: TtsPreviewState.Idle
    }

    // ---- 保存已生成音频到本地 ----
    private val _saveMessage = MutableStateFlow<String?>(null)
    /** 保存结果提示，UI 单独 collect 弹 snackbar。 */
    val saveMessage: StateFlow<String?> = _saveMessage.asStateFlow()
    fun consumeSaveMessage() { _saveMessage.value = null }

    /** 建议的保存文件名。 */
    fun suggestedFileName(): String = "glassmic-tts-${System.currentTimeMillis()}.wav"

    /** 是否已有可保存的生成音频。 */
    fun hasGenerated(): Boolean = generatedPcm != null

    /** 把上次生成的语音写成 WAV 保存到用户选择的 [uri]。 */
    fun saveGeneratedTo(uri: Uri) {
        val pcm = generatedPcm
        if (pcm == null) {
            _saveMessage.value = AppLocale.string(context, R.string.ai_tts_generate_audio_first)
            return
        }
        val sr = generatedSr
        val ch = generatedCh
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out -> writeWav(out, pcm, sr, ch) }
                    ?: error("无法打开输出流")
            }.isSuccess
            _saveMessage.value = AppLocale.string(context, if (ok) R.string.ai_tts_saved else R.string.ai_tts_save_failed)
        }
    }

    /** 给裸 PCM16 加 WAV 头写出。 */
    private fun writeWav(out: OutputStream, pcm: ByteArray, sampleRate: Int, channels: Int) {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)                       // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        out.write(header.array())
        out.write(pcm)
        out.flush()
    }

    override fun onCleared() {
        stopPlayback()
        aiTts.cancel()
    }
}
