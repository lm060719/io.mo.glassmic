package io.mo.glassmic.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.mo.glassmic.BuildConfig
import io.mo.glassmic.R
import io.mo.glassmic.data.diag.AudioPipelineProbe
import io.mo.glassmic.data.runtime.HookActivity
import io.mo.glassmic.proto.FloatingSize
import io.mo.glassmic.proto.LogLevel
import io.mo.glassmic.proto.PlaybackPolicy
import io.mo.glassmic.proto.ThemeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

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

    val iconError by vm.iconError.collectAsState()
    LaunchedEffect(iconError) {
        iconError?.let { snackbar.showSnackbar(it); vm.consumeIconError() }
    }
    val iconPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.setFloatingIcon(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(snackbarData = it) } }
    ) { inner ->
        val cfg = state.config
        val visCompat by vm.visibilityCompat.collectAsState()
        // 每次进入设置页，按系统属性真实值刷新开关（属性非 1 显示关闭，避免误导）
        LaunchedEffect(Unit) { vm.refreshVisibilityCompat() }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Section(stringResource(R.string.settings_section_appearance)) {
                ThemePicker(cfg.appearance.theme, vm::setTheme)
            } }

            item { Section(stringResource(R.string.settings_section_floating)) {
                SwitchRow(
                    label = stringResource(R.string.settings_floating_enabled),
                    hint = stringResource(R.string.settings_floating_enabled_hint),
                    checked = cfg.floatingWindow.enabled,
                    onChange = vm::setFloatingEnabled
                )
                OpacitySlider(
                    value = cfg.floatingWindow.opacity.takeIf { it > 0f } ?: 0.85f,
                    onChange = vm::setFloatingOpacity
                )
                FloatingSizePicker(cfg.floatingWindow.size, vm::setFloatingSize)
                FloatingIconRow(
                    hasCustom = cfg.floatingWindow.customIconPath.isNotBlank(),
                    onPick = { iconPickerLauncher.launch(arrayOf("image/*")) },
                    onReset = { vm.setFloatingIcon(null) }
                )
            } }

            item { Section(stringResource(R.string.settings_section_policy)) {
                Text(
                    stringResource(R.string.settings_policy_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                PolicyOption(stringResource(R.string.library_policy_loop),
                    cfg.playbackPolicy == PlaybackPolicy.LOOP) { vm.setPolicy(PlaybackPolicy.LOOP) }
                PolicyOption(stringResource(R.string.library_policy_silence),
                    cfg.playbackPolicy == PlaybackPolicy.SILENCE) { vm.setPolicy(PlaybackPolicy.SILENCE) }
                PolicyOption(stringResource(R.string.library_policy_real_mic),
                    cfg.playbackPolicy == PlaybackPolicy.REAL_MIC) { vm.setPolicy(PlaybackPolicy.REAL_MIC) }
            } }

            item { Section(stringResource(R.string.settings_section_hook)) {
                HookCard(state.hook.activity, state.hook.lastPingMs, state.hook.lastPackage, state.hook.api)
            } }

            item { Section(stringResource(R.string.settings_section_intercept)) {
                InterceptStatsCard(
                    reads = state.interceptReads,
                    bytes = state.interceptBytes,
                    lastMs = state.interceptLastMs,
                    lastPkg = state.interceptLastPkg,
                    sampleRate = state.interceptLastSr,
                    channels = state.interceptLastCh
                )
                ActionRow(stringResource(R.string.settings_intercept_reset), onClick = vm::resetInterceptStats)
            } }

            item { Section(stringResource(R.string.settings_section_diag)) {
                LogLevelPicker(cfg.logging.level, vm::setLogLevel)
                ActionRow(stringResource(R.string.settings_pipeline_probe),
                    busy = state.probing,
                    onClick = vm::runPipelineProbe)
                state.probeResult?.let { ProbeResultCard(it) }
                Text(
                    stringResource(R.string.settings_pipeline_probe_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                ActionRow(stringResource(R.string.settings_export_diag),
                    busy = state.exporting,
                    onClick = vm::exportDiagnostic)
                ActionRow(stringResource(R.string.settings_clear_log), onClick = vm::clearLog)
            } }

            item { Section(stringResource(R.string.settings_section_compat)) {
                SwitchRow(
                    label = stringResource(R.string.settings_visibility_compat),
                    hint = stringResource(R.string.settings_visibility_compat_hint),
                    checked = visCompat,
                    onChange = vm::setVisibilityCompat
                )
            } }

            item { Section(stringResource(R.string.settings_section_experimental)) {
                SwitchRow(
                    label = stringResource(R.string.settings_experimental_unlock),
                    hint = stringResource(R.string.settings_experimental_unlock_hint),
                    checked = cfg.experimental.unlocked,
                    onChange = vm::setExperimentalUnlocked
                )
                if (cfg.experimental.unlocked) {
                    SwitchRow(stringResource(R.string.settings_exp_stress),
                        checked = cfg.experimental.stressTest, onChange = vm::setStressTest)
                    SwitchRow(stringResource(R.string.settings_exp_high_gain),
                        checked = cfg.experimental.highGain, onChange = vm::setHighGain)
                    SwitchRow(stringResource(R.string.settings_exp_noise),
                        checked = cfg.experimental.noiseSim, onChange = vm::setNoiseSim)
                    SwitchRow(
                        label = stringResource(R.string.settings_exp_limiter),
                        hint = stringResource(R.string.settings_exp_limiter_hint),
                        checked = cfg.experimental.limiterEnabled,
                        onChange = vm::setLimiter
                    )
                }
            } }

            item { Section(stringResource(R.string.settings_section_about)) {
                InfoRow(stringResource(R.string.settings_about_version),
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow(stringResource(R.string.settings_about_license), "GPL-3.0")
                InfoRow(stringResource(R.string.settings_about_repo), "github.com/lm060719/io.mo.glassmic")
            } }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

// ============ 基础组件 ============
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainer,
                    RoundedCornerShape(16.dp)
                )
                .padding(vertical = 4.dp)
        ) { content() }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    hint: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (!hint.isNullOrBlank()) {
                Text(
                    hint, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionRow(label: String, busy: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (busy) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        if (busy) Text("导出中…", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun PolicyOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
    }
}

// ============ 主题 ============
@Composable
private fun ThemePicker(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column {
        Text(
            stringResource(R.string.settings_theme),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
        )
        PolicyOption(stringResource(R.string.settings_theme_follow),
            current == ThemeMode.FOLLOW_SYSTEM) { onSelect(ThemeMode.FOLLOW_SYSTEM) }
        PolicyOption(stringResource(R.string.settings_theme_light),
            current == ThemeMode.LIGHT) { onSelect(ThemeMode.LIGHT) }
        PolicyOption(stringResource(R.string.settings_theme_dark),
            current == ThemeMode.DARK) { onSelect(ThemeMode.DARK) }
    }
}

// ============ 日志级别 ============
@Composable
private fun LogLevelPicker(current: LogLevel, onSelect: (LogLevel) -> Unit) {
    Column {
        Text(
            stringResource(R.string.settings_log_level),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
        )
        PolicyOption(stringResource(R.string.settings_log_off),
            current == LogLevel.OFF) { onSelect(LogLevel.OFF) }
        PolicyOption(stringResource(R.string.settings_log_basic),
            current == LogLevel.BASIC) { onSelect(LogLevel.BASIC) }
        PolicyOption(stringResource(R.string.settings_log_verbose),
            current == LogLevel.VERBOSE) { onSelect(LogLevel.VERBOSE) }
        PolicyOption(stringResource(R.string.settings_log_debug),
            current == LogLevel.DEBUG) { onSelect(LogLevel.DEBUG) }
    }
}

// ============ 悬浮窗不透明度 ============
@Composable
private fun OpacitySlider(value: Float, onChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_floating_opacity), modifier = Modifier.weight(1f))
            Text("%.0f%%".format(value * 100), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0.2f..1f,
            steps = 7
        )
    }
}

// ============ 悬浮球大小 ============
@Composable
private fun FloatingSizePicker(current: FloatingSize, onSelect: (FloatingSize) -> Unit) {
    Column {
        Text(
            stringResource(R.string.settings_floating_size),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
        )
        val effective = if (current == FloatingSize.UNRECOGNIZED) FloatingSize.STANDARD else current
        PolicyOption(stringResource(R.string.settings_floating_size_small),
            effective == FloatingSize.SMALL) { onSelect(FloatingSize.SMALL) }
        PolicyOption(stringResource(R.string.settings_floating_size_standard),
            effective == FloatingSize.STANDARD) { onSelect(FloatingSize.STANDARD) }
        PolicyOption(stringResource(R.string.settings_floating_size_large),
            effective == FloatingSize.LARGE) { onSelect(FloatingSize.LARGE) }
    }
}

// ============ 悬浮球图标 ============
@Composable
private fun FloatingIconRow(hasCustom: Boolean, onPick: () -> Unit, onReset: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_floating_icon), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_floating_icon_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        if (hasCustom) {
            TextButton(onClick = onReset) { Text(stringResource(R.string.settings_floating_icon_reset)) }
        }
        TextButton(onClick = onPick) { Text(stringResource(R.string.settings_floating_icon_pick)) }
    }
}

// ============ 自检结果 ============
@Composable
private fun ProbeResultCard(r: AudioPipelineProbe.Result) {
    val color = if (r.ok && r.rms >= 1.0) Color(0xFF34C759)
                else if (r.bytesRead > 0) Color(0xFFFFB020)
                else Color(0xFFE5484D)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
            Spacer(modifier = Modifier.width(8.dp))
            Text(r.message, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "字节: ${r.bytesRead}  ·  耗时: ${r.durationMs}ms  ·  RMS: %.1f".format(r.rms),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

// ============ 劫持统计 ============
@Composable
private fun InterceptStatsCard(
    reads: Long,
    bytes: Long,
    lastMs: Long,
    lastPkg: String?,
    sampleRate: Int,
    channels: Int
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        if (reads == 0L) {
            Text(
                stringResource(R.string.intercept_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "可能原因：模块尚未注入 / 目标 App 没在录音 / 目标 App 使用 AAudio（NDK）路径",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            return
        }
        StatLine(stringResource(R.string.settings_intercept_total_reads), reads.toString())
        StatLine(stringResource(R.string.settings_intercept_total_bytes),
            formatBytesSettings(bytes))
        if (lastMs > 0) {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastMs))
            StatLine(stringResource(R.string.settings_intercept_last), time)
        }
        if (!lastPkg.isNullOrBlank()) {
            StatLine(stringResource(R.string.settings_intercept_last_pkg), lastPkg)
        }
        if (sampleRate > 0 || channels > 0) {
            StatLine(stringResource(R.string.settings_intercept_format),
                "${sampleRate} Hz / ${channels} ch")
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatBytesSettings(b: Long): String = when {
    b < 1024 -> "${b} B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024.0)
    else -> "%.2f GB".format(b / 1024.0 / 1024.0 / 1024.0)
}

// ============ Xposed 状态卡片 ============
@Composable
private fun HookCard(activity: HookActivity, lastPingMs: Long, lastPkg: String?, api: Int) {
    val (statusText, dotColor, longHint) = when (activity) {
        HookActivity.ACTIVE -> Triple(
            stringResource(R.string.hook_status_active),
            Color(0xFF34C759),
            stringResource(R.string.settings_hook_active_long)
        )
        HookActivity.STALE -> Triple(
            stringResource(R.string.hook_status_stale),
            Color(0xFFFFB020),
            stringResource(R.string.settings_hook_stale_long)
        )
        HookActivity.NEVER_PINGED -> Triple(
            stringResource(R.string.hook_status_never),
            Color(0xFFE5484D),
            stringResource(R.string.settings_hook_never_long)
        )
    }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(statusText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(longHint, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        if (activity != HookActivity.NEVER_PINGED && lastPingMs > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastPingMs))
            Text("最近 ping: $time", style = MaterialTheme.typography.bodySmall)
            if (!lastPkg.isNullOrBlank()) {
                Text("最近 App: $lastPkg", style = MaterialTheme.typography.bodySmall)
            }
            if (api > 0) {
                Text("API: $api", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
