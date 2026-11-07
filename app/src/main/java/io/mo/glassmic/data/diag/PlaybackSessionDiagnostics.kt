package io.mo.glassmic.data.diag

import dagger.Lazy
import io.mo.glassmic.audio.SharedPcmPublisher
import io.mo.glassmic.core.model.RuntimeState
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单次播放会话的 Publisher 统计。
 *
 * Publisher 原有统计是进程生命周期累计值，无法判断某一次复现到底丢了多少数据。这里在
 * FILE/TTS 开始播放、恢复播放、切换音源或进度回到前方时记录基线，并定期对 Publisher
 * 快照做差。即使某个 consumer 随后断开，它在本会话中观测到的最后数据也会保留下来。
 *
 * 本类只记录计数和时间，不读取、保存或导出用户音频内容。
 */
@Singleton
class PlaybackSessionDiagnostics @Inject constructor(
    private val runtime: RuntimeStateHolder,
    private val publisher: Lazy<SharedPcmPublisher>
) {

    private data class ConsumerCounters(
        val id: String,
        val packageName: String,
        val sampleRate: Int,
        val channels: Int,
        val writtenBytes: Long,
        val droppedBytes: Long,
        val lastWriteMs: Long
    )

    private data class PublisherSnapshot(
        val capturedAt: Long,
        val totalDroppedBytes: Long,
        val writeFailures: Long,
        val consumers: Map<String, ConsumerCounters>
    )

    private data class ConsumerDelta(
        val id: String,
        val packageName: String,
        val sampleRate: Int,
        val channels: Int,
        var writtenBytes: Long,
        var droppedBytes: Long,
        var lastWriteMs: Long
    )

    private data class Session(
        val id: String,
        val startedAt: Long,
        val reason: String,
        val sourceType: SourceType,
        val groupId: String?,
        val audioId: String?,
        val baseline: PublisherSnapshot,
        val consumers: MutableMap<String, ConsumerDelta> = linkedMapOf(),
        var totalDroppedBytes: Long = 0L,
        var writeFailures: Long = 0L,
        var lastSampleAt: Long = 0L,
        var lastPublisherCapturedAt: Long = 0L
    )

    private companion object {
        const val SAMPLE_INTERVAL_MS = 500L
        const val POSITION_RESTART_THRESHOLD_MS = 500L
        const val PCM16_BYTES_PER_SAMPLE = 2L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sequence = AtomicLong()
    private val lock = Any()

    @Volatile private var collectorJob: Job? = null
    private var session: Session? = null

    fun start() {
        if (collectorJob?.isActive == true) return
        synchronized(lock) {
            if (collectorJob?.isActive == true) return
            collectorJob = scope.launch {
                var previous: RuntimeState? = null
                runtime.flow.collect { current ->
                    val reason = startReason(previous, current)
                    if (reason != null) {
                        begin(current, reason)
                    } else if (isPlaying(current)) {
                        sampleIfDue()
                    }
                    previous = current
                }
            }
        }
    }

    fun stop() {
        val job = synchronized(lock) {
            sampleLocked(force = true)
            collectorJob.also { collectorJob = null }
        }
        // cancel() 会立即阻止观察器继续处理后续状态；无需另起协程等待 join，
        // 避免服务快速重启时旧观察器与新观察器短暂并存。
        job?.cancel()
    }

    fun diagnostics(): JSONObject = synchronized(lock) {
        sampleLocked(force = true)
        val current = session
        if (current == null) {
            return@synchronized JSONObject().apply {
                put("available", false)
                put("tracker_running", collectorJob?.isActive == true)
                put("message", "本次进程尚未观测到 FILE/TTS 播放会话")
            }
        }

        val now = System.currentTimeMillis()
        val consumers = JSONArray()
        var totalWritten = 0L
        var totalDropped = 0L
        current.consumers.values.forEach { c ->
            totalWritten += c.writtenBytes
            totalDropped += c.droppedBytes
            consumers.put(JSONObject().apply {
                put("id", c.id)
                put("package", c.packageName)
                put("sample_rate", c.sampleRate)
                put("channels", c.channels)
                put("written_bytes", c.writtenBytes)
                put("dropped_bytes", c.droppedBytes)
                put("written_duration_ms", durationMs(c.writtenBytes, c.sampleRate, c.channels))
                put("dropped_duration_ms", durationMs(c.droppedBytes, c.sampleRate, c.channels))
                val all = c.writtenBytes + c.droppedBytes
                put("drop_ratio", if (all > 0L) c.droppedBytes.toDouble() / all else 0.0)
                put("last_write_ms", c.lastWriteMs)
            })
        }

        JSONObject().apply {
            put("available", true)
            put("tracker_running", collectorJob?.isActive == true)
            put("session_id", current.id)
            put("started_at", current.startedAt)
            put("elapsed_ms", (now - current.startedAt).coerceAtLeast(0L))
            put("start_reason", current.reason)
            put("source_type", current.sourceType.name)
            put("group_id", current.groupId ?: "")
            put("audio_id", current.audioId ?: "")
            put("last_sample_at", current.lastSampleAt)
            put("last_publisher_captured_at", current.lastPublisherCapturedAt)
            put("publisher_total_dropped_bytes", current.totalDroppedBytes)
            put("publisher_write_failures", current.writeFailures)
            put("observed_consumer_count", current.consumers.size)
            put("consumer_written_bytes_sum", totalWritten)
            put("consumer_dropped_bytes_sum", totalDropped)
            put("consumers", consumers)
            put("note", "consumer 字节为各并行录音通道分别统计，不可直接相加为远端缺失时长")
        }
    }

    private fun begin(state: RuntimeState, reason: String) {
        val now = System.currentTimeMillis()
        val baseline = readPublisherSnapshot()
        synchronized(lock) {
            session = Session(
                id = "$now-${sequence.incrementAndGet()}",
                startedAt = now,
                reason = reason,
                sourceType = state.currentSourceType,
                groupId = state.currentGroupId,
                audioId = state.currentAudioId,
                baseline = baseline,
                lastSampleAt = now,
                lastPublisherCapturedAt = baseline.capturedAt
            )
        }
    }

    private fun sampleIfDue() = synchronized(lock) {
        sampleLocked(force = false)
    }

    private fun sampleLocked(force: Boolean) {
        val current = session ?: return
        val now = System.currentTimeMillis()
        if (!force && now - current.lastSampleAt < SAMPLE_INTERVAL_MS) return

        val snapshot = readPublisherSnapshot()
        current.lastSampleAt = now
        current.lastPublisherCapturedAt = snapshot.capturedAt
        current.totalDroppedBytes = delta(snapshot.totalDroppedBytes, current.baseline.totalDroppedBytes)
        current.writeFailures = delta(snapshot.writeFailures, current.baseline.writeFailures)

        snapshot.consumers.values.forEach { latest ->
            val base = current.baseline.consumers[latest.id]
            val written = delta(latest.writtenBytes, base?.writtenBytes ?: 0L)
            val dropped = delta(latest.droppedBytes, base?.droppedBytes ?: 0L)
            val observed = current.consumers[latest.id]
            if (observed == null) {
                current.consumers[latest.id] = ConsumerDelta(
                    id = latest.id,
                    packageName = latest.packageName,
                    sampleRate = latest.sampleRate,
                    channels = latest.channels,
                    writtenBytes = written,
                    droppedBytes = dropped,
                    lastWriteMs = latest.lastWriteMs
                )
            } else {
                observed.writtenBytes = maxOf(observed.writtenBytes, written)
                observed.droppedBytes = maxOf(observed.droppedBytes, dropped)
                observed.lastWriteMs = maxOf(observed.lastWriteMs, latest.lastWriteMs)
            }
        }
    }

    private fun readPublisherSnapshot(): PublisherSnapshot {
        val json = runCatching { publisher.get().diagnostics() }.getOrElse { JSONObject() }
        val map = linkedMapOf<String, ConsumerCounters>()
        val array = json.optJSONArray("consumers")
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                map[id] = ConsumerCounters(
                    id = id,
                    packageName = item.optString("package"),
                    sampleRate = item.optInt("sample_rate"),
                    channels = item.optInt("channels"),
                    writtenBytes = item.optLong("written_bytes"),
                    droppedBytes = item.optLong("dropped_bytes"),
                    lastWriteMs = item.optLong("last_write_ms")
                )
            }
        }
        return PublisherSnapshot(
            capturedAt = json.optLong("captured_at", System.currentTimeMillis()),
            totalDroppedBytes = json.optLong("total_dropped_bytes"),
            writeFailures = json.optLong("write_failures"),
            consumers = map
        )
    }

    private fun startReason(previous: RuntimeState?, current: RuntimeState): String? {
        if (!isPlaying(current)) return null
        if (previous == null || !isPlaying(previous)) return "playback_started_or_resumed"

        val sourceChanged = previous.currentSourceType != current.currentSourceType ||
            previous.currentGroupId != current.currentGroupId ||
            previous.currentAudioId != current.currentAudioId
        if (sourceChanged) return "source_changed"

        if (current.positionMs + POSITION_RESTART_THRESHOLD_MS < previous.positionMs) {
            return "position_restarted"
        }
        return null
    }

    private fun isPlaying(state: RuntimeState): Boolean =
        state.enabled &&
            !state.paused &&
            (state.currentSourceType == SourceType.FILE || state.currentSourceType == SourceType.TTS ||
                state.currentSourceType == SourceType.LIVE)

    private fun delta(current: Long, baseline: Long): Long =
        (current - baseline).coerceAtLeast(0L)

    private fun durationMs(bytes: Long, sampleRate: Int, channels: Int): Long {
        if (bytes <= 0L || sampleRate <= 0 || channels <= 0) return 0L
        val bytesPerSecond = sampleRate.toLong() * channels * PCM16_BYTES_PER_SAMPLE
        return bytes * 1000L / bytesPerSecond
    }
}
