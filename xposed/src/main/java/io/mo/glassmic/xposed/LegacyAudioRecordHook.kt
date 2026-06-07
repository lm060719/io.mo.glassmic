package io.mo.glassmic.xposed

import android.content.Context
import android.media.AudioRecord
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.mo.glassmic.core.model.SourceType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap

/** legacy API 82 的 AudioRecord read() 拦截实现。 */
object LegacyAudioRecordHook {

    private const val TAG = "GlassMic-LegacyAudio"

    private val readers = WeakHashMap<AudioRecord, XposedPcmReader>()

    @Volatile private var installed = false

    fun install(appCtx: Context, callerPkg: String) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            if (!XposedHookGate.tryMarkAudioHookInstalled()) {
                installed = true
                XposedBridge.log("$TAG: skip duplicate legacy hook in $callerPkg")
                return
            }
            installed = true
        }

        hookReadByteArray(appCtx, callerPkg, false)
        hookReadByteArray(appCtx, callerPkg, true)
        hookReadShortArray(appCtx, callerPkg, false)
        hookReadShortArray(appCtx, callerPkg, true)
        hookReadFloatArray(appCtx, callerPkg)
        hookReadByteBuffer(appCtx, callerPkg, false)
        hookReadByteBuffer(appCtx, callerPkg, true)
        hookRelease()

        XposedBridge.log("$TAG: installed in $callerPkg")
    }

    private fun hookReadByteArray(appCtx: Context, pkg: String, withMode: Boolean) {
        hook("read", ByteArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, withMode = withMode) { param ->
            val src = XBridge.resolveSource(appCtx, pkg)
            if (src == SourceType.REAL_MIC) return@hook

            val buf = param.args[0] as ByteArray
            val offset = param.args[1] as Int
            val size = param.args[2] as Int
            if (!validRange(buf.size, offset, size)) return@hook

            val record = param.thisObject as AudioRecord
            if (src == SourceType.SILENCE) {
                java.util.Arrays.fill(buf, offset, offset + size, 0.toByte())
                param.result = size
                XBridge.recordInterception(appCtx, pkg, size, record.sampleRate, record.channelCount)
                return@hook
            }

            val n = obtainReader(appCtx, record).read(buf, offset, size)
            if (n < 0) return@hook
            if (n < size) java.util.Arrays.fill(buf, offset + n, offset + size, 0.toByte())
            param.result = size
            XBridge.recordInterception(appCtx, pkg, size, record.sampleRate, record.channelCount)
        }
    }

    private fun hookReadShortArray(appCtx: Context, pkg: String, withMode: Boolean) {
        hook("read", ShortArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, withMode = withMode) { param ->
            val src = XBridge.resolveSource(appCtx, pkg)
            if (src == SourceType.REAL_MIC) return@hook

            val buf = param.args[0] as ShortArray
            val offset = param.args[1] as Int
            val sizeInShorts = param.args[2] as Int
            if (!validRange(buf.size, offset, sizeInShorts)) return@hook

            val record = param.thisObject as AudioRecord
            if (src == SourceType.SILENCE) {
                java.util.Arrays.fill(buf, offset, offset + sizeInShorts, 0.toShort())
                param.result = sizeInShorts
                XBridge.recordInterception(appCtx, pkg, sizeInShorts * 2, record.sampleRate, record.channelCount)
                return@hook
            }

            val byteBuf = ByteArray(sizeInShorts * 2)
            val n = obtainReader(appCtx, record).read(byteBuf, 0, byteBuf.size)
            if (n < 0) return@hook
            val bb = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val shortsRead = n / 2
            repeat(shortsRead) { i -> buf[offset + i] = bb.get(i) }
            if (shortsRead < sizeInShorts) {
                java.util.Arrays.fill(buf, offset + shortsRead, offset + sizeInShorts, 0.toShort())
            }
            param.result = sizeInShorts
            XBridge.recordInterception(appCtx, pkg, sizeInShorts * 2, record.sampleRate, record.channelCount)
        }
    }

    private fun hookReadFloatArray(appCtx: Context, pkg: String) {
        hook("read", FloatArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!) { param ->
            val src = XBridge.resolveSource(appCtx, pkg)
            if (src == SourceType.REAL_MIC) return@hook

            val buf = param.args[0] as FloatArray
            val offset = param.args[1] as Int
            val sizeInFloats = param.args[2] as Int
            if (!validRange(buf.size, offset, sizeInFloats)) return@hook

            val record = param.thisObject as AudioRecord
            if (src == SourceType.SILENCE) {
                java.util.Arrays.fill(buf, offset, offset + sizeInFloats, 0f)
                param.result = sizeInFloats
                XBridge.recordInterception(appCtx, pkg, sizeInFloats * 4, record.sampleRate, record.channelCount)
                return@hook
            }

            val byteBuf = ByteArray(sizeInFloats * 4)
            val n = obtainReader(appCtx, record).read(byteBuf, 0, byteBuf.size)
            if (n < 0) return@hook
            val bb = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val floatsRead = n / 4
            repeat(floatsRead) { i -> buf[offset + i] = bb.get(i) }
            if (floatsRead < sizeInFloats) {
                java.util.Arrays.fill(buf, offset + floatsRead, offset + sizeInFloats, 0f)
            }
            param.result = sizeInFloats
            XBridge.recordInterception(appCtx, pkg, sizeInFloats * 4, record.sampleRate, record.channelCount)
        }
    }

    private fun hookReadByteBuffer(appCtx: Context, pkg: String, withMode: Boolean) {
        hook("read", ByteBuffer::class.java, Int::class.javaPrimitiveType!!, withMode = withMode) { param ->
            val src = XBridge.resolveSource(appCtx, pkg)
            if (src == SourceType.REAL_MIC) return@hook

            val buf = param.args[0] as ByteBuffer
            val size = param.args[1] as Int
            if (size < 0 || size > buf.remaining()) return@hook

            val record = param.thisObject as AudioRecord
            if (src == SourceType.SILENCE) {
                repeat(size) { buf.put(0.toByte()) }
                param.result = size
                XBridge.recordInterception(appCtx, pkg, size, record.sampleRate, record.channelCount)
                return@hook
            }

            val pos = buf.position()
            val n = obtainReader(appCtx, record).read(buf, size)
            if (n < 0) {
                buf.position(pos)
                return@hook
            }
            if (n < size) repeat(size - n) { buf.put(0.toByte()) }
            param.result = size
            XBridge.recordInterception(appCtx, pkg, size, record.sampleRate, record.channelCount)
        }
    }

    private fun hookRelease() {
        hook("release") { param ->
            val record = param.thisObject as? AudioRecord ?: return@hook
            synchronized(readers) {
                readers.remove(record)?.release()
            }
        }
    }

    private fun hook(
        name: String,
        vararg paramTypes: Class<*>,
        withMode: Boolean = false,
        before: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        val args = if (withMode) {
            arrayOf(*paramTypes, Int::class.javaPrimitiveType!!, hookCallback(before))
        } else {
            arrayOf(*paramTypes, hookCallback(before))
        }
        runCatching {
            XposedHelpers.findAndHookMethod(AudioRecord::class.java, name, *args)
        }.onFailure {
            XposedBridge.log("$TAG: hook $name failed: ${it.message}")
        }
    }

    private fun hookCallback(before: (XC_MethodHook.MethodHookParam) -> Unit): XC_MethodHook =
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching { before(param) }
                    .onFailure {
                        XposedBridge.log("$TAG: hook error: ${it.message}")
                        XposedBridge.log(it)
                    }
            }
        }

    private fun obtainReader(ctx: Context, record: AudioRecord): XposedPcmReader {
        synchronized(readers) {
            readers[record]?.let { return it }
            val r = XposedPcmReader(
                ctx,
                sampleRate = record.sampleRate,
                channels = record.channelCount
            )
            readers[record] = r
            return r
        }
    }

    private fun validRange(total: Int, offset: Int, size: Int): Boolean =
        offset >= 0 && size >= 0 && offset <= total && size <= total - offset
}
