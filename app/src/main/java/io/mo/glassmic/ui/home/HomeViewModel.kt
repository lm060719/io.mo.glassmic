package io.mo.glassmic.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.audio.PlaybackController
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.runtime.BootGateRepository
import io.mo.glassmic.data.runtime.AudioStatsRepository
import io.mo.glassmic.data.runtime.HookActivity
import io.mo.glassmic.data.runtime.HookStatusRepository
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.data.runtime.SafeModeRepository
import io.mo.glassmic.proto.PlaybackPolicy as ProtoPolicy
import io.mo.glassmic.proto.ScopeMode as ProtoScope
import io.mo.glassmic.service.FloatingWindowService
import io.mo.glassmic.service.GlassForegroundService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val running: Boolean = false,
    val hasFileSource: Boolean = false,
    val paused: Boolean = false,
    val sourceName: String = "",
    val groupName: String = "—",
    val policyLabel: String = "循环",
    val scopeLabel: String = "全局",
    val floatingLabel: String = "关闭",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val progress: Float = 0f,
    val hookActivity: HookActivity = HookActivity.NEVER_PINGED,
    val hookLastPkg: String? = null,
    val hookLastPingMs: Long = 0L,
    val hookApi: Int = 0,
    val interceptReads: Long = 0L,
    val interceptBytes: Long = 0L,
    val interceptLastMs: Long = 0L,
    val interceptLastPkg: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtime: RuntimeStateHolder,
    private val configStore: ConfigStore,
    private val bootGate: BootGateRepository,
    private val safeModeRepo: SafeModeRepository,
    private val playback: PlaybackController,
    audioDao: AudioDao,
    hookStatusRepo: HookStatusRepository,
    audioStatsRepo: AudioStatsRepository
) : ViewModel() {

    private val groupsFlow = audioDao.observeGroups()
    private val clipsFlow = audioDao.observeAllClips()
    private val hookFlow = hookStatusRepo.flow
    private val statsFlow = audioStatsRepo.flow

    val state: StateFlow<HomeUiState> = combine(
        combine(runtime.flow, configStore.flow) { rt, cfg -> rt to cfg },
        combine(groupsFlow, clipsFlow) { g, c -> g to c },
        combine(hookFlow, statsFlow) { h, s -> h to s }
    ) { rtCfg, groupsClips, hookStats ->
        val (rt, cfg) = rtCfg
        val (groups, clips) = groupsClips
        val (hook, stats) = hookStats
        val clip = clips.firstOrNull { it.id == rt.currentAudioId }
        val group = groups.firstOrNull { it.id == rt.currentGroupId }
        HomeUiState(
            running = rt.enabled && !rt.safeMode && bootGate.userEnabledAfterBoot(),
            hasFileSource = rt.currentSourceType == SourceType.FILE && clip != null,
            paused = rt.paused,
            sourceName = when (rt.currentSourceType) {
                SourceType.FILE -> clip?.displayName ?: rt.currentAudioId ?: "—"
                SourceType.REAL_MIC -> "真实麦克风"
                SourceType.SILENCE -> "静音"
            },
            groupName = group?.let { "${it.emoji} ${it.name}" } ?: "—",
            policyLabel = when (cfg.playbackPolicy) {
                ProtoPolicy.SILENCE -> "静音"
                ProtoPolicy.LOOP -> "循环"
                ProtoPolicy.REAL_MIC -> "切回真实麦克风"
                else -> "循环"
            },
            scopeLabel = when (cfg.scopeMode) {
                ProtoScope.GLOBAL -> "全系统"
                ProtoScope.WHITELIST -> "白名单（${cfg.whitelistCount}）"
                // BLACKLIST 已在 UI 上隐藏；旧配置统一按"全系统"展示
                else -> "全系统"
            },
            floatingLabel = if (rt.floatingWindowVisible) "开启" else "关闭",
            positionMs = rt.positionMs,
            durationMs = rt.durationMs,
            progress = if (rt.durationMs > 0) rt.positionMs.toFloat() / rt.durationMs else 0f,
            hookActivity = hook.activity,
            hookLastPkg = hook.lastPackage,
            hookLastPingMs = hook.lastPingMs,
            hookApi = hook.api,
            interceptReads = stats.totalReads,
            interceptBytes = stats.totalBytes,
            interceptLastMs = stats.lastInterceptMs,
            interceptLastPkg = stats.lastPackage
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    fun togglePause() {
        playback.togglePause()
    }

    fun seekTo(positionMs: Long) {
        viewModelScope.launch { playback.seekTo(positionMs) }
    }

    fun toggleMaster(enable: Boolean) {
        viewModelScope.launch {
            if (enable) {
                if (safeModeRepo.isActive()) return@launch
                bootGate.markEnabledForThisBoot()
                configStore.update { it.setGlobalSwitch(true) }
                GlassForegroundService.start(context)
            } else {
                configStore.update { it.setGlobalSwitch(false) }
                bootGate.clear()
                GlassForegroundService.stop(context)
                FloatingWindowService.stop(context)
            }
        }
    }

    fun toggleFloating() {
        if (runtime.value.floatingWindowVisible) {
            FloatingWindowService.stop(context)
        } else {
            FloatingWindowService.start(context)
        }
    }

    fun restoreRealMic() {
        viewModelScope.launch { playback.setRealMic() }
    }
}
