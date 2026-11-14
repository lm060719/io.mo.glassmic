package io.mo.glassmic.ui.common

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.mo.glassmic.memory.MemoryPressure
import io.mo.glassmic.memory.MemoryPressureBus
import io.mo.glassmic.memory.MemoryReleasable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 已解码的应用图标。按需加载、按字节限容；纯界面缓存，任何内存压力下都整体丢弃。
 * 容量要装得下几百个应用（平板常见）：按个数只留 128 个时，长列表来回滑会不停淘汰、
 * 重新解码，图标反复闪占位块，慢速滑动时一顿一顿的（issue #46）。
 */
private object AppIconCache : MemoryReleasable {
    // 40dp 图标在高密度屏上约 50KB；16MB 约可容 300+ 个，同时不超过堆上限的 1/8
    private val maxBytes = minOf(16L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt()
    private val cache = object : LruCache<String, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    init {
        MemoryPressureBus.register(this)
    }

    fun get(pkg: String): ImageBitmap? = cache.get(pkg)

    fun put(pkg: String, icon: ImageBitmap) {
        cache.put(pkg, icon)
    }

    override fun onMemoryPressure(level: MemoryPressure): Long {
        val freed = cache.size().toLong()
        cache.evictAll()
        return freed
    }
}

/** 应用图标，加载前显示同尺寸的填充占位块。 */
@Composable
fun AppIcon(packageName: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 统一按 40dp 解码，小尺寸复用同一份缓存
    val sizePx = with(LocalDensity.current) { 40.dp.roundToPx() }
    val icon by produceState(AppIconCache.get(packageName), packageName) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(sizePx, sizePx)
                    .asImageBitmap()
            }.getOrNull()
        }?.also { AppIconCache.put(packageName, it) }
    }
    val bitmap = icon
    val shape = RoundedCornerShape(size * 0.28f)
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(size).clip(shape))
    } else {
        Box(modifier.size(size).background(glass.fill, shape))
    }
}
