package io.mo.glassmic.data.diag

import android.content.Context
import android.os.Build
import androidx.core.content.FileProvider
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.BuildConfig
import io.mo.glassmic.audio.SharedPcmPublisher
import io.mo.glassmic.core.Constants
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.runtime.AudioStatsRepository
import io.mo.glassmic.data.runtime.BootGateRepository
import io.mo.glassmic.data.runtime.EffectiveSourceResolver
import io.mo.glassmic.data.runtime.HookStatusRepository
import io.mo.glassmic.data.runtime.SafeModeRepository
import io.mo.glassmic.data.runtime.VisibilityCompatRepository
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.memory.FairMemoryController
import io.mo.glassmic.memory.MemoryProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 诊断包导出。
 *
 * 默认脱敏：不写入用户路径、不写入 App 列表、不写入音频文件。
 * 内容仅限：环境信息、配置摘要、最近日志、安全模式记录、Xposed ping 状态、决策历史与拦截统计。
 *
 * 产物：filesDir/diagnostics/glassmic-diag-YYYYMMDD-HHmmss.zip
 * 返回值是可分享的 content:// Uri（用 FileProvider，需要 res/xml/file_paths.xml）。
 */
@Singleton
class DiagnosticBundler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: ConfigStore,
    private val safeModeRepo: SafeModeRepository,
    private val bootGate: BootGateRepository,
    private val hookStatusRepo: HookStatusRepository,
    private val audioStatsRepo: AudioStatsRepository,
    private val sourceResolver: EffectiveSourceResolver,
    private val fairMemory: FairMemoryController,
    private val visibilityCompatRepo: VisibilityCompatRepository,
    private val audioDao: AudioDao,
    private val publisher: Lazy<SharedPcmPublisher>,
    private val ksu: io.mo.glassmic.root.KsuController,
    private val audioPolicy: io.mo.glassmic.root.AudioPolicyController,
    private val playbackSessionDiagnostics: PlaybackSessionDiagnostics
) {

    suspend fun export(): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "diagnostics").apply { if (!exists()) mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(dir, "glassmic-diag-$ts.zip")

        ZipOutputStream(FileOutputStream(target)).use { zip ->
            writeEntry(zip, "summary.json", buildSummary())
            writeEntry(zip, "audio_policy.json", audioPolicy.diagnostics().toString(2))
            writeEntry(zip, "log.txt", GlassLog.dump())
            writeEntry(zip, "safe_mode.json", buildSafeMode())
            writeEntry(zip, "hook_status.json", buildHook())
            writeEntry(zip, "audio_stats.json", buildAudioStats())
            writeEntry(zip, "publisher_stats.json", publisher.get().diagnostics().toString(2))
            writeEntry(zip, "playback_session.json", playbackSessionDiagnostics.diagnostics().toString(2))
            writeEntry(zip, "audio_timeline.json", audioStatsRepo.diagnosticTimeline() ?: "[]")
            writeEntry(zip, "audio_diagnosis.json", buildAudioDiagnosis())
            writeEntry(zip, "decisions.json", buildDecisions())
            writeEntry(zip, "memory.json", buildMemory())
        }
        GlassLog.b("Diag") { "诊断包已生成: ${target.name} size=${target.length()}" }
        target
    }

    fun shareUri(file: File) =
        FileProvider.getUriForFile(context, "${Constants.APP_PACKAGE}.fileprovider", file)

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private suspend fun buildSummary(): String {
        val cfg = configStore.current()
        val clip = if (cfg.currentAudioId.isNotBlank()) audioDao.findClip(cfg.currentAudioId) else null

        return JSONObject().apply {
            put("generated_at", System.currentTimeMillis())
            put("app", JSONObject().apply {
                put("versionName", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
                put("debug", BuildConfig.DEBUG)
            })
            put("device", JSONObject().apply {
                put("sdk", Build.VERSION.SDK_INT)
                put("release", Build.VERSION.RELEASE)
                put("brand", Build.BRAND)
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("supported_abis", JSONArray(Build.SUPPORTED_ABIS))
                put("visibility_compat", visibilityCompatRepo.isEnabled())
            })
            if (cfg.injectionBackend == io.mo.glassmic.proto.InjectionBackend.KERNEL_SU) {
                val state = ksu.state.value
                put("ksu_controller", JSONObject().apply {
                    put("connected", state.connected)
                    put("busy", state.busy)
                    put("stopping", state.stopping)
                    put("has_error", state.error != null)
                    state.snapshot?.let { snapshot ->
                        put("module_version", snapshot.version)
                        put("mode", snapshot.mode)
                        put("paused", snapshot.paused)
                        put("target_count", snapshot.targets.size)
                        put("file_count", snapshot.files.size)
                    }
                })
            }
            put("native_hook_support", JSONArray().apply {
                put("NDK AAudio (libaaudio.so shadowhook)")
                put("OpenSL ES (libOpenSLES.so buffer queue)")
                put("AudioRecord (libaudioclient.so native read)")
            })
            put("config", JSONObject().apply {
                put("global_switch", cfg.globalSwitch)
                put("injection_backend", cfg.injectionBackend.name)
                put("scope_mode", cfg.scopeMode.name)
                put("playback_policy", cfg.playbackPolicy.name)
                put("onboarding_completed", cfg.onboardingCompleted)
                put("whitelist_count", cfg.whitelistCount)
                put("blacklist_count", cfg.blacklistCount)
                put("has_current_audio", cfg.currentAudioId.isNotBlank())
                put("appearance_theme", cfg.appearance.theme.name)
                put("appearance_glass", cfg.appearance.glassEffect)
                put("logging_level", cfg.logging.level.name)
                put("experimental_unlocked", cfg.experimental.unlocked)
            })
            put("active_audio_metadata", JSONObject().apply {
                put("has_clip", clip != null)
                if (clip != null) {
                    put("display_name", clip.displayName)
                    put("duration_ms", clip.durationMs)
                    put("size_bytes", clip.sizeBytes)
                    put("sample_rate", clip.sampleRate)
                    put("channels", clip.channels)
                    put("mime_type", clip.mimeType)
                }
            })
            put("boot_gate", JSONObject().apply {
                put("user_enabled_after_boot", bootGate.userEnabledAfterBoot())
            })
        }.toString(2)
    }

    private fun buildSafeMode(): String {
        val info = safeModeRepo.snapshot()
        return JSONObject().apply {
            put("active", info != null)
            if (info != null) {
                put("reason", info.reason.name)
                put("occurred_at", info.occurredAt)
            }
        }.toString(2)
    }

    /** 公平运行内存现场：当前水位 + 最近一次系统通知/回收 + 上次被查杀的记录。 */
    private fun buildMemory(): String {
        val s = MemoryProbe.snapshot()
        return JSONObject().apply {
            put("pss_kb", s.pssKb)
            put("java_heap_used_kb", s.heapUsedKb)
            put("java_heap_max_kb", s.heapMaxKb)
            put("java_heap_ratio", String.format(Locale.US, "%.2f", s.heapRatio))
            put("last_notice", fairMemory.lastNotice ?: "")
            put("last_reclaim", fairMemory.lastReclaim ?: "")
            put("last_kill_record", fairMemory.lastKillRecord() ?: "")
        }.toString(2)
    }

    private fun buildHook(): String {
        val s = hookStatusRepo.snapshot()
        return JSONObject().apply {
            put("activity", s.activity.name)
            put("last_ping_ms", s.lastPingMs)
            put("last_package", s.lastPackage ?: "")
            put("api", s.api)
            put("meaning", "Provider communication heartbeat; not proof of recording or replacement")
            val prefs = context.getSharedPreferences(Constants.XPOSED_STATUS_PREFS, Context.MODE_PRIVATE)
            put("package_states", runCatching {
                JSONObject(prefs.getString(Constants.XPOSED_STATUS_PACKAGES, "{}") ?: "{}")
            }.getOrElse { JSONObject() })
        }.toString(2)
    }

    private fun buildAudioStats(): String {
        val s = audioStatsRepo.snapshot()
        return JSONObject().apply {
            put("total_reads", s.totalReads)
            put("total_bytes", s.totalBytes)
            put("last_intercept_ms", s.lastInterceptMs)
            put(
                "last_intercept_age_ms",
                if (s.lastInterceptMs > 0L) {
                    (System.currentTimeMillis() - s.lastInterceptMs).coerceAtLeast(0L)
                } else {
                    JSONObject.NULL
                }
            )
            put("last_package", s.lastPackage ?: "")
            put("last_sample_rate", s.lastSampleRate)
            put("last_channels", s.lastChannels)
            audioStatsRepo.nativeDiagnostics()?.let { put("native_diagnostics", JSONObject(it)) }
            audioStatsRepo.pcmReadDiagnostics()?.let { put("pcm_read_diagnostics", JSONObject(it)) }
        }.toString(2)
    }

    /**
     * 把分散在各层的指标汇总成可机器/人工快速阅读的初步判断。
     * 这里只做证据归类，不把启发式结论当成绝对事实。
     */
    private fun buildAudioDiagnosis(): String {
        val publisherJson = publisher.get().diagnostics()
        val session = playbackSessionDiagnostics.diagnostics()
        val nativeRoot = runCatching {
            JSONObject(audioStatsRepo.nativeDiagnostics() ?: "{}")
        }.getOrElse { JSONObject() }
        val pcmRoot = runCatching {
            JSONObject(audioStatsRepo.pcmReadDiagnostics() ?: "{}")
        }.getOrElse { JSONObject() }

        val sessionStart = session.optLong("started_at", 0L)
        val publisherDropped = session.optLong(
            "publisher_total_dropped_bytes",
            publisherJson.optLong("total_dropped_bytes")
        )
        val observedConsumers = session.optInt(
            "observed_consumer_count",
            publisherJson.optInt("active_consumer_count")
        )

        val nativeLatest = nativeRoot.optJSONObject("latest") ?: JSONObject()
        val nativeLastUnderrun = nativeRoot.optJSONObject("last_underrun")
        val nativeGapInSession = sessionStart > 0L &&
            nativeLastUnderrun != null &&
            nativeLastUnderrun.optLong("time", 0L) >= sessionStart &&
            nativeLastUnderrun.optLong("missing_frames", 0L) > 0L
        val skippedInLatest = if (
            sessionStart > 0L && nativeLatest.optLong("time", 0L) >= sessionStart
        ) {
            nativeLatest.optLong("skipped_source_frames", 0L)
        } else {
            0L
        }

        val pcmLatest = pcmRoot.optJSONObject("latest") ?: JSONObject()
        val pcmInSession = sessionStart > 0L && pcmLatest.optLong("time", 0L) >= sessionStart
        val pcmShortReads = if (pcmInSession) pcmLatest.optLong("short_reads", 0L) else 0L
        val pcmZeroFill = if (pcmInSession) pcmLatest.optLong("zero_fill_pcm16_bytes", 0L) else 0L

        val integrity = publisherJson.optJSONObject("master_integrity") ?: JSONObject()
        val maxNearSilentMs = integrity.optLong("max_near_silent_run_ms", 0L)
        val captureActive = nativeLatest.optLong("reads", 0L) > 0L
        val pcmFdActive = nativeLatest.optBoolean("pcm_fd_active", false)

        val stage: String
        val summary: String
        when {
            publisherDropped > 0L -> {
                stage = "PUBLISHER_QUEUE"
                summary = "本次播放观察到 Publisher consumer 队列丢帧，优先检查慢 consumer、僵尸 consumer 或调度阻塞。"
            }
            nativeGapInSession || skippedInLatest > 0L -> {
                stage = "NATIVE_BUFFER"
                summary = "Publisher 未见会话级丢帧，但 native 层出现欠载或追帧丢弃，优先检查 pipe/ring buffer 供给。"
            }
            pcmShortReads > 0L || pcmZeroFill > 0L -> {
                stage = "JAVA_PIPE"
                summary = "Java AudioRecord pipe 出现短读或补零，优先检查 Provider consumer 写入与 XposedPcmReader。"
            }
            maxNearSilentMs >= 500L -> {
                stage = "SOURCE_OR_APP_DSP"
                summary = "Publisher 主输出自身出现较长近静音段；需结合源文件确认是源内容、GlassMic DSP，还是播放状态切换。"
            }
            captureActive && !publisherJson.optBoolean("paused", false) -> {
                stage = "DOWNSTREAM_APP_DSP_OR_NETWORK"
                summary = "当前未记录明显丢帧/欠载，但尚未排除交付时序问题；需结合回放抓取的 timing 与远端录音，区分回调时序和目标 App 后处理、编码或网络链路。"
            }
            else -> {
                stage = "INSUFFICIENT_DATA"
                summary = "当前缺少足够的活动录音或会话数据，建议在问题复现后立即导出诊断包。"
            }
        }

        return JSONObject().apply {
            put("generated_at", System.currentTimeMillis())
            put("suspected_stage", stage)
            put("summary", summary)
            put("heuristic", true)
            put("evidence", JSONObject().apply {
                put("session_available", session.optBoolean("available", false))
                put("session_started_at", sessionStart)
                put("publisher_dropped_bytes", publisherDropped)
                put("observed_consumer_count", observedConsumers)
                put("multiple_consumers", observedConsumers > 1)
                put("publisher_max_near_silent_run_ms", maxNearSilentMs)
                put("native_capture_active", captureActive)
                put("native_pcm_fd_active", pcmFdActive)
                put("native_gap_in_session", nativeGapInSession)
                put("native_latest_skipped_source_frames", skippedInLatest)
                put("pcm_short_reads", pcmShortReads)
                put("pcm_zero_fill_pcm16_bytes", pcmZeroFill)
            })
            put("next_checks", JSONArray().apply {
                when (stage) {
                    "PUBLISHER_QUEUE" -> {
                        put("查看 publisher_stats.json/recent_events 中 consumer_queue_drop 的时间与对象")
                        put("查看 playback_session.json 各 consumer 的 drop_ratio 与 dropped_duration_ms")
                    }
                    "NATIVE_BUFFER" -> {
                        put("查看 audio_stats.json 的 last_underrun/last_overrun 与 path")
                        put("对照 audio_timeline.json 中 native_gap_detected 与 pcm_fd_opened/closed")
                        put("若欠载仅出现在开麦/切源附近，不足以解释持续断音；继续核对回放抓取 timing")
                    }
                    "JAVA_PIPE" -> {
                        put("查看 pcm_read_diagnostics.latest 的 short_reads/errors/zero_fill_pcm16_bytes")
                    }
                    "SOURCE_OR_APP_DSP" -> {
                        put("确认源音频同一时间段是否本身静音")
                        put("检查音频频段、增益、混响、变速等 DSP 设置")
                    }
                    "DOWNSTREAM_APP_DSP_OR_NETWORK" -> {
                        put("导出回放抓取，查看 index.json 的 timing 和 timing_file；WAV 拼接会隐藏交付间隔")
                        put("对照远端录音的缺音时间，检查目标 App VAD/降噪/AGC/编码链路")
                        put("若只在音乐/背景声发生，优先测试语音后处理假设")
                    }
                    else -> put("复现问题后立即导出诊断包，避免历史统计覆盖现场")
                }
            })
        }.toString(2)
    }

    private fun buildDecisions(): String {
        val list = sourceResolver.getRecentDecisions()
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("timestamp", d.timestamp)
                put("caller_package", d.callerPackage)
                put("result", d.result.name)
                put("reason_code", d.reasonCode)
                put("reason_description", d.reasonDescription)
                put("last_seen", d.lastSeen)
                put("occurrences", d.occurrences)
                put("backend", d.backend)
            })
        }
        return arr.toString(2)
    }
}
