package io.mo.glassmic.service

import android.graphics.BitmapFactory
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mo.glassmic.R
import io.mo.glassmic.ui.theme.LocalGlassEnabled
import io.mo.glassmic.ui.theme.LocalReduceMotion
import kotlinx.coroutines.flow.Flow
import java.io.File

// ============ 悬浮窗数据模型（不直接暴露 Room 实体）============

enum class FloatMode { BALL, MINI_BAR, MENU, TTS, TTS_SETTINGS }

data class FloatGroupItem(val id: String, val emoji: String, val name: String)
data class FloatClipItem(val id: String, val name: String, val isCurrent: Boolean)

/**
 * 展开态面板固定宽度。四种展开态共用同一个宽度，切 tab 时面板才不会横向跳动。
 * 服务端做右边缘避让时也按这个值算 —— 改宽度只改这里，不要再在别处写死数字。
 */
const val EXPANDED_PANEL_WIDTH_DP = 308
val EXPANDED_PANEL_WIDTH: Dp = EXPANDED_PANEL_WIDTH_DP.dp

/** 顶部分段切换器的两个 tab 序号。 */
private const val TAB_TTS = 0
private const val TAB_LIBRARY = 1

/**
 * 悬浮窗根 UI。五态：球 / 迷你播放条 / 音频库 / 文字转语音 / TTS 设置。
 * 所有位置/窗口管理由 Service 处理，这里只负责渲染与回调。
 */
@Composable
fun FloatingBubbleRoot(
    mode: FloatMode,
    activeFile: Boolean,
    paused: Boolean,
    positionMs: Long,
    durationMs: Long,
    currentName: String?,
    sizeDp: Dp,
    iconPath: String?,
    opacity: Float,
    groups: List<FloatGroupItem>,
    clipsProvider: (String) -> Flow<List<FloatClipItem>>,
    onBallTap: () -> Unit,
    onTogglePause: () -> Unit,
    onSeek: (Float) -> Unit,
    onOpenMenu: () -> Unit,
    onCollapse: () -> Unit,
    onSelectClip: (String) -> Unit,
    onOpenTts: () -> Unit,
    ttsGenerating: Boolean,
    ttsReady: Boolean,
    ttsFailed: Boolean,
    onGenerateTts: (String) -> Unit,
    onPlayTts: () -> Unit,
    ttsProgressBarEnabled: Boolean,
    ttsActive: Boolean,
    onOpenTtsSettings: () -> Unit,
    onToggleTtsProgressBar: (Boolean) -> Unit,
    ttsDelayMs: Int,
    onSetTtsDelay: (Int) -> Unit,
    ttsDelayRemainingMs: Long,
    onCancelDelayedTts: () -> Unit,
    onSeekTts: (Float) -> Unit,
    onCloseTtsSettings: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    // TTS 草稿提到这一层：分段切换器换 tab 会重建下方内容，草稿放在 TtsTab 内部会被清空。
    // FloatingBubbleRoot 在模式切换之间始终在位，remember 得以存活。
    var ttsDraft by remember { mutableStateOf("") }
    // 音频库当前展开的分组，同理提到这一层，切走再切回来还停在原分组。
    var selectedGroup by remember { mutableStateOf<FloatGroupItem?>(null) }

    when (mode) {
        FloatMode.BALL -> Ball(
            sizeDp = sizeDp,
            iconPath = iconPath,
            opacity = opacity,
            active = activeFile && !paused,
            positionMs = positionMs,
            durationMs = durationMs,
            onTap = onBallTap,
            onDragBy = onDragBy,
            onDragEnd = onDragEnd
        )

        FloatMode.MINI_BAR -> MiniBar(
            paused = paused,
            positionMs = positionMs,
            durationMs = durationMs,
            currentName = currentName,
            onTogglePause = onTogglePause,
            onSeek = onSeek,
            onOpenMenu = onOpenMenu,
            onCollapse = onCollapse,
            onDragBy = onDragBy,
            onDragEnd = onDragEnd
        )

        // 音频库 / 文字转语音共用同一个外壳，顶部分段切换器就地互换下方内容。
        // 注意这里刻意不合并成一个 FloatMode：切 tab 仍然走 Service 的 setMode，
        // 输入法焦点（FLAG_NOT_FOCUSABLE / FLAG_NOT_TOUCH_MODAL）那套逻辑得以原样复用。
        FloatMode.MENU, FloatMode.TTS -> {
            val onTts = mode == FloatMode.TTS
            ExpandedPanel(
                onCollapse = onCollapse,
                onDragBy = onDragBy,
                onDragEnd = onDragEnd,
                tabIndex = if (onTts) TAB_TTS else TAB_LIBRARY,
                onSelectTab = { if (it == TAB_TTS) onOpenTts() else onOpenMenu() },
                titleTrailing = {
                    if (onTts) {
                        IconChip(
                            icon = Icons.Filled.Settings,
                            desc = stringResource(R.string.float_settings),
                            onClick = onOpenTtsSettings
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
            ) {
                if (onTts) {
                    TtsTab(
                        text = ttsDraft,
                        onTextChange = { ttsDraft = it },
                        generating = ttsGenerating,
                        ready = ttsReady,
                        failed = ttsFailed,
                        onGenerate = onGenerateTts,
                        onPlay = onPlayTts,
                        progressBarEnabled = ttsProgressBarEnabled,
                        ttsActive = ttsActive,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        onSeek = onSeekTts,
                        delayRemainingMs = ttsDelayRemainingMs,
                        onCancelDelayed = onCancelDelayedTts
                    )
                } else {
                    LibraryTab(
                        groups = groups,
                        selectedGroup = selectedGroup,
                        onSelectGroup = { selectedGroup = it },
                        clipsProvider = clipsProvider,
                        onSelectClip = onSelectClip
                    )
                }
            }
        }

        FloatMode.TTS_SETTINGS -> TtsSettingsPanel(
            progressBarEnabled = ttsProgressBarEnabled,
            onToggleProgressBar = onToggleTtsProgressBar,
            delayMs = ttsDelayMs,
            onSetDelay = onSetTtsDelay,
            onBack = onCloseTtsSettings,
            onCollapse = onCollapse,
            onDragBy = onDragBy,
            onDragEnd = onDragEnd
        )
    }
}

/**
 * 拖动手柄：贴到展开态面板的标题栏上，让面板在展开时也能整体拖动。
 * 标题栏里的按钮/「收起」等子元素自身 clickable，会优先消费点击，不影响拖动空白处。
 */
private fun Modifier.dragHandle(
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
): Modifier = this.pointerInput(Unit) {
    detectDragGestures(onDragEnd = { onDragEnd() }) { change, drag ->
        change.consume()
        onDragBy(drag.x, drag.y)
    }
}

// ============ 展开态共享外壳 ============

/**
 * 所有展开态面板的统一外壳：玻璃底 + 可拖动标题栏 +（可选）顶部分段切换器 + 内容。
 *
 * [tabIndex] 为 null 时不显示分段切换器（TTS 设置这类二级页用）。
 * [onBack] 非空时标题栏左侧显示返回箭头，否则显示拖动握把。
 */
@Composable
private fun ExpandedPanel(
    onCollapse: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    tabIndex: Int? = null,
    onSelectTab: (Int) -> Unit = {},
    title: String? = null,
    onBack: (() -> Unit)? = null,
    titleTrailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassPanel(modifier = Modifier.width(EXPANDED_PANEL_WIDTH)) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .dragHandle(onDragBy, onDragEnd)
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        stringResource(R.string.float_back),
                        tint = OverlayColors.OnDark,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onBack)
                            .padding(3.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    // 拖动握把：给用户一个「这条可以拖」的视觉暗示
                    Text("⠿", color = OverlayColors.OnDarkFaint, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = title.orEmpty(),
                    color = OverlayColors.OnDark,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                titleTrailing()
                Text(
                    stringResource(R.string.float_collapse),
                    color = OverlayColors.OnDarkDim,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(OverlayShapes.Chip))
                        .clickable(onClick = onCollapse)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }

            if (tabIndex != null) {
                SegmentedSwitch(
                    options = listOf(
                        stringResource(R.string.float_tab_tts),
                        stringResource(R.string.float_tab_library)
                    ),
                    selectedIndex = tabIndex,
                    onSelect = onSelectTab,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            content()
        }
    }
}

// ============ 球态 ============
@Composable
private fun Ball(
    sizeDp: Dp,
    iconPath: String?,
    opacity: Float,
    active: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTap: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val glass = LocalGlassEnabled.current
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val bitmap = remember(iconPath) {
        iconPath?.takeIf { File(it).exists() }
            ?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() }
    }

    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = if (reduceMotion) snap() else tween(120, easing = FastOutSlowInEasing),
        label = "ballScale"
    )
    // 呼吸：播放中让描边/进度轨微微起伏，静止时保持恒定，避免无谓的持续重绘
    val breath = if (active && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "ballBreath")
        transition.animateFloat(
            initialValue = 0.18f,
            targetValue = 0.38f,
            animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "ballBreathAlpha"
        ).value
    } else {
        0.22f
    }

    // 进度环画在球尺寸「之内」：窗口是 WRAP_CONTENT，画到外面会撑大窗口，
    // 收起时 clampToBounds 会按新尺寸把球往屏幕里推。
    val ringBand = 4.dp
    val innerSize = sizeDp - ringBand * 2
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Box(
        modifier = Modifier
            .size(sizeDp)
            .alpha(opacity.coerceIn(0.2f, 1f))
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onDragEnd() }
                ) { change, drag ->
                    change.consume()
                    onDragBy(drag.x, drag.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onTap() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // 外圈：轨道 + 播放进度
        Canvas(modifier = Modifier.size(sizeDp)) {
            val stroke = 2.5.dp.toPx()
            val inset = 1.5.dp.toPx() + stroke / 2f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = Color.White.copy(alpha = 0.12f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke)
            )
            if (progress > 0f) {
                drawArc(
                    color = OverlayColors.Accent,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke)
                )
            }
        }

        // 内圈：玻璃底 + 图标
        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(CircleShape)
                .background(OverlayColors.BallBase)
                .then(
                    if (glass) {
                        // 高光偏左上，模拟球面受光；渐变坐标是像素，必须用 density 换算
                        val innerPx = with(density) { innerSize.toPx() }
                        Modifier
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(Color.White.copy(alpha = 0.20f), Color.Transparent),
                                    center = Offset(innerPx * 0.32f, innerPx * 0.22f),
                                    radius = innerPx * 0.9f
                                )
                            )
                            .border(BorderStroke(0.8.dp, Color.White.copy(alpha = breath)), CircleShape)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(innerSize).clip(CircleShape)
                )
            } else {
                Text("🎵", fontSize = (innerSize.value * 0.42f).sp)
            }
        }

        // 没有时长可展示时（未播放/流式音源），退回原来的状态指示点
        if (durationMs <= 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .size(sizeDp.value.times(0.16f).dp.coerceAtLeast(6.dp))
                    .clip(CircleShape)
                    .background(if (active) OverlayColors.Accent else OverlayColors.Idle)
            )
        }
    }
}

// ============ 迷你播放条 ============
@Composable
private fun MiniBar(
    paused: Boolean,
    positionMs: Long,
    durationMs: Long,
    currentName: String?,
    onTogglePause: () -> Unit,
    onSeek: (Float) -> Unit,
    onOpenMenu: () -> Unit,
    onCollapse: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    GlassPanel(modifier = Modifier.width(EXPANDED_PANEL_WIDTH)) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().dragHandle(onDragBy, onDragEnd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⠿", color = OverlayColors.OnDarkFaint, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = currentName ?: "GlassMic",
                    color = OverlayColors.OnDark,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 换音源
                IconChip(Icons.Filled.Refresh, stringResource(R.string.float_change_source), onOpenMenu)
                Spacer(Modifier.width(6.dp))
                // 播放/暂停
                Text(
                    text = if (paused) "▶" else "⏸",
                    color = OverlayColors.OnDark,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(OverlayColors.FillStrong)
                        .clickable(onClick = onTogglePause)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Slider(
                value = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                onValueChange = onSeek,
                enabled = durationMs > 0
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMs(positionMs), color = OverlayColors.OnDarkDim, fontSize = 11.sp)
                Text(
                    stringResource(R.string.float_collapse),
                    color = OverlayColors.OnDarkDim,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable(onClick = onCollapse)
                )
                Text(formatMs(durationMs), color = OverlayColors.OnDarkDim, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun IconChip(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Icon(
        imageVector = icon,
        contentDescription = desc,
        tint = OverlayColors.OnDark,
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(OverlayColors.FillStrong)
            .clickable(onClick = onClick)
            .padding(6.dp)
    )
}

// ============ 音频库 tab（两级：分组 → 片段）============
@Composable
private fun LibraryTab(
    groups: List<FloatGroupItem>,
    selectedGroup: FloatGroupItem?,
    onSelectGroup: (FloatGroupItem?) -> Unit,
    clipsProvider: (String) -> Flow<List<FloatClipItem>>,
    onSelectClip: (String) -> Unit,
) {
    if (selectedGroup == null) {
        if (groups.isEmpty()) {
            EmptyHint(stringResource(R.string.float_no_groups))
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(groups, key = { it.id }) { g ->
                    GroupRow(group = g) { onSelectGroup(g) }
                }
            }
        }
    } else {
        // 顶部已被分段切换器占用，返回入口做成一条胶囊放在列表上方
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(OverlayShapes.Chip))
                .background(OverlayColors.FillWeak)
                .clickable { onSelectGroup(null) }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.float_back),
                tint = OverlayColors.OnDarkDim,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${selectedGroup.emoji}  ${selectedGroup.name}",
                color = OverlayColors.OnDark,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(6.dp))

        val clipsFlow = remember(selectedGroup.id) { clipsProvider(selectedGroup.id) }
        val clips by clipsFlow.collectAsState(initial = emptyList())
        if (clips.isEmpty()) {
            EmptyHint(stringResource(R.string.float_no_clips_in_group))
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                items(clips, key = { it.id }) { c ->
                    ClipRow(clip = c) { onSelectClip(c.id) }
                }
            }
        }
    }
}

/** 分组行：emoji 装进圆角方块，和纯文字的片段行拉开层级。 */
@Composable
private fun GroupRow(group: FloatGroupItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OverlayShapes.Card))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(OverlayShapes.Chip))
                .background(OverlayColors.FillWeak),
            contentAlignment = Alignment.Center
        ) {
            Text(group.emoji, fontSize = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = group.name,
            color = OverlayColors.OnDark,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text("›", color = OverlayColors.OnDarkFaint, fontSize = 16.sp)
    }
}

/** 片段行：当前播放项除了绿色 ✓，整行还铺一层淡绿底，扫一眼就能定位。 */
@Composable
private fun ClipRow(clip: FloatClipItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OverlayShapes.Card))
            .background(if (clip.isCurrent) OverlayColors.SelectedRow else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (clip.isCurrent) {
            Icon(
                Icons.Filled.Check,
                stringResource(R.string.float_current),
                tint = OverlayColors.Accent,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = clip.name,
            color = OverlayColors.OnDark,
            fontSize = 14.sp,
            fontWeight = if (clip.isCurrent) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

// ============ 文字转语音 tab（先生成后播放，可重复播放）============
@Composable
private fun TtsTab(
    text: String,
    onTextChange: (String) -> Unit,
    generating: Boolean,
    ready: Boolean,
    failed: Boolean,
    onGenerate: (String) -> Unit,
    onPlay: () -> Unit,
    progressBarEnabled: Boolean,
    ttsActive: Boolean,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Float) -> Unit,
    delayRemainingMs: Long,
    onCancelDelayed: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(OverlayShapes.Card)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OverlayColors.Fill)
            .border(
                BorderStroke(1.dp, if (focused) OverlayColors.Accent else OverlayColors.Border),
                shape
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = TextStyle(color = OverlayColors.OnDark, fontSize = 14.sp),
            cursorBrush = SolidColor(OverlayColors.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    Text(
                        stringResource(R.string.float_tts_input_hint),
                        color = OverlayColors.OnDarkDim,
                        fontSize = 14.sp
                    )
                }
                inner()
            }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val canGenerate = !generating && text.isNotBlank()
        PillButton(
            label = if (generating) {
                stringResource(R.string.float_tts_generating)
            } else {
                stringResource(R.string.float_tts_generate)
            },
            style = if (canGenerate) PillStyle.Secondary else PillStyle.Disabled,
            onClick = { onGenerate(text.trim()) },
            modifier = Modifier.weight(1f)
        )
        // 延时倒计时中：按钮变成「⏱ 1.5s 取消」，再点一次即取消本次播放
        val counting = delayRemainingMs > 0
        PillButton(
            label = if (counting) {
                stringResource(R.string.float_tts_delay_countdown, formatSeconds(delayRemainingMs))
            } else {
                stringResource(R.string.float_tts_play)
            },
            style = when {
                counting -> PillStyle.Danger
                ready -> PillStyle.Primary
                else -> PillStyle.Disabled
            },
            onClick = { if (counting) onCancelDelayed() else onPlay() },
            modifier = Modifier.weight(1f)
        )
    }

    // 仅在失败时提示，正常流程不堆文字
    if (failed) {
        Text(
            text = stringResource(R.string.float_tts_generate_failed),
            color = OverlayColors.Danger,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }

    // 进度条：设置里开启后，生成/播放后可拖动控制播放进度
    if (progressBarEnabled && (ready || ttsActive)) {
        val hasDuration = ttsActive && durationMs > 0
        Slider(
            value = if (hasDuration) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
            onValueChange = onSeek,
            enabled = hasDuration,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(if (hasDuration) positionMs else 0L), color = OverlayColors.OnDarkDim, fontSize = 11.sp)
            Text(formatMs(if (hasDuration) durationMs else 0L), color = OverlayColors.OnDarkDim, fontSize = 11.sp)
        }
    }
}

// ============ 文字转语音设置面板 ============
@Composable
private fun TtsSettingsPanel(
    progressBarEnabled: Boolean,
    onToggleProgressBar: (Boolean) -> Unit,
    delayMs: Int,
    onSetDelay: (Int) -> Unit,
    onBack: () -> Unit,
    onCollapse: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    ExpandedPanel(
        onCollapse = onCollapse,
        onDragBy = onDragBy,
        onDragEnd = onDragEnd,
        title = stringResource(R.string.float_tts_settings_title),
        onBack = onBack
    ) {
        // 自定义延时输入展开后内容会变长，小屏上会顶出屏幕，这里给一个滚动兜底
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 进度条开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.float_tts_progress_bar),
                    color = OverlayColors.OnDark,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = progressBarEnabled,
                    onCheckedChange = onToggleProgressBar
                )
            }
            // 延时播放：点「播放」后等多久才真正出声，留出切到目标 App 的时间
            TtsDelaySetting(delayMs = delayMs, onSetDelay = onSetDelay)
        }
    }
}

/** 延时播放：关 / 0.5s / 1s / 2s 四个预设 + 自定义秒数输入。 */
@Composable
private fun TtsDelaySetting(delayMs: Int, onSetDelay: (Int) -> Unit) {
    val presets = listOf(0, 500, 1_000, 2_000)
    // 当前值不在预设里 → 说明用的是自定义，输入框默认展开并回填
    var customOpen by remember(delayMs) { mutableStateOf(delayMs !in presets) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(
            stringResource(R.string.float_tts_delay), color = OverlayColors.OnDark, fontSize = 14.sp
        )
        Text(
            stringResource(R.string.float_tts_delay_hint), color = OverlayColors.OnDarkDim, fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            presets.forEach { preset ->
                DelayChip(
                    label = if (preset == 0) {
                        stringResource(R.string.float_tts_delay_off)
                    } else {
                        stringResource(R.string.float_tts_delay_seconds, formatSeconds(preset.toLong()))
                    },
                    selected = !customOpen && delayMs == preset,
                    onClick = {
                        customOpen = false
                        onSetDelay(preset)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            DelayChip(
                label = stringResource(R.string.float_tts_delay_custom),
                selected = customOpen,
                onClick = { customOpen = true },
                modifier = Modifier.weight(1.2f)
            )
        }
        if (customOpen) {
            CustomDelayInput(delayMs = delayMs, onSetDelay = onSetDelay)
        }
    }
}

@Composable
private fun DelayChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        color = if (selected) Color.Black else OverlayColors.OnDark,
        fontSize = 11.sp,
        maxLines = 1,
        textAlign = TextAlign.Center,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = modifier
            .clip(RoundedCornerShape(OverlayShapes.Chip))
            .background(if (selected) OverlayColors.Accent else OverlayColors.Fill)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp)
    )
}

/**
 * 自定义秒数输入。只在合法（0 < 秒 ≤ 上限）时才写配置，
 * 输入过程中的空串 / "1." 等中间态保持原值不动，避免边打字边把设置改坏。
 */
@Composable
private fun CustomDelayInput(delayMs: Int, onSetDelay: (Int) -> Unit) {
    var raw by remember(delayMs) {
        mutableStateOf(if (delayMs > 0) formatSeconds(delayMs.toLong()) else "")
    }
    val maxSeconds = FloatingWindowService.MAX_TTS_DELAY_MS / 1000
    val invalid = raw.isNotBlank() && raw.toFloatOrNull().let { it == null || it <= 0f || it > maxSeconds }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(OverlayShapes.Chip))
                .background(OverlayColors.Fill)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            BasicTextField(
                value = raw,
                onValueChange = { input ->
                    // 只收数字和小数点，避免 toFloat 每次都在异常里兜底
                    raw = input.filter { it.isDigit() || it == '.' }.take(6)
                    raw.toFloatOrNull()
                        ?.takeIf { it > 0f && it <= maxSeconds }
                        ?.let { onSetDelay((it * 1000).toInt()) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = TextStyle(color = OverlayColors.OnDark, fontSize = 13.sp),
                cursorBrush = SolidColor(OverlayColors.Accent),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (raw.isEmpty()) {
                        Text(
                            stringResource(R.string.float_tts_delay_custom_hint),
                            color = OverlayColors.OnDarkDim, fontSize = 13.sp
                        )
                    }
                    inner()
                }
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.float_tts_delay_unit), color = OverlayColors.OnDarkDim, fontSize = 12.sp
        )
    }
    if (invalid) {
        Text(
            stringResource(R.string.float_tts_delay_range, maxSeconds),
            color = OverlayColors.Danger, fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** 1500 → "1.5"，1000 → "1"：整秒不拖小数尾巴。 */
private fun formatSeconds(ms: Long): String {
    val seconds = ms / 1000.0
    return if (seconds % 1.0 == 0.0) seconds.toInt().toString() else "%.1f".format(seconds)
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text, color = OverlayColors.OnDarkDim, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 20.dp)
    )
}

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}
