package io.mo.glassmic.ui.library

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import io.mo.glassmic.ui.common.EmptyHint
import io.mo.glassmic.ui.common.GlassSearchField
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.R
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.audio.PlaybackController
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.root.LiveCaptureController
import io.mo.glassmic.root.LivePhase
import io.mo.glassmic.root.LiveStatus
import io.mo.glassmic.root.appLabelOf
import io.mo.glassmic.ui.common.AppIcon
import io.mo.glassmic.ui.common.GlassCard
import io.mo.glassmic.ui.common.GlassSheet
import io.mo.glassmic.ui.common.PrimaryButton
import io.mo.glassmic.ui.common.StatusKind
import io.mo.glassmic.ui.common.StatusPill
import io.mo.glassmic.ui.common.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class LiveAppEntry(val packageName: String, val label: String)

data class LiveSourceUiState(
    val status: LiveStatus = LiveStatus(),
    val liveIsCurrent: Boolean = false,
    val serviceRunning: Boolean = false,
    val selectedPackage: String = "",
    val selectedLabel: String = ""
)

/** 实时应用音频（issue #42，实验）：选择音源 App、开始/停止 root 抓取。 */
@HiltViewModel
class LiveSourceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playback: PlaybackController,
    private val liveCapture: LiveCaptureController,
    runtime: RuntimeStateHolder
) : ViewModel() {
    private val _apps = MutableStateFlow<List<LiveAppEntry>>(emptyList())
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 选择面板里显示的应用：按名称或包名过滤（不区分大小写）。 */
    val apps: StateFlow<List<LiveAppEntry>> = combine(_apps, _query) { list, q ->
        val key = q.trim()
        if (key.isEmpty()) list
        else list.filter { it.label.contains(key, ignoreCase = true) || it.packageName.contains(key, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val state: StateFlow<LiveSourceUiState> = combine(
        liveCapture.status, runtime.flow, liveCapture.selectedPackage
    ) { status, rt, pkg ->
        LiveSourceUiState(
            status = status,
            liveIsCurrent = rt.currentSourceType == SourceType.LIVE,
            serviceRunning = rt.enabled && !rt.safeMode,
            selectedPackage = pkg,
            selectedLabel = if (pkg.isBlank()) "" else context.appLabelOf(pkg)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveSourceUiState())

    /** 打开选择面板：清空上次的搜索词，首次打开时加载应用列表。 */
    fun openPicker() {
        _query.value = ""
        loadApps()
    }

    fun setQuery(q: String) { _query.value = q }

    /** 可作为音源的应用：有桌面入口的应用（含系统音乐/视频 App），不含 GlassMic 自身。 */
    private fun loadApps() {
        if (_apps.value.isNotEmpty()) return
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcher, 0)
                    .map { it.activityInfo.packageName }
                    .distinct()
                    .filter { it != context.packageName }
                    .map { LiveAppEntry(it, context.appLabelOf(it)) }
                    .sortedBy { it.label.lowercase() }
            }
        }
    }

    fun select(pkg: String) = liveCapture.selectPackage(pkg)

    fun start() {
        val pkg = liveCapture.selectedPackage.value.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            playback.setLiveSource(pkg)?.let { error ->
                _notice.value = context.getString(R.string.live_start_failed, error)
            }
        }
    }

    fun stop() {
        viewModelScope.launch { playback.setRealMic() }
    }

    fun consumeNotice() { _notice.value = null }
}

/**
 * 吃掉列表滚到头之后剩下的滚动和惯性：不传给外层底部面板，
 * 免得在列表顶部往下滑时把整个面板拖走、一甩就关掉。面板仍可拖顶部把手关闭。
 */
private object KeepScrollInList : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

@Composable
fun LiveSourceCard(
    onNotice: suspend (String) -> Unit,
    vm: LiveSourceViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val notice by vm.notice.collectAsState()
    val t = glass
    var choosing by remember { mutableStateOf(false) }

    LaunchedEffect(notice) { notice?.let { onNotice(it); vm.consumeNotice() } }

    val running = state.liveIsCurrent && state.status.phase == LivePhase.ACTIVE
    val starting = state.status.phase == LivePhase.STARTING

    GlassCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        radius = 26.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.live_title),
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
            )
            when {
                running -> StatusPill(stringResource(R.string.live_status_active), StatusKind.Ok)
                starting -> StatusPill(stringResource(R.string.live_status_starting), StatusKind.Warn)
                state.status.phase == LivePhase.ERROR -> StatusPill(stringResource(R.string.live_status_error), StatusKind.Error)
            }
        }
        Text(
            stringResource(R.string.live_hint),
            fontSize = 12.sp, color = t.ink3, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(enabled = !running && !starting) { vm.openPicker(); choosing = true }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.selectedPackage.isNotBlank()) {
                AppIcon(state.selectedPackage, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    state.selectedLabel, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Text(stringResource(R.string.live_pick_app), fontSize = 15.sp, color = t.primaryInk, modifier = Modifier.weight(1f))
            }
            if (!running && !starting) {
                Text(stringResource(R.string.live_change), fontSize = 13.sp, color = t.primaryInk)
            }
        }
        if (!state.serviceRunning && !running) {
            Text(stringResource(R.string.live_need_service), fontSize = 12.sp, color = t.ink3, modifier = Modifier.padding(bottom = 6.dp))
        } else if (state.status.phase == LivePhase.ERROR && state.status.detail.isNotBlank()) {
            Text(state.status.detail, fontSize = 12.sp, color = t.err, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.padding(top = 6.dp))
        PrimaryButton(
            text = stringResource(
                when {
                    running -> R.string.live_stop
                    starting -> R.string.live_status_starting
                    else -> R.string.live_start
                }
            ),
            onClick = { if (running) vm.stop() else vm.start() },
            enabled = !starting && (running || (state.serviceRunning && state.selectedPackage.isNotBlank())),
            color = if (running) t.err else androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (choosing) {
        val apps by vm.apps.collectAsState()
        val query by vm.query.collectAsState()
        GlassSheet(
            title = stringResource(R.string.live_pick_app),
            meta = stringResource(R.string.live_pick_meta),
            onDismiss = { choosing = false }
        ) {
            GlassSearchField(
                query, vm::setQuery, stringResource(R.string.scope_search_hint),
                Modifier.padding(horizontal = 4.dp).padding(bottom = 8.dp),
                onCard = false
            )
            // 固定高度：过滤时面板不跟着跳；屏幕不够高（如弹出键盘）时由 weight 收缩
            Box(Modifier.fillMaxWidth().weight(1f, fill = false).height(520.dp)) {
                if (apps.isEmpty()) {
                    if (query.isNotBlank()) EmptyHint(stringResource(R.string.live_pick_no_match))
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().nestedScroll(KeepScrollInList),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(apps, key = { it.packageName }) { app ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { vm.select(app.packageName); choosing = false }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(app.packageName, size = 36.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    app.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (app.packageName == state.selectedPackage) t.primaryInk else t.ink,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
