package io.mo.glassmic.audio.tts

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.proto.TtsAiConfig
import io.mo.glassmic.proto.TtsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 在线 AI TTS 合成器，兼容多种接口协议，支持自定义 url 与模型：
 *
 * - [TtsProvider.OPENAI]：POST {base}/audio/speech，返回音频字节（wav/pcm）。
 * - [TtsProvider.GEMINI]：POST {base}/models/{model}:generateContent（AUDIO 模态），
 *                         返回 base64 PCM（默认 24kHz、16bit、单声道）。
 * - [TtsProvider.MIMO]  ：小米 MiMo，POST {base}/chat/completions（api-key 头 + audio 参数），
 *                         音频以 base64 藏在 message 里。
 *
 * （Anthropic 官方无 TTS 接口，故不支持。）
 * endpoint / model 留空时按 provider 用默认值。合成结果统一以 PCM16 经 [PcmSink] 回调。
 */
@Singleton
class AiTtsSynthesizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: ConfigStore
) : SpeechSynthesizer {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var job: Job? = null

    override fun synthesize(request: TtsRequest, sink: PcmSink) {
        job?.cancel()
        job = scope.launch {
            // 不检查 enabled：正式路径由 TtsSynthesizerFactory 把关，这里也允许「测试连接」直接调用。
            val ai = runCatching { configStore.current().tts.ai }.getOrNull()
            if (ai == null) {
                sink.onError("AI TTS 配置缺失")
                return@launch
            }
            runCatching {
                when (ai.provider) {
                    TtsProvider.GEMINI -> gemini(ai, request, sink)
                    TtsProvider.MIMO -> mimo(ai, request, sink)
                    else -> openAi(ai, request, sink) // OPENAI 及默认
                }
            }.onFailure {
                GlassLog.b("Tts") { "AI TTS 失败(${ai.provider}): ${it.message}" }
                sink.onError("AI TTS 失败: ${it.message}")
            }
        }
    }

    override fun cancel() {
        job?.cancel()
        job = null
    }

    /**
     * 通过接口拉取可用模型列表，供 UI 下拉选择。
     * 含 "tts" 的模型排前面（TTS 场景更相关）。失败抛异常，由调用方处理。
     */
    suspend fun fetchModels(): List<String> = withContext(Dispatchers.IO) {
        val ai = configStore.current().tts.ai
        val models = when (ai.provider) {
            TtsProvider.GEMINI -> geminiModels(ai)
            TtsProvider.MIMO -> openAiStyleModels(ai, "https://api.xiaomimimo.com/v1", apiKeyHeader = true)
            else -> openAiStyleModels(ai, "https://api.openai.com/v1", apiKeyHeader = false)
        }
        models.distinct().sortedWith(compareByDescending<String> { it.contains("tts", true) }.thenBy { it })
    }

    /** OpenAI 风格 GET {base}/models，返回 data[].id。base 从 endpoint 推导（剥掉已知动作后缀）。 */
    private fun openAiStyleModels(ai: TtsAiConfig, default: String, apiKeyHeader: Boolean): List<String> {
        val base = ai.endpoint.ifBlank { default }.trimEnd('/')
            .removeSuffix("/audio/speech").removeSuffix("/chat/completions").trimEnd('/')
        val headers = linkedMapOf("Content-Type" to "application/json")
        if (ai.apiKey.isNotBlank()) {
            if (apiKeyHeader) headers["api-key"] = ai.apiKey else headers["Authorization"] = "Bearer ${ai.apiKey}"
        }
        val bytes = httpGet("$base/models", headers)
        val data = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }
    }

    /** Gemini GET {base}/models?key=，返回 models[].name（去掉 "models/" 前缀）。 */
    private fun geminiModels(ai: TtsAiConfig): List<String> {
        val base = ai.endpoint.ifBlank { "https://generativelanguage.googleapis.com/v1beta" }.trimEnd('/')
        var url = "$base/models"
        if (ai.apiKey.isNotBlank()) url += "?key=${ai.apiKey}"
        val bytes = httpGet(url, linkedMapOf("Content-Type" to "application/json"))
        val arr = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            arr.optJSONObject(it)?.optString("name")?.removePrefix("models/")?.takeIf { s -> s.isNotBlank() }
        }
    }

    // ============ OpenAI /audio/speech ============
    private fun openAi(ai: TtsAiConfig, req: TtsRequest, sink: PcmSink) {
        val base = ai.endpoint.ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
        val url = if (base.endsWith("/audio/speech")) base else "$base/audio/speech"
        val format = ai.format.ifBlank { "wav" }
        val body = JSONObject()
            .put("model", ai.model.ifBlank { "gpt-4o-mini-tts" })
            .put("input", req.text)
            .put("voice", ai.voice.ifBlank { "alloy" })
            .put("response_format", format)
            .toString()
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer ${ai.apiKey}"
        )
        val audio = httpPost(url, headers, body.toByteArray(Charsets.UTF_8))
        // OpenAI 的 pcm 固定 24kHz/16bit/单声道
        decodeAudio(format, if (ai.sampleRate > 0) ai.sampleRate else 24_000, audio, sink)
    }

    // ============ Google Gemini generateContent（AUDIO 模态） ============
    private fun gemini(ai: TtsAiConfig, req: TtsRequest, sink: PcmSink) {
        val base = ai.endpoint.ifBlank { "https://generativelanguage.googleapis.com/v1beta" }.trimEnd('/')
        val model = ai.model.ifBlank { "gemini-2.5-flash-preview-tts" }
        var url = if (base.contains(":generateContent")) base else "$base/models/$model:generateContent"
        if (ai.apiKey.isNotBlank()) url += (if (url.contains('?')) "&" else "?") + "key=${ai.apiKey}"

        val speechConfig = JSONObject().put(
            "voiceConfig",
            JSONObject().put(
                "prebuiltVoiceConfig",
                JSONObject().put("voiceName", ai.voice.ifBlank { "Kore" })
            )
        )
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", req.text)))
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", speechConfig)
            )
            .toString()
        val bytes = httpPost(url, linkedMapOf("Content-Type" to "application/json"), body.toByteArray(Charsets.UTF_8))

        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: return sink.onError("Gemini 返回无 candidates（检查模型是否支持 TTS）")
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val data = inline.optString("data").takeIf { it.isNotBlank() } ?: continue
            val mime = inline.optString("mimeType")
            val sr = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24_000
            val pcm = Base64.decode(data, Base64.DEFAULT)
            sink.onFormat(sr, 1)
            sink.onPcm(pcm)
            sink.onDone()
            return
        }
        sink.onError("Gemini 返回未包含音频数据")
    }

    // ============ 小米 MiMo（/chat/completions + audio 参数） ============
    // 三种模型的请求体不同：
    // - mimo-v2.5-tts        ：audio.voice = 预置音色名；user 消息 = 可选风格指令
    // - mimo-v2.5-tts-voicedesign：音色描述放 user 消息，audio.optimize_text_preview=true，无 voice
    // - mimo-v2.5-tts-voiceclone ：audio.voice = 参考音频 base64 data URI
    private fun mimo(ai: TtsAiConfig, req: TtsRequest, sink: PcmSink) {
        val base = ai.endpoint.ifBlank { "https://api.xiaomimimo.com/v1" }.trimEnd('/')
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val format = ai.format.ifBlank { "wav" }
        val model = ai.model.ifBlank { "mimo-v2.5-tts" }

        val audioParams = JSONObject().put("format", format)
        val userContent = ai.stylePrompt
        when {
            model.contains("voiceclone", ignoreCase = true) -> {
                val dataUri = cloneSampleDataUri(ai.cloneSamplePath)
                    ?: return sink.onError("voiceclone 需要先在设置里选择参考音频样本")
                audioParams.put("voice", dataUri)
            }
            model.contains("voicedesign", ignoreCase = true) -> {
                if (userContent.isBlank()) return sink.onError("voicedesign 需要在「音色描述」里填写描述文本")
                val optimizePreview = if (ai.hasMimoOptimizeTextPreview()) ai.mimoOptimizeTextPreview else true
                audioParams.put("optimize_text_preview", optimizePreview)
            }
            else -> audioParams.put("voice", ai.voice.ifBlank { "Chloe" })
        }

        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "user").put("content", userContent))
                    .put(JSONObject().put("role", "assistant").put("content", req.text))
            )
            .put("audio", audioParams)
            .put("stream", false)
            .toString()
        // MiMo 用 api-key 头（非 Authorization: Bearer）
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "api-key" to ai.apiKey
        )
        val bytes = httpPost(url, headers, body.toByteArray(Charsets.UTF_8))

        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        val b64 = message?.optJSONObject("audio")?.optString("data")?.takeIf { it.isNotBlank() }
            ?: message?.optString("audio")?.takeIf { it.isNotBlank() }
            ?: return sink.onError("MiMo 返回未包含音频（检查模型/音色是否正确）")
        val audio = Base64.decode(b64, Base64.DEFAULT)
        decodeAudio(format, if (ai.sampleRate > 0) ai.sampleRate else 24_000, audio, sink)
    }

    /** 读取 voiceclone 参考音频文件 → data:audio/...;base64,... （MiMo 仅支持 wav / mpeg）。 */
    private fun cloneSampleDataUri(relativePath: String): String? {
        if (relativePath.isBlank()) return null
        val file = java.io.File(context.filesDir, relativePath)
        if (!file.exists()) return null
        val mime = if (file.extension.lowercase() in setOf("mp3", "mpeg")) "audio/mpeg" else "audio/wav"
        val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        return "data:$mime;base64,$b64"
    }

    // ============ 音频字节 → PCM16 ============
    private fun decodeAudio(format: String, cfgSampleRate: Int, bytes: ByteArray, sink: PcmSink) {
        when (format.ifBlank { "wav" }.lowercase()) {
            "pcm16", "pcm", "raw", "l16" -> {
                sink.onFormat(cfgSampleRate.takeIf { it > 0 } ?: 24_000, 1)
                sink.onPcm(bytes)
                sink.onDone()
            }
            "wav" -> emitWav(bytes, sink)
            else -> sink.onError("暂不支持的音频格式: $format（内置支持 wav / pcm；mp3 等需自行扩展解码）")
        }
    }

    /** 解析最简 WAV：定位 fmt/data 子块，取采样率、声道与 PCM 数据。 */
    private fun emitWav(bytes: ByteArray, sink: PcmSink) {
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") {
            sink.onError("返回数据不是合法 WAV")
            return
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var sampleRate = 24_000
        var channels = 1
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4)
            val chunkSize = bb.getInt(pos + 4)
            val dataStart = pos + 8
            when (chunkId) {
                "fmt " -> {
                    channels = bb.getShort(dataStart + 2).toInt().coerceAtLeast(1)
                    sampleRate = bb.getInt(dataStart + 4)
                }
                "data" -> {
                    val end = (dataStart + chunkSize).coerceAtMost(bytes.size)
                    sink.onFormat(sampleRate, channels)
                    sink.onPcm(bytes.copyOfRange(dataStart, end))
                    sink.onDone()
                    return
                }
            }
            pos = dataStart + chunkSize + (chunkSize and 1) // 子块 2 字节对齐
        }
        sink.onError("WAV 缺少 data 块")
    }

    // ============ HTTP ============
    private fun httpPost(urlStr: String, headers: Map<String, String>, body: ByteArray): ByteArray {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.let { readAll(it) }?.toString(Charsets.UTF_8) }.getOrNull()
                error("HTTP $code ${err.orEmpty().take(300)}")
            }
            return conn.inputStream.use { readAll(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(urlStr: String, headers: Map<String, String>): ByteArray {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.let { readAll(it) }?.toString(Charsets.UTF_8) }.getOrNull()
                error("HTTP $code ${err.orEmpty().take(300)}")
            }
            return conn.inputStream.use { readAll(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun readAll(input: InputStream): ByteArray {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        while (true) {
            val n = input.read(tmp)
            if (n < 0) break
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }
}
