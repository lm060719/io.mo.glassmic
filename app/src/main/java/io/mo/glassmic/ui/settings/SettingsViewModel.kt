package io.mo.glassmic.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.service.WaveformOverlayService
import io.mo.glassmic.data.audio.FloatingIconStore
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.runtime.AudioStatsRepository
import io.mo.glassmic.data.runtime.HookStatus
import io.mo.glassmic.data.runtime.HookStatusRepository
import io.mo.glassmic.data.runtime.VisibilityCompatRepository
import kotlinx.coroutines.flow.asStateFlow
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.data.config.AppLocale
import io.mo.glassmic.proto.AppConfig
import io.mo.glassmic.proto.InjectionBackend
import io.mo.glassmic.root.AudioPolicyController
import io.mo.glassmic.root.PolicyPhase
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.audio.BandSettings
import io.mo.glassmic.audio.OutputGain
import io.mo.glassmic.proto.AppLanguage
import io.mo.glassmic.proto.FloatingSize
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
    val interceptLastCh: Int = 0
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: ConfigStore,
    private val floatingIconStore: FloatingIconStore,
    private val audioStatsRepo: AudioStatsRepository,
    private val visibilityCompatRepo: VisibilityCompatRepository,
    private val audioPolicy: AudioPolicyController,
    private val runtime: RuntimeStateHolder,
    private val bundler: io.mo.glassmic.data.diag.DiagnosticBundler,
    private val backgrounds: io.mo.glassmic.data.appearance.BackgroundStore,
    hookStatusRepo: HookStatusRepository
) : ViewModel() {

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    /** 打包诊断数据，成功后把可分享的 Uri 交给调用方弹出分享面板。 */
    fun exportDiagnostic(onReady: (Uri) -> Unit, onError: (Throwable) -> Unit) {
        if (_exporting.value) return
        _exporting.value = true
        viewModelScope.launch {
            runCatching { bundler.export() }
                .onSuccess { onReady(bundler.shareUri(it)) }
                .onFailure(onError)
            _exporting.value = false
        }
    }

    val policyStatus = audioPolicy.status
    val runtimeState = runtime.flow

    fun setBackend(backend: InjectionBackend) = viewModelScope.launch {
        if (runtime.value.enabled ||
            policyStatus.value.phase in listOf(PolicyPhase.STARTING, PolicyPhase.ACTIVE, PolicyPhase.STOPPING)) return@launch
        // A killed process can leave global_switch persisted while the actual service is off.
        // Also cancel a queued service start before replacing the backend.
        io.mo.glassmic.service.GlassForegroundService.stop(context)
        configStore.update { it.setGlobalSwitch(false).setInjectionBackend(backend) }
    }

    fun retryAudioPolicy() = audioPolicy.retry()

    private val _visibilityCompat = MutableStateFlow(visibilityCompatRepo.isEnabled())
    /** 「严格 ROM 兼容」开关状态，UI 单独 collect。 */
    val visibilityCompat: StateFlow<Boolean> = _visibilityCompat.asStateFlow()

    /** 每次进入设置页时按系统属性真实值刷新开关（属性非 1 一律显示关闭）。 */
    fun refreshVisibilityCompat() {
        _visibilityCompat.value = visibilityCompatRepo.isEnabled()
    }

    private val _iconError = MutableStateFlow<String?>(null)
    /** 悬浮球图标导入错误，UI 单独 collect 弹提示。 */
    val iconError: StateFlow<String?> = _iconError.asStateFlow()

    fun setVisibilityCompat(enabled: Boolean) {
        _visibilityCompat.value = enabled  // 立即回显
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = visibilityCompatRepo.setEnabled(enabled)
            // 开启时若 Root 写入失败（多半是未授权 su），回滚开关让用户察觉。
            if (enabled && !ok) _visibilityCompat.value = false
        }
    }

    val state: StateFlow<SettingsUiState> = combine(
        configStore.flow, hookStatusRepo.flow, audioStatsRepo.flow
    ) { cfg, hook, stats ->
        SettingsUiState(
            config = cfg,
            hook = hook,
            interceptReads = stats.totalReads,
            interceptBytes = stats.totalBytes,
            interceptLastMs = stats.lastInterceptMs,
            interceptLastPkg = stats.lastPackage,
            interceptLastSr = stats.lastSampleRate,
            interceptLastCh = stats.lastChannels
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    // ============ 外观 ============
    fun setTheme(theme: ThemeMode) = viewModelScope.launch {
        configStore.update { it.setAppearance(it.appearance.toBuilder().setTheme(theme)) }
    }

    fun setGlassEffect(enabled: Boolean) = viewModelScope.launch {
        configStore.update { it.setAppearance(it.appearance.toBuilder().setGlassEffect(enabled)) }
    }

    // ============ 页面背景 ============
    val wallpaperStatus = backgrounds.wallpaperStatus

    private val _backgroundError = MutableStateFlow<String?>(null)
    val backgroundError: StateFlow<String?> = _backgroundError.asStateFlow()
    fun consumeBackgroundError() { _backgroundError.value = null }

    fun setBackgroundMode(mode: io.mo.glassmic.proto.BackgroundMode) = viewModelScope.launch {
        configStore.update { it.setAppearance(it.appearance.toBuilder().setBackgroundMode(mode)) }
        if (mode == io.mo.glassmic.proto.BackgroundMode.BACKGROUND_WALLPAPER) backgrounds.refreshWallpaper(force = true)
    }

    /** 导入自选图片并切到「自选图片」背景。 */
    fun setBackgroundImage(uri: Uri) = viewModelScope.launch {
        val path = backgrounds.importImage(uri)
        if (path == null) {
            _backgroundError.value = AppLocale.string(context, io.mo.glassmic.R.string.settings_background_import_failed)
            return@launch
        }
        configStore.update {
            it.setAppearance(
                it.appearance.toBuilder()
                    .setBackgroundMode(io.mo.glassmic.proto.BackgroundMode.BACKGROUND_IMAGE)
                    .setBackgroundImagePath(path)
            )
        }
    }

    fun refreshWallpaper() = viewModelScope.launch { backgrounds.refreshWallpaper(force = true) }

    fun setBackgroundBlur(v: Float) = viewModelScope.launch {
        configStore.update { it.setAppearance(it.appearance.toBuilder().setBackgroundBlur(v)) }
    }

    fun setBackgroundDim(v: Float) = viewModelScope.launch {
        configStore.update { it.setAppearance(it.appearance.toBuilder().setBackgroundDim(v)) }
    }

    fun setReduceMotion(reduce: Boolean) = viewModelScope.launch {
        configStore.update { it.setAppearance(it.appearance.toBuilder().setReduceMotion(reduce)) }
    }

    fun setLanguage(language: AppLanguage) {
        // 同步执行：调用方（设置页）选完语言后会立即 recreate() 让改动马上生效，
        // 必须保证 SharedPreferences 缓存 + AppCompatDelegate 的语言在 recreate() 之前就已写入，
        // 否则会因为协程还没跑到这里而导致第一次点击"没反应"，要点第二次才生效。
        AppLocale.apply(context, language)
        viewModelScope.launch {
            configStore.update {
                it.setAppearance(it.appearance.toBuilder().setLanguage(language).setLanguageResolved(true))
            }
        }
    }

    // ============ 悬浮窗 ============
    fun setFloatingEnabled(enabled: Boolean) = viewModelScope.launch {
        configStore.update { it.setFloatingWindow(it.floatingWindow.toBuilder().setEnabled(enabled)) }
    }

    /** 展开面板恢复默认大小。 */
    fun resetFloatingPanelSize() = viewModelScope.launch {
        configStore.update {
            it.setFloatingWindow(it.floatingWindow.toBuilder().setPanelWidthDp(0).setPanelHeightDp(0).build())
        }
    }

    fun setFloatingOpacity(opacity: Float) = viewModelScope.launch {
        configStore.update {
            it.setFloatingWindow(it.floatingWindow.toBuilder().setOpacity(opacity.coerceIn(0.2f, 1f)))
        }
    }

    fun setFloatingSize(size: FloatingSize) = viewModelScope.launch {
        configStore.update { it.setFloatingWindow(it.floatingWindow.toBuilder().setSize(size)) }
    }

    /** 导入自定义悬浮球图标：复制到私有目录并持久化相对路径。传 null 表示清除，回退默认图标。 */
    fun setFloatingIcon(uri: Uri?) = viewModelScope.launch {
        val relPath = if (uri == null) "" else floatingIconStore.importIcon(uri) ?: run {
            _iconError.value = "图标导入失败"
            return@launch
        }
        configStore.update { it.setFloatingWindow(it.floatingWindow.toBuilder().setCustomIconPath(relPath)) }
    }

    fun consumeIconError() { _iconError.value = null }

    // ============ 实时波形悬浮窗 ============
    fun setWaveformEnabled(enabled: Boolean) = viewModelScope.launch {
        configStore.update {
            it.setFloatingWindow(it.floatingWindow.toBuilder().setWaveformEnabled(enabled))
        }
        if (enabled) WaveformOverlayService.start(context) else WaveformOverlayService.stop(context)
    }

    fun setWaveformOpacity(opacity: Float) = viewModelScope.launch {
        configStore.update {
            it.setFloatingWindow(it.floatingWindow.toBuilder().setWaveformOpacity(opacity.coerceIn(0.15f, 1f)))
        }
    }

    // ============ 快捷操作 ============
    /**
     * 音量键双击快捷操作。开关只写配置——实际布防由悬浮窗服务在运行期间同步给 Xposed 侧，
     * 悬浮窗没开时这里打开也不会拦任何按键。
     */
    fun setVolumeKeysEnabled(enabled: Boolean) = viewModelScope.launch {
        configStore.update { it.setShortcuts(it.shortcuts.toBuilder().setVolumeKeysEnabled(enabled)) }
    }

    // ============ 实时耳返 / 音频监听 ============
    fun setAudioMonitorEnabled(enabled: Boolean) = viewModelScope.launch {
        configStore.update {
            val cur = it.audioMonitor
            it.setAudioMonitor(cur.toBuilder().setEnabled(enabled))
        }
    }

    fun setAudioMonitorVolume(volume: Float) = viewModelScope.launch {
        configStore.update {
            val cur = it.audioMonitor
            it.setAudioMonitor(cur.toBuilder().setVolume(volume.coerceIn(0f, 1f)))
        }
    }

    fun setOutputGain(gain: Float) = viewModelScope.launch {
        configStore.update { it.setOutputGain(OutputGain.normalize(gain)) }
    }

    // ============ 默认播放策略 ============
    fun setPolicy(policy: PlaybackPolicy) = viewModelScope.launch {
        configStore.update { it.setPlaybackPolicy(policy) }
    }

    // ============ 实验功能 ============
    fun setAudioBandEnabled(enabled: Boolean) = viewModelScope.launch {
        configStore.update { it.setAudioBand(it.audioBand.toBuilder().setEnabled(enabled)) }
    }

    fun setAudioBand(lowHz: Int, highHz: Int) = viewModelScope.launch {
        if (lowHz !in BandSettings.MIN_HZ until highHz || highHz > BandSettings.MAX_HZ) return@launch
        configStore.update {
            it.setAudioBand(it.audioBand.toBuilder().setEnabled(true).setLowHz(lowHz).setHighHz(highHz))
        }
    }

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

    fun setReverbEnabled(v: Boolean) = viewModelScope.launch {
        configStore.update { it.setExperimental(it.experimental.toBuilder().setReverbEnabled(v)) }
    }

    fun setReverbAmount(v: Float) = viewModelScope.launch {
        configStore.update {
            it.setExperimental(it.experimental.toBuilder().setReverbAmount(v.coerceIn(0f, 1f)))
        }
    }

    fun setSpeedEnabled(v: Boolean) = viewModelScope.launch {
        configStore.update { it.setExperimental(it.experimental.toBuilder().setSpeedEnabled(v)) }
    }

    fun setSpeedFactor(v: Float) = viewModelScope.launch {
        configStore.update {
            it.setExperimental(it.experimental.toBuilder().setSpeedFactor(v.coerceIn(0.5f, 2.0f)))
        }
    }
}
