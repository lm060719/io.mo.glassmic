package io.mo.glassmic.ui.diag

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.mo.glassmic.ui.common.BackHeader
import io.mo.glassmic.ui.common.GlassCard
import io.mo.glassmic.ui.common.GlassIconButton
import io.mo.glassmic.ui.common.GlassPage
import io.mo.glassmic.ui.common.GroupCard
import io.mo.glassmic.ui.common.MonoFamily
import io.mo.glassmic.ui.common.OutlineGlassButton
import io.mo.glassmic.ui.common.PrimaryButton
import io.mo.glassmic.ui.common.SectionLabel
import io.mo.glassmic.ui.common.Segmented
import io.mo.glassmic.ui.common.SettingRow
import io.mo.glassmic.ui.common.SoftButton
import io.mo.glassmic.ui.common.StackedRow
import io.mo.glassmic.ui.common.StatusKind
import io.mo.glassmic.ui.common.StatusPill
import io.mo.glassmic.ui.common.glass
import io.mo.glassmic.R
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.diag.AudioPipelineProbe
import io.mo.glassmic.data.runtime.DecisionRecord
import io.mo.glassmic.data.runtime.HookActivity
import io.mo.glassmic.proto.LogLevel
import io.mo.glassmic.proto.PlaybackPolicy
import io.mo.glassmic.proto.ScopeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticScreen(
    onBack: () -> Unit,
    vm: DiagnosticViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    DisposableEffect(Unit) {
        onDispose {
            vm.restoreInitialState()
        }
    }

    LaunchedEffect(state.exportedUri) {
        val uri = state.exportedUri ?: return@LaunchedEffect
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, "导出诊断包")) }
        vm.consumeExport()
    }

    LaunchedEffect(state.exportError) {
        state.exportError?.let { snackbar.showSnackbar(it); vm.consumeExport() }
    }

    val t = glass
    GlassPage(snackbar) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                BackHeader(stringResource(R.string.diag_title), onBack) {
                    GlassIconButton(Icons.Rounded.Refresh, stringResource(R.string.diag_refresh), vm::refreshAll, size = 40.dp)
                }
            }
            // ============ 1. 全链路健康自检 ============
            item {
                DiagSection(stringResource(R.string.diag_section_health)) {
                    // LSPosed 注入
                    val hookActivityText = when (state.hook.activity) {
                        HookActivity.ACTIVE -> stringResource(R.string.diag_hook_communication_active, state.hook.api)
                        HookActivity.STALE -> stringResource(R.string.diag_hook_communication_stale)
                        HookActivity.NEVER_PINGED -> stringResource(R.string.diag_hook_communication_none)
                    }
                    val hookColor = when (state.hook.activity) {
                        HookActivity.ACTIVE -> t.ok
                        HookActivity.STALE -> t.warn
                        HookActivity.NEVER_PINGED -> t.err
                    }
                    DiagRow(
                        label = stringResource(R.string.diag_health_lsposed),
                        value = hookActivityText,
                        valueColor = hookColor,
                        statusDotColor = hookColor,
                        subtitle = if (state.hook.lastPingMs > 0) stringResource(
                            R.string.diag_hook_communication_hint, state.hook.lastPackage ?: "—",
                            formatTimestamp(state.hook.lastPingMs)
                        ) else stringResource(R.string.diag_hook_communication_setup)
                    )

                    // 前台服务与运行态
                    val serviceOk = state.runtimeServiceEnabled && state.config.globalSwitch && state.bootGateUnlocked && !state.safeModeActive
                    val serviceText = when {
                        state.safeModeActive -> "安全模式生效中"
                        !state.bootGateUnlocked -> "重启保护待解锁"
                        !state.config.globalSwitch -> "总开关关闭"
                        !state.runtimeServiceEnabled -> "前台服务未启动"
                        else -> "正常运行中"
                    }
                    val serviceColor = if (serviceOk) t.ok else t.err
                    DiagRow(
                        label = stringResource(R.string.diag_health_service),
                        value = serviceText,
                        valueColor = serviceColor,
                        statusDotColor = serviceColor,
                        subtitle = if (serviceOk) "运行态正常，可响应 PCM 跨进程传输" else "服务未就绪，录音请求将回退到真麦"
                    )

                    // 生效范围
                    val scopeText = when (state.config.scopeMode) {
                        ScopeMode.GLOBAL -> "全系统模式"
                        ScopeMode.WHITELIST -> "白名单 (${state.config.whitelistCount} 个)"
                        ScopeMode.BLACKLIST -> "黑名单 (${state.config.blacklistCount} 个)"
                        else -> "全系统"
                    }
                    DiagRow(
                        label = stringResource(R.string.diag_health_scope),
                        value = scopeText,
                        valueColor = t.ok,
                        statusDotColor = t.ok,
                        subtitle = "若目标 App 没声音，请确认包名在生效清单内"
                    )

                    // 音频源就绪
                    val hasAudio = state.config.currentAudioId.isNotBlank() || state.audioInfo.displayName != "—"
                    val sourceColor = if (hasAudio) t.ok else t.warn
                    DiagRow(
                        label = stringResource(R.string.diag_health_source),
                        value = state.audioInfo.displayName,
                        valueColor = sourceColor,
                        statusDotColor = sourceColor,
                        subtitle = if (hasAudio) "音源类型: ${state.audioInfo.sourceType.name}" else "未选定音频，建议前往音频库导入"
                    )
                }
            }

            // ============ 5. 音频推流自检与试听 ============
            item {
                SectionLabel(stringResource(R.string.diag_section_probe))
                Spacer(Modifier.height(8.dp))
                ProbeCard(
                    probing = state.probing,
                    result = state.probeResult,
                    isPlaying = state.auditionPlaying,
                    onRun = vm::runPipelineProbe,
                    onToggleAudition = vm::toggleAudition
                )
            }

            // ============ 2. 设备与运行环境 ============
            item {
                DiagSection(stringResource(R.string.diag_section_device)) {
                    DiagRow(
                        label = stringResource(R.string.diag_device_model),
                        value = state.deviceEnv.model,
                        subtitle = state.deviceEnv.osVersion
                    )
                    DiagRow(
                        label = stringResource(R.string.diag_device_abi),
                        value = "arm64-v8a (64位)",
                        valueColor = t.ok,
                        subtitle = "设备支持: ${state.deviceEnv.abiList.take(32)}…"
                    )
                    val pssMb = state.deviceEnv.memoryPssKb / 1024.0
                    val heapMb = state.deviceEnv.heapUsedKb / 1024.0
                    val maxMb = state.deviceEnv.heapMaxKb / 1024.0
                    val ratioPct = (state.deviceEnv.heapRatio * 100).toInt()
                    DiagRow(
                        label = stringResource(R.string.diag_device_memory),
                        value = "PSS: %.1f MB".format(pssMb),
                        subtitle = "Java 堆: %.1f / %.1f MB (%d%%)".format(heapMb, maxMb, ratioPct)
                    )
                    DiagRow(
                        label = stringResource(R.string.diag_device_visibility),
                        value = if (state.deviceEnv.visibilityCompat) "已开启 (兼容高版本ROM)" else "关闭 (默认)",
                        valueColor = if (state.deviceEnv.visibilityCompat) t.ok else t.ink3,
                        subtitle = "若模块无效果，可在设置页开启此开关并授权 Root"
                    )
                }
            }

            // ============ 3. Native Hook 框架支持 ============
            item {
                DiagSection(stringResource(R.string.diag_section_native_hooks)) {
                    DiagRow(
                        label = stringResource(R.string.diag_hook_aaudio),
                        value = "AAudioStream_read",
                        valueColor = t.ok,
                        subtitle = "ShadowHook NDK 原生层拦截"
                    )
                    DiagRow(
                        label = stringResource(R.string.diag_hook_opensl),
                        value = "slCreateEngine / BQ",
                        valueColor = t.ok,
                        subtitle = "OpenSL ES 录音缓冲队列 Hook"
                    )
                    DiagRow(
                        label = stringResource(R.string.diag_hook_audiorecord_native),
                        value = "AudioRecord::read",
                        valueColor = t.ok,
                        subtitle = "libaudioclient.so C++ 实例拦截"
                    )
                }
            }

            // ============ 4. 音源参数与推流管线 ============
            item {
                DiagSection(stringResource(R.string.diag_section_audio_detail)) {
                    DiagRow(
                        label = stringResource(R.string.diag_audio_source_type),
                        value = state.audioInfo.sourceType.name,
                        valueColor = t.primaryInk
                    )
                    DiagRow(
                        label = stringResource(R.string.diag_audio_name),
                        value = state.audioInfo.displayName
                    )
                    if (state.audioInfo.sampleRate > 0) {
                        DiagRow(
                            label = stringResource(R.string.diag_audio_format),
                            value = "${state.audioInfo.sampleRate} Hz · ${if (state.audioInfo.channels == 2) "立体声" else "单声道"}",
                            subtitle = "MIME: ${state.audioInfo.mimeType}"
                        )
                    }
                    if (state.audioInfo.durationMs > 0 || state.audioInfo.sizeBytes > 0) {
                        DiagRow(
                            label = stringResource(R.string.diag_audio_duration_size),
                            value = "%.1f 秒 · %s".format(state.audioInfo.durationMs / 1000.0, formatBytes(state.audioInfo.sizeBytes))
                        )
                    }
                    val policyName = when (state.config.playbackPolicy) {
                        PlaybackPolicy.LOOP -> "循环播放"
                        PlaybackPolicy.SILENCE -> "播完静音"
                        PlaybackPolicy.REAL_MIC -> "切回真麦"
                        else -> "循环"
                    }
                    DiagRow(
                        label = "播放策略",
                        value = policyName
                    )
                }
            }

            // ============ 6. 实时拦截统计 ============
            item {
                DiagSection(stringResource(R.string.settings_section_intercept)) {
                    DiagRow(
                        label = stringResource(R.string.settings_intercept_total_reads),
                        value = "${state.stats.totalReads} 次",
                        isMonoValue = true
                    )
                    DiagRow(
                        label = stringResource(R.string.settings_intercept_total_bytes),
                        value = formatBytes(state.stats.totalBytes),
                        isMonoValue = true
                    )
                    DiagRow(
                        label = stringResource(R.string.settings_intercept_last_pkg),
                        value = state.stats.lastPackage ?: "—",
                        subtitle = if (state.stats.lastInterceptMs > 0) stringResource(
                            R.string.diag_last_replacement, formatTimestamp(state.stats.lastInterceptMs)
                        ) else stringResource(R.string.diag_no_replacement)
                    )
                    if (state.stats.lastSampleRate > 0) {
                        DiagRow(
                            label = stringResource(R.string.settings_intercept_format),
                            value = "${state.stats.lastSampleRate} Hz / ${state.stats.lastChannels} ch"
                        )
                    }
                }
            }

            // ============ 7. 最近拦截决策追踪 ============
            item {
                DiagSection(stringResource(R.string.diag_section_decisions)) {
                    if (state.decisions.isEmpty()) {
                        Text(
                            stringResource(R.string.diag_decisions_empty),
                            style = TextStyle(fontSize = 12.sp),
                            color = t.ink3,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        state.decisions.take(20).forEach { d ->
                            DecisionItemRow(d)
                        }
                    }
                    Text(
                        stringResource(R.string.diag_decisions_hint),
                        style = TextStyle(fontSize = 12.sp),
                        color = t.ink3,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            // ============ 8. 日志管理与诊断包 ============
            item {
                DiagSection(stringResource(R.string.diag_section_logs)) {
                    LogLevelPickerRow(state.config.logging.level, vm::setLogLevel)
                    DiagButtonRow(
                        label = stringResource(R.string.settings_export_diag),
                        busy = state.exporting,
                        busyText = stringResource(R.string.diag_exporting),
                        onClick = vm::exportDiagnostic
                    )
                    DiagButtonRow(
                        label = stringResource(R.string.settings_clear_log),
                        onClick = vm::clearLog
                    )
                    if (state.config.logging.level == LogLevel.DEBUG) {
                        DiagButtonRow(
                            label = stringResource(R.string.diag_tap_export),
                            busy = state.exporting,
                            busyText = stringResource(R.string.diag_exporting),
                            onClick = vm::exportAudioTap
                        )
                        DiagButtonRow(
                            label = stringResource(R.string.diag_tap_clear),
                            onClick = vm::clearAudioTap
                        )
                        Text(
                            stringResource(R.string.diag_tap_hint),
                            style = TextStyle(fontSize = 12.sp),
                            color = t.ink3,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            item {
                OutlineGlassButton(
                    text = if (state.exporting) stringResource(R.string.diag_exporting) else stringResource(R.string.settings_export_diag),
                    onClick = vm::exportDiagnostic,
                    enabled = !state.exporting,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

// ============ 子组件 ============

@Composable
private fun DiagSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        GroupCard(content = content)
    }
}

/**
 * 诊断信息行：左侧标题 + 说明；右侧为数值。
 * 带 [statusDotColor] 的行（健康自检）把数值渲染成状态胶囊。
 */
@Composable
private fun DiagRow(
    label: String,
    value: String,
    subtitle: String? = null,
    statusDotColor: Color? = null,
    valueColor: Color = Color.Unspecified,
    isMonoValue: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val t = glass
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 15.sp)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 12.sp, lineHeight = 17.sp, color = t.ink3)
        }
        if (statusDotColor != null) {
            StatusPill(
                value,
                when (statusDotColor) {
                    t.ok -> StatusKind.Ok
                    t.err -> StatusKind.Error
                    else -> StatusKind.Warn
                }
            )
        } else {
            Text(
                value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (valueColor == Color.Unspecified) t.ink else valueColor,
                fontFamily = if (isMonoValue) MonoFamily else null,
                textAlign = TextAlign.End,
                modifier = Modifier.wrapContentWidth(Alignment.End)
            )
        }
    }
}

/** 推流自检卡片：说明 + 32 根电平柱 + 主按钮（自检 → 试听）。 */
@Composable
private fun ProbeCard(
    probing: Boolean,
    result: AudioPipelineProbe.Result?,
    isPlaying: Boolean,
    onRun: () -> Unit,
    onToggleAudition: () -> Unit
) {
    val t = glass
    val levels = remember(result) { result?.pcmData?.let { pcmLevels(it, 32) } }
    val statusColor = when {
        result == null -> t.ink3
        result.ok && result.rms >= 1.0 -> t.ok
        result.bytesRead > 0 -> t.warn
        else -> t.err
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.diag_probe_hint), fontSize = 13.sp, lineHeight = 20.sp, color = t.ink2)
            Row(
                Modifier.fillMaxWidth().height(36.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                repeat(32) { i ->
                    val lv = levels?.getOrNull(i)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(if (lv == null) 4.dp else (6 + 30 * lv).dp)
                            .background(if (lv == null) t.fill else t.primary, RoundedCornerShape(2.dp))
                    )
                }
            }
            if (result != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(statusColor, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(result.message, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("RMS %.1f".format(result.rms), fontSize = 12.sp, color = statusColor, fontFamily = MonoFamily)
                }
                Text(
                    stringResource(R.string.diag_probe_detail, result.bytesRead, result.durationMs),
                    fontSize = 12.sp, color = t.ink3, fontFamily = MonoFamily
                )
            }
            val canAudition = result?.pcmData?.isNotEmpty() == true
            PrimaryButton(
                text = when {
                    probing -> stringResource(R.string.diag_probe_running)
                    canAudition && isPlaying -> stringResource(R.string.diag_probe_audition_stop)
                    canAudition -> stringResource(R.string.diag_probe_audition)
                    else -> stringResource(R.string.diag_probe_run)
                },
                onClick = if (canAudition) onToggleAudition else onRun,
                enabled = !probing,
                height = 46.dp,
                color = if (canAudition && isPlaying) t.err else Color.Unspecified,
                modifier = Modifier.fillMaxWidth()
            )
            if (canAudition && !probing) {
                SoftButton(
                    stringResource(R.string.diag_probe_rerun),
                    onRun,
                    height = 40.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 把 16 位小端 PCM 切成 [bars] 段，取每段 RMS 并归一化到 0..1。 */
private fun pcmLevels(pcm: ByteArray, bars: Int): List<Float> {
    val samples = pcm.size / 2
    if (samples < bars) return List(bars) { 0f }
    val per = samples / bars
    val rms = (0 until bars).map { b ->
        var sum = 0.0
        for (i in 0 until per) {
            val idx = (b * per + i) * 2
            val v = ((pcm[idx + 1].toInt() shl 8) or (pcm[idx].toInt() and 0xFF)).toShort().toDouble()
            sum += v * v
        }
        kotlin.math.sqrt(sum / per)
    }
    val max = rms.maxOrNull()?.takeIf { it > 0 } ?: return List(bars) { 0f }
    return rms.map { (it / max).toFloat().coerceIn(0f, 1f) }
}

@Composable
private fun DecisionItemRow(record: DecisionRecord) {
    val t = glass
    val tagColor = when (record.result) {
        SourceType.FILE, SourceType.TTS, SourceType.LIVE -> t.ok
        SourceType.SILENCE -> t.primaryInk
        SourceType.REAL_MIC -> t.ink3
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.callerPackage,
                    style = TextStyle(fontSize = 14.sp),
                    fontWeight = FontWeight.Medium,
                    color = t.ink
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(tagColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        record.result.name,
                        style = TextStyle(fontSize = 11.sp),
                        color = tagColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                if (record.occurrences > 1L) stringResource(
                    R.string.diag_decision_repeated, record.reasonDescription, record.occurrences
                ) else record.reasonDescription,
                style = TextStyle(fontSize = 12.sp),
                color = t.ink3,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            formatTimeOnly(record.lastSeen),
            style = TextStyle(fontSize = 11.sp),
            fontFamily = FontFamily.Monospace,
            color = t.ink3
        )
    }
}

@Composable
private fun DiagButtonRow(
    label: String,
    busy: Boolean = false,
    busyText: String = "处理中…",
    onClick: () -> Unit
) {
    val t = glass
    SettingRow(title = label, onClick = onClick, enabled = !busy, titleColor = t.primaryInk) {
        if (busy) Text(busyText, fontSize = 12.sp, color = t.ink3)
    }
}

@Composable
private fun LogLevelPickerRow(current: LogLevel, onSelect: (LogLevel) -> Unit) {
    StackedRow(stringResource(R.string.settings_log_level)) {
        Segmented(
            options = listOf(
                LogLevel.OFF to stringResource(R.string.settings_log_off),
                LogLevel.BASIC to stringResource(R.string.settings_log_basic),
                LogLevel.VERBOSE to stringResource(R.string.settings_log_verbose),
                LogLevel.DEBUG to stringResource(R.string.settings_log_debug)
            ),
            selected = if (current == LogLevel.UNRECOGNIZED) LogLevel.BASIC else current,
            onSelect = onSelect
        )
    }
}

private fun formatTimestamp(timeMs: Long): String {
    if (timeMs <= 0) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timeMs))
}

private fun formatTimeOnly(timeMs: Long): String {
    if (timeMs <= 0) return "—"
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timeMs))
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(Locale.US, "%.2f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
        else -> "$bytes B"
    }
}
