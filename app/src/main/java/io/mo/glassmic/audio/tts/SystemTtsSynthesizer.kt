package io.mo.glassmic.audio.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.log.GlassLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统 TextToSpeech 合成器。
 *
 * 关键点：用 [TextToSpeech.synthesizeToFile] 而不是 speak()，这样音频不会从扬声器播出，
 * 但引擎仍会通过 [UtteranceProgressListener.onAudioAvailable] 把 PCM 分块回调出来，
 * 我们据此把语音喂给虚拟麦克风管线（需要引擎实现 onAudioAvailable，Google TTS 支持）。
 *
 * 合成写入的临时 WAV 文件仅为满足 API 需要，回调结束即删除。
 */
@Singleton
class SystemTtsSynthesizer @Inject constructor(
    @ApplicationContext private val context: Context
) : SpeechSynthesizer {

    private val seq = AtomicLong(0L)
    private val sinks = ConcurrentHashMap<String, PcmSink>()
    private val files = ConcurrentHashMap<String, File>()

    @Volatile private var ready = false
    @Volatile private var initFailed = false
    // 引擎初始化前进来的请求，就绪后补发（只保留最后一个）。
    @Volatile private var pending: Pair<TtsRequest, PcmSink>? = null

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            GlassLog.b("Tts") { "系统 TTS 就绪" }
            pending?.let { (req, sink) ->
                pending = null
                synthesize(req, sink)
            }
        } else {
            initFailed = true
            GlassLog.b("Tts") { "系统 TTS 初始化失败: status=$status" }
            pending?.let { (_, sink) -> sink.onError("系统 TTS 初始化失败") }
            pending = null
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onBeginSynthesis(
                utteranceId: String?,
                sampleRateInHz: Int,
                audioFormat: Int,
                channelCount: Int
            ) {
                val id = utteranceId ?: return
                sinks[id]?.onFormat(sampleRateInHz, channelCount.coerceAtLeast(1))
            }

            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                val id = utteranceId ?: return
                if (audio != null && audio.isNotEmpty()) sinks[id]?.onPcm(audio)
            }

            override fun onDone(utteranceId: String?) {
                val id = utteranceId ?: return
                sinks.remove(id)?.onDone()
                cleanupFile(id)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) = failUtterance(utteranceId, "合成错误")

            override fun onError(utteranceId: String?, errorCode: Int) =
                failUtterance(utteranceId, "合成错误 code=$errorCode")
        })
    }

    override fun synthesize(request: TtsRequest, sink: PcmSink) {
        if (initFailed) {
            sink.onError("系统 TTS 不可用")
            return
        }
        if (!ready) {
            pending = request to sink
            return
        }
        if (request.text.isBlank()) {
            sink.onError("文本为空")
            return
        }

        runCatching {
            if (request.rate > 0f) tts.setSpeechRate(request.rate)
            if (request.pitch > 0f) tts.setPitch(request.pitch)
            if (request.voice.isNotBlank()) {
                val locale = java.util.Locale.forLanguageTag(request.voice)
                if (locale.language.isNotBlank()) tts.language = locale
            }
        }.onFailure { GlassLog.b("Tts") { "应用 TTS 参数失败: ${it.message}" } }

        val id = "glass-tts-${seq.incrementAndGet()}"
        val file = File(context.cacheDir, "$id.wav")
        sinks[id] = sink
        files[id] = file

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        val result = runCatching { tts.synthesizeToFile(request.text, params, file, id) }
            .getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) {
            sinks.remove(id)
            cleanupFile(id)
            sink.onError("合成启动失败")
        }
    }

    override fun cancel() {
        runCatching { tts.stop() }
        sinks.keys.toList().forEach { cleanupFile(it) }
        sinks.clear()
    }

    override fun release() {
        cancel()
        runCatching { tts.shutdown() }
    }

    private fun failUtterance(utteranceId: String?, message: String) {
        val id = utteranceId ?: return
        sinks.remove(id)?.onError(message)
        cleanupFile(id)
    }

    private fun cleanupFile(id: String) {
        files.remove(id)?.let { runCatching { it.delete() } }
    }
}
