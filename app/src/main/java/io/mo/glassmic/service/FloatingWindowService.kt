package io.mo.glassmic.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.mo.glassmic.R
import io.mo.glassmic.core.Constants
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.audio.FloatingIconStore
import io.mo.glassmic.data.audio.PlaybackController
import io.mo.glassmic.data.config.AppLocale
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.data.runtime.VolumeShortcutRepository
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.proto.AppConfig
import io.mo.glassmic.proto.FloatingSize
import io.mo.glassmic.ui.theme.GlassThemeContent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
    @Inject lateinit var volumeShortcut: VolumeShortcutRepository

    private var windowManager: WindowManager? = null
    private var host: FloatingOverlayHost? = null
    private var params: WindowManager.LayoutParams? = null

    private val modeFlow = MutableStateFlow(FloatMode.BALL)
    private val overlayBoundsFlow = MutableStateFlow(Rect())
    private val clampRunnable = Runnable { clampToBounds() }

    /**
     * 悬浮球的锚点位置（用户拖动决定）。
     * 展开态（菜单/迷你条/TTS）面板比球宽，靠右时会被 [clampToBounds] 临时左移避让屏幕边缘，
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
        // 音量键快捷操作只在悬浮窗存活期间布防，开关变化即时生效
        lifecycleScope.launch {
            configStore.flow
                .map { it.shortcuts.volumeKeysEnabled }
                .distinctUntilChanged()
                .collect { applyVolumeShortcut(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshOverlayBounds()
        host?.view?.requestApplyInsets()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        // 先撤防再拆窗口：system_server 侧一旦撤防就立刻停止拦截音量键，
        // 不会留下「悬浮窗已关、音量键却还发涩」的空窗期。
        applyVolumeShortcut(false)
        host?.let { h ->
            h.view.removeCallbacks(clampRunnable)
            runCatching { windowManager?.removeView(h.view) }
            h.onDestroy()
        }
        host = null
        runtime.setFloatingVisible(false)
        super.onDestroy()
    }

    // ============ 音量键双击快捷操作 ============

    /**
     * 接收 Xposed 侧（system_server）检测到的双击。
     *
     * 必须注册为 EXPORTED 才收得到来自 system_server 的广播，因此用 token 校验来源——
     * token 由本进程随机生成、写进只有模块读得到的 remote preferences，第三方 App 伪造不出来。
     */
    private val shortcutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Constants.ACTION_VOLUME_SHORTCUT) return
            if (!volumeShortcut.verifyToken(intent.getStringExtra(Constants.EXTRA_SHORTCUT_TOKEN))) {
                GlassLog.b("VolKey") { "丢弃 token 不匹配的快捷操作广播" }
                return
            }
            when (intent.getStringExtra(Constants.EXTRA_SHORTCUT_ACTION)) {
                Constants.SHORTCUT_PLAY -> onShortcutPlay()
                Constants.SHORTCUT_PAUSE -> playback.pause()
            }
        }
    }

    private var shortcutArmed = false

    private fun applyVolumeShortcut(enabled: Boolean) {
        if (enabled == shortcutArmed) return
        if (enabled) {
            val filter = IntentFilter(Constants.ACTION_VOLUME_SHORTCUT)
            val registered = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(shortcutReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(shortcutReceiver, filter)
                }
            }.onFailure {
                GlassLog.b("VolKey") { "注册快捷操作接收器失败: ${it.message}" }
            }.isSuccess
            if (!registered) return
            shortcutArmed = true
            // 先注册后布防：布防之后模块才会开始发广播，不会有收不到的一瞬
            volumeShortcut.setArmed(true)
        } else {
            volumeShortcut.setArmed(false)
            runCatching { unregisterReceiver(shortcutReceiver) }
            shortcutArmed = false
        }
    }

    /**
     * 双击音量上键 = 播放。
     *
     * 冷启动后还没选过音源时（运行态仍是默认的 REAL_MIC），先把上次持久化的片段装回来，
     * 否则 resume() 无声可出，用户会以为快捷键没反应。
     */
    private fun onShortcutPlay() {
        lifecycleScope.launch {
            val src = runtime.flow.value.currentSourceType
            if (src != SourceType.FILE && src != SourceType.TTS) {
                playback.restorePersistedClip()
            }
            playback.resume()
        }
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
        refreshOverlayBounds()
        overlayHost.view.setOnApplyWindowInsetsListener { _, insets ->
            refreshOverlayBounds(insets)
            insets
        }
        // 等 Compose 完成实际测量后再约束位置；分组加载、字号变化和旋转都可能改变高度。
        overlayHost.view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                scheduleClampToBounds()
            }
        }
        overlayHost.setContent {
            val cfgState = configStore.flow.collectAsState(initial = AppConfig.getDefaultInstance())
            val appearance = cfgState.value.appearance
            // 悬浮窗不走 GlassMicTheme（需要 ViewModel），直接用同一套主题外壳：跟随 App 的深浅色与「卡片透明」设置
            GlassThemeContent(appearance.theme, appearance.glassEffect, appearance.reduceMotion) {
                val rt by runtime.flow.collectAsState()
                val cfg by cfgState
                val groups by audioDao.observeGroups().collectAsState(initial = emptyList())
                val allClips by audioDao.observeAllClips().collectAsState(initial = emptyList())
                val mode by modeFlow.collectAsState()
                val overlayBounds by overlayBoundsFlow.collectAsState()
                val density = LocalDensity.current
                val ttsGen by playback.ttsGen.collectAsState()
                val ttsPreviewing by playback.ttsPreviewing.collectAsState()
                val ttsDelayRemainingMs by playback.ttsDelayRemainingMs.collectAsState()

                val activeFile = (rt.currentSourceType == SourceType.FILE || rt.currentSourceType == SourceType.TTS) && rt.enabled && !rt.safeMode
                val currentId = cfg.currentAudioId
                val currentName = if (rt.currentSourceType == SourceType.TTS) stringResource(R.string.source_tts)
                                  else allClips.firstOrNull { it.id == currentId }?.displayName

                FloatingBubbleRoot(
                    playbackPolicy = cfg.playbackPolicy,
                    onSetPlaybackPolicy = { policy ->
                        lifecycleScope.launch { configStore.update { it.setPlaybackPolicy(policy) } }
                    },
                    mode = mode,
                    panelMaxWidth = with(density) { overlayBounds.width().toDp() },
                    panelMaxHeight = with(density) { overlayBounds.height().toDp() },
                    activeFile = activeFile,
                    paused = rt.paused,
                    isStreaming = rt.isStreaming,
                    audioMonitorEnabled = cfg.audioMonitor.enabled,
                    onToggleAudioMonitor = ::toggleAudioMonitor,
                    positionMs = rt.positionMs,
                    durationMs = rt.durationMs,
                    currentName = currentName,
                    currentGroupId = cfg.currentGroupId.takeIf { it.isNotBlank() },
                    sizeDp = sizeToDp(cfg.floatingWindow.size),
                    iconPath = cfg.floatingWindow.customIconPath.takeIf { it.isNotBlank() }
                        ?.let { iconStore.iconFile(it).absolutePath },
                    opacity = cfg.floatingWindow.opacity.takeIf { it > 0f } ?: 0.85f,
                    groups = groups.map { FloatGroupItem(it.id, it.emoji, it.name) },
                    clipsProvider = { gid ->
                        audioDao.observeClipsInGroup(gid).map { list ->
                            list.map { FloatClipItem(it.id, it.displayName, it.id == currentId, it.durationMs) }
                        }
                    },
                    onBallTap = { onBallTap(rt.currentSourceType == SourceType.TTS) },
                    onTogglePause = { playback.togglePause() },
                    onSeek = { frac -> onSeek(frac, rt.durationMs) },
                    onOpenMenu = { setMode(FloatMode.MENU) },
                    onCollapse = { setMode(FloatMode.BALL) },
                    onSelectClip = { clipId -> onSelectClip(clipId) },
                    onOpenTts = { setMode(FloatMode.TTS) },
                    ttsGenerating = ttsGen == PlaybackController.TtsGen.GENERATING,
                    ttsReady = ttsGen == PlaybackController.TtsGen.READY,
                    ttsFailed = ttsGen == PlaybackController.TtsGen.FAILED,
                    ttsPreviewing = ttsPreviewing,
                    onGenerateTts = { text -> onGenerateTts(text) },
                    onTogglePreviewTts = { onTogglePreviewTts() },
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
        runCatching { wm.addView(overlayHost.view, lp) }
            .onFailure { GlassLog.b("Float") { "addView 失败: ${it.message}" } }
    }

    // ============ 手势 / 交互回调 ============
    /** 轻点悬浮球直接展开面板；当前音源是文字转语音时停在 TTS 页。 */
    private fun onBallTap(ttsSource: Boolean) {
        setMode(if (ttsSource) FloatMode.TTS else FloatMode.MENU)
    }

    private fun toggleAudioMonitor() {
        lifecycleScope.launch {
            configStore.update {
                val cur = it.audioMonitor
                it.setAudioMonitor(cur.toBuilder().setEnabled(!cur.enabled).build())
            }
        }
    }

    private fun onTogglePreviewTts() {
        lifecycleScope.launch {
            playback.togglePreviewTts()
        }
    }

    private fun onSelectClip(clipId: String) {
        lifecycleScope.launch {
            val ok = playback.setCurrentClip(clipId)
            // 选中后停留在音频库面板，当前项高亮即为反馈
            if (!ok) GlassLog.b("Float") { "选中片段失败: $clipId" }
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
        // 展开后的边界约束由布局监听按实际尺寸执行；收起时还原到球的锚点。
        if (mode == FloatMode.BALL) restoreAnchor()
    }

    /**
     * 收起为球态时还原用户拖动过的锚点位置。
     * 边界兜底由布局监听执行：此刻 view 仍是展开态的宽度，需等重新测量成球的尺寸后再 clamp，
     * 否则会用面板宽度去约束球，把靠右的球再次推向左边。
     */
    private fun restoreAnchor() {
        val lp = params ?: return
        val h = host ?: return
        lp.x = anchorX
        lp.y = anchorY
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
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

    /** 屏幕安全区域，始终预留系统栏、刘海和 8dp 边距。Android 10 使用稳定 Insets。 */
    @Suppress("DEPRECATION")
    private fun refreshOverlayBounds(appliedInsets: WindowInsets? = host?.view?.rootWindowInsets) {
        val wm = windowManager ?: return
        val bounds: Rect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            bounds = Rect(metrics.bounds)
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            bounds.inset(insets.left, insets.top, insets.right, insets.bottom)
        } else {
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            bounds = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
            appliedInsets?.let { insets ->
                val cutout = insets.displayCutout
                bounds.inset(
                    maxOf(insets.stableInsetLeft, cutout?.safeInsetLeft ?: 0),
                    maxOf(insets.stableInsetTop, cutout?.safeInsetTop ?: 0),
                    maxOf(insets.stableInsetRight, cutout?.safeInsetRight ?: 0),
                    maxOf(insets.stableInsetBottom, cutout?.safeInsetBottom ?: 0)
                )
            }
        }
        val margin = dpToPx(8)
        if (bounds.width() > margin * 2 && bounds.height() > margin * 2) {
            bounds.inset(margin, margin)
        }
        if (bounds.isEmpty || bounds == overlayBoundsFlow.value) return
        overlayBoundsFlow.value = bounds
        scheduleClampToBounds()
    }

    /** 合并同一帧的 Insets / 布局回调，避免对尚未生效的位置重复叠加修正量。 */
    private fun scheduleClampToBounds() {
        val view = host?.view ?: return
        view.removeCallbacks(clampRunnable)
        view.postOnAnimation(clampRunnable)
    }

    /** 以实际屏幕坐标约束窗口，避免不同系统的悬浮窗原点/系统栏偏移导致越界。 */
    private fun clampToBounds() {
        val lp = params ?: return
        val h = host ?: return
        if (!h.view.isAttachedToWindow || h.view.width == 0 || h.view.height == 0) return
        val bounds = overlayBoundsFlow.value
        if (bounds.isEmpty) return
        val location = IntArray(2)
        h.view.getLocationOnScreen(location)
        val targetX = location[0].coerceIn(bounds.left, maxOf(bounds.left, bounds.right - h.view.width))
        val targetY = location[1].coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - h.view.height))
        if (targetX == location[0] && targetY == location[1]) return
        lp.x += targetX - location[0]
        lp.y += targetY - location[1]
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
