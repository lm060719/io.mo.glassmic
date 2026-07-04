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
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import java.io.File

// ============ 悬浮窗数据模型（不直接暴露 Room 实体）============

enum class FloatMode { BALL, MINI_BAR, MENU, TTS }

data class FloatGroupItem(val id: String, val emoji: String, val name: String)
data class FloatClipItem(val id: String, val name: String, val isCurrent: Boolean)

private val PanelBg = Color(0xEE1C1C20)
private val BallBg = Color(0xCC1C1C20)
private val Accent = Color(0xFF34C759)
private val PausedAccent = Color(0xFFFFCC33)
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
    onSpeakTts: (String) -> Unit,
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
            onCollapse = onCollapse
        )
        FloatMode.MENU -> SelectMenu(
            groups = groups,
            clipsProvider = clipsProvider,
            onSelectClip = onSelectClip,
            onOpenTts = onOpenTts,
            onCollapse = onCollapse
        )
        FloatMode.TTS -> TtsPanel(
            onSpeak = onSpeakTts,
            onCollapse = onCollapse
        )
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
) {
    Column(
        modifier = Modifier
            .widthIn(min = 240.dp, max = 300.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PanelBg)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            IconChip(Icons.Filled.Refresh, "换音源", onOpenMenu)
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
                "收起",
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
        // 标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectedGroup != null) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = OnDark,
                    modifier = Modifier.size(22.dp).clip(CircleShape)
                        .clickable { selectedGroup = null }.padding(2.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = selectedGroup?.let { "${it.emoji} ${it.name}" } ?: "选择音频",
                color = OnDark, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                "收起", color = OnDarkDim, fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onCollapse)
            )
        }

        val group = selectedGroup
        if (group == null) {
            // 文字转语音入口：输入文字实时合成喂给目标 App
            MenuRow(text = "🗣  文字转语音（TTS）", trailing = "›", onClick = onOpenTts)
            if (groups.isEmpty()) {
                EmptyHint("还没有音频分组")
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
                EmptyHint("该分组还没有音频")
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

// ============ 文字转语音面板 ============
@Composable
private fun TtsPanel(
    onSpeak: (String) -> Unit,
    onCollapse: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .widthIn(min = 260.dp, max = 320.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PanelBg)
            .padding(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "🗣 文字转语音", color = OnDark, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
            )
            Text(
                "收起", color = OnDarkDim, fontSize = 12.sp,
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
                        Text("输入要合成的文字", color = OnDarkDim, fontSize = 14.sp)
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
            Text(
                text = "播报",
                color = OnDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (text.isBlank()) Color(0x33FFFFFF) else Accent)
                    .clickable(enabled = text.isNotBlank()) {
                        onSpeak(text.trim())
                        text = ""
                    }
                    .padding(horizontal = 18.dp, vertical = 8.dp)
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
            Icon(Icons.Filled.Check, "当前", tint = Accent, modifier = Modifier.size(16.dp))
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
