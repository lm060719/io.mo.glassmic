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
internal fun TtsTab(
    text: String,
    onTextChange: (String) -> Unit,
    generating: Boolean,
    ready: Boolean,
    failed: Boolean,
    previewing: Boolean,
    onGenerate: (String) -> Unit,
    onPreview: () -> Unit,
    onPlay: () -> Unit,
    isStreaming: Boolean,
    audioMonitorEnabled: Boolean,
    onToggleAudioMonitor: () -> Unit,
    progressBarEnabled: Boolean,
    ttsActive: Boolean,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Float) -> Unit,
    delayRemainingMs: Long,
    onCancelDelayed: () -> Unit,
    fishMode: Boolean = false,
    fishVoices: List<Pair<String, String>> = emptyList(),
    fishVoiceId: String = "",
    onSelectFishVoice: (String) -> Unit = {},
    fishEnhance: Boolean = false,
    onToggleFishEnhance: (Boolean) -> Unit = {},
    progressLines: List<String> = emptyList(),
) {
    val t = glass
    val counting = delayRemainingMs > 0

    GlassTextField(
        value = text,
        onValueChange = onTextChange,
        singleLine = false,
        minLines = 3,
        placeholder = stringResource(R.string.float_tts_input_hint)
    )

    // Fish Audio：音色预设切换 + 「优化文本」（自动标签）开关；关闭则按原文直接合成
    if (fishMode) {
        FishOptions(
            voices = fishVoices,
            selectedId = fishVoiceId,
            onSelectVoice = onSelectFishVoice,
            enhance = fishEnhance,
            onToggleEnhance = onToggleFishEnhance
        )
    }

    // 状态行：推流指示 + 耳返监听
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(if (isStreaming) t.err else t.ink3, 7.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                counting -> stringResource(R.string.float_tts_counting, formatSeconds(delayRemainingMs))
                isStreaming -> stringResource(R.string.float_streaming_live)
                else -> stringResource(R.string.float_streaming_idle)
            },
            fontSize = 12.sp,
            color = if (isStreaming) t.err else t.ink3,
            modifier = Modifier.weight(1f)
        )
        if (text.isNotEmpty()) {
            Box(
                Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(t.fill)
                    .clickable { onTextChange("") }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.float_tts_clear),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = t.ink2
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        Box(
            Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(if (audioMonitorEnabled) t.primarySoft else t.fill)
                .clickable(onClick = onToggleAudioMonitor)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.float_audio_monitor),
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = if (audioMonitorEnabled) t.primaryInk else t.ink2
            )
        }
    }

    // 生成 / 试听 / 播放
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TtsButton(
            label = stringResource(if (generating) R.string.float_tts_generating else R.string.float_tts_generate),
            onClick = { onGenerate(text.trim()) },
            enabled = !generating && text.isNotBlank(),
            bold = true,
            modifier = Modifier.weight(1f)
        )
        TtsButton(
            label = stringResource(if (previewing) R.string.float_tts_preview_stop else R.string.float_tts_preview),
            onClick = onPreview,
            enabled = ready,
            modifier = Modifier.weight(1f)
        )
        // 延时倒计时中：按钮变成「1.5s 取消」，再点一次即取消本次播放
        TtsButton(
            label = if (counting) stringResource(R.string.float_tts_delay_countdown, formatSeconds(delayRemainingMs))
                    else stringResource(R.string.float_tts_play),
            onClick = { if (counting) onCancelDelayed() else onPlay() },
            enabled = ready || counting,
            background = when {
                counting -> t.err
                ready -> t.primary
                else -> t.fill
            },
            contentColor = if (ready || counting) Color.White else t.ink3,
            bold = true,
            modifier = Modifier.weight(1f)
        )
    }

    // 仅在失败时提示，正常流程不堆文字
    if (failed) {
        Text(stringResource(R.string.float_tts_generate_failed), fontSize = 12.sp, color = t.err)
    }

    // 生成过程：LLM 优化 / 请求合成 / 耗时 / 错误，逐行显示
    if (progressLines.isNotEmpty() && (generating || ready || failed)) {
        GenerateLog(progressLines, generating)
    }

    // 进度条：开启后，生成就绪或正在播放时显示
    if (progressBarEnabled && (ready || ttsActive)) {
        val hasDuration = durationMs > 0
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ThinProgress(
                fraction = if (hasDuration) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                onSeek = if (hasDuration) onSeek else null
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    formatMs(if (hasDuration) positionMs else 0L),
                    fontSize = 11.sp, color = t.ink3, fontFamily = MonoFamily,
                    modifier = Modifier.weight(1f)
                )
                Text(formatMs(if (hasDuration) durationMs else 0L), fontSize = 11.sp, color = t.ink3, fontFamily = MonoFamily)
            }
        }
    }
}

/** 生成日志：等宽小字，最后一行在生成中高亮。 */
@Composable
private fun GenerateLog(lines: List<String>, generating: Boolean) {
    val t = glass
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.fill)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        lines.forEachIndexed { i, line ->
            val current = generating && i == lines.lastIndex
            Text(
                line,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = MonoFamily,
                color = if (current) t.ink else t.ink3,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 音色预设按钮（未配置预设时不显示）；当前音色不在预设里时多一个选中的「自定义」。 */
@Composable
private fun FishOptions(
    voices: List<Pair<String, String>>,
    selectedId: String,
    onSelectVoice: (String) -> Unit,
    enhance: Boolean,
    onToggleEnhance: (Boolean) -> Unit,
) {
    val t = glass
    if (voices.isNotEmpty()) {
        val custom = selectedId.isNotBlank() && voices.none { it.second == selectedId }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.float_tts_voice), fontSize = 12.sp, color = t.ink3)
            Spacer(Modifier.width(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(voices, key = { it.second }) { (label, id) ->
                    VoiceChip(label, selected = id == selectedId) { onSelectVoice(id) }
                }
                if (custom) item { VoiceChip(stringResource(R.string.float_tts_voice_custom), selected = true) {} }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.float_tts_enhance), fontSize = 13.sp, modifier = Modifier.weight(1f))
        GlassToggle(enhance, onToggleEnhance)
    }
}

@Composable
private fun VoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = glass
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) t.primaryInk else t.ink2,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) t.primarySoft else t.fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun TtsButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    background: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    bold: Boolean = false,
) {
    val t = glass
    Box(
        modifier
            .liquidClickable(enabled = enabled, pressed = 0.94f, onClick = onClick)
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(if (background == Color.Unspecified) t.fill else background)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium,
            color = when {
                contentColor != Color.Unspecified -> contentColor
                enabled -> t.ink
                else -> t.ink3
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ============ 文字转语音设置 ============

@Composable
internal fun TtsSettings(
    progressBarEnabled: Boolean,
    onToggleProgressBar: (Boolean) -> Unit,
    delayMs: Int,
    onSetDelay: (Int) -> Unit,
) {
    val t = glass
    // 自定义延时输入展开后内容会变长，小屏上会顶出屏幕，这里给一个滚动兜底
    Column(
        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.float_tts_progress_bar), fontSize = 15.sp, modifier = Modifier.weight(1f))
            GlassToggle(progressBarEnabled, onToggleProgressBar)
        }
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.float_tts_delay), fontSize = 15.sp)
            Text(stringResource(R.string.float_tts_delay_hint), fontSize = 12.sp, lineHeight = 17.sp, color = t.ink3)
        }
        TtsDelaySetting(delayMs, onSetDelay)
    }
}

/** 延时播放：关 / 0.5s / 1s / 2s 四个预设 + 自定义秒数输入。 */
@Composable
private fun TtsDelaySetting(delayMs: Int, onSetDelay: (Int) -> Unit) {
    val t = glass
    val presets = listOf(0, 500, 1_000, 2_000)
    // 当前值不在预设里 → 说明用的是自定义，输入框默认展开并回填
    var customOpen by remember(delayMs) { mutableStateOf(delayMs !in presets) }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.forEach { preset ->
            DelayChip(
                label = if (preset == 0) stringResource(R.string.float_tts_delay_off)
                        else stringResource(R.string.float_tts_delay_seconds, formatSeconds(preset.toLong())),
                selected = !customOpen && delayMs == preset,
                onClick = { customOpen = false; onSetDelay(preset) },
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
        // 只在合法（0 < 秒 ≤ 上限）时才写配置，输入过程中的中间态保持原值不动
        var raw by remember(delayMs) {
            mutableStateOf(if (delayMs > 0) formatSeconds(delayMs.toLong()) else "")
        }
        val maxSeconds = FloatingWindowService.MAX_TTS_DELAY_MS / 1000
        val invalid = raw.isNotBlank() && raw.toFloatOrNull().let { it == null || it <= 0f || it > maxSeconds }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GlassTextField(
                value = raw,
                onValueChange = { input ->
                    raw = input.filter { it.isDigit() || it == '.' }.take(6)
                    raw.toFloatOrNull()
                        ?.takeIf { it > 0f && it <= maxSeconds }
                        ?.let { onSetDelay((it * 1000).toInt()) }
                },
                placeholder = stringResource(R.string.float_tts_delay_custom_hint),
                mono = true,
                isError = invalid,
                suffix = stringResource(R.string.float_tts_delay_unit),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            if (invalid) {
                Text(stringResource(R.string.float_tts_delay_range, maxSeconds), fontSize = 12.sp, color = t.err)
            }
        }
    }
}

@Composable
private fun DelayChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = glass
    Box(
        modifier
            .liquidClickable(pressed = 0.92f, onClick = onClick)
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) t.primary else t.fill),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontFamily = MonoFamily,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else t.ink,
            maxLines = 1
        )
    }
}
