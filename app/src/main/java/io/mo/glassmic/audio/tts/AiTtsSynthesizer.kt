package io.mo.glassmic.audio.tts

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.R
import io.mo.glassmic.data.config.AppLocale
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.proto.TtsAiConfig
import io.mo.glassmic.proto.TtsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
 * - [TtsProvider.FISH]  ：Fish Audio，POST {base}/v1/tts（model 头 + reference_id 音色），返回 16bit PCM。
 *                         可选「自动标签」：先经 OpenAI 兼容 LLM 插入 Fish 情绪/音效标签（[FishAutoTag]）。
 *
 * （Anthropic 官方无 TTS 接口，故不支持。）
 * endpoint / model 留空时按 provider 用默认值。合成结果统一以 PCM16 经 [PcmSink] 回调。
 */
@Singleton
class AiTtsSynthesizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: ConfigStore,
    private val progress: TtsProgress
) : SpeechSynthesizer {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var job: Job? = null

    override fun synthesize(request: TtsRequest, sink: PcmSink) {
        job?.cancel()
        progress.start()
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
                    TtsProvider.FISH -> fish(ai, request, sink)
                    else -> openAi(ai, request, sink) // OPENAI 及默认
                }
            }.onFailure {
                // 被取消（超时 / 重新生成）：调用方已不再等结果，不再写进度也不回调
                if (it is CancellationException || !isActive) return@onFailure
                GlassLog.b("Tts") { "AI TTS 失败(${ai.provider}): ${it.message}" }
                progress.step(str(R.string.tts_step_failed, it.message.orEmpty()))
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
            TtsProvider.FISH -> FISH_MODELS // Fish 无模型列表接口，model 头可选值固定
            else -> openAiStyleModels(ai, "https://api.openai.com/v1", apiKeyHeader = false)
        }
        models.distinct().sortedWith(compareByDescending<String> { it.contains("tts", true) }.thenBy { it })
    }

    /** OpenAI 风格 GET {base}/models，返回 data[].id。base 从 endpoint 推导（剥掉已知动作后缀）。 */
    private suspend fun openAiStyleModels(ai: TtsAiConfig, default: String, apiKeyHeader: Boolean): List<String> {
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

    /** Gemini GET {base}/models，返回 models[].name（去掉 "models/" 前缀）。 */
    private suspend fun geminiModels(ai: TtsAiConfig): List<String> {
        val base = ai.endpoint.ifBlank { "https://generativelanguage.googleapis.com/v1beta" }.trimEnd('/')
        val bytes = httpGet("$base/models", geminiHeaders(ai))
        val arr = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            arr.optJSONObject(it)?.optString("name")?.removePrefix("models/")?.takeIf { s -> s.isNotBlank() }
        }
    }

    // ============ OpenAI /audio/speech ============
    private suspend fun openAi(ai: TtsAiConfig, req: TtsRequest, sink: PcmSink) {
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
    private suspend fun gemini(ai: TtsAiConfig, req: TtsRequest, sink: PcmSink) {
        val base = ai.endpoint.ifBlank { "https://generativelanguage.googleapis.com/v1beta" }.trimEnd('/')
        val model = ai.model.ifBlank { "gemini-2.5-flash-preview-tts" }
        val url = if (base.contains(":generateContent")) base else "$base/models/$model:generateContent"

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
        val bytes = httpPost(url, geminiHeaders(ai), body.toByteArray(Charsets.UTF_8))

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

    /** Gemini 的 Key 走 x-goog-api-key 头：放在 URL 查询串里会出现在代理、日志和报错信息中。 */
    private fun geminiHeaders(ai: TtsAiConfig): Map<String, String> = linkedMapOf(
        "Content-Type" to "application/json"
    ).apply { if (ai.apiKey.isNotBlank()) put("x-goog-api-key", ai.apiKey) }

    // ============ 小米 MiMo（/chat/completions + audio 参数） ============
    // 三种模型的请求体不同：
    // - mimo-v2.5-tts        ：audio.voice = 预置音色名；user 消息 = 可选风格指令
    // - mimo-v2.5-tts-voicedesign：音色描述放 user 消息，audio.optimize_text_preview=true，无 voice
    // - mimo-v2.5-tts-voiceclone ：audio.voice = 参考音频 base64 data URI
    private suspend fun mimo(ai: TtsAiConfig, req: TtsRequest, sink: PcmSink) {
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

    // ============ Fish Audio /v1/tts ============
    private suspend fun fish(ai: TtsAiConfig, req: TtsRequest, sink: PcmSink) {
        val sampleRate = if (ai.sampleRate > 0) ai.sampleRate else 44_100
        val text = when {
            // 未开自动标签：只去 emoji，*…* 原样保留（可能是算式等正文，不当动作删）
            !ai.fishAutoTag -> FishAutoTag.stripUnspeakable(req.text, actions = false).ifBlank { req.text }
            // 文本里已有 Fish 标签（通常是上次优化后回填到输入框的结果）→ 直接合成，省掉一次 LLM 往返
            FishAutoTag.hasFishTags(req.text) -> {
                step(str(R.string.tts_step_already_tagged))
                FishAutoTag.keepFishTags(FishAutoTag.singleLine(req.text))
            }
            else -> fishAutoTag(ai, req.text)
        }
        val body = JSONObject()
            .put("text", text)
            // 用裸 PCM（16bit 单声道）而非 WAV：流式 WAV 头的 data 长度可能是占位值，解析不可靠
            .put("format", "pcm")
            .put("sample_rate", sampleRate)
            .put("normalize", true)
            .put("latency", "normal")
            .put("temperature", FISH_TEMPERATURE)
            .put("top_p", 0.7)
            .put("chunk_length", 200)
        // 音色留空则不传 reference_id，由 Fish 使用默认音色
        ai.voice.trim().takeIf { it.isNotEmpty() }?.let { body.put("reference_id", it) }
        val model = ai.model.ifBlank { FISH_MODELS.first() }
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer ${ai.apiKey}",
            "model" to model
        )
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        step(str(R.string.tts_step_fish_request, model))
        val t0 = System.currentTimeMillis()
        val audio = try {
            httpPost("${fishBase(ai)}/v1/tts", headers, payload)
        } catch (e: java.io.IOException) {
            // 网络抖动（如 connection abort）重试一次；HTTP 错误码不重试。
            // step 先检查取消：超时 / 重新生成后不再重发，免得白耗额度
            step(str(R.string.tts_step_retry, e.message ?: e.javaClass.simpleName))
            httpPost("${fishBase(ai)}/v1/tts", headers, payload)
        }
        if (audio.isEmpty()) error(str(R.string.tts_step_fish_empty))
        step(str(R.string.tts_step_fish_done, seconds(t0), audio.size / 2.0 / sampleRate))
        decodeAudio("pcm", sampleRate, audio, sink)
    }

    /** Fish 接口根地址：endpoint 留空用官方地址，兼容填了 /v1 或 /v1/tts 的写法。 */
    private fun fishBase(ai: TtsAiConfig): String =
        ai.endpoint.ifBlank { "https://api.fish.audio" }.trimEnd('/')
            .removeSuffix("/tts").removeSuffix("/v1").trimEnd('/')

    /**
     * 拉取 Fish 声音列表：[query] 为空时取自己账号下的声音，否则按名称搜索公开声音。
     * 返回 (名称, 声音ID)。
     */
    suspend fun fetchFishVoices(query: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val ai = configStore.current().tts.ai
        require(ai.apiKey.isNotBlank()) { str(R.string.ai_tts_fish_need_key) }
        val q = query.trim()
        val params = if (q.isEmpty()) "self=true&page_size=100"
                     else "title=${URLEncoder.encode(q, "UTF-8")}&page_size=30"
        val bytes = httpGet("${fishBase(ai)}/model?$params", mapOf("Authorization" to "Bearer ${ai.apiKey}"))
        val items = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("items") ?: JSONArray()
        (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("_id").trim()
            if (id.isEmpty()) return@mapNotNull null
            // 名称里的逗号 / 换行会打断「名称:ID」预设格式
            val title = o.optString("title").replace(Regex("[,，\\s]+"), " ").trim()
            title.ifEmpty { id } to id
        }.distinctBy { it.second }
    }

    /**
     * 用 OpenAI 兼容 LLM 给文本插入 Fish 内置标签。
     * 未配置 key 或调用失败时退回原文（去掉 emoji / *动作*），不影响合成。
     */
    private suspend fun fishAutoTag(ai: TtsAiConfig, input: String): String {
        val fallback = FishAutoTag.stripUnspeakable(input).ifBlank { input }
        if (ai.llmApiKey.isBlank()) {
            GlassLog.b("Tts") { "Fish 自动标签：未填写 LLM API Key，跳过" }
            step(str(R.string.tts_step_llm_no_key))
            return fallback
        }
        val model = ai.llmModel.ifBlank { "deepseek-chat" }
        step(str(R.string.tts_step_llm_request, model))
        val t0 = System.currentTimeMillis()
        return runCatching {
            val base = ai.llmEndpoint.ifBlank { "https://api.deepseek.com/v1" }.trimEnd('/')
            val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            val body = JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", FishAutoTag.systemPrompt(ai.fishHumanize)))
                        .put(JSONObject().put("role", "user").put("content", input))
                )
                .put("temperature", LLM_TEMPERATURE)
                .put("max_tokens", 800)
                .put("stream", false)
                .toString()
            val headers = linkedMapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer ${ai.llmApiKey}"
            )
            val bytes = httpPost(url, headers, body.toByteArray(Charsets.UTF_8))
            val content = JSONObject(String(bytes, Charsets.UTF_8))
                .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content").orEmpty().trim().trim('"')
            val tagged = FishAutoTag.keepFishTags(FishAutoTag.stripUnspeakable(FishAutoTag.singleLine(content)))
            if (FishAutoTag.hasSpeakableText(tagged)) {
                step(str(R.string.tts_step_llm_done, seconds(t0)))
                progress.publishEnhanced(input, tagged)
                tagged
            } else {
                step(str(R.string.tts_step_llm_unusable))
                fallback
            }
        }.getOrElse {
            // 取消不算 LLM 失败：不能退回原文接着去请求 Fish
            if (it is CancellationException) throw it
            GlassLog.b("Tts") { "Fish 自动标签失败，使用原文: ${it.message}" }
            step(str(R.string.tts_step_llm_failed, it.message.orEmpty()))
            fallback
        }.also { GlassLog.b("Tts") { "Fish 文本: ${it.take(20)}" } }
    }

    /**
     * 写一行生成进度。先检查本次合成是否已被取消：HTTP 是阻塞调用，取消后请求仍会跑完，
     * 不检查的话旧任务会在「已超时」后继续写日志、甚至发起重试。
     */
    private suspend fun step(message: String) {
        currentCoroutineContext().ensureActive()
        progress.step(message)
    }

    private fun str(resId: Int, vararg args: Any): String = AppLocale.string(context, resId, *args)

    private fun seconds(sinceMs: Long): Double = (System.currentTimeMillis() - sinceMs) / 1000.0

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
    private suspend fun httpPost(urlStr: String, headers: Map<String, String>, body: ByteArray): ByteArray {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return exchange(conn) { it.outputStream.use { out -> out.write(body) } }
    }

    private suspend fun httpGet(urlStr: String, headers: Map<String, String>): ByteArray {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return exchange(conn) {}
    }

    /**
     * 执行一次阻塞 HTTP 交换，并让它真正可取消：协程被取消（超时 / 重新生成）时由旁路协程
     * 断开连接，阻塞中的读写随即抛异常退出，而不是把整段音频下载完、白耗流量和额度。
     * 响应体有上限，异常大的响应不会把进程内存撑爆。
     */
    private suspend fun exchange(conn: HttpURLConnection, send: (HttpURLConnection) -> Unit): ByteArray =
        coroutineScope {
            val watchdog = launch {
                try { awaitCancellation() } finally { conn.disconnect() }
            }
            try {
                send(conn)
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = runCatching {
                        conn.errorStream?.let { readAll(it, MAX_ERROR_BYTES) }?.toString(Charsets.UTF_8)
                    }.getOrNull()
                    error("HTTP $code ${err.orEmpty().take(300)}")
                }
                val declared = conn.contentLengthLong
                if (declared > MAX_RESPONSE_BYTES) error("响应过大（${declared / 1024 / 1024} MB），已放弃")
                conn.inputStream.use { readAll(it, MAX_RESPONSE_BYTES) }
            } catch (e: java.io.IOException) {
                // 被取消导致的断连按取消处理，不当成网络错误去重试或上报。
                currentCoroutineContext().ensureActive()
                throw e
            } finally {
                watchdog.cancel()
                conn.disconnect()
            }
        }

    private companion object {
        /** Fish model 头可选值，第一个为默认（免费版 S2.1 Pro）。 */
        val FISH_MODELS = listOf("s2.1-pro-free", "s2.1-pro", "s2-pro", "s1")

        /** Fish 合成温度：保留一定随机性，语气更自然。 */
        const val FISH_TEMPERATURE = 0.7
        /** 自动标签 LLM 温度：取低值，标签与措辞更稳定、少改写。 */
        const val LLM_TEMPERATURE = 0.2

        /** 单次响应上限。24kHz 16bit 单声道约 22 分钟，Gemini 的 base64 JSON 也留足余量。 */
        const val MAX_RESPONSE_BYTES = 64L * 1024 * 1024
        const val MAX_ERROR_BYTES = 64L * 1024
    }

    private fun readAll(input: InputStream, limit: Long): ByteArray {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(tmp)
            if (n < 0) break
            total += n
            if (total > limit) error("响应超过 ${limit / 1024 / 1024} MB 上限，已放弃")
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }
}
