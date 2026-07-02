package io.mo.glassmic.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleService
import dagger.hilt.android.AndroidEntryPoint
import io.mo.glassmic.audio.SharedPcmPublisher
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.proto.AppConfig
import javax.inject.Inject

/**
 * 实时波形悬浮窗服务。独立于控制悬浮窗（[FloatingWindowService]）。
 *
 * 从 [SharedPcmPublisher.waveform] 收实时振幅点，维护一个滚动缓冲，用 Compose Canvas 画波形。
 * 透明度由 AppConfig.floatingWindow.waveformOpacity 控制。
 */
@AndroidEntryPoint
class WaveformOverlayService : LifecycleService() {

    @Inject lateinit var configStore: ConfigStore
    @Inject lateinit var publisher: SharedPcmPublisher

    private var windowManager: WindowManager? = null
    private var host: FloatingOverlayHost? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            GlassLog.b("Waveform") { "overlay 权限缺失，停止波形窗" }
            stopSelf()
            return
        }
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        host?.let { h ->
            runCatching { windowManager?.removeView(h.view) }
            h.onDestroy()
        }
        host = null
        super.onDestroy()
    }

    private fun showOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 120
        }
        params = lp

        val overlayHost = FloatingOverlayHost(this).also { it.onCreate() }
        host = overlayHost
        overlayHost.setContent {
            MaterialTheme {
                val cfg by configStore.flow.collectAsState(initial = AppConfig.getDefaultInstance())
                val opacity = cfg.floatingWindow.waveformOpacity.takeIf { it > 0f } ?: 0.6f
                var samples by remember { mutableStateOf(FloatArray(WAVE_POINTS)) }
                LaunchedEffect(Unit) {
                    val ring = FloatArray(WAVE_POINTS)
                    publisher.waveform.collect { chunk ->
                        val n = chunk.size
                        when {
                            n >= ring.size -> chunk.copyInto(ring, 0, n - ring.size, n)
                            n > 0 -> {
                                System.arraycopy(ring, n, ring, 0, ring.size - n)
                                chunk.copyInto(ring, ring.size - n, 0, n)
                            }
                        }
                        samples = ring.copyOf()  // 新数组引用触发重绘
                    }
                }
                WaveformOverlay(
                    samples = samples,
                    opacity = opacity,
                    onDragBy = ::onDragBy,
                    onDragEnd = ::onDragEnd,
                )
            }
        }
        runCatching { wm.addView(overlayHost.view, lp) }
            .onFailure { GlassLog.b("Waveform") { "addView 失败: ${it.message}" } }
    }

    private fun onDragBy(dx: Float, dy: Float) {
        val lp = params ?: return
        val h = host ?: return
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
    }

    private fun onDragEnd() {
        val lp = params ?: return
        val h = host ?: return
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val viewW = h.view.width.takeIf { it > 0 } ?: 0
        val viewH = h.view.height.takeIf { it > 0 } ?: 0
        lp.x = lp.x.coerceIn(0, (screenW - viewW).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screenH - viewH).coerceAtLeast(0))
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
    }

    companion object {
        private const val WAVE_POINTS = 240

        fun start(ctx: Context) {
            if (!Settings.canDrawOverlays(ctx)) return
            ctx.startService(Intent(ctx, WaveformOverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WaveformOverlayService::class.java))
        }
    }
}
