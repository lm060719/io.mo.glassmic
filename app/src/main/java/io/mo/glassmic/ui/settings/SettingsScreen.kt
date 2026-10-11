package io.mo.glassmic.ui.settings

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.mo.glassmic.BuildConfig
import io.mo.glassmic.R
import io.mo.glassmic.data.config.audioPolicyTargets
import io.mo.glassmic.proto.AppLanguage
import io.mo.glassmic.proto.Appearance
import io.mo.glassmic.proto.BackgroundMode
import io.mo.glassmic.data.appearance.WallpaperStatus
import io.mo.glassmic.ui.theme.PageBackdrop
import androidx.activity.result.PickVisualMediaRequest
import io.mo.glassmic.proto.FloatingSize
import io.mo.glassmic.proto.InjectionBackend
import io.mo.glassmic.proto.PlaybackPolicy
import io.mo.glassmic.proto.ThemeMode
import io.mo.glassmic.proto.TtsProvider
import io.mo.glassmic.root.PolicyPhase
import io.mo.glassmic.service.GlassTileService
import io.mo.glassmic.ui.common.GlassPage
import io.mo.glassmic.ui.common.GlassSlider
import io.mo.glassmic.ui.common.GroupCard
import io.mo.glassmic.ui.common.LargeTitle
import io.mo.glassmic.ui.common.MonoFamily
import io.mo.glassmic.ui.common.RadioDot
import io.mo.glassmic.ui.common.SectionLabel
import io.mo.glassmic.ui.common.Segmented
import io.mo.glassmic.ui.common.SettingRow
import io.mo.glassmic.ui.common.SoftButton
import io.mo.glassmic.ui.common.StackedRow
import io.mo.glassmic.ui.common.ToggleRow
import io.mo.glassmic.ui.common.glass
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onOpenScope: () -> Unit,
    onOpenAiTts: () -> Unit,
    onOpenDiagnostic: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val policyStatus by vm.policyStatus.collectAsState()
    val runtimeState by vm.runtimeState.collectAsState()
    val exporting by vm.exporting.collectAsState()
    val toast = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = glass

    val iconError by vm.iconError.collectAsState()
    LaunchedEffect(iconError) {
        iconError?.let { toast.showSnackbar(it); vm.consumeIconError() }
    }
    val backgroundError by vm.backgroundError.collectAsState()
    LaunchedEffect(backgroundError) {
        backgroundError?.let { toast.showSnackbar(it); vm.consumeBackgroundError() }
    }
    val wallpaperStatus by vm.wallpaperStatus.collectAsState()
    // 系统照片选择器：无需存储权限
    val backgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.setBackgroundImage(uri) }
    val pickBackground = {
        backgroundPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
                    scope.launch { toast.showSnackbar(context.getString(R.string.settings_tile_added)) }
                }
            }
        }
    }

    val onExportDiag: () -> Unit = {
        scope.launch { toast.showSnackbar(context.getString(R.string.diag_exporting)) }
        vm.exportDiagnostic(
            onReady = { uri ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.settings_export_diag))) }
            },
            onError = { e -> scope.launch { toast.showSnackbar(e.message ?: context.getString(R.string.settings_export_failed)) } }
        )
    }

    val cfg = state.config
    val visCompat by vm.visibilityCompat.collectAsState()
    // 每次进入设置页，按系统属性真实值刷新开关（属性非 1 显示关闭，避免误导）
    LaunchedEffect(Unit) { vm.refreshVisibilityCompat() }

    GlassPage(toast) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { LargeTitle(stringResource(R.string.settings_title)) }

            // ============ 外观 ============
            item { Section(stringResource(R.string.settings_section_appearance)) {
                StackedRow(stringResource(R.string.settings_theme)) {
                    Segmented(
                        options = listOf(
                            ThemeMode.FOLLOW_SYSTEM to stringResource(R.string.settings_theme_follow),
                            ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                            ThemeMode.DARK to stringResource(R.string.settings_theme_dark)
                        ),
                        selected = if (cfg.appearance.theme == ThemeMode.UNRECOGNIZED) ThemeMode.FOLLOW_SYSTEM else cfg.appearance.theme,
                        onSelect = vm::setTheme
                    )
                }
                StackedRow(stringResource(R.string.settings_language)) {
                    Segmented(
                        options = listOf(
                            AppLanguage.SYSTEM to stringResource(R.string.settings_language_follow),
                            AppLanguage.ZH to stringResource(R.string.settings_language_zh),
                            AppLanguage.EN to stringResource(R.string.settings_language_en)
                        ),
                        selected = if (cfg.appearance.language == AppLanguage.UNRECOGNIZED) AppLanguage.SYSTEM else cfg.appearance.language,
                        onSelect = { lang ->
                            if (lang != cfg.appearance.language) {
                                vm.setLanguage(lang)
                                // MainActivity 非 AppCompatActivity，Android 13 以下切换语言后需要手动
                                // recreate() 才能让新的 attachBaseContext() 包装立即生效，而不必等下次冷启动。
                                (context as? Activity)?.recreate()
                            }
                        }
                    )
                }
                BackgroundRows(
                    appearance = cfg.appearance,
                    wallpaperStatus = wallpaperStatus,
                    onMode = { mode ->
                        // 选「自选图片」但还没有图片时，直接打开选择器；选好才真正切换
                        if (mode == BackgroundMode.BACKGROUND_IMAGE && cfg.appearance.backgroundImagePath.isBlank()) pickBackground()
                        else vm.setBackgroundMode(mode)
                    },
                    onPick = pickBackground,
                    onRefreshWallpaper = vm::refreshWallpaper,
                    onBlur = vm::setBackgroundBlur,
                    onDim = vm::setBackgroundDim
                )
                ToggleRow(
                    title = stringResource(R.string.settings_glass_effect),
                    subtitle = stringResource(R.string.settings_glass_effect_hint),
                    checked = cfg.appearance.glassEffect,
                    onChange = vm::setGlassEffect
                )
                ToggleRow(
                    title = stringResource(R.string.settings_reduce_motion),
                    checked = cfg.appearance.reduceMotion,
                    onChange = vm::setReduceMotion
                )
            } }

            // ============ 悬浮窗 ============
            item { Section(stringResource(R.string.settings_section_floating)) {
                ToggleRow(
                    title = stringResource(R.string.settings_floating_enabled),
                    subtitle = stringResource(R.string.settings_floating_enabled_hint),
                    checked = cfg.floatingWindow.enabled,
                    onChange = vm::setFloatingEnabled
                )
                val opacity = cfg.floatingWindow.opacity.takeIf { it > 0f } ?: 0.85f
                StackedRow(stringResource(R.string.settings_floating_opacity), value = "%.0f%%".format(opacity * 100)) {
                    // 不设 steps：与波形透明度一致的无级滑动
                    GlassSlider(value = opacity, onValueChange = vm::setFloatingOpacity, valueRange = 0.2f..1f)
                }
                StackedRow(stringResource(R.string.settings_floating_size)) {
                    Segmented(
                        options = listOf(
                            FloatingSize.SMALL to stringResource(R.string.settings_floating_size_small),
                            FloatingSize.STANDARD to stringResource(R.string.settings_floating_size_standard),
                            FloatingSize.LARGE to stringResource(R.string.settings_floating_size_large)
                        ),
                        selected = if (cfg.floatingWindow.size == FloatingSize.UNRECOGNIZED) FloatingSize.STANDARD else cfg.floatingWindow.size,
                        onSelect = vm::setFloatingSize
                    )
                }
                SettingRow(
                    title = stringResource(R.string.settings_floating_icon),
                    subtitle = stringResource(R.string.settings_floating_icon_hint)
                ) {
                    if (cfg.floatingWindow.customIconPath.isNotBlank()) {
                        SoftButton(stringResource(R.string.settings_floating_icon_reset), { vm.setFloatingIcon(null) }, height = 32.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    SoftButton(stringResource(R.string.settings_floating_icon_pick), { iconPickerLauncher.launch(arrayOf("image/*")) }, height = 32.dp)
                }
                ToggleRow(
                    title = stringResource(R.string.settings_waveform_enabled),
                    subtitle = stringResource(R.string.settings_waveform_enabled_hint),
                    checked = cfg.floatingWindow.waveformEnabled,
                    onChange = vm::setWaveformEnabled
                )
                if (cfg.floatingWindow.waveformEnabled) {
                    val wfOpacity = cfg.floatingWindow.waveformOpacity.takeIf { it > 0f } ?: 0.6f
                    StackedRow(stringResource(R.string.settings_waveform_opacity), value = "%.0f%%".format(wfOpacity * 100)) {
                        GlassSlider(value = wfOpacity, onValueChange = vm::setWaveformOpacity, valueRange = 0.15f..1f)
                    }
                }
                ToggleRow(
                    title = stringResource(R.string.settings_volume_shortcut),
                    subtitle = stringResource(R.string.settings_volume_shortcut_hint),
                    checked = cfg.shortcuts.volumeKeysEnabled,
                    onChange = vm::setVolumeKeysEnabled
                )
            } }

            // ============ 音频 ============
            item { Section(stringResource(R.string.settings_section_audio)) {
                SettingRow(
                    title = stringResource(R.string.ai_tts_title),
                    subtitle = if (cfg.tts.ai.enabled) {
                        stringResource(R.string.ai_tts_nav_enabled, providerLabel(cfg.tts.ai.provider))
                    } else {
                        stringResource(R.string.ai_tts_nav_disabled)
                    },
                    onClick = onOpenAiTts,
                    chevron = true
                )
                val scopeSubtitle = if (cfg.injectionBackend == InjectionBackend.AUDIO_POLICY) {
                    val targets = cfg.audioPolicyTargets()
                    when {
                        cfg.audioPolicyAllApps -> stringResource(R.string.scope_all_apps_selected)
                        targets.isEmpty() -> stringResource(R.string.scope_none_selected)
                        targets.size == 1 -> targets.first()
                        else -> stringResource(R.string.home_scope_whitelist_count, targets.size)
                    }
                } else if (cfg.whitelistCount > 0) {
                    stringResource(R.string.home_scope_whitelist_count, cfg.whitelistCount)
                } else {
                    stringResource(R.string.scope_none_selected)
                }
                SettingRow(
                    title = stringResource(R.string.scope_title),
                    subtitle = scopeSubtitle,
                    onClick = onOpenScope,
                    chevron = true
                )
                StackedRow(stringResource(R.string.settings_section_policy), subtitle = stringResource(R.string.settings_policy_hint)) {
                    Segmented(
                        options = listOf(
                            PlaybackPolicy.LOOP to stringResource(R.string.library_policy_loop),
                            PlaybackPolicy.SILENCE to stringResource(R.string.library_policy_silence),
                            PlaybackPolicy.REAL_MIC to stringResource(R.string.policy_real_short)
                        ),
                        selected = if (cfg.playbackPolicy == PlaybackPolicy.UNRECOGNIZED) PlaybackPolicy.LOOP else cfg.playbackPolicy,
                        onSelect = vm::setPolicy
                    )
                }
                ToggleRow(
                    title = stringResource(R.string.settings_audio_monitor_enabled),
                    subtitle = stringResource(R.string.settings_audio_monitor_enabled_hint),
                    checked = cfg.audioMonitor.enabled,
                    onChange = vm::setAudioMonitorEnabled
                )
                if (cfg.audioMonitor.enabled) {
                    val monVol = cfg.audioMonitor.volume.takeIf { it > 0f } ?: 1.0f
                    StackedRow(stringResource(R.string.settings_audio_monitor_volume), value = "%.0f%%".format(monVol * 100)) {
                        GlassSlider(value = monVol, onValueChange = vm::setAudioMonitorVolume)
                    }
                }
            } }

            item {
                AudioBandSection(
                    config = cfg.audioBand,
                    onEnabled = vm::setAudioBandEnabled,
                    onBand = vm::setAudioBand
                )
            }

            // ============ 注入方式 ============
            item { Section(stringResource(R.string.backend_title)) {
                val canChange = !runtimeState.enabled &&
                    policyStatus.phase !in listOf(PolicyPhase.STARTING, PolicyPhase.ACTIVE, PolicyPhase.STOPPING)
                listOf(
                    InjectionBackend.LSPOSED to stringResource(R.string.backend_lsposed),
                    InjectionBackend.AUDIO_POLICY to stringResource(R.string.backend_audio_policy)
                ).forEach { (backend, label) ->
                    PolicyOption(label, cfg.injectionBackend == backend, enabled = canChange) { vm.setBackend(backend) }
                }
                Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.backend_switch_hint), fontSize = 12.sp, color = t.ink3, lineHeight = 17.sp)
                    if (cfg.injectionBackend == InjectionBackend.AUDIO_POLICY) {
                        Text(stringResource(R.string.backend_policy_hint), fontSize = 12.sp, color = t.ink3, lineHeight = 17.sp)
                        Text(
                            policyStatus.label(context), fontSize = 13.sp,
                            color = if (policyStatus.phase == PolicyPhase.ERROR) t.err else t.ink2
                        )
                        if (runtimeState.enabled && policyStatus.phase == PolicyPhase.ERROR) {
                            SoftButton(stringResource(R.string.backend_retry), vm::retryAudioPolicy, textColor = t.primaryInk)
                        }
                    }
                }
            } }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item { Section(stringResource(R.string.settings_section_tile)) {
                    SettingRow(
                        title = stringResource(R.string.settings_tile_add),
                        subtitle = stringResource(R.string.settings_tile_add_hint),
                        onClick = onAddTile,
                        chevron = true
                    )
                } }
            }

            item { Section(stringResource(R.string.settings_section_compat)) {
                ToggleRow(
                    title = stringResource(R.string.settings_visibility_compat),
                    subtitle = stringResource(R.string.settings_visibility_compat_hint),
                    checked = visCompat,
                    onChange = vm::setVisibilityCompat
                )
            } }

            item { Section(stringResource(R.string.settings_section_experimental)) {
                ToggleRow(
                    title = stringResource(R.string.settings_experimental_unlock),
                    subtitle = stringResource(R.string.settings_experimental_unlock_hint),
                    checked = cfg.experimental.unlocked,
                    onChange = vm::setExperimentalUnlocked
                )
                if (cfg.experimental.unlocked) {
                    ToggleRow(stringResource(R.string.settings_exp_stress), checked = cfg.experimental.stressTest, onChange = vm::setStressTest)
                    ToggleRow(stringResource(R.string.settings_exp_high_gain), checked = cfg.experimental.highGain, onChange = vm::setHighGain)
                    ToggleRow(stringResource(R.string.settings_exp_noise), checked = cfg.experimental.noiseSim, onChange = vm::setNoiseSim)
                    ToggleRow(
                        title = stringResource(R.string.settings_exp_limiter),
                        subtitle = stringResource(R.string.settings_exp_limiter_hint),
                        checked = cfg.experimental.limiterEnabled,
                        onChange = vm::setLimiter
                    )
                    ToggleRow(
                        title = stringResource(R.string.settings_exp_reverb),
                        subtitle = stringResource(R.string.settings_exp_reverb_hint),
                        checked = cfg.experimental.reverbEnabled,
                        onChange = vm::setReverbEnabled
                    )
                    if (cfg.experimental.reverbEnabled) {
                        val amount = cfg.experimental.reverbAmount.takeIf { it > 0f } ?: 0.5f
                        StackedRow(stringResource(R.string.settings_exp_reverb_amount), value = "%.0f%%".format(amount * 100)) {
                            GlassSlider(value = amount, onValueChange = vm::setReverbAmount)
                        }
                    }
                    ToggleRow(
                        title = stringResource(R.string.settings_exp_speed),
                        subtitle = stringResource(R.string.settings_exp_speed_hint),
                        checked = cfg.experimental.speedEnabled,
                        onChange = vm::setSpeedEnabled
                    )
                    if (cfg.experimental.speedEnabled) {
                        val factor = cfg.experimental.speedFactor.takeIf { it > 0f } ?: 1f
                        StackedRow(stringResource(R.string.settings_exp_speed_factor), value = "%.2fx".format(factor)) {
                            GlassSlider(value = factor, onValueChange = vm::setSpeedFactor, valueRange = 0.5f..2.0f)
                        }
                    }
                }
            } }

            // ============ 诊断与关于 ============
            item { Section(stringResource(R.string.settings_section_diag_about)) {
                SettingRow(stringResource(R.string.diag_title), onClick = onOpenDiagnostic, chevron = true)
                SettingRow(
                    stringResource(R.string.settings_export_diag),
                    onClick = onExportDiag,
                    enabled = !exporting
                ) {
                    if (exporting) Text(stringResource(R.string.diag_exporting), fontSize = 12.sp, color = t.ink3)
                }
                SettingRow(stringResource(R.string.settings_about_version)) {
                    Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", fontSize = 12.sp, color = t.ink3, fontFamily = MonoFamily)
                }
                SettingRow(stringResource(R.string.settings_about_license)) {
                    Text("GPL-3.0", fontSize = 12.sp, color = t.ink3, fontFamily = MonoFamily)
                }
                SettingRow(stringResource(R.string.settings_about_repo)) {
                    Text("lm060719/io.mo.glassmic", fontSize = 12.sp, color = t.ink3, fontFamily = MonoFamily)
                }
            } }
        }
    }
}

// ============ 基础组件（AiTtsSettingsScreen / AudioBandSection 复用） ============

/** 小标题 + 玻璃分组卡片。 */
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        GroupCard(content = content)
    }
}

@Composable
internal fun SwitchRow(
    label: String,
    hint: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) = ToggleRow(title = label, subtitle = hint, checked = checked, onChange = onChange)

@Composable
internal fun PolicyOption(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    SettingRow(title = label, onClick = onSelect, enabled = enabled) {
        RadioDot(selected)
    }
}

/** 点击展开为悬浮菜单的选择行。 */
@Composable
internal fun <T> DropdownPickerRow(
    title: String,
    hint: String? = null,
    current: T,
    options: List<Pair<String, T>>,
    onSelect: (T) -> Unit
) {
    val t = glass
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.second == current }?.first.orEmpty()
    SettingRow(title = title, subtitle = hint, onClick = { expanded = true }) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(currentLabel, fontSize = 13.sp, color = t.ink2)
                Icon(Icons.Rounded.UnfoldMore, null, tint = t.ink3, modifier = Modifier.padding(start = 2.dp).size(18.dp))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset(x = 0.dp, y = 4.dp),
                shape = RoundedCornerShape(18.dp),
                containerColor = t.sheet
            ) {
                options.forEach { (label, value) ->
                    DropdownMenuItem(
                        text = { Text(label, fontSize = 14.sp, color = if (value == current) t.primaryInk else t.ink) },
                        onClick = { onSelect(value); expanded = false },
                        trailingIcon = {
                            if (value == current) Icon(Icons.Rounded.Check, null, tint = t.primaryInk, modifier = Modifier.size(18.dp))
                        }
                    )
                }
            }
        }
    }
}

internal fun providerLabel(p: TtsProvider): String = when (p) {
    TtsProvider.GEMINI -> "Gemini"
    TtsProvider.MIMO -> "MiMo"
    else -> "OpenAI"
}

// ============ 页面背景 ============
@Composable
private fun BackgroundRows(
    appearance: Appearance,
    wallpaperStatus: WallpaperStatus,
    onMode: (BackgroundMode) -> Unit,
    onPick: () -> Unit,
    onRefreshWallpaper: () -> Unit,
    onBlur: (Float) -> Unit,
    onDim: (Float) -> Unit
) {
    val mode = if (appearance.backgroundMode == BackgroundMode.UNRECOGNIZED) BackgroundMode.BACKGROUND_DEFAULT
               else appearance.backgroundMode
    StackedRow(stringResource(R.string.settings_background)) {
        Segmented(
            options = listOf(
                BackgroundMode.BACKGROUND_DEFAULT to stringResource(R.string.settings_background_default),
                BackgroundMode.BACKGROUND_IMAGE to stringResource(R.string.settings_background_image),
                BackgroundMode.BACKGROUND_WALLPAPER to stringResource(R.string.settings_background_wallpaper)
            ),
            selected = mode,
            onSelect = onMode
        )
    }
    when (mode) {
        BackgroundMode.BACKGROUND_IMAGE -> SettingRow(
            title = stringResource(R.string.settings_background_image_row),
            subtitle = stringResource(R.string.settings_background_image_hint)
        ) {
            SoftButton(stringResource(R.string.settings_background_pick), onPick, height = 32.dp)
        }
        BackgroundMode.BACKGROUND_WALLPAPER -> SettingRow(
            title = stringResource(R.string.settings_background_wallpaper_row),
            subtitle = stringResource(
                when (wallpaperStatus) {
                    WallpaperStatus.IMAGE -> R.string.settings_background_wallpaper_ok
                    WallpaperStatus.COLORS_ONLY -> R.string.settings_background_wallpaper_colors
                    WallpaperStatus.FAILED -> R.string.settings_background_wallpaper_failed
                    WallpaperStatus.UNKNOWN -> R.string.settings_background_wallpaper_loading
                }
            )
        ) {
            SoftButton(stringResource(R.string.settings_background_refresh), onRefreshWallpaper, height = 32.dp)
        }
        else -> Unit
    }
    // 卡片模糊 / 遮罩作用在卡片的磨砂层上（背景本身保持清晰），关闭液态玻璃时卡片为实色，不显示
    if (appearance.glassEffect) {
        val blur = if (appearance.hasBackgroundBlur()) appearance.backgroundBlur else PageBackdrop.DEFAULT_BLUR
        StackedRow(stringResource(R.string.settings_background_blur), value = "%.0f%%".format(blur * 100)) {
            GlassSlider(value = blur, onValueChange = onBlur)
        }
        val dim = if (appearance.hasBackgroundDim()) appearance.backgroundDim else PageBackdrop.DEFAULT_DIM
        StackedRow(
            stringResource(R.string.settings_background_dim),
            subtitle = stringResource(R.string.settings_background_dim_hint),
            value = "%.0f%%".format(dim * 100)
        ) {
            GlassSlider(value = dim, onValueChange = onDim, valueRange = 0f..0.8f)
        }
    }
}
