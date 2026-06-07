package io.mo.glassmic.data.audio

import io.mo.glassmic.audio.FileAudioSource
import io.mo.glassmic.audio.SharedPcmPublisher
import io.mo.glassmic.audio.SilenceSource
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 UI 的"设为当前音源"动作衔接到音频管线。
 *
 * - 修改 RuntimeStateHolder 当前音源
 * - 通知 SharedPcmPublisher 切换 source
 * - 持久化当前选中 ID 到 ConfigStore（用于重启后恢复显示）
 *
 * 不负责 BootGate / SafeMode 判断——这些在 EffectiveSourceResolver 里。
 */
@Singleton
class PlaybackController @Inject constructor(
    private val publisher: SharedPcmPublisher,
    private val resolver: AudioFileResolver,
    private val configStore: ConfigStore,
    private val runtime: RuntimeStateHolder,
    private val dao: AudioDao
) {

    /** 设置某个片段为当前虚拟麦克风音源。文件不存在则自动清掉数据库记录并返回 false。 */
    suspend fun setCurrentClip(clipId: String, startPaused: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val clip = dao.findClip(clipId) ?: return@withContext false
        val file = resolver.fileFor(clip.relativePath)
        if (!file.exists()) {
            GlassLog.b("Playback") { "音频文件丢失，清理记录: ${clip.relativePath}" }
            dao.deleteClip(clipId)
            return@withContext false
        }
        val src = runCatching { FileAudioSource(clip.toModel(), file.absolutePath) }
            .onFailure { GlassLog.b("Playback") { "FileAudioSource 创建失败: ${it.message}" } }
            .getOrNull() ?: return@withContext false

        publisher.setSource(src, groupId = clip.groupId, audioId = clip.id)
        publisher.setPaused(startPaused)
        configStore.update {
            it.setCurrentGroupId(clip.groupId)
            it.setCurrentAudioId(clip.id)
        }
        true
    }

    /** 切回真实麦克风：释放当前 file source，运行态置 REAL_MIC，清空持久化选中。 */
    suspend fun setRealMic() = withContext(Dispatchers.IO) {
        publisher.setPaused(false)
        publisher.setSource(SilenceSource)
        runtime.setSource(SourceType.REAL_MIC, groupId = null, audioId = null, durationMs = 0L)
        configStore.update {
            it.setCurrentGroupId("")
            it.setCurrentAudioId("")
        }
    }

    suspend fun restorePersistedClip(): Boolean {
        val clipId = configStore.current().currentAudioId.takeIf { it.isNotBlank() } ?: return false
        return setCurrentClip(clipId, startPaused = true)
    }

    val isPaused: Boolean get() = publisher.isPaused

    fun pause() {
        publisher.setPaused(true)
        GlassLog.b("Playback") { "暂停" }
    }

    fun resume() {
        publisher.setPaused(false)
        GlassLog.b("Playback") { "恢复" }
    }

    fun togglePause() {
        if (publisher.isPaused) resume() else pause()
    }

    suspend fun seekTo(positionMs: Long) = withContext(Dispatchers.IO) {
        publisher.seekCurrent(positionMs)
    }
}
