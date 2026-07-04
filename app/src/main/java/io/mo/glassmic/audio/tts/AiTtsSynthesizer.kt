package io.mo.glassmic.audio.tts

import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 在线 AI TTS 合成器（预留接口）。
 *
 * 走一个通用的「POST 文本 → 返回音频字节」协议，兼容 OpenAI /audio/speech 风格：
 *   POST {endpoint}
 *   Authorization: Bearer {apiKey}
 *   { "model": ..., "voice": ..., "input": text, "response_format": format }
 *
 * 返回音频按 [io.mo.glassmic.proto.TtsAiConfig.getFormat] 解析：
 * - pcm16：裸 PCM16（需在配置里给出 sample_rate）
 * - wav  ：解析 WAV 头拿采样率/声道后取 data 块
 * - 其它（mp3/opus…）：暂未内置解码，回调错误——这里是后续扩展点。
 *
 * 该实现默认隐藏在设置里，仅当用户在（隐藏的）AI TTS 配置区填好并开启后才会被 [TtsSynthesizerFactory] 选中。
 */
@Singleton
class AiTtsSynthesizer @Inject constructor(
    private val configStore: ConfigStore
) : SpeechSynthesizer {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var job: Job? = null

    override fun synthesize(request: TtsRequest, sink: PcmSink) {
        job?.cancel()
        job = scope.launch {
            val ai = runCatching { configStore.current().tts.ai }.getOrNull()
            if (ai == null || !ai.enabled || ai.endpoint.isBlank()) {
                sink.onError("AI TTS 未配置")
                return@launch
            }
            runCatching { request(ai.endpoint, ai.apiKey, buildBody(ai, request)) }
                .onSuccess { bytes -> decodeAndEmit(bytes, ai.format, ai.sampleRate, sink) }
                .onFailure {
                    GlassLog.b("Tts") { "AI TTS 请求失败: ${it.message}" }
                    sink.onError("AI TTS 请求失败: ${it.message}")
                }
        }
    }

    override fun cancel() {
        job?.cancel()
        job = null
    }

    // ============ 网络 ============
    private fun request(endpoint: String, apiKey: String, body: String): ByteArray {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull()
                error("HTTP $code ${err.orEmpty().take(200)}")
            }
            return conn.inputStream.use { input ->
                val buf = ByteArrayOutputStream()
                val tmp = ByteArray(8192)
                while (true) {
                    val n = input.read(tmp)
                    if (n < 0) break
                    buf.write(tmp, 0, n)
                }
                buf.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun buildBody(ai: io.mo.glassmic.proto.TtsAiConfig, req: TtsRequest): String {
        val fmt = ai.format.ifBlank { "pcm16" }
        return """{"model":"${esc(ai.model)}","voice":"${esc(ai.voice)}",""" +
            """"input":"${esc(req.text)}","response_format":"${esc(fmt)}"}"""
    }

    private fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }

    // ============ 解码 ============
    private fun decodeAndEmit(bytes: ByteArray, format: String, cfgSampleRate: Int, sink: PcmSink) {
        when (format.ifBlank { "pcm16" }.lowercase()) {
            "pcm16", "pcm", "raw" -> {
                val sr = cfgSampleRate.takeIf { it > 0 } ?: 24_000
                sink.onFormat(sr, 1)
                sink.onPcm(bytes)
                sink.onDone()
            }
            "wav" -> emitWav(bytes, sink)
            else -> sink.onError("暂不支持的 AI 音频格式: $format（可扩展；建议用 pcm16 或 wav）")
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
}
