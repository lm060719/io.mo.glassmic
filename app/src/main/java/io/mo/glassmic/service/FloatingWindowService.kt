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
import androidx.compose.runtime.CompositionLocalProvider
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
import io.mo.glassmic.data.config.AppLocale
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.proto.AppConfig
import io.mo.glassmic.proto.FloatingSize
import io.mo.glassmic.ui.theme.LocalGlassEnabled
import io.mo.glassmic.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Job
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

    /**
     * 悬浮球的锚点位置（用户拖动决定）。
     * 展开态（菜单/迷你条/TTS）面板比球宽，靠右时会被 [clampExpandedX] 临时左移避让屏幕边缘，
     * 但该偏移不写回锚点；收起时按锚点还原，否则球会一次次被"推"到屏幕左侧。
     */
    private var anchorX = DEFAULT_X
    private var anchorY = DEFAULT_Y

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
        return START_NOT_STICKY
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
            x = anchorX
            y = anchorY
        }
        params = lp

        // Service 不是 Activity，Android 13 以下不会自动跟随应用内切换的语言，
        // 这里手动包一层 Locale Context 传给 ComposeView，保证悬浮窗文案也能正确显示。
        val overlayHost = FloatingOverlayHost(AppLocale.wrap(this)).also { it.onCreate() }
        host = overlayHost
        overlayHost.setContent {
            // 悬浮窗以前只包了裸 MaterialTheme{}，Slider/Switch 会用 Material3 默认紫色，
            // 和面板里的绿色 Accent 打架。挂上 OverlayColorScheme 后它们自动跟随主色。
            MaterialTheme(colorScheme = OverlayColorScheme) {
                val rt by runtime.flow.collectAsState()
                val cfg by configStore.flow.collectAsState(initial = AppConfig.getDefaultInstance())
                val groups by audioDao.observeGroups().collectAsState(initial = emptyList())
                val allClips by audioDao.observeAllClips().collectAsState(initial = emptyList())
                val mode by modeFlow.collectAsState()
                val ttsGen by playback.ttsGen.collectAsState()
                val ttsDelayRemainingMs by playback.ttsDelayRemainingMs.collectAsState()

                val activeFile = rt.currentSourceType == SourceType.FILE && rt.enabled && !rt.safeMode
                val currentId = cfg.currentAudioId
                val currentName = allClips.firstOrNull { it.id == currentId }?.displayName

                // 悬浮窗不走 GlassMicTheme，这两个外观开关得在这里手动注入
                CompositionLocalProvider(
                    LocalGlassEnabled provides cfg.appearance.glassEffect,
                    LocalReduceMotion provides cfg.appearance.reduceMotion,
                ) {
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
                    onOpenTts = { setMode(FloatMode.TTS) },
                    ttsGenerating = ttsGen == PlaybackController.TtsGen.GENERATING,
                    ttsReady = ttsGen == PlaybackController.TtsGen.READY,
                    ttsFailed = ttsGen == PlaybackController.TtsGen.FAILED,
                    onGenerateTts = { text -> onGenerateTts(text) },
                    onPlayTts = { onPlayTts() },
                    ttsProgressBarEnabled = cfg.tts.progressBarEnabled,
                    ttsActive = rt.currentSourceType == SourceType.TTS,
                    onOpenTtsSettings = { setMode(FloatMode.TTS_SETTINGS) },
                    onToggleTtsProgressBar = { enabled -> onToggleTtsProgressBar(enabled) },
                    ttsDelayMs = cfg.tts.delayMs,
                    onSetTtsDelay = { ms -> onSetTtsDelay(ms) },
                    ttsDelayRemainingMs = ttsDelayRemainingMs,
                    onCancelDelayedTts = { onCancelDelayedTts() },
                    onSeekTts = { frac -> onSeek(frac, rt.durationMs) },
                    onCloseTtsSettings = { setMode(FloatMode.TTS) },
                    onDragBy = { dx, dy -> onDragBy(dx, dy) },
                    onDragEnd = { onDragEnd() },
                )
                }
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

    private fun onGenerateTts(text: String) {
        lifecycleScope.launch {
            val ok = playback.generateTts(text)
            if (!ok) GlassLog.b("Float") { "TTS 生成失败（文本为空或引擎不可用）" }
        }
    }

    /** 延时播放期间持有倒计时协程，供再次点击时取消。 */
    private var ttsPlayJob: Job? = null

    private fun onPlayTts() {
        ttsPlayJob = lifecycleScope.launch {
            val ok = playback.playGeneratedTts()
            if (!ok) GlassLog.b("Float") { "TTS 播放失败（尚未生成）" }
        }
    }

    /** 倒计时中再点一次播放按钮 → 取消，不出声。 */
    private fun onCancelDelayedTts() {
        ttsPlayJob?.cancel()
        ttsPlayJob = null
        playback.clearTtsDelayCountdown()
    }

    private fun onToggleTtsProgressBar(enabled: Boolean) {
        lifecycleScope.launch {
            configStore.update {
                it.setTts(it.tts.toBuilder().setProgressBarEnabled(enabled).build())
            }
        }
    }

    private fun onSetTtsDelay(delayMs: Int) {
        lifecycleScope.launch {
            configStore.update {
                it.setTts(it.tts.toBuilder().setDelayMs(delayMs.coerceIn(0, MAX_TTS_DELAY_MS)).build())
            }
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
        // 用户主动拖动即视为重新选定位置：同步锚点，收起时才不会跳回旧位置
        params?.let { anchorX = it.x; anchorY = it.y }
    }

    private fun setMode(mode: FloatMode) {
        modeFlow.value = mode
        // TTS 面板与其设置面板（自定义延时输入）需要输入法焦点，其它态保持不可聚焦（不拦截触摸）
        setWindowFocusable(mode == FloatMode.TTS || mode == FloatMode.TTS_SETTINGS)
        // 展开态（迷你条/菜单/TTS）确保不超出屏幕右边；收起时还原到球的锚点
        if (mode != FloatMode.BALL) clampExpandedX() else restoreAnchor()
    }

    /**
     * 收起为球态时还原用户拖动过的锚点位置。
     * 边界兜底放到 post 里执行：此刻 view 仍是展开态的宽度，需等重新测量成球的尺寸后再 clamp，
     * 否则会用面板宽度去约束球，把靠右的球再次推向左边。
     */
    private fun restoreAnchor() {
        val lp = params ?: return
        val h = host ?: return
        lp.x = anchorX
        lp.y = anchorY
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
        h.view.post { clampToBounds() }
    }

    /**
     * 切换悬浮窗是否可获得焦点。
     * TTS 输入需要软键盘 → 去掉 FLAG_NOT_FOCUSABLE；其它态恢复不可聚焦，避免拦截系统触摸。
     */
    private fun setWindowFocusable(focusable: Boolean) {
        val lp = params ?: return
        val h = host ?: return
        lp.flags = if (focusable) {
            // 可聚焦（软键盘）但仍放行窗口外的触摸：FLAG_NOT_FOCUSABLE 会隐式带上
            // FLAG_NOT_TOUCH_MODAL，去掉它后必须显式补回，否则整屏触摸都会被悬浮窗吞掉
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
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
        val panelW = dpToPx(EXPANDED_PANEL_WIDTH_DP)
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
        private const val DEFAULT_X = 24
        private const val DEFAULT_Y = 300

        /** 自定义延时上限 60s：再长就该用别的方式了，也避免误输入把语音永远压住。 */
        const val MAX_TTS_DELAY_MS = 60_000

        fun start(ctx: Context) {
            if (!Settings.canDrawOverlays(ctx)) return
            ctx.startService(Intent(ctx, FloatingWindowService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FloatingWindowService::class.java))
        }
    }
}
