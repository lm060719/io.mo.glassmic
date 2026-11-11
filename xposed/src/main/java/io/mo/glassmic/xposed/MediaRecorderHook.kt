package io.mo.glassmic.xposed

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.mo.glassmic.core.model.SourceType
import java.io.FileDescriptor
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 纯音频 MediaRecorder 接管（最小原型）。
 *
 * 背景：Snapchat 聊天语音（classes6.dex LtE0;->c()/g()）用 MediaRecorder.setAudioSource(1)
 * → prepare → start，PCM 在**系统媒体服务进程**里被 callback 型 native AudioRecord 采集，
 * GlassMic 应用进程内的 AudioRecord / AAudio / OpenSL Hook 都覆盖不到这条链。
 *
 * 本 Hook 在应用进程内拦截 android.media.MediaRecorder：当音源决策不是 REAL_MIC、且是
 * **纯音频 + AAC 编码 + MPEG_4/3GP 容器**时，不让系统媒体服务采集真实麦克风，改为用应用内
 * AudioRecord（已被 [AudioRecordHook] 覆盖 → 自动填入虚拟 PCM）+ MediaCodec(AAC) + MediaMuxer
 * 写到应用原本指定的同一输出。其余情况一律放行真实录音（= 当前行为，安全默认）。
 *
 * API 101 与 legacy API 82 两个入口共用这一份实现（经 [MethodHooker] 适配）。
 *
 * 不处理：视频 / Camcorder 分支、AMR / 裸 AAC-ADTS 容器、setMaxDuration 的 info 回调投递。
 */
object MediaRecorderHook {

    private const val TAG = "GlassMic-MediaRec"

    // MediaRecorder.AudioEncoder
    private const val ENC_AAC = 3
    private const val ENC_HE_AAC = 4
    private const val ENC_AAC_ELD = 5
    // MediaRecorder.OutputFormat
    private const val OUT_THREE_GPP = 1
    private const val OUT_MPEG_4 = 2

    private const val DEFAULT_SAMPLE_RATE = 44100
    private const val DEFAULT_CHANNELS = 1
    private const val DEFAULT_BITRATE = 128_000
    private const val CODEC_TIMEOUT_US = 10_000L

    private val states = WeakHashMap<MediaRecorder, RecorderState>()

    @Volatile private var installed = false

    private class RecorderState {
        var audioSource: Int = -1
        var hasVideoSource: Boolean = false
        var outputFormat: Int = -1
        var audioEncoder: Int = -1
        var sampleRate: Int = DEFAULT_SAMPLE_RATE
        var channels: Int = DEFAULT_CHANNELS
        var bitRate: Int = DEFAULT_BITRATE
        var outputPath: String? = null
        var outputFd: FileDescriptor? = null
        var maxDurationMs: Int = 0

        @Volatile var pipeline: CapturePipeline? = null
        val takeover: Boolean get() = pipeline != null
    }

    /** libxposed API 101 入口。 */
    fun install(api: XposedInterface, appCtx: Context, callerPkg: String) =
        install(ModernHooker(api, TAG), appCtx, callerPkg)

    /** legacy API 82 入口。 */
    fun installLegacy(appCtx: Context, callerPkg: String) =
        install(LegacyHooker(TAG), appCtx, callerPkg)

    private fun install(api: MethodHooker, appCtx: Context, callerPkg: String) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            if (!XposedHookGate.tryMarkMediaRecorderHookInstalled()) {
                installed = true
                api.log(Log.INFO, "skip duplicate MediaRecorder hook in $callerPkg")
                return
            }
            installed = true
        }

        val cls = MediaRecorder::class.java
        val intp = Int::class.javaPrimitiveType!!
        val longp = Long::class.javaPrimitiveType!!

        hook(api, appCtx, callerPkg, find(cls, "setAudioSource", intp)) { _, _, chain ->
            stateOf(chain).audioSource = chain.arg(0) as Int
            HookDecision.Proceed
        }
        hook(api, appCtx, callerPkg, find(cls, "setVideoSource", intp)) { _, _, chain ->
            stateOf(chain).hasVideoSource = true
            HookDecision.Proceed
        }
        hook(api, appCtx, callerPkg, find(cls, "setOutputFormat", intp)) { _, _, chain ->
            stateOf(chain).outputFormat = chain.arg(0) as Int
            HookDecision.Proceed
        }
        hook(api, appCtx, callerPkg, find(cls, "setAudioEncoder", intp)) { _, _, chain ->
            stateOf(chain).audioEncoder = chain.arg(0) as Int
            HookDecision.Proceed
        }
        hook(api, appCtx, callerPkg, find(cls, "setAudioSamplingRate", intp)) { _, _, chain ->
            (chain.arg(0) as Int).let { if (it > 0) stateOf(chain).sampleRate = it }
            HookDecision.Proceed
        }
        hook(api, appCtx, callerPkg, find(cls, "setAudioChannels", intp)) { _, _, chain ->
            (chain.arg(0) as Int).let { if (it > 0) stateOf(chain).channels = it }
            HookDecision.Proceed
        }
        hook(api, appCtx, callerPkg, find(cls, "setAudioEncodingBitRate", intp)) { _, _, chain ->
            (chain.arg(0) as Int).let { if (it > 0) stateOf(chain).bitRate = it }
            HookDecision.Proceed
        }
        hook(api, appCtx, callerPkg, find(cls, "setMaxDuration", intp)) { _, _, chain ->
            stateOf(chain).maxDurationMs = chain.arg(0) as Int
            HookDecision.Proceed
        }
        hook(api, appCtx, callerPkg, find(cls, "setOutputFile", String::class.java)) { _, _, chain ->
            stateOf(chain).apply { outputPath = chain.arg(0) as? String; outputFd = null }
            HookDecision.Proceed
        }
        hook(api, appCtx, callerPkg, find(cls, "setOutputFile", FileDescriptor::class.java)) { _, _, chain ->
            stateOf(chain).apply { outputFd = chain.arg(0) as? FileDescriptor; outputPath = null }
            HookDecision.Proceed
        }
        find(cls, "setOutputFile", java.io.File::class.java)?.let { m ->
            hook(api, appCtx, callerPkg, m) { _, _, chain ->
                stateOf(chain).apply {
                    outputPath = (chain.arg(0) as? java.io.File)?.absolutePath; outputFd = null
                }
                HookDecision.Proceed
            }
        }

        hook(api, appCtx, callerPkg, find(cls, "prepare"), ::onPrepare)
        hook(api, appCtx, callerPkg, find(cls, "start"), ::onStart)
        hook(api, appCtx, callerPkg, find(cls, "stop"), ::onStop)
        hook(api, appCtx, callerPkg, find(cls, "reset"), ::onResetOrRelease)
        hook(api, appCtx, callerPkg, find(cls, "release"), ::onResetOrRelease)
        hook(api, appCtx, callerPkg, find(cls, "getMaxAmplitude"), ::onGetMaxAmplitude)

        api.log(Log.INFO, "installed in $callerPkg")
    }

    // -------- 生命周期 --------

    private fun onPrepare(appCtx: Context, pkg: String, chain: HookCall): HookDecision {
        val st = stateOf(chain)

        if (XBridge.resolveSource(appCtx, pkg) == SourceType.REAL_MIC) return HookDecision.Proceed
        if (!canTakeOver(st)) return HookDecision.Proceed

        val pipeline = runCatching { CapturePipeline(st) }
            .onFailure {
                Log.w(TAG, "takeover init failed, fall back to real recorder: ${it.message}")
            }
            .getOrNull() ?: return HookDecision.Proceed

        st.pipeline = pipeline
        XBridge.reportDiagnosticEvent(appCtx, pkg, "mediarecorder_takeover", android.os.Bundle().apply {
            putInt("sample_rate", st.sampleRate)
            putInt("channels", st.channels)
            putInt("encoder", st.audioEncoder)
            putInt("output_format", st.outputFormat)
        })
        Log.i(TAG, "takeover prepared pkg=$pkg sr=${st.sampleRate} ch=${st.channels}")
        return HookDecision.Replace(null)
    }

    private fun onStart(appCtx: Context, pkg: String, chain: HookCall): HookDecision {
        val st = stateOf(chain)
        val pipeline = st.pipeline ?: return HookDecision.Proceed
        return if (runCatching { pipeline.start() }.isSuccess) {
            HookDecision.Replace(null)
        } else {
            // 启动失败无法再回退到真麦（已跳过真实 prepare），只能清理并抛给上层默认异常。
            runCatching { pipeline.release() }
            st.pipeline = null
            HookDecision.Proceed
        }
    }

    private fun onStop(appCtx: Context, pkg: String, chain: HookCall): HookDecision {
        val st = stateOf(chain)
        val pipeline = st.pipeline ?: return HookDecision.Proceed
        runCatching { pipeline.stop() }.onFailure { Log.w(TAG, "stop error: ${it.message}") }
        st.pipeline = null
        return HookDecision.Replace(null)
    }

    private fun onResetOrRelease(appCtx: Context, pkg: String, chain: HookCall): HookDecision {
        val st = stateOf(chain)
        st.pipeline?.let { runCatching { it.release() } }
        st.pipeline = null
        return HookDecision.Proceed
    }

    private fun onGetMaxAmplitude(appCtx: Context, pkg: String, chain: HookCall): HookDecision {
        val pipeline = stateOf(chain).pipeline ?: return HookDecision.Proceed
        return HookDecision.Replace(pipeline.drainMaxAmplitude())
    }

    private fun canTakeOver(st: RecorderState): Boolean {
        if (st.hasVideoSource) return false
        if (st.audioEncoder !in intArrayOf(ENC_AAC, ENC_HE_AAC, ENC_AAC_ELD)) return false
        if (st.outputFormat !in intArrayOf(OUT_MPEG_4, OUT_THREE_GPP)) return false
        return st.outputPath != null || st.outputFd != null
    }

    // -------- 工具 --------

    private fun stateOf(chain: HookCall): RecorderState {
        val recorder = chain.thisObject as MediaRecorder
        synchronized(states) {
            return states.getOrPut(recorder) { RecorderState() }
        }
    }

    private fun find(cls: Class<*>, name: String, vararg paramTypes: Class<*>?): Method? =
        findMethod(cls, name, *paramTypes)

    private fun hook(
        api: MethodHooker,
        appCtx: Context,
        callerPkg: String,
        method: Method?,
        handler: (Context, String, HookCall) -> HookDecision
    ) {
        method ?: return
        api.hook(method) { call -> handler(appCtx, callerPkg, call) }
    }

    /**
     * AudioRecord(已被 Hook → 虚拟 PCM) → MediaCodec(AAC) → MediaMuxer，写到应用指定输出。
     * 实时节奏由 AudioRecord.read 的 Hook 继承（FILE 阻塞 / SILENCE paceSilence），这里不额外节流。
     */
    private class CapturePipeline(private val st: RecorderState) {
        private val sampleRate = st.sampleRate
        private val channels = st.channels.coerceIn(1, 2)

        private val record: AudioRecord
        private val codec: MediaCodec
        private val muxer: MediaMuxer

        @Volatile private var running = false
        @Volatile private var finished = false
        private var thread: Thread? = null
        private var trackIndex = -1
        private var muxerStarted = false
        private var framesWritten = 0L
        private val peak = AtomicInteger(0)

        init {
            val channelMask =
                if (channels >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                error("AudioRecord not initialized")
            }

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, st.bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)
            }
            codec = runCatching {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
            }.getOrElse {
                runCatching { record.release() }
                throw it
            }

            val muxerFormat =
                if (st.outputFormat == OUT_THREE_GPP) MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP
                else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            muxer = runCatching {
                val path = st.outputPath
                if (path != null) MediaMuxer(path, muxerFormat)
                else MediaMuxer(st.outputFd!!, muxerFormat)
            }.getOrElse {
                runCatching { codec.release() }
                runCatching { record.release() }
                throw it
            }
        }

        fun start() {
            codec.start()
            record.startRecording()
            running = true
            thread = Thread({ loop() }, "GlassMic-MediaRecCap").apply { start() }
        }

        private fun loop() {
            val info = MediaCodec.BufferInfo()
            val pcm = ByteArray(2048 * channels)
            val maxFrames =
                if (st.maxDurationMs > 0) st.maxDurationMs.toLong() * sampleRate / 1000 else Long.MAX_VALUE
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val n = record.read(pcm, 0, pcm.size)
                        val eos = !running || n < 0 || framesWritten >= maxFrames
                        if (n > 0) trackPeak(pcm, n)
                        feedInput(pcm, if (n > 0) n else 0, eos)
                        if (eos) inputDone = true
                    }
                    outputDone = drainOutput(info)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "capture loop error: ${t.message}")
            }
        }

        private fun feedInput(pcm: ByteArray, len: Int, eos: Boolean) {
            val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (index < 0) return
            val input = codec.getInputBuffer(index) ?: return
            input.clear()
            if (len > 0) input.put(pcm, 0, len)
            val ptsUs = framesWritten * 1_000_000L / sampleRate
            framesWritten += len / (2L * channels)
            codec.queueInputBuffer(
                index, 0, len, ptsUs,
                if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            )
        }

        /** @return true 表示收到 END_OF_STREAM，应结束。 */
        private fun drainOutput(info: MediaCodec.BufferInfo): Boolean {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    index >= 0 -> {
                        val out = codec.getOutputBuffer(index)
                        if (out != null && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted
                        ) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, out, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
                    }
                }
            }
        }

        private fun trackPeak(pcm: ByteArray, len: Int) {
            val bb = ByteBuffer.wrap(pcm, 0, len).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var p = 0
            while (bb.hasRemaining()) {
                val a = kotlin.math.abs(bb.get().toInt())
                if (a > p) p = a
            }
            // 保留区间最大值
            var cur = peak.get()
            while (p > cur && !peak.compareAndSet(cur, p)) cur = peak.get()
        }

        fun drainMaxAmplitude(): Int = peak.getAndSet(0)

        fun stop() {
            running = false
            thread?.join(3000)
            finalizeOutput()
        }

        fun release() {
            running = false
            thread?.join(1500)
            finalizeOutput()
        }

        private fun finalizeOutput() {
            if (finished) return
            finished = true
            runCatching { record.stop() }
            runCatching { record.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }
}
