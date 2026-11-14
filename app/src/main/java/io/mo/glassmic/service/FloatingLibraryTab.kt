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

@Composable
internal fun ColumnScope.LibraryTab(
    fixedHeight: Boolean,
    compactLayout: Boolean,
    outputGain: Float,
    onSetOutputGain: (Float) -> Unit,
    activeFile: Boolean,
    paused: Boolean,
    currentName: String?,
    positionMs: Long,
    durationMs: Long,
    onTogglePause: () -> Unit,
    onSeek: (Float) -> Unit,
    groups: List<FloatGroupItem>,
    browsingGroupId: String?,
    onBrowseGroup: (String) -> Unit,
    clipsProvider: (String) -> Flow<List<FloatClipItem>>,
    onSelectClip: (String) -> Unit,
    playbackPolicy: PlaybackPolicy,
    onSetPlaybackPolicy: (PlaybackPolicy) -> Unit,
    live: FloatLiveItem?,
    onToggleLive: () -> Unit,
) {
    val t = glass
    var controlsExpanded by remember { mutableStateOf(false) }
    val playback: @Composable () -> Unit = {
        PlaybackRow(
            enabled = activeFile,
            paused = paused,
            currentName = currentName,
            positionMs = positionMs,
            durationMs = durationMs,
            onTogglePause = onTogglePause,
            onSeek = onSeek
        )
    }
    val groupSelector: @Composable () -> Unit = {
        if (groups.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(groups, key = { it.id }) { g ->
                    val on = g.id == browsingGroupId
                    Text(
                        "${g.emoji} ${g.name}",
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) t.ink else t.ink2,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (on) t.primarySoft else t.fill)
                            .clickable { onBrowseGroup(g.id) }
                            .heightIn(min = LIBRARY_TOUCH_HEIGHT)
                            .padding(horizontal = LIBRARY_ITEM_PADDING, vertical = LIBRARY_ITEM_PADDING)
                    )
                }
            }
        }
    }
    val policySelector: @Composable () -> Unit = {
        Segmented(
            options = listOf(
                PlaybackPolicy.SILENCE to stringResource(R.string.float_policy_once),
                PlaybackPolicy.LOOP to stringResource(R.string.float_policy_loop),
                PlaybackPolicy.REAL_MIC to stringResource(R.string.float_policy_real)
            ),
            selected = if (playbackPolicy == PlaybackPolicy.UNRECOGNIZED) PlaybackPolicy.SILENCE else playbackPolicy,
            onSelect = onSetPlaybackPolicy,
            height = 32.dp,
            fontSize = 12.sp
        )
    }
    val clipsFlow = remember(browsingGroupId) {
        browsingGroupId?.let(clipsProvider) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val clips by clipsFlow.collectAsState(initial = emptyList())
    val emptyMessage = stringResource(
        if (browsingGroupId == null) R.string.float_no_groups else R.string.float_no_clips_in_group
    )

    if (!compactLayout) {
        playback()
        groupSelector()
    }
    LazyColumn(
        modifier = Modifier.weight(1f, fill = compactLayout || fixedHeight)
            .then(if (compactLayout || fixedHeight) Modifier else Modifier.heightIn(max = 264.dp)),
        verticalArrangement = Arrangement.spacedBy(LIBRARY_ITEM_GAP)
    ) {
        if (compactLayout) {
            // 控制区和分组也参与滚动：极矮屏、大字体或展开设置都不能挤掉歌曲。
            item(key = "controls") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { playback() }
                    TextButton(onClick = { controlsExpanded = !controlsExpanded }) {
                        Text(stringResource(if (controlsExpanded) R.string.float_hide_controls else R.string.float_show_controls))
                    }
                }
            }
            if (controlsExpanded) {
                item(key = "volume") { VolumeRow(gain = outputGain, onSetGain = onSetOutputGain) }
                item(key = "policy") { policySelector() }
                if (live != null) item(key = "live") { LiveRow(live, onToggleLive) }
            }
            if (groups.size > 1) item(key = "groups") { groupSelector() }
        }
        if (clips.isEmpty()) {
            item(key = "empty") { PanelEmpty(emptyMessage) }
        } else {
            items(clips, key = { "clip:${it.id}" }) { c ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = LIBRARY_TOUCH_HEIGHT)
                        .clip(RoundedCornerShape(LIBRARY_ITEM_RADIUS))
                        .background(if (c.isCurrent) t.primarySoft else Color.Transparent)
                        .clickable { onSelectClip(c.id) }
                        .padding(horizontal = LIBRARY_ITEM_PADDING, vertical = COMPACT_PANEL_GAP),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        c.name,
                        fontSize = 14.sp,
                        fontWeight = if (c.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = t.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (c.durationMs > 0) {
                        Spacer(Modifier.width(COMPACT_PANEL_GAP))
                        Text(formatMs(c.durationMs), fontSize = 12.sp, color = t.ink3, fontFamily = MonoFamily)
                    }
                }
            }
        }
    }
    if (!compactLayout) {
        policySelector()
        if (live != null) LiveRow(live, onToggleLive)
    }
}

/**
 * 实时应用音频快捷开关：一键把音源切到音频库页选好的 App，或停掉回到真实麦克风。
 * 免得点过语音包后还要回主界面重新「开始实时输入」（issue #46）。
 */
@Composable
private fun LiveRow(live: FloatLiveItem, onToggle: () -> Unit) {
    val t = glass
    Row(
        Modifier.fillMaxWidth().heightIn(min = LIBRARY_TOUCH_HEIGHT),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(live.packageName, size = 26.dp)
        Spacer(Modifier.width(COMPACT_PANEL_GAP))
        Text(
            stringResource(R.string.source_live, live.label),
            fontSize = 13.sp,
            fontWeight = if (live.running) FontWeight.SemiBold else FontWeight.Normal,
            color = if (live.running) t.ink else t.ink2,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(COMPACT_PANEL_GAP))
        val enabled = !live.starting
        Text(
            stringResource(
                when {
                    live.starting -> R.string.live_status_starting
                    live.running -> R.string.float_live_stop
                    else -> R.string.float_live_start
                }
            ),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                !enabled -> t.ink3
                live.running -> t.err
                else -> Color.White
            },
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(
                    when {
                        !enabled -> t.fill
                        live.running -> t.err.copy(alpha = 0.14f)
                        else -> t.primary
                    }
                )
                .clickable(enabled = enabled, onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

/** 播放头：播放/暂停 + 名称 + 进度 + 时间。音频库页与精简档面板共用；窄时隐藏时间。 */
@Composable
internal fun PlaybackRow(
    enabled: Boolean,
    paused: Boolean,
    currentName: String?,
    positionMs: Long,
    durationMs: Long,
    onTogglePause: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    val t = glass
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showTime = maxWidth >= 200.dp
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .liquidClickable(enabled = enabled, pressed = 0.9f, onClick = onTogglePause)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (enabled) t.primary else t.fill),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (paused || !enabled) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    null,
                    tint = if (enabled) Color.White else t.ink3,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    currentName ?: stringResource(R.string.float_no_source),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                ThinProgress(
                    fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                    onSeek = if (durationMs > 0) onSeek else null
                )
            }
            if (showTime) {
                Spacer(Modifier.width(12.dp))
                Text(formatMs(positionMs), fontSize = 12.sp, color = t.ink3, fontFamily = MonoFamily)
            }
        }
    }
}

/**
 * 注入音量：导入音频与 TTS 共用，0% - 300%。拖动时每跨过一个 5% 档就写一次配置，
 * 边拖边能听到变化；点右侧百分比恢复 100%。
 */
@Composable
internal fun VolumeRow(gain: Float, onSetGain: (Float) -> Unit) {
    val t = glass
    // 拖动中以本地值为准，避免配置回读的旧值把滑块拽回去
    var dragging by remember { mutableStateOf<Float?>(null) }
    var lastSent by remember { mutableStateOf(gain) }
    val shown = dragging ?: gain
    val percent = (OutputGain.normalize(shown) * 100).roundToInt()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (percent == 0) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            stringResource(R.string.float_volume),
            tint = t.ink2,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            GlassSlider(
                value = shown,
                onValueChange = { v ->
                    dragging = v
                    val n = OutputGain.normalize(v)
                    if (n != lastSent) {
                        lastSent = n
                        onSetGain(n)
                    }
                },
                valueRange = OutputGain.MIN..OutputGain.MAX,
                onValueChangeFinished = { dragging = null }
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "$percent%",
            fontSize = 12.sp,
            fontFamily = MonoFamily,
            color = if (percent == 100) t.ink3 else t.primaryInk,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    lastSent = OutputGain.DEFAULT
                    onSetGain(OutputGain.DEFAULT)
                }
                .padding(vertical = 4.dp)
        )
    }
}

/** 最小档：只有一个圆形播放键（整块面板都是点击区，由外壳处理点击）。 */
@Composable
internal fun BoxScope.MiniPlayButton(enabled: Boolean, playing: Boolean, panelSide: Dp) {
    val t = glass
    val d = (panelSide - 16.dp).coerceIn(40.dp, 64.dp)
    Box(
        Modifier
            .align(Alignment.Center)
            .size(d)
            .clip(CircleShape)
            .background(if (enabled) t.primary else t.fill),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            stringResource(if (playing) R.string.float_pause else R.string.float_play_resume),
            tint = if (enabled) Color.White else t.ink3,
            modifier = Modifier.size(d * 0.46f)
        )
    }
}

/** 4dp 细进度条，可选点按跳转。 */
@Composable
internal fun ThinProgress(fraction: Float, onSeek: ((Float) -> Unit)?) {
    val t = glass
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .then(
                if (onSeek != null) Modifier.pointerInput(onSeek) {
                    detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) }
                } else Modifier
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(t.fill, RoundedCornerShape(2.dp)))
        Box(Modifier.width(maxWidth * fraction).height(4.dp).background(t.primary, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun PanelEmpty(text: String) {
    Text(
        text, fontSize = 13.sp, color = glass.ink3, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
    )
}

// ============ 文字转语音 tab（先生成后播放，可重复播放）============
