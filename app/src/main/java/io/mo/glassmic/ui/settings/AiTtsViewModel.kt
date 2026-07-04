package io.mo.glassmic.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.mo.glassmic.audio.tts.AiTtsSynthesizer
import io.mo.glassmic.audio.tts.PcmSink
import io.mo.glassmic.audio.tts.TtsRequest
import io.mo.glassmic.data.audio.SampleImport
import io.mo.glassmic.data.audio.TtsCloneSampleStore
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.proto.AppConfig
import io.mo.glassmic.proto.TtsAiConfig
import io.mo.glassmic.proto.TtsProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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

/** AI 供应商（TTS）配置页的 ViewModel。 */
@HiltViewModel
class AiTtsViewModel @Inject constructor(
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
                        if (cont.isActive) cont.resumeWith(Result.success(true to "连接成功，收到 ${bytes / 1024} KB 音频"))
                    }
                    override fun onError(message: String) {
                        if (cont.isActive) cont.resumeWith(Result.success(false to message))
                    }
                }
                aiTts.synthesize(TtsRequest("这是一段连接测试。"), sink)
                cont.invokeOnCancellation { aiTts.cancel() }
            }
        } ?: (false to "测试超时（25 秒）")
        _test.value = TtsTestState.Result(result.first, result.second)
    }

    // ---- 获取模型 ----
    private val _models = MutableStateFlow<TtsModelsState>(TtsModelsState.Idle)
    val models: StateFlow<TtsModelsState> = _models.asStateFlow()

    fun fetchModels() = viewModelScope.launch {
        if (_models.value is TtsModelsState.Loading) return@launch
        _models.value = TtsModelsState.Loading
        _models.value = runCatching { aiTts.fetchModels() }.fold(
            onSuccess = { if (it.isEmpty()) TtsModelsState.Error("接口未返回模型") else TtsModelsState.Loaded(it) },
            onFailure = { TtsModelsState.Error(it.message ?: "获取失败") }
        )
    }
}
