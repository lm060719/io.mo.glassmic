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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.audio.FloatingIconStore
import io.mo.glassmic.data.audio.PlaybackController
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.proto.AppConfig
import io.mo.glassmic.proto.FloatingSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 悬浮窗服务（Compose 版）。
 *
 * 三态：球态 / 迷你播放条 / 选曲菜单，全部渲染在同一个挂到 WindowManager 的 ComposeView 里。
 * 手势：
 * - 未播放点击球 → 选曲菜单；播放中点击球 → 迷你播放条
 * - 迷你条内「换音源」→ 选曲菜单；选中片段 → 立即播放并回到迷你条
 * - 拖动球移动，松手贴边
 */
@AndroidEntryPoint
class FloatingWindowService : LifecycleService() {

    @Inject lateinit var runtime: RuntimeStateHolder
    @Inject lateinit var playback: PlaybackController
    @Inject lateinit var configStore: ConfigStore
    @Inject lateinit var audioDao: AudioDao
    @Inject lateinit var iconStore: FloatingIconStore

    private var windowManager: WindowManager? = null
    private var host: FloatingOverlayHost? = null
    private var params: WindowManager.LayoutParams? = null

    private val modeFlow = MutableStateFlow(FloatMode.BALL)

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            GlassLog.b("Float") { "overlay permission missing; stop floating window" }
            stopSelf()
            return
        }
        showOverlay()
        runtime.setFloatingVisible(true)
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
        runtime.setFloatingVisible(false)
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
            y = 300
        }
        params = lp

        val overlayHost = FloatingOverlayHost(this).also { it.onCreate() }
        host = overlayHost
        overlayHost.setContent {
            MaterialTheme {
                val rt by runtime.flow.collectAsState()
                val cfg by configStore.flow.collectAsState(initial = AppConfig.getDefaultInstance())
                val groups by audioDao.observeGroups().collectAsState(initial = emptyList())
                val allClips by audioDao.observeAllClips().collectAsState(initial = emptyList())
                val mode by modeFlow.collectAsState()

                val activeFile = rt.currentSourceType == SourceType.FILE && rt.enabled && !rt.safeMode
                val currentId = cfg.currentAudioId
                val currentName = allClips.firstOrNull { it.id == currentId }?.displayName

                FloatingBubbleRoot(
                    mode = mode,
                    activeFile = activeFile,
                    paused = rt.paused,
                    positionMs = rt.positionMs,
                    durationMs = rt.durationMs,
                    currentName = currentName,
                    sizeDp = sizeToDp(cfg.floatingWindow.size),
                    iconPath = cfg.floatingWindow.customIconPath.takeIf { it.isNotBlank() }
                        ?.let { iconStore.iconFile(it).absolutePath },
                    opacity = cfg.floatingWindow.opacity.takeIf { it > 0f } ?: 0.85f,
                    groups = groups.map { FloatGroupItem(it.id, it.emoji, it.name) },
                    clipsProvider = { gid ->
                        audioDao.observeClipsInGroup(gid).map { list ->
                            list.map { FloatClipItem(it.id, it.displayName, it.id == currentId) }
                        }
                    },
                    onBallTap = { onBallTap(activeFile) },
                    onTogglePause = { playback.togglePause() },
                    onSeek = { frac -> onSeek(frac, rt.durationMs) },
                    onOpenMenu = { setMode(FloatMode.MENU) },
                    onCollapse = { setMode(FloatMode.BALL) },
                    onSelectClip = { clipId -> onSelectClip(clipId) },
                    onDragBy = { dx, dy -> onDragBy(dx, dy) },
                    onDragEnd = { onDragEnd() },
                )
            }
        }
        runCatching { wm.addView(overlayHost.view, lp) }
            .onFailure { GlassLog.b("Float") { "addView 失败: ${it.message}" } }
    }

    // ============ 手势 / 交互回调 ============
    private fun onBallTap(activeFile: Boolean) {
        setMode(if (activeFile) FloatMode.MINI_BAR else FloatMode.MENU)
    }

    private fun onSelectClip(clipId: String) {
        lifecycleScope.launch {
            val ok = playback.setCurrentClip(clipId)
            if (ok) setMode(FloatMode.MINI_BAR)
            else GlassLog.b("Float") { "选中片段失败: $clipId" }
        }
    }

    private fun onSeek(frac: Float, durationMs: Long) {
        if (durationMs <= 0) return
        val target = (durationMs * frac).toLong().coerceIn(0L, durationMs)
        lifecycleScope.launch { playback.seekTo(target) }
    }

    private fun onDragBy(dx: Float, dy: Float) {
        val lp = params ?: return
        val h = host ?: return
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
    }

    private fun onDragEnd() {
        // 松手后停在原地，仅做边界兜底，允许自由悬停（不贴边）
        clampToBounds()
    }

    private fun setMode(mode: FloatMode) {
        modeFlow.value = mode
        // 展开态（迷你条/菜单）确保不超出屏幕右边
        if (mode != FloatMode.BALL) clampExpandedX()
    }

    /** 边界兜底：把悬浮球约束在屏幕内，但不贴边，保留自由悬停位置。 */
    private fun clampToBounds() {
        val lp = params ?: return
        val h = host ?: return
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val viewW = h.view.width.takeIf { it > 0 } ?: dpToPx(56)
        val viewH = h.view.height.takeIf { it > 0 } ?: dpToPx(56)
        lp.x = lp.x.coerceIn(0, (screenW - viewW).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screenH - viewH).coerceAtLeast(0))
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
    }

    private fun clampExpandedX() {
        val lp = params ?: return
        val h = host ?: return
        val screenW = resources.displayMetrics.widthPixels
        val panelW = dpToPx(300)
        if (lp.x + panelW > screenW) lp.x = (screenW - panelW).coerceAtLeast(8)
        if (lp.x < 8) lp.x = 8
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
    }

    private fun sizeToDp(size: FloatingSize): Dp = when (size) {
        FloatingSize.SMALL -> 44.dp
        FloatingSize.LARGE -> 72.dp
        else -> 56.dp   // STANDARD / UNRECOGNIZED
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        fun start(ctx: Context) {
            if (!Settings.canDrawOverlays(ctx)) return
            ctx.startService(Intent(ctx, FloatingWindowService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FloatingWindowService::class.java))
        }
    }
}
