package io.mo.glassmic.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.audio.PlaybackController
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class FloatingWindowService : LifecycleService() {

    @Inject lateinit var runtime: RuntimeStateHolder
    @Inject lateinit var playback: PlaybackController
    @Inject lateinit var configStore: ConfigStore

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var textView: TextView? = null
    private var playButton: TextView? = null
    private var progressBar: SeekBar? = null
    private var timeView: TextView? = null
    private var statusDot: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var userSeeking = false
    private var expanded = false
    private var floatingOpacity = 0.85f

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            GlassLog.b("Float") { "overlay permission missing; stop floating window" }
            stopSelf()
            return
        }

        showPanel()
        runtime.setFloatingVisible(true)

        lifecycleScope.launch {
            runtime.flow.collect { rt ->
                val activeFile = rt.currentSourceType == SourceType.FILE && rt.enabled && !rt.safeMode
                updateVisuals(activeFile, rt.paused, rt.positionMs, rt.durationMs)
            }
        }
        lifecycleScope.launch {
            configStore.flow.collect { cfg ->
                floatingOpacity = cfg.floatingWindow.opacity
                    .takeIf { it > 0f }
                    ?.coerceIn(0.2f, 1f)
                    ?: 0.85f
                rootView?.alpha = floatingOpacity
            }
        }
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
        rootView?.let { runCatching { windowManager?.removeView(it) } }
        rootView = null
        textView = null
        playButton = null
        progressBar = null
        timeView = null
        statusDot = null
        runtime.setFloatingVisible(false)
        super.onDestroy()
    }

    private fun showPanel() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
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
        layoutParams = params

        val dot = View(this).apply {
            background = dotBackground(active = false)
            val size = dp(8f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = dp(8f).toInt()
                gravity = Gravity.CENTER_VERTICAL
            }
        }

        val title = TextView(this).apply {
            text = "GlassMic"
            setTextColor(Color.WHITE)
            textSize = 14f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                gravity = Gravity.CENTER_VERTICAL
            }
        }

        val button = TextView(this).apply {
            text = "||"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = buttonBackground(enabled = false)
            val size = dp(30f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                leftMargin = dp(8f).toInt()
                gravity = Gravity.CENTER_VERTICAL
            }
            isEnabled = false
            setOnClickListener { playback.togglePause() }
            setOnTouchListener { _, _ -> false }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(dot)
            addView(title)
            addView(button)
        }

        val seek = SeekBar(this).apply {
            max = 1000
            progress = 0
            isEnabled = false
            splitTrack = false
            setPadding(0, dp(4f).toInt(), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(32f).toInt()
            )
            setOnTouchListener { _, _ -> false }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = Unit

                override fun onStartTrackingTouch(bar: SeekBar) {
                    userSeeking = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    val duration = runtime.value.durationMs
                    if (duration > 0) {
                        val target = duration * bar.progress / bar.max
                        lifecycleScope.launch { playback.seekTo(target) }
                    }
                    userSeeking = false
                }
            })
        }

        val time = TextView(this).apply {
            text = "00:00 / 00:00"
            setTextColor(Color.argb(0xCC, 0xFF, 0xFF, 0xFF))
            textSize = 11f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = idleBackground()
            minimumWidth = dp(240f).toInt()
            setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(9f).toInt())
            addView(topRow)
            addView(seek)
            addView(time)
        }

        attachInteractions(container, params)

        rootView = container
        rootView?.alpha = floatingOpacity
        textView = title
        playButton = button
        progressBar = seek
        timeView = time
        statusDot = dot
        setExpanded(false, updateWindow = false)
        wm.addView(container, params)
    }

    private fun attachInteractions(view: View, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var dragging = false
        var downTime = 0L

        view.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = ev.rawX
                    touchStartY = ev.rawY
                    dragging = false
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - touchStartX
                    val dy = ev.rawY - touchStartY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        runCatching { windowManager?.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging && System.currentTimeMillis() - downTime < 400) {
                        v.performClick()
                    } else if (dragging) {
                        snapToEdge(v, params)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        view.setOnClickListener {
            setExpanded(!expanded)
        }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val screenWidth = resources.displayMetrics.widthPixels
        val viewWidth = view.width.takeIf { it > 0 } ?: dp(240f).toInt()
        val centerX = params.x + viewWidth / 2
        params.x = if (centerX < screenWidth / 2) 8 else screenWidth - viewWidth - 8
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun updateVisuals(activeFile: Boolean, paused: Boolean, positionMs: Long, durationMs: Long) {
        val root = rootView ?: return
        val title = textView ?: return
        val dot = statusDot ?: return
        val button = playButton ?: return
        val seek = progressBar ?: return
        val time = timeView ?: return

        title.text = when {
            !activeFile -> "GlassMic"
            expanded -> if (paused) "Paused" else "Playing"
            else -> "${formatMs(positionMs)} / ${formatMs(durationMs)}"
        }

        button.text = if (paused) ">" else "||"
        button.isEnabled = activeFile
        button.alpha = if (activeFile) 1f else 0.45f
        button.background = buttonBackground(enabled = activeFile)

        seek.isEnabled = activeFile && durationMs > 0
        seek.alpha = if (seek.isEnabled) 1f else 0.45f
        if (!userSeeking) {
            seek.progress = if (durationMs > 0) {
                ((positionMs.coerceIn(0L, durationMs) * seek.max) / durationMs).toInt()
            } else {
                0
            }
        }

        time.text = if (activeFile) {
            "${formatMs(positionMs)} / ${formatMs(durationMs)}"
        } else {
            "00:00 / 00:00"
        }

        dot.background = dotBackground(activeFile && !paused)
        root.background = when {
            activeFile && expanded -> activeBackground(paused)
            activeFile -> capsuleBackground(paused)
            else -> idleBackground()
        }
    }

    private fun setExpanded(value: Boolean, updateWindow: Boolean = true) {
        if (expanded == value && updateWindow) return
        expanded = value

        val root = rootView ?: return
        val title = textView ?: return
        val button = playButton ?: return
        val seek = progressBar ?: return
        val time = timeView ?: return

        title.layoutParams = LinearLayout.LayoutParams(
            if (expanded) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            weight = if (expanded) 1f else 0f
            gravity = Gravity.CENTER_VERTICAL
        }
        button.visibility = if (expanded) View.VISIBLE else View.GONE
        seek.visibility = if (expanded) View.VISIBLE else View.GONE
        time.visibility = if (expanded) View.VISIBLE else View.GONE
        root.minimumWidth = if (expanded) dp(240f).toInt() else 0
        root.setPadding(
            dp(14f).toInt(),
            dp(if (expanded) 8f else 8f).toInt(),
            dp(14f).toInt(),
            dp(if (expanded) 9f else 8f).toInt()
        )

        val rt = runtime.value
        val activeFile = rt.currentSourceType == SourceType.FILE && rt.enabled && !rt.safeMode
        updateVisuals(activeFile, rt.paused, rt.positionMs, rt.durationMs)

        if (updateWindow) {
            layoutParams?.let { params ->
                runCatching { windowManager?.updateViewLayout(root, params) }
            }
        }
    }

    private fun activeBackground(paused: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(18f)
        setColor(Color.argb(0xEE, 0x1C, 0x1C, 0x20))
        setStroke(
            dp(1f).toInt(),
            if (paused) Color.argb(0x88, 0xFF, 0xCC, 0x33)
            else Color.argb(0x88, 0x34, 0xC7, 0x59)
        )
    }

    private fun idleBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(20f)
        setColor(Color.argb(0xCC, 0x1C, 0x1C, 0x20))
        setStroke(dp(1f).toInt(), Color.argb(0x40, 0xFF, 0xFF, 0xFF))
    }

    private fun capsuleBackground(paused: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(20f)
        setColor(Color.argb(0xEE, 0x1C, 0x1C, 0x20))
        setStroke(
            dp(1f).toInt(),
            if (paused) Color.argb(0x66, 0xFF, 0xCC, 0x33)
            else Color.argb(0x66, 0x34, 0xC7, 0x59)
        )
    }

    private fun dotBackground(active: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(
            if (active) Color.argb(0xFF, 0x34, 0xC7, 0x59)
            else Color.argb(0xFF, 0x9E, 0x9E, 0xA0)
        )
    }

    private fun buttonBackground(enabled: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(15f)
        setColor(
            if (enabled) Color.argb(0x44, 0xFF, 0xFF, 0xFF)
            else Color.argb(0x22, 0xFF, 0xFF, 0xFF)
        )
        setStroke(dp(1f).toInt(), Color.argb(0x44, 0xFF, 0xFF, 0xFF))
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun formatMs(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }

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
