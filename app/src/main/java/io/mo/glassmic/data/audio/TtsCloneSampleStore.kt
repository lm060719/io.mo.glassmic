package io.mo.glassmic.data.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 参考音频导入结果。 */
sealed interface SampleImport {
    data class Ok(val relativePath: String) : SampleImport
    data class Err(val message: String) : SampleImport
}

/**
 * MiMo voiceclone 参考音频样本存储。
 *
 * 把 SAF 选到的音频复制到 filesDir/tts 下，只记录相对路径（tts/xxx.wav），
 * 合成时由 AiTtsSynthesizer 读文件转 base64。只保留最新一份，避免堆积。
 *
 * 限制：时长 ≤ [MAX_DURATION_MS]，大小 ≤ [MAX_BYTES]（复刻只需一小段样本）。
 */
@Singleton
class TtsCloneSampleStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dir: File by lazy {
        File(context.filesDir, "tts").apply { if (!exists()) mkdirs() }
    }

    fun sampleFile(relativePath: String): File = File(context.filesDir, relativePath)

    /** 删除已保存的参考音频文件（清除时调用，避免磁盘残留）。 */
    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    /** 导入参考音频，先校验大小与时长再复制。 */
    suspend fun importSample(uri: Uri): SampleImport = withContext(Dispatchers.IO) {
        querySize(uri)?.let { size ->
            if (size > MAX_BYTES) return@withContext SampleImport.Err("音频超过 5MB（当前约 ${size / 1024} KB）")
        }
        queryDurationMs(uri)?.let { dur ->
            if (dur > MAX_DURATION_MS) return@withContext SampleImport.Err("音频超过 60 秒（当前约 ${dur / 1000} 秒）")
        }
        runCatching {
            val ext = when (context.contentResolver.getType(uri)) {
                "audio/mpeg", "audio/mp3" -> "mp3"
                else -> "wav"
            }
            dir.listFiles()?.forEach { runCatching { it.delete() } }
            val out = File(dir, "clone_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法打开音频 Uri" }
                out.outputStream().use { o -> input.copyTo(o) }
            }
            // 大小查询可能为空，用实际文件长度兜底
            if (out.length() > MAX_BYTES) {
                out.delete()
                return@runCatching SampleImport.Err("音频超过 5MB")
            }
            SampleImport.Ok("tts/${out.name}")
        }.getOrElse {
            GlassLog.b("TtsClone") { "参考音频导入失败: ${it.message}" }
            SampleImport.Err("参考音频导入失败：${it.message}")
        }
    }

    private fun querySize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    private fun queryDurationMs(uri: Uri): Long? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private companion object {
        const val MAX_BYTES = 5L * 1024 * 1024   // 5MB
        const val MAX_DURATION_MS = 60_000L       // 60s
    }
}
