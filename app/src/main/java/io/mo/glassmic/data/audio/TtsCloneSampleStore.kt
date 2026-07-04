package io.mo.glassmic.data.audio

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MiMo voiceclone 参考音频样本存储。
 *
 * 把 SAF 选到的音频复制到 filesDir/tts 下，只记录相对路径（tts/xxx.wav），
 * 合成时由 AiTtsSynthesizer 读文件转 base64。只保留最新一份，避免堆积。
 */
@Singleton
class TtsCloneSampleStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dir: File by lazy {
        File(context.filesDir, "tts").apply { if (!exists()) mkdirs() }
    }

    fun sampleFile(relativePath: String): File = File(context.filesDir, relativePath)

    /** 导入参考音频；成功返回相对路径，失败返回 null。 */
    suspend fun importSample(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val ext = when (context.contentResolver.getType(uri)) {
                "audio/mpeg", "audio/mp3" -> "mp3"
                else -> "wav"
            }
            dir.listFiles()?.forEach { runCatching { it.delete() } }
            val name = "clone_${System.currentTimeMillis()}.$ext"
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法打开音频 Uri" }
                File(dir, name).outputStream().use { out -> input.copyTo(out) }
            }
            "tts/$name"
        }.onFailure {
            GlassLog.b("TtsClone") { "参考音频导入失败: ${it.message}" }
        }.getOrNull()
    }
}
