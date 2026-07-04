package io.mo.glassmic.audio.tts

/**
 * 语音合成抽象。把"文字 → PCM 音频"这一步与具体引擎解耦。
 *
 * 目前有两种实现：
 * - [SystemTtsSynthesizer]：系统 TextToSpeech，离线、零依赖、随时可用。
 * - [AiTtsSynthesizer]：在线 AI TTS（预留接口，暂时隐藏在设置里）。
 *
 * 约定：合成结果以 PCM16（小端、交错）分块经 [PcmSink] 回调返回，
 * 绝不直接播放到扬声器——音频只喂给虚拟麦克风管线。
 */
interface SpeechSynthesizer {

    /**
     * 开始合成 [request]。实现应立即返回（异步产出），
     * 通过 [sink] 依次回调 onFormat → onPcm* → onDone / onError。
     */
    fun synthesize(request: TtsRequest, sink: PcmSink)

    /** 取消当前合成（如有）。不释放底层引擎。 */
    fun cancel()

    /** 释放底层引擎资源。App 退出时调用。 */
    fun release() {}
}

/** 一次合成请求。rate/pitch 为 0 时按引擎默认处理。 */
data class TtsRequest(
    val text: String,
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val voice: String = ""
)

/**
 * PCM 输出汇。所有回调可能来自任意线程，实现需自行保证线程安全。
 */
interface PcmSink {
    /** 合成开始，告知采样率/声道数。onPcm 的数据即为此格式的 PCM16。 */
    fun onFormat(sampleRate: Int, channels: Int)

    /** 一块 PCM16（小端、交错）数据。 */
    fun onPcm(chunk: ByteArray)

    /** 合成正常结束。 */
    fun onDone()

    /** 合成失败。 */
    fun onError(message: String)
}
