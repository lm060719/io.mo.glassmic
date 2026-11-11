package io.mo.glassmic.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.mo.glassmic.audio.SharedPcmPublisher
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.diag.AudioTapStore
import io.mo.glassmic.data.runtime.EffectiveSourceResolver
import java.io.FileNotFoundException

/**
 * 给 Xposed 进程读取当前 PCM 流。
 *
 * URI: content://io.mo.glassmic.provider.pcm/stream?sr=48000&ch=1
 *      content://io.mo.glassmic.provider.pcm/tap?pid=123（调试回放抓取，见 AudioTapStore）
 *
 * 设计说明详见 SharedPcmPublisher：用 pipe 而非 mmap，避免 chmod 与
 * WORLD_READABLE 风险。调用方关闭 fd 即触发 EOF，写线程自动清理。
 */
class PcmStreamProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PcmEntryPoint {
        fun publisher(): SharedPcmPublisher
        fun audioTapStore(): AudioTapStore
        fun resolver(): EffectiveSourceResolver
    }

    private companion object {
        const val MIN_SAMPLE_RATE = 8_000
        const val MAX_SAMPLE_RATE = 192_000
        const val MAX_CHANNELS = 2
        // 多进程 App（如王者）每个录音进程各一个 fd，Java/native 路径再各占一个；
        // 8 个足够正常使用，又能挡住无限开管道。
        const val MAX_CONSUMERS_PER_PACKAGE = 8
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val callerPkg = callingPackage ?: "unknown"
        if (uri.path == "/tap") {
            // 调试回放抓取：仅 DEBUG 日志级别下接受，否则抛 FileNotFoundException 让 hook 侧退避。
            require(mode == "w") { "tap 只支持只写" }
            val pid = uri.getQueryParameter("pid")?.toIntOrNull() ?: 0
            return entryPoint().audioTapStore().openSink(callerPkg, pid)
        }
        require(mode == "r") { "PcmStreamProvider 只支持只读" }
        // 参数来自其他进程，必须钳到转换器能安全处理的范围，否则一个极大的 sr/ch 就能
        // 让 Pcm16Converter 分配巨量内存把本进程打崩。
        val sampleRate = (uri.getQueryParameter("sr")?.toIntOrNull() ?: 48000)
            .coerceIn(MIN_SAMPLE_RATE, MAX_SAMPLE_RATE)
        val channels = (uri.getQueryParameter("ch")?.toIntOrNull() ?: 1)
            .coerceIn(1, MAX_CHANNELS)

        val ep = entryPoint()
        // 本进程自检（AudioPipelineProbe）直接放行；其他调用方一律以 Binder 校验过的
        // callingPackage 走决策——不在生效范围内的 App 不能读到当前注入的音频。
        if (Binder.getCallingUid() != Process.myUid()) {
            if (ep.resolver().resolve(callerPkg) == SourceType.REAL_MIC) {
                throw FileNotFoundException("PCM stream not available for $callerPkg")
            }
            if (ep.publisher().consumerCountFor(callerPkg) >= MAX_CONSUMERS_PER_PACKAGE) {
                throw FileNotFoundException("too many PCM consumers for $callerPkg")
            }
        }

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        ep.publisher().attachConsumer(
            consumerPackage = callerPkg,
            sampleRate = sampleRate,
            channels = channels,
            writeFd = writeSide
        )
        return readSide
    }

    private fun entryPoint(): PcmEntryPoint =
        EntryPointAccessors.fromApplication(
            context!!.applicationContext, PcmEntryPoint::class.java
        )

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null
    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
