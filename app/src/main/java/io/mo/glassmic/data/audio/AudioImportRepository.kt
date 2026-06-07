package io.mo.glassmic.data.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.model.PlaybackPolicy
import io.mo.glassmic.data.db.AudioClipEntity
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.db.AudioGroupEntity
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音频库导入/管理仓库。
 *
 * 导入流程：
 *   1. content:// Uri → 探测 MIME + display name
 *   2. 用 ContentResolver 打开输入流，复制到 audio_lib/{groupId}/{uuid}.{ext}
 *   3. MediaExtractor 探测 sampleRate / channels
 *   4. MediaMetadataRetriever 探测 durationMs（更稳定）
 *   5. 写入 Room
 *
 * 任何异常都向上抛出，由调用方决定提示。
 */
@Singleton
class AudioImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AudioDao,
    private val resolver: AudioFileResolver
) {

    // ============== 片段组 ==============
    suspend fun createGroup(name: String, emoji: String, policy: PlaybackPolicy?): AudioGroupEntity =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val nextOrder = dao.maxGroupOrder() + 1
            val g = AudioGroupEntity(
                id = id,
                name = name.ifBlank { "未命名" },
                emoji = emoji.ifBlank { "🎵" },
                sortOrder = nextOrder,
                playbackPolicyOverride = policy?.name
            )
            dao.upsertGroup(g)
            resolver.ensureGroupDir(id)
            g
        }

    suspend fun updateGroup(group: AudioGroupEntity) = withContext(Dispatchers.IO) {
        dao.updateGroup(group)
    }

    suspend fun deleteGroup(groupId: String) = withContext(Dispatchers.IO) {
        // Room 配置了 onDelete = CASCADE 会自动删 clips；文件目录单独清
        dao.deleteGroup(groupId)
        resolver.deleteGroupDir(groupId)
    }

    // ============== 音频片段 ==============
    suspend fun importFromUri(
        uri: Uri,
        groupId: String,
        displayNameOverride: String? = null
    ): AudioClipEntity = withContext(Dispatchers.IO) {
        require(dao.findGroup(groupId) != null) { "片段组不存在: $groupId" }

        val resolver1 = context.contentResolver
        val mimeType = resolver1.getType(uri) ?: "audio/mpeg"
        require(mimeType.startsWith("audio/")) { "非音频文件: $mimeType" }

        val (origName, sizeFromUri) = queryDisplayInfo(uri)
        val displayName = displayNameOverride?.takeIf { it.isNotBlank() } ?: origName ?: "audio"
        val ext = mimeToExt(mimeType) ?: (origName?.substringAfterLast('.', "")?.lowercase()) ?: "bin"

        val clipId = UUID.randomUUID().toString()
        val rel = "$groupId/$clipId.$ext"
        val target = resolver.fileFor(rel).apply { parentFile?.mkdirs() }

        resolver1.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开音频 Uri" }
            FileOutputStream(target).use { out -> input.copyTo(out) }
        }

        val (durationMs, sampleRate, channels) = probe(target)
        val nextOrder = dao.maxClipOrderInGroup(groupId) + 1
        val entity = AudioClipEntity(
            id = clipId,
            groupId = groupId,
            displayName = displayName.removeSuffix(".$ext"),
            fileName = "$displayName.$ext",
            relativePath = rel,
            mimeType = mimeType,
            durationMs = durationMs,
            sizeBytes = if (sizeFromUri > 0) sizeFromUri else target.length(),
            sampleRate = sampleRate,
            channels = channels,
            createdAt = System.currentTimeMillis(),
            sortOrder = nextOrder
        )
        dao.upsertClip(entity)
        GlassLog.b("AudioImport") {
            "导入完成: name=${entity.displayName} dur=${durationMs}ms sr=$sampleRate ch=$channels"
        }
        entity
    }

    suspend fun renameClip(clipId: String, newName: String) = withContext(Dispatchers.IO) {
        dao.renameClip(clipId, newName.ifBlank { "未命名" })
    }

    suspend fun deleteClip(clipId: String) = withContext(Dispatchers.IO) {
        val clip = dao.findClip(clipId) ?: return@withContext
        dao.deleteClip(clipId)
        resolver.deleteFile(clip.relativePath)
    }

    // ============== 工具 ==============
    private fun queryDisplayInfo(uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = 0L
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        return name to size
    }

    private fun mimeToExt(mime: String): String? =
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: when (mime.lowercase()) {
            "audio/mpeg" -> "mp3"
            "audio/mp3" -> "mp3"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/aac" -> "aac"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/ogg" -> "ogg"
            "audio/flac", "audio/x-flac" -> "flac"
            else -> null
        }

    private fun probe(file: File): Triple<Long, Int, Int> {
        val durationMs = runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.absolutePath)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally { mmr.release() }
        }.getOrDefault(0L)

        val (sr, ch) = runCatching {
            val ext = MediaExtractor()
            try {
                ext.setDataSource(file.absolutePath)
                val idx = (0 until ext.trackCount).firstOrNull { i ->
                    ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return@runCatching 0 to 1
                val fmt = ext.getTrackFormat(idx)
                val sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                sr to ch
            } finally { ext.release() }
        }.getOrDefault(0 to 1)

        return Triple(durationMs, sr, ch)
    }
}
