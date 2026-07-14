package io.mo.glassmic.service

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mo.glassmic.R
import kotlinx.coroutines.flow.Flow
import java.io.File

// ============ 悬浮窗数据模型（不直接暴露 Room 实体）============

enum class FloatMode { BALL, MINI_BAR, MENU, TTS, TTS_SETTINGS }

data class FloatGroupItem(val id: String, val emoji: String, val name: String)
data class FloatClipItem(val id: String, val name: String, val isCurrent: Boolean)

private val PanelBg = Color(0xEE1C1C20)
private val BallBg = Color(0xCC1C1C20)
private val Accent = Color(0xFF34C759)
private val PausedAccent = Color(0xFFFFCC33)
private val Danger = Color(0xFFFF6B6B)
private val OnDark = Color.White
private val OnDarkDim = Color(0xB3FFFFFF)

/**
 * 悬浮窗根 UI。三态：球态 / 迷你播放条 / 选曲菜单。
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
    onSeekTts: (Float) -> Unit,
    onCloseTtsSettings: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    when (mode) {
        FloatMode.BALL -> Ball(
            sizeDp = sizeDp,
            iconPath = iconPath,
            opacity = opacity,
            active = activeFile && !paused,
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
        FloatMode.MENU -> SelectMenu(
            groups = groups,
            clipsProvider = clipsProvider,
            onSelectClip = onSelectClip,
            onOpenTts = onOpenTts,
            onCollapse = onCollapse,
            onDragBy = onDragBy,
            onDragEnd = onDragEnd
        )
        FloatMode.TTS -> TtsPanel(
            generating = ttsGenerating,
            ready = ttsReady,
            failed = ttsFailed,
            onGenerate = onGenerateTts,
            onPlay = onPlayTts,
            onCollapse = onCollapse,
            progressBarEnabled = ttsProgressBarEnabled,
            ttsActive = ttsActive,
            positionMs = positionMs,
            durationMs = durationMs,
            onSeek = onSeekTts,
            onOpenSettings = onOpenTtsSettings,
            onDragBy = onDragBy,
            onDragEnd = onDragEnd
        )
        FloatMode.TTS_SETTINGS -> TtsSettingsPanel(
            progressBarEnabled = ttsProgressBarEnabled,
            onToggleProgressBar = onToggleTtsProgressBar,
            onBack = onCloseTtsSettings,
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

// ============ 球态 ============
@Composable
private fun Ball(
    sizeDp: Dp,
    iconPath: String?,
    opacity: Float,
    active: Boolean,
    onTap: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val bitmap = remember(iconPath) {
        iconPath?.takeIf { File(it).exists() }
            ?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() }
    }
    Box(
        modifier = Modifier
            .size(sizeDp)
            .alpha(opacity.coerceIn(0.2f, 1f))
            .clip(CircleShape)
            .background(BallBg)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onDragEnd() }
                ) { change, drag ->
                    change.consume()
                    onDragBy(drag.x, drag.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(sizeDp).clip(CircleShape)
            )
        } else {
            Text("🎵", fontSize = (sizeDp.value * 0.42f).sp)
        }
        // 运行指示点
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(2.dp)
                .size(sizeDp.value.times(0.16f).dp.coerceAtLeast(6.dp))
                .clip(CircleShape)
                .background(if (active) Accent else Color(0xFF9E9EA0))
        )
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
    Column(
        modifier = Modifier
            .widthIn(min = 240.dp, max = 300.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PanelBg)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().dragHandle(onDragBy, onDragEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = currentName ?: "GlassMic",
                color = OnDark,
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
                color = OnDark,
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF))
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
            Text(formatMs(positionMs), color = OnDarkDim, fontSize = 11.sp)
            Text(
                stringResource(R.string.float_collapse),
                color = OnDarkDim,
                fontSize = 11.sp,
                modifier = Modifier.clickable(onClick = onCollapse)
            )
            Text(formatMs(durationMs), color = OnDarkDim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun IconChip(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Icon(
        imageVector = icon,
        contentDescription = desc,
        tint = OnDark,
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(6.dp)
    )
}

// ============ 选曲菜单（两级：分组 → 片段）============
@Composable
private fun SelectMenu(
    groups: List<FloatGroupItem>,
    clipsProvider: (String) -> Flow<List<FloatClipItem>>,
    onSelectClip: (String) -> Unit,
    onOpenTts: () -> Unit,
    onCollapse: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    var selectedGroup by remember { mutableStateOf<FloatGroupItem?>(null) }

    Column(
        modifier = Modifier
            .widthIn(min = 240.dp, max = 300.dp)
            .heightIn(max = 380.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PanelBg)
            .padding(vertical = 8.dp)
    ) {
        // 标题栏（兼作拖动手柄）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .dragHandle(onDragBy, onDragEnd)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectedGroup != null) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.float_back), tint = OnDark,
                    modifier = Modifier.size(22.dp).clip(CircleShape)
                        .clickable { selectedGroup = null }.padding(2.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = selectedGroup?.let { "${it.emoji} ${it.name}" } ?: stringResource(R.string.home_pick_audio),
                color = OnDark, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                stringResource(R.string.float_collapse), color = OnDarkDim, fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onCollapse)
            )
        }

        val group = selectedGroup
        if (group == null) {
            // 文字转语音入口：输入文字实时合成喂给目标 App
            MenuRow(text = stringResource(R.string.float_tts_entry), trailing = "›", onClick = onOpenTts)
            if (groups.isEmpty()) {
                EmptyHint(stringResource(R.string.float_no_groups))
            } else {
                LazyColumn {
                    items(groups, key = { it.id }) { g ->
                        MenuRow(text = "${g.emoji}  ${g.name}", trailing = "›") {
                            selectedGroup = g
                        }
                    }
                }
            }
        } else {
            val clipsFlow = remember(group.id) { clipsProvider(group.id) }
            val clips by clipsFlow.collectAsState(initial = emptyList())
            if (clips.isEmpty()) {
                EmptyHint(stringResource(R.string.float_no_clips_in_group))
            } else {
                LazyColumn {
                    items(clips, key = { it.id }) { c ->
                        MenuRow(
                            text = c.name,
                            trailing = null,
                            leadingCurrent = c.isCurrent
                        ) { onSelectClip(c.id) }
                    }
                }
            }
        }
    }
}

// ============ 文字转语音面板（先生成后播放，可重复播放）============
@Composable
private fun TtsPanel(
    generating: Boolean,
    ready: Boolean,
    failed: Boolean,
    onGenerate: (String) -> Unit,
    onPlay: () -> Unit,
    onCollapse: () -> Unit,
    progressBarEnabled: Boolean,
    ttsActive: Boolean,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Float) -> Unit,
    onOpenSettings: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .widthIn(min = 260.dp, max = 320.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PanelBg)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().dragHandle(onDragBy, onDragEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.float_tts_title), color = OnDark, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.float_settings),
                tint = OnDarkDim,
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onOpenSettings)
                    .padding(3.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.float_collapse), color = OnDarkDim, fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onCollapse)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x22FFFFFF))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = OnDark, fontSize = 14.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(stringResource(R.string.float_tts_input_hint), color = OnDarkDim, fontSize = 14.sp)
                    }
                    inner()
                }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val canGenerate = !generating && text.isNotBlank()
            Text(
                text = if (generating) stringResource(R.string.float_tts_generating) else stringResource(R.string.float_tts_generate),
                color = OnDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (canGenerate) Color(0x33FFFFFF) else Color(0x1AFFFFFF))
                    .clickable(enabled = canGenerate) { onGenerate(text.trim()) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.float_tts_play),
                color = OnDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (ready) Accent else Color(0x1AFFFFFF))
                    .clickable(enabled = ready, onClick = onPlay)
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            )
        }
        // 仅在失败时提示，正常流程不堆文字
        if (failed) {
            Text(
                text = stringResource(R.string.float_tts_generate_failed),
                color = Danger, fontSize = 11.sp,
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
                Text(formatMs(if (hasDuration) positionMs else 0L), color = OnDarkDim, fontSize = 11.sp)
                Text(formatMs(if (hasDuration) durationMs else 0L), color = OnDarkDim, fontSize = 11.sp)
            }
        }
    }
}

// ============ 文字转语音设置面板 ============
@Composable
private fun TtsSettingsPanel(
    progressBarEnabled: Boolean,
    onToggleProgressBar: (Boolean) -> Unit,
    onBack: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(min = 260.dp, max = 320.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PanelBg)
            .padding(14.dp)
    ) {
        // 标题栏（兼作拖动手柄）
        Row(
            modifier = Modifier.fillMaxWidth().dragHandle(onDragBy, onDragEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.float_back), tint = OnDark,
                modifier = Modifier.size(22.dp).clip(CircleShape)
                    .clickable(onClick = onBack).padding(2.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.float_tts_settings_title), color = OnDark, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
            )
        }
        // 进度条开关
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.float_tts_progress_bar), color = OnDark, fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = progressBarEnabled,
                onCheckedChange = onToggleProgressBar
            )
        }
    }
}

@Composable
private fun MenuRow(
    text: String,
    trailing: String?,
    leadingCurrent: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingCurrent) {
            Icon(Icons.Filled.Check, stringResource(R.string.float_current), tint = Accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text, color = OnDark, fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) Text(trailing, color = OnDarkDim, fontSize = 16.sp)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text, color = OnDarkDim, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 20.dp)
    )
}

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}
