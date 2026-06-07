package io.mo.glassmic.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.mo.glassmic.audio.SharedPcmPublisher

/**
 * 给 Xposed 进程读取当前 PCM 流。
 *
 * URI: content://io.mo.glassmic.provider.pcm/stream?sr=48000&ch=1
 *
 * 设计说明详见 SharedPcmPublisher：用 pipe 而非 mmap，避免 chmod 与
 * WORLD_READABLE 风险。调用方关闭 fd 即触发 EOF，写线程自动清理。
 */
class PcmStreamProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PcmEntryPoint {
        fun publisher(): SharedPcmPublisher
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "PcmStreamProvider 只支持只读" }
        val callerPkg = callingPackage ?: "unknown"
        val sampleRate = uri.getQueryParameter("sr")?.toIntOrNull() ?: 48000
        val channels = uri.getQueryParameter("ch")?.toIntOrNull() ?: 1

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        entryPoint().publisher().attachConsumer(
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
