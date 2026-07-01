package io.mo.glassmic.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.diag.AudioPipelineProbe
import io.mo.glassmic.data.diag.DiagnosticBundler
import io.mo.glassmic.data.runtime.AudioStatsRepository
import io.mo.glassmic.data.runtime.HookStatus
import io.mo.glassmic.data.runtime.HookStatusRepository
import io.mo.glassmic.data.runtime.VisibilityCompatRepository
import kotlinx.coroutines.flow.asStateFlow
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.proto.AppConfig
import io.mo.glassmic.proto.LogLevel
import io.mo.glassmic.proto.PlaybackPolicy
import io.mo.glassmic.proto.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val config: AppConfig = AppConfig.getDefaultInstance(),
    val hook: HookStatus = HookStatus(
        activity = io.mo.glassmic.data.runtime.HookActivity.NEVER_PINGED,
        lastPingMs = 0L,
        lastPackage = null,
        api = 0
    ),
    val interceptReads: Long = 0L,
    val interceptBytes: Long = 0L,
    val interceptLastMs: Long = 0L,
    val interceptLastPkg: String? = null,
    val interceptLastSr: Int = 0,
    val interceptLastCh: Int = 0,
    val exporting: Boolean = false,
    val exportedUri: Uri? = null,
    val exportError: String? = null,
    val probing: Boolean = false,
    val probeResult: AudioPipelineProbe.Result? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configStore: ConfigStore,
    private val bundler: DiagnosticBundler,
    private val probe: AudioPipelineProbe,
    private val audioStatsRepo: AudioStatsRepository,
    private val visibilityCompatRepo: VisibilityCompatRepository,
    hookStatusRepo: HookStatusRepository
) : ViewModel() {

    private val _visibilityCompat = MutableStateFlow(visibilityCompatRepo.isEnabled())
    /** 「严格 ROM 兼容」开关状态，UI 单独 collect。 */
    val visibilityCompat: StateFlow<Boolean> = _visibilityCompat.asStateFlow()

    /** 每次进入设置页时按系统属性真实值刷新开关（属性非 1 一律显示关闭）。 */
    fun refreshVisibilityCompat() {
        _visibilityCompat.value = visibilityCompatRepo.isEnabled()
    }

    fun setVisibilityCompat(enabled: Boolean) {
        _visibilityCompat.value = enabled  // 立即回显
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = visibilityCompatRepo.setEnabled(enabled)
            // 开启时若 Root 写入失败（多半是未授权 su），回滚开关让用户察觉。
            if (enabled && !ok) _visibilityCompat.value = false
        }
    }

    private val _exporting = MutableStateFlow(false)
    private val _exportedUri = MutableStateFlow<Uri?>(null)
    private val _exportError = MutableStateFlow<String?>(null)
    private val _probing = MutableStateFlow(false)
    private val _probeResult = MutableStateFlow<AudioPipelineProbe.Result?>(null)

    val state: StateFlow<SettingsUiState> = combine(
        combine(configStore.flow, hookStatusRepo.flow, audioStatsRepo.flow) { cfg, hook, stats ->
            Triple(cfg, hook, stats)
        },
        combine(_exporting, _exportedUri, _exportError) { e, u, err -> Triple(e, u, err) },
        combine(_probing, _probeResult) { p, r -> p to r }
    ) { core, exp, prb ->
        val (cfg, hook, stats) = core
        SettingsUiState(
            config = cfg,
            hook = hook,
            interceptReads = stats.totalReads,
            interceptBytes = stats.totalBytes,
            interceptLastMs = stats.lastInterceptMs,
            interceptLastPkg = stats.lastPackage,
            interceptLastSr = stats.lastSampleRate,
            interceptLastCh = stats.lastChannels,
            exporting = exp.first,
            exportedUri = exp.second,
            exportError = exp.third,
            probing = prb.first,
            probeResult = prb.second
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    // ============ 外观 ============
    fun setTheme(theme: ThemeMode) = viewModelScope.launch {
        configStore.update { it.setAppearance(it.appearance.toBuilder().setTheme(theme)) }
    }

    fun setGlassEffect(enabled: Boolean) = viewModelScope.launch {
        configStore.update { it.setAppearance(it.appearance.toBuilder().setGlassEffect(enabled)) }
    }

    fun setReduceMotion(reduce: Boolean) = viewModelScope.launch {
        configStore.update { it.setAppearance(it.appearance.toBuilder().setReduceMotion(reduce)) }
    }

    // ============ 悬浮窗 ============
    fun setFloatingEnabled(enabled: Boolean) = viewModelScope.launch {
        configStore.update { it.setFloatingWindow(it.floatingWindow.toBuilder().setEnabled(enabled)) }
    }

    fun setFloatingOpacity(opacity: Float) = viewModelScope.launch {
        configStore.update {
            it.setFloatingWindow(it.floatingWindow.toBuilder().setOpacity(opacity.coerceIn(0.2f, 1f)))
        }
    }

    // ============ 默认播放策略 ============
    fun setPolicy(policy: PlaybackPolicy) = viewModelScope.launch {
        configStore.update { it.setPlaybackPolicy(policy) }
    }

    // ============ 日志 ============
    fun setLogLevel(level: LogLevel) = viewModelScope.launch {
        configStore.update { it.setLogging(it.logging.toBuilder().setLevel(level).setEnabled(level != LogLevel.OFF)) }
        GlassLog.level = when (level) {
            LogLevel.OFF -> io.mo.glassmic.core.model.LogLevel.OFF
            LogLevel.BASIC -> io.mo.glassmic.core.model.LogLevel.BASIC
            LogLevel.VERBOSE -> io.mo.glassmic.core.model.LogLevel.VERBOSE
            LogLevel.DEBUG -> io.mo.glassmic.core.model.LogLevel.DEBUG
            else -> io.mo.glassmic.core.model.LogLevel.BASIC
        }
        GlassLog.enabled = level != LogLevel.OFF
    }

    fun clearLog() {
        GlassLog.clear()
    }

    fun exportDiagnostic() = viewModelScope.launch {
        _exporting.value = true
        _exportError.value = null
        runCatching { bundler.export() }
            .onSuccess { _exportedUri.value = bundler.shareUri(it) }
            .onFailure { _exportError.value = it.message ?: "导出失败" }
        _exporting.value = false
    }

    fun consumeExport() {
        _exportedUri.value = null
        _exportError.value = null
    }

    // ============ 实验功能 ============
    fun setExperimentalUnlocked(unlocked: Boolean) = viewModelScope.launch {
        configStore.update { it.setExperimental(it.experimental.toBuilder().setUnlocked(unlocked)) }
    }

    fun setStressTest(v: Boolean) = viewModelScope.launch {
        configStore.update { it.setExperimental(it.experimental.toBuilder().setStressTest(v)) }
    }

    fun setHighGain(v: Boolean) = viewModelScope.launch {
        configStore.update { it.setExperimental(it.experimental.toBuilder().setHighGain(v)) }
    }

    fun setNoiseSim(v: Boolean) = viewModelScope.launch {
        configStore.update { it.setExperimental(it.experimental.toBuilder().setNoiseSim(v)) }
    }

    fun setLimiter(v: Boolean) = viewModelScope.launch {
        configStore.update { it.setExperimental(it.experimental.toBuilder().setLimiterEnabled(v)) }
    }

    // ============ 管线自检 + 统计 ============
    fun runPipelineProbe() {
        if (_probing.value) return
        _probing.value = true
        _probeResult.value = null
        viewModelScope.launch {
            _probeResult.value = probe.probe()
            _probing.value = false
        }
    }

    fun consumeProbe() { _probeResult.value = null }

    fun resetInterceptStats() { audioStatsRepo.reset() }
}
