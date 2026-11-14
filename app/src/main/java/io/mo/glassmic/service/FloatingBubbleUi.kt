package io.mo.glassmic.service

import android.graphics.BitmapFactory
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Stable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mo.glassmic.R
import io.mo.glassmic.audio.OutputGain
import io.mo.glassmic.audio.tts.TtsProgress
import io.mo.glassmic.proto.PlaybackPolicy
import io.mo.glassmic.ui.common.AppIcon
import io.mo.glassmic.ui.common.Dot
import io.mo.glassmic.ui.common.GlassSlider
import io.mo.glassmic.ui.common.GlassTextField
import io.mo.glassmic.ui.common.GlassToggle
import io.mo.glassmic.ui.common.MonoFamily
import io.mo.glassmic.ui.common.Segmented
import io.mo.glassmic.ui.common.glass
import io.mo.glassmic.ui.common.liquidClickable
import io.mo.glassmic.ui.theme.LocalReduceMotion
import kotlinx.coroutines.flow.Flow
import java.io.File


// ============ 悬浮窗数据模型（不直接暴露 Room 实体）============

/**
 * 悬浮窗形态。MINI_BAR 为旧版迷你播放条，重设计后并入音频库面板，仅为兼容保留枚举值。
 */
enum class FloatMode { BALL, MINI_BAR, MENU, TTS, TTS_SETTINGS }

data class FloatGroupItem(val id: String, val emoji: String, val name: String)
data class FloatClipItem(val id: String, val name: String, val isCurrent: Boolean, val durationMs: Long = 0L)

/** 实时应用音频快捷入口（issue #46）：在音频库页选好的音源 App 及当前抓取状态。 */
data class FloatLiveItem(val packageName: String, val label: String, val running: Boolean, val starting: Boolean)

/** 展开态面板的默认宽度（设计稿 390 宽屏幕左右各留 14）。窄屏按可用宽度收缩。 */
const val EXPANDED_PANEL_WIDTH_DP = 340
val EXPANDED_PANEL_WIDTH: Dp = EXPANDED_PANEL_WIDTH_DP.dp

private enum class PanelTab { LIBRARY, TTS }

/**
 * 悬浮窗根 UI：悬浮球 / 主面板（音频库 · 文字转语音）/ 文字转语音设置。
 * 所有位置/窗口管理由 Service 处理，这里只负责渲染与回调。
 */
@Composable
fun FloatingBubbleRoot(
    mode: FloatMode,
    panelMaxWidth: Dp,
    panelMaxHeight: Dp,
    activeFile: Boolean,
    paused: Boolean,
    isStreaming: Boolean = false,
    audioMonitorEnabled: Boolean = false,
    onToggleAudioMonitor: () -> Unit = {},
    outputGain: Float = OutputGain.DEFAULT,
    onSetOutputGain: (Float) -> Unit = {},
    playbackPolicy: PlaybackPolicy = PlaybackPolicy.SILENCE,
    onSetPlaybackPolicy: (PlaybackPolicy) -> Unit = {},
    positionMs: Long,
    durationMs: Long,
    currentName: String?,
    currentGroupId: String? = null,
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
    ttsPreviewing: Boolean = false,
    onGenerateTts: (String) -> Unit,
    onTogglePreviewTts: () -> Unit = {},
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
    panelWidthDp: Int = 0,
    panelHeightDp: Int = 0,
    onResizePanel: (widthDp: Int, heightDp: Int) -> Unit = { _, _ -> },
    fishMode: Boolean = false,
    fishVoices: List<Pair<String, String>> = emptyList(),
    fishVoiceId: String = "",
    onSelectFishVoice: (String) -> Unit = {},
    fishEnhance: Boolean = false,
    onToggleFishEnhance: (Boolean) -> Unit = {},
    ttsProgressLines: List<String> = emptyList(),
    ttsEnhanced: TtsProgress.Enhanced? = null,
    live: FloatLiveItem? = null,
    onToggleLive: () -> Unit = {},
) {
    // 面板尺寸：拖动中实时变化，松手后经 onResizePanel 存进配置。
    // 对象只创建一次——拖动手势在 pointerInput(Unit) 里常驻，若配置一变就换新对象，
    // 手势仍握着旧对象，第二次拖动改的就是界面不再显示的那个（表现为"松手后再拖拖不动"）。
    // 配置值变化（保存后回读、恢复默认）只同步数值。
    val panelSize = remember { PanelSize(panelWidthDp.takeIf { it > 0 }?.dp, panelHeightDp.takeIf { it > 0 }?.dp) }
    LaunchedEffect(panelWidthDp, panelHeightDp) {
        panelSize.width = panelWidthDp.takeIf { it > 0 }?.dp
        panelSize.height = panelHeightDp.takeIf { it > 0 }?.dp
    }
    val saveSize = {
        onResizePanel(panelSize.width?.value?.roundToInt() ?: 0, panelSize.height?.value?.roundToInt() ?: 0)
    }
    val resetSize = { onResizePanel(0, 0) }

    // 精简 / 最小档的播放控制：有正在播的音源（文件或 TTS）就暂停 / 继续；
    // 否则若文字转语音已生成好，点一下开始播放
    val canToggle = activeFile || ttsActive
    val miniEnabled = canToggle || ttsReady
    val miniPlaying = canToggle && !paused
    val miniToggle: () -> Unit = { if (canToggle) onTogglePause() else if (ttsReady) onPlayTts() }
    val compactContent: @Composable ColumnScope.() -> Unit = {
        PlaybackRow(
            enabled = miniEnabled,
            paused = !miniPlaying,
            currentName = currentName,
            positionMs = positionMs,
            durationMs = durationMs,
            onTogglePause = miniToggle,
            onSeek = if (ttsActive) onSeekTts else onSeek
        )
    }
    val miniContent: @Composable BoxScope.(Dp) -> Unit = { side -> MiniPlayButton(miniEnabled, miniPlaying, side) }
    // TTS 草稿提到这一层：切 tab 会重建下方内容，草稿放在 TtsTab 内部会被清空。
    var ttsDraft by remember { mutableStateOf("") }
    // 自动标签完成后把带标签的文本回填到输入框（仅当输入框仍是送去优化的那段原文），
    // 用户能看到加了哪些标签，再次生成时也会跳过 LLM 直接合成
    LaunchedEffect(ttsEnhanced) {
        val e = ttsEnhanced ?: return@LaunchedEffect
        if (ttsDraft.trim() == e.original.trim()) ttsDraft = e.tagged
    }
    // 音频库当前查看的分组，同理提到这一层；默认跟随当前音源所在分组。
    var browsingGroupId by remember { mutableStateOf<String?>(null) }

    CompositionLocalProvider(LocalContentColor provides glass.ink) {
        when (mode) {
            FloatMode.BALL -> Ball(
                sizeDp = sizeDp,
                iconPath = iconPath,
                opacity = opacity,
                streaming = isStreaming && !paused,
                onTap = onBallTap,
                onDragBy = onDragBy,
                onDragEnd = onDragEnd
            )

            FloatMode.MINI_BAR, FloatMode.MENU, FloatMode.TTS -> {
                val tab = if (mode == FloatMode.TTS) PanelTab.TTS else PanelTab.LIBRARY
                val availableHeight = minOf(panelSize.height ?: panelMaxHeight, panelMaxHeight - PANEL_SHADOW_MARGIN * 2)
                val compactLibrary = tab == PanelTab.LIBRARY &&
                    availableHeight < LIBRARY_COMPACT_HEIGHT * LocalDensity.current.fontScale
                PanelShell(
                    maxWidth = panelMaxWidth,
                    maxHeight = if (tab == PanelTab.TTS) Dp.Infinity else panelMaxHeight,
                    panel = panelSize,
                    scrollable = tab == PanelTab.TTS,
                    dense = compactLibrary,
                    onResizeEnd = saveSize,
                    onResetSize = resetSize,
                    onDragBy = onDragBy,
                    onDragEnd = onDragEnd,
                    compact = compactContent,
                    mini = miniContent,
                    onMiniTap = miniToggle,
                    onMiniLongPress = onCollapse,
                    header = {
                        Dot(if (isStreaming) glass.err else glass.ink3)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(
                                when {
                                    !(activeFile || ttsActive) -> R.string.float_streaming_idle
                                    paused -> R.string.home_paused
                                    isStreaming -> R.string.float_streaming_live
                                    else -> R.string.float_streaming_idle
                                }
                            ),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (tab == PanelTab.TTS) {
                            CircleIconButton(Icons.Rounded.Tune, stringResource(R.string.float_settings), onOpenTtsSettings)
                            Spacer(Modifier.width(10.dp))
                        }
                        CollapsePill(onCollapse)
                    }
                ) {
                    Segmented(
                        options = listOf(
                            PanelTab.LIBRARY to stringResource(R.string.float_tab_library),
                            PanelTab.TTS to stringResource(R.string.float_tab_tts)
                        ),
                        selected = tab,
                        onSelect = { if (it == PanelTab.TTS) onOpenTts() else onOpenMenu() },
                        height = 36.dp
                    )
                    if (!compactLibrary) VolumeRow(gain = outputGain, onSetGain = onSetOutputGain)
                    if (tab == PanelTab.LIBRARY) {
                        LibraryTab(
                            compactLayout = compactLibrary,
                            outputGain = outputGain,
                            onSetOutputGain = onSetOutputGain,
                            fixedHeight = panelSize.height != null,
                            activeFile = activeFile,
                            paused = paused,
                            currentName = currentName,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            onTogglePause = onTogglePause,
                            onSeek = onSeek,
                            groups = groups,
                            browsingGroupId = (browsingGroupId ?: currentGroupId)
                                ?.takeIf { id -> groups.any { it.id == id } } ?: groups.firstOrNull()?.id,
                            onBrowseGroup = { browsingGroupId = it },
                            clipsProvider = clipsProvider,
                            onSelectClip = onSelectClip,
                            playbackPolicy = playbackPolicy,
                            onSetPlaybackPolicy = onSetPlaybackPolicy,
                            live = live,
                            onToggleLive = onToggleLive
                        )
                    } else {
                        TtsTab(
                            text = ttsDraft,
                            onTextChange = { ttsDraft = it },
                            generating = ttsGenerating,
                            ready = ttsReady,
                            failed = ttsFailed,
                            previewing = ttsPreviewing,
                            onGenerate = onGenerateTts,
                            onPreview = onTogglePreviewTts,
                            onPlay = onPlayTts,
                            isStreaming = isStreaming,
                            audioMonitorEnabled = audioMonitorEnabled,
                            onToggleAudioMonitor = onToggleAudioMonitor,
                            progressBarEnabled = ttsProgressBarEnabled,
                            ttsActive = ttsActive,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            onSeek = onSeekTts,
                            delayRemainingMs = ttsDelayRemainingMs,
                            onCancelDelayed = onCancelDelayedTts,
                            fishMode = fishMode,
                            fishVoices = fishVoices,
                            fishVoiceId = fishVoiceId,
                            onSelectFishVoice = onSelectFishVoice,
                            fishEnhance = fishEnhance,
                            onToggleFishEnhance = onToggleFishEnhance,
                            progressLines = ttsProgressLines
                        )
                    }
                }
            }

            FloatMode.TTS_SETTINGS -> PanelShell(
                maxWidth = panelMaxWidth,
                maxHeight = Dp.Infinity,
                panel = panelSize,
                scrollable = false,
                onResizeEnd = saveSize,
                onResetSize = resetSize,
                onDragBy = onDragBy,
                onDragEnd = onDragEnd,
                compact = compactContent,
                mini = miniContent,
                onMiniTap = miniToggle,
                onMiniLongPress = onCollapse,
                header = {
                    CircleIconButton(
                        Icons.AutoMirrored.Rounded.ArrowBackIos,
                        stringResource(R.string.float_back),
                        onCloseTtsSettings,
                        iconOffset = 3.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.float_tts_settings_title),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    CollapsePill(onCollapse)
                }
            ) {
                TtsSettings(
                    progressBarEnabled = ttsProgressBarEnabled,
                    onToggleProgressBar = onToggleTtsProgressBar,
                    delayMs = ttsDelayMs,
                    onSetDelay = onSetTtsDelay
                )
            }
        }
    }
}

/** 拖动手柄：贴在面板标题栏上，让面板展开时也能整体拖动。 */
private fun Modifier.dragHandle(
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
): Modifier = this.pointerInput(Unit) {
    detectDragGestures(onDragEnd = { onDragEnd() }) { change, drag ->
        change.consume()
        onDragBy(drag.x, drag.y)
    }
}

// ============ 面板外壳 ============

/** 展开面板的尺寸；null 表示默认（宽 [EXPANDED_PANEL_WIDTH]、高随内容自适应）。 */
@Stable
private class PanelSize(width: Dp?, height: Dp?) {
    var width by mutableStateOf(width)
    var height by mutableStateOf(height)
}

/** 能缩到的绝对最小尺寸：只剩一个播放键。 */
private val PANEL_MIN_WIDTH = 72.dp
private val PANEL_MIN_HEIGHT = 72.dp
/** 不小于这个尺寸时显示完整面板（标签切换 + 列表 / 文字转语音）。 */
private val FULL_MIN_WIDTH = 260.dp
private val FULL_MIN_HEIGHT = 300.dp
/** 小于这个尺寸时只剩播放键；介于两者之间是精简档（标题行 + 播放头）。 */
private val MINI_MAX_WIDTH = 150.dp
private val MINI_MAX_HEIGHT = 120.dp

// 矮屏优先保留选曲列表；只收紧留白，不缩小歌曲和控制按钮的触摸区域。
private val LIBRARY_COMPACT_HEIGHT = 480.dp
private val PANEL_SHADOW_MARGIN = 10.dp
private val COMPACT_PANEL_PADDING = 12.dp
internal val COMPACT_PANEL_GAP = 8.dp
internal val LIBRARY_TOUCH_HEIGHT = 48.dp
internal val LIBRARY_ITEM_GAP = 2.dp
internal val LIBRARY_ITEM_RADIUS = 14.dp
internal val LIBRARY_ITEM_PADDING = 12.dp

/** 面板随尺寸自动切换的显示档位。 */
private enum class PanelLevel { FULL, COMPACT, MINI }

/**
 * 玻璃面板：30 圆角、sheet 材质（浮在任意 App 之上，用近不透明底保证可读）、
 * 发丝描边与投影；标题栏可拖动。
 *
 * 右边缘 / 下边缘 / 右下角可拖动调整大小（右下角有握把，双击恢复默认），松手后保存。
 * 调过高度后内容区填满面板：[scrollable] 为 true 时内容可滚动，音频库则由列表自身滚动。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelShell(
    maxWidth: Dp,
    maxHeight: Dp,
    panel: PanelSize,
    scrollable: Boolean,
    dense: Boolean = false,
    onResizeEnd: () -> Unit,
    onResetSize: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    header: @Composable RowScope.() -> Unit,
    compact: @Composable ColumnScope.() -> Unit,
    mini: @Composable BoxScope.(side: Dp) -> Unit,
    onMiniTap: () -> Unit,
    onMiniLongPress: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = glass
    val density = LocalDensity.current
    val maxW = (maxWidth - PANEL_SHADOW_MARGIN * 2).coerceAtLeast(PANEL_MIN_WIDTH)
    val maxH = if (maxHeight == Dp.Infinity) null else (maxHeight - PANEL_SHADOW_MARGIN * 2).coerceAtLeast(PANEL_MIN_HEIGHT)
    val width = (panel.width ?: EXPANDED_PANEL_WIDTH).coerceIn(PANEL_MIN_WIDTH, maxW)
    val fixedHeight = panel.height?.let { h -> if (maxH != null) h.coerceIn(PANEL_MIN_HEIGHT, maxH) else h.coerceAtLeast(PANEL_MIN_HEIGHT) }
        ?: if (dense && width >= FULL_MIN_WIDTH) maxH else null
    var measuredHeight by remember { mutableStateOf(0.dp) }
    // 档位依据用户主动设置的高度。旋转导致的屏幕限高不能把选曲入口隐藏。
    val level = when {
        width < MINI_MAX_WIDTH || (panel.height?.let { it < MINI_MAX_HEIGHT } == true) -> PanelLevel.MINI
        width < FULL_MIN_WIDTH || (panel.height?.let { it < FULL_MIN_HEIGHT } == true) -> PanelLevel.COMPACT
        else -> PanelLevel.FULL
    }

    // 拖动手势用 pointerInput(Unit) 常驻，读取最新尺寸要经 rememberUpdatedState
    val curWidth by rememberUpdatedState(width)
    val curHeight by rememberUpdatedState(fixedHeight ?: measuredHeight)
    val curMaxW by rememberUpdatedState(maxW)
    val curMaxH by rememberUpdatedState(maxH)

    fun resizeBy(dx: Dp, dy: Dp) {
        if (dx != 0.dp) panel.width = (curWidth + dx).coerceIn(PANEL_MIN_WIDTH, curMaxW)
        if (dy != 0.dp) {
            val h = curHeight + dy
            panel.height = curMaxH?.let { h.coerceIn(PANEL_MIN_HEIGHT, it) } ?: h.coerceAtLeast(PANEL_MIN_HEIGHT)
        }
    }

    val latestResizeEnd by rememberUpdatedState(onResizeEnd)
    val latestReset by rememberUpdatedState(onResetSize)

    fun Modifier.resizeHandle(horizontal: Boolean, vertical: Boolean): Modifier = pointerInput(Unit) {
        detectDragGestures(
            onDragStart = {
                // 第一次拖动时把默认值落成具体尺寸，之后按增量调整
                if (panel.width == null) panel.width = curWidth
                if (vertical && panel.height == null) panel.height = curHeight
            },
            onDragEnd = { latestResizeEnd() },
            onDragCancel = { latestResizeEnd() }
        ) { change, drag ->
            change.consume()
            resizeBy(if (horizontal) drag.x.toDp() else 0.dp, if (vertical) drag.y.toDp() else 0.dp)
        }
    }

    // 投影画在外层边距里：窗口是 WRAP_CONTENT，投影外溢会被裁掉
    Box(Modifier.padding(PANEL_SHADOW_MARGIN)) {
        if (level == PanelLevel.MINI) {
            // 最小档：整块是播放键——轻点播放/继续，拖动移动位置，长按收起成悬浮球
            val boxHeight = fixedHeight ?: width
            val side = minOf(width, boxHeight)
            val shape = RoundedCornerShape(minOf(30.dp, side / 2))
            Box(
                modifier = Modifier
                    .size(width, boxHeight)
                    .onSizeChanged { measuredHeight = with(density) { it.height.toDp() } }
                    .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                    .clip(shape)
                    .background(t.sheet)
                    .border(BorderStroke(1.dp, t.rim), shape)
                    .dragHandle(onDragBy, onDragEnd)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onLongClick = onMiniLongPress,
                        onClick = onMiniTap
                    )
            ) { mini(side) }
        } else {
            val shape = RoundedCornerShape(30.dp)
            Column(
                modifier = Modifier
                    .width(width)
                    .then(
                        when {
                            fixedHeight != null -> Modifier.height(fixedHeight)
                            maxH != null -> Modifier.heightIn(max = maxH)
                            else -> Modifier
                        }
                    )
                    .onSizeChanged { measuredHeight = with(density) { it.height.toDp() } }
                    .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                    .clip(shape)
                    .background(t.sheet)
                    .border(BorderStroke(1.dp, t.rim), shape)
                    .padding(if (dense) COMPACT_PANEL_PADDING else if (level == PanelLevel.FULL) 16.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (dense) COMPACT_PANEL_GAP else if (level == PanelLevel.FULL) 14.dp else 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().dragHandle(onDragBy, onDragEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    content = header
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (fixedHeight != null) Modifier.weight(1f) else Modifier)
                        .then(
                            if (fixedHeight != null && (scrollable || level == PanelLevel.COMPACT))
                                Modifier.verticalScroll(rememberScrollState()) else Modifier
                        ),
                    verticalArrangement = Arrangement.spacedBy(if (dense) COMPACT_PANEL_GAP else 14.dp),
                    content = if (level == PanelLevel.FULL) content else compact
                )
            }
        }

        // 调整大小的触摸区：跨在面板边缘内外各约 9dp（外侧是投影留白，同属本窗口）
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(vertical = if (level == PanelLevel.FULL) 44.dp else 22.dp)
                    .width(20.dp)
                    .fillMaxHeight()
                    .resizeHandle(horizontal = true, vertical = false)
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = if (level == PanelLevel.FULL) 44.dp else 22.dp)
                    .height(20.dp)
                    .fillMaxWidth()
                    .resizeHandle(horizontal = false, vertical = true)
            )
            // 右下角握把：可见的两道斜线，双向拖动；双击恢复默认大小
            val grip = t.ink3
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(if (level == PanelLevel.FULL) 40.dp else 30.dp)
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { latestReset() }) }
                    .resizeHandle(horizontal = true, vertical = true)
                    .drawBehind {
                        val stroke = 2.dp.toPx()
                        val inset = 18.dp.toPx()
                        val c = Offset(size.width - inset, size.height - inset)
                        val long = if (level == PanelLevel.FULL) 10.dp.toPx() else 7.dp.toPx()
                        for (len in listOf(long, long / 2f)) {
                            drawLine(
                                grip,
                                Offset(c.x - len, c.y),
                                Offset(c.x, c.y - len),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round
                            )
                        }
                    }
            )
        }
    }
}

@Composable
private fun CollapsePill(onClick: () -> Unit) {
    Box(
        Modifier
            .liquidClickable(pressed = 0.92f, onClick = onClick)
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(glass.fill)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(stringResource(R.string.float_collapse), fontSize = 13.sp)
    }
}

@Composable
private fun CircleIconButton(icon: ImageVector, desc: String, onClick: () -> Unit, iconOffset: Dp = 0.dp) {
    val t = glass
    Box(
        Modifier.liquidClickable(pressed = 0.88f, onClick = onClick).size(34.dp).clip(CircleShape).background(t.fill),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = t.ink, modifier = Modifier.padding(start = iconOffset).size(17.dp))
    }
}

// ============ 悬浮球 ============

/**
 * 悬浮球：玻璃外圈 + 72% 内核。推流中内核为主色，否则为中性灰；自定义图标铺满内核。
 */
@Composable
private fun Ball(
    sizeDp: Dp,
    iconPath: String?,
    opacity: Float,
    streaming: Boolean,
    onTap: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val t = glass
    val reduceMotion = LocalReduceMotion.current
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
    val inner = sizeDp * 0.72f
    // 外层留 6dp 给投影，窗口是 WRAP_CONTENT
    Box(
        modifier = Modifier
            .padding(6.dp)
            .size(sizeDp)
            .alpha(opacity.coerceIn(0.2f, 1f))
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { onDragEnd() }) { change, drag ->
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
            }
            .shadow(8.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .clip(CircleShape)
            .background(t.sheet)
            .background(t.bar)
            .border(BorderStroke(1.dp, t.rim), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(inner)
                .clip(CircleShape)
                .background(if (streaming) t.primary else if (t.isDark) Color(0xFF4A4B52) else Color(0xFF9A9BA2)),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(inner).clip(CircleShape)
                )
            } else {
                Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(inner * 0.5f))
            }
        }
    }
}

// ============ 音频库 tab ============

/** 1500 → "1.5"，1000 → "1"：整秒不拖小数尾巴。 */
internal fun formatSeconds(ms: Long): String {
    val seconds = ms / 1000.0
    return if (seconds % 1.0 == 0.0) seconds.toInt().toString() else "%.1f".format(seconds)
}

internal fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}
