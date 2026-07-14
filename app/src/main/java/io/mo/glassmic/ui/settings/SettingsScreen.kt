package io.mo.glassmic.ui.settings

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.mo.glassmic.BuildConfig
import io.mo.glassmic.R
import io.mo.glassmic.data.diag.AudioPipelineProbe
import io.mo.glassmic.proto.AppLanguage
import io.mo.glassmic.proto.FloatingSize
import io.mo.glassmic.proto.LogLevel
import io.mo.glassmic.proto.PlaybackPolicy
import io.mo.glassmic.proto.ThemeMode
import io.mo.glassmic.proto.TtsProvider
import io.mo.glassmic.service.GlassTileService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAiTts: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

    // 请求把快捷设置磁贴添加到系统下拉面板（Android 13+）
    val onAddTile: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
                ComponentName(context, GlassTileService::class.java),
                context.getString(R.string.tile_label),
                Icon.createWithResource(context, R.drawable.ic_notification),
                context.mainExecutor
            ) { result ->
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                ) {
                    scope.launch {
                        snackbar.showSnackbar(context.getString(R.string.settings_tile_added))
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item { Section(stringResource(R.string.settings_section_tile)) {
                    ActionRow(stringResource(R.string.settings_tile_add), onClick = onAddTile)
                } }
            }

            item { Section(stringResource(R.string.settings_section_appearance)) {
                ThemePicker(cfg.appearance.theme, vm::setTheme)
                LanguagePicker(cfg.appearance.language) { lang ->
                    vm.setLanguage(lang)
                    // MainActivity 非 AppCompatActivity，Android 13 以下切换语言后需要手动
                    // recreate() 才能让新的 attachBaseContext() 包装立即生效，而不必等下次冷启动。
                    (context as? Activity)?.recreate()
                }
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
                SwitchRow(
                    label = stringResource(R.string.settings_waveform_enabled),
                    hint = stringResource(R.string.settings_waveform_enabled_hint),
                    checked = cfg.floatingWindow.waveformEnabled,
                    onChange = vm::setWaveformEnabled
                )
                if (cfg.floatingWindow.waveformEnabled) {
                    val wfOpacity = cfg.floatingWindow.waveformOpacity.takeIf { it > 0f } ?: 0.6f
                    LabeledSlider(
                        label = stringResource(R.string.settings_waveform_opacity),
                        value = wfOpacity,
                        valueRange = 0.15f..1f,
                        display = "%.0f%%".format(wfOpacity * 100),
                        onChange = vm::setWaveformOpacity
                    )
                }
            } }

            item { Section(stringResource(R.string.settings_section_policy)) {
                PlaybackPolicyPicker(cfg.playbackPolicy, vm::setPolicy)
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
                    SwitchRow(
                        label = stringResource(R.string.settings_exp_reverb),
                        hint = stringResource(R.string.settings_exp_reverb_hint),
                        checked = cfg.experimental.reverbEnabled,
                        onChange = vm::setReverbEnabled
                    )
                    if (cfg.experimental.reverbEnabled) {
                        val amount = cfg.experimental.reverbAmount.takeIf { it > 0f } ?: 0.5f
                        LabeledSlider(
                            label = stringResource(R.string.settings_exp_reverb_amount),
                            value = amount,
                            valueRange = 0f..1f,
                            display = "%.0f%%".format(amount * 100),
                            onChange = vm::setReverbAmount
                        )
                    }
                    SwitchRow(
                        label = stringResource(R.string.settings_exp_speed),
                        hint = stringResource(R.string.settings_exp_speed_hint),
                        checked = cfg.experimental.speedEnabled,
                        onChange = vm::setSpeedEnabled
                    )
                    if (cfg.experimental.speedEnabled) {
                        val factor = cfg.experimental.speedFactor.takeIf { it > 0f } ?: 1f
                        LabeledSlider(
                            label = stringResource(R.string.settings_exp_speed_factor),
                            value = factor,
                            valueRange = 0.5f..2.0f,
                            display = "%.2fx".format(factor),
                            onChange = vm::setSpeedFactor
                        )
                    }
                }
            } }

            item { Section(stringResource(R.string.settings_section_ai_tts)) {
                NavRow(
                    title = stringResource(R.string.ai_tts_title),
                    subtitle = if (cfg.tts.ai.enabled) {
                        stringResource(R.string.ai_tts_nav_enabled, providerLabel(cfg.tts.ai.provider))
                    } else {
                        stringResource(R.string.ai_tts_nav_disabled)
                    },
                    onClick = onOpenAiTts
                )
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

// ============ 基础组件（部分供 AiTtsSettingsScreen 复用） ============
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
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
internal fun SwitchRow(
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
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        Text("›", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
    }
}

private fun providerLabel(p: TtsProvider): String = when (p) {
    TtsProvider.GEMINI -> "Gemini"
    TtsProvider.MIMO -> "MiMo"
    else -> "OpenAI"
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
internal fun PolicyOption(label: String, selected: Boolean, onSelect: () -> Unit) {
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

// ============ 可折叠下拉选择行（点击展开为悬浮菜单） ============
@Composable
internal fun <T> DropdownPickerRow(
    title: String,
    hint: String? = null,
    current: T,
    options: List<Pair<String, T>>,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.second == current }?.first.orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!hint.isNullOrBlank()) {
                Text(
                    hint, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    currentLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset(x = 0.dp, y = 4.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                options.forEach { (label, value) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { onSelect(value); expanded = false },
                        trailingIcon = {
                            if (value == current) {
                                Icon(
                                    Icons.Filled.Check, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

// ============ 主题 ============
@Composable
private fun ThemePicker(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    DropdownPickerRow(
        title = stringResource(R.string.settings_theme),
        current = current,
        options = listOf(
            stringResource(R.string.settings_theme_follow) to ThemeMode.FOLLOW_SYSTEM,
            stringResource(R.string.settings_theme_light) to ThemeMode.LIGHT,
            stringResource(R.string.settings_theme_dark) to ThemeMode.DARK
        ),
        onSelect = onSelect
    )
}

// ============ 语言 ============
@Composable
private fun LanguagePicker(current: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    DropdownPickerRow(
        title = stringResource(R.string.settings_language),
        current = current,
        options = listOf(
            stringResource(R.string.settings_language_follow) to AppLanguage.SYSTEM,
            stringResource(R.string.settings_language_zh) to AppLanguage.ZH,
            stringResource(R.string.settings_language_en) to AppLanguage.EN
        ),
        onSelect = onSelect
    )
}

// ============ 默认播放策略 ============
@Composable
private fun PlaybackPolicyPicker(current: PlaybackPolicy, onSelect: (PlaybackPolicy) -> Unit) {
    DropdownPickerRow(
        title = stringResource(R.string.settings_policy_hint),
        current = current,
        options = listOf(
            stringResource(R.string.library_policy_loop) to PlaybackPolicy.LOOP,
            stringResource(R.string.library_policy_silence) to PlaybackPolicy.SILENCE,
            stringResource(R.string.library_policy_real_mic) to PlaybackPolicy.REAL_MIC
        ),
        onSelect = onSelect
    )
}

// ============ 日志级别 ============
@Composable
private fun LogLevelPicker(current: LogLevel, onSelect: (LogLevel) -> Unit) {
    DropdownPickerRow(
        title = stringResource(R.string.settings_log_level),
        current = current,
        options = listOf(
            stringResource(R.string.settings_log_off) to LogLevel.OFF,
            stringResource(R.string.settings_log_basic) to LogLevel.BASIC,
            stringResource(R.string.settings_log_verbose) to LogLevel.VERBOSE,
            stringResource(R.string.settings_log_debug) to LogLevel.DEBUG
        ),
        onSelect = onSelect
    )
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

// ============ 通用带标签滑块 ============
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Slider(value = value, onValueChange = onChange, valueRange = valueRange, steps = steps)
    }
}

// ============ 悬浮球大小 ============
@Composable
private fun FloatingSizePicker(current: FloatingSize, onSelect: (FloatingSize) -> Unit) {
    val effective = if (current == FloatingSize.UNRECOGNIZED) FloatingSize.STANDARD else current
    DropdownPickerRow(
        title = stringResource(R.string.settings_floating_size),
        current = effective,
        options = listOf(
            stringResource(R.string.settings_floating_size_small) to FloatingSize.SMALL,
            stringResource(R.string.settings_floating_size_standard) to FloatingSize.STANDARD,
            stringResource(R.string.settings_floating_size_large) to FloatingSize.LARGE
        ),
        onSelect = onSelect
    )
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

