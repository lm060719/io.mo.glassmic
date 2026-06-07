package io.mo.glassmic.data.diag

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.Constants
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * 在 App 进程内直接打开自己的 PcmStreamProvider，读 1 秒，证实"App 侧管线 (FileAudioSource → SharedPcmPublisher → pipe) 是否通"。
 *
 * 与 Xposed 拦截无关——这能帮用户区分：
 *  - App 侧管线 OK，但 Xposed 拦截 0 次 → 目标 App 没用 AudioRecord，或者 hook 没装
 *  - App 侧管线就是死的 → 还没选音频 / 文件读不到 / publisher 异常
 */
@Singleton
class AudioPipelineProbe @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class Result(
        val ok: Boolean,
        val bytesRead: Int,
        val durationMs: Long,
        val rms: Double,          // 16-bit PCM 振幅 RMS（0..32767）
        val message: String
    )

    /**
     * 拉 [durationMs] 毫秒 PCM 数据测试。
     * 默认 48000Hz mono 16-bit ≈ 96 KB/秒。
     */
    suspend fun probe(durationMs: Long = 1000, sampleRate: Int = 48000, channels: Int = 1): Result =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(
                "content://${Constants.PROVIDER_PCM}/stream?sr=$sampleRate&ch=$channels"
            )
            val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }
                .onFailure { GlassLog.b("Probe") { "openFileDescriptor 失败: ${it.message}" } }
                .getOrNull()
                ?: return@withContext Result(
                    ok = false, bytesRead = 0, durationMs = 0, rms = 0.0,
                    message = "无法打开 PCM 通道（可能没选音频或 Provider 未启动）"
                )

            val start = System.currentTimeMillis()
            var total = 0
            var rms = 0.0
            var samples = 0L

            val outcome = withTimeoutOrNull(durationMs + 500) {
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val buf = ByteArray(4096)
                    while (System.currentTimeMillis() - start < durationMs) {
                        val n = runCatching { input.read(buf, 0, buf.size) }.getOrDefault(-1)
                        if (n <= 0) break
                        total += n
                        // 把 16-bit LE 当成 short 累计 RMS
                        var i = 0
                        while (i + 1 < n) {
                            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                            rms += (s.toDouble() * s.toDouble())
                            samples += 1
                            i += 2
                        }
                    }
                }
                true
            }
            runCatching { pfd.close() }

            val elapsed = System.currentTimeMillis() - start
            val rmsValue = if (samples > 0) sqrt(rms / samples) else 0.0
            val ok = total > 0 && outcome == true
            val msg = when {
                outcome == null -> "读取超时"
                total == 0 -> "未读取到任何字节（管线没数据）"
                rmsValue < 1.0 -> "管线通，但全是静音（请检查是否选了音频源）"
                else -> "管线正常"
            }
            GlassLog.b("Probe") {
                "probe ok=$ok bytes=$total elapsed=${elapsed}ms rms=${"%.1f".format(rmsValue)}"
            }
            Result(
                ok = ok,
                bytesRead = total,
                durationMs = elapsed,
                rms = rmsValue,
                message = msg
            )
        }
}
