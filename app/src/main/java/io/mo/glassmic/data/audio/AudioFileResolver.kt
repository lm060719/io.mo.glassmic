package io.mo.glassmic.data.audio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音频文件根目录解析器。
 *
 * 所有导入的音频统一落到 filesDir/audio_lib/{groupId}/{clipId}.{ext}
 * AudioClip 仅记录 relativePath，不持久化绝对路径——
 * 这样应用迁移 / 备份 / 恢复都不会因绝对路径漂移而失效。
 */
@Singleton
class AudioFileResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val root: File by lazy {
        File(context.filesDir, "audio_lib").apply { if (!exists()) mkdirs() }
    }

    fun audioRoot(): File = root

    fun fileFor(relativePath: String): File = File(root, relativePath)

    fun ensureGroupDir(groupId: String): File =
        File(root, groupId).apply { if (!exists()) mkdirs() }

    fun deleteFile(relativePath: String): Boolean =
        runCatching { fileFor(relativePath).delete() }.getOrDefault(false)

    fun deleteGroupDir(groupId: String) {
        runCatching { File(root, groupId).deleteRecursively() }
    }
}
