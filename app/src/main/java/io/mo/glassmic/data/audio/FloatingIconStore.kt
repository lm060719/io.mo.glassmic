package io.mo.glassmic.data.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * 悬浮球自定义图标存储。
 *
 * 流程：SAF content:// → 解码 → 居中裁成正方形 → 下采样到 [MAX_ICON_PX] → 存 filesDir/floating 目录下的 png。
 * 只记录相对路径（floating/xxx.png），与 AudioFileResolver 同思路，避免绝对路径漂移。
 * 圆形裁切放在渲染层（Compose clip CircleShape），这里只保证是正方形，形状由 UI 决定。
 */
@Singleton
class FloatingIconStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dir: File by lazy {
        File(context.filesDir, "floating").apply { if (!exists()) mkdirs() }
    }

    fun iconFile(relativePath: String): File = File(context.filesDir, relativePath)

    /** 导入图标；成功返回相对路径，失败返回 null。会先清掉旧图标避免堆积。 */
    suspend fun importIcon(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val decoded = context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法打开图标 Uri" }
                BitmapFactory.decodeStream(input)
            } ?: return@runCatching null

            val squared = centerCropSquare(decoded)
            if (squared !== decoded) decoded.recycle()

            // 每次只保留最新一张
            dir.listFiles()?.forEach { runCatching { it.delete() } }
            val name = "icon_${System.currentTimeMillis()}.png"
            FileOutputStream(File(dir, name)).use { out ->
                squared.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            squared.recycle()
            "floating/$name"
        }.onFailure {
            GlassLog.b("FloatingIcon") { "图标导入失败: ${it.message}" }
        }.getOrNull()
    }

    private fun centerCropSquare(src: Bitmap): Bitmap {
        val side = min(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        return if (side > MAX_ICON_PX) {
            val scaled = Bitmap.createScaledBitmap(cropped, MAX_ICON_PX, MAX_ICON_PX, true)
            if (scaled !== cropped) cropped.recycle()
            scaled
        } else {
            cropped
        }
    }

    companion object {
        private const val MAX_ICON_PX = 256
    }
}
