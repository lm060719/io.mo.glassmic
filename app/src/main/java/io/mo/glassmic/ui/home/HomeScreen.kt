package io.mo.glassmic.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.mo.glassmic.R
import io.mo.glassmic.data.runtime.HookActivity
import io.mo.glassmic.ui.common.GlassSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScope: () -> Unit,
    vm: HomeViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            // 顶部栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置")
                }
            }

            // 主状态卡片
            StatusHeroCard(
                state = state,
                onToggle = vm::toggleMaster,
                onTogglePause = vm::togglePause,
                onSeek = vm::seekTo,
                onToggleFloating = vm::toggleFloating
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 信息行
            InfoRow(label = stringResource(R.string.home_field_source), value = state.sourceName)
            InfoRow(label = stringResource(R.string.home_field_group), value = state.groupName)
            InfoRow(label = stringResource(R.string.home_field_policy), value = state.policyLabel)
            InfoRow(
                label = stringResource(R.string.home_field_scope),
                value = state.scopeLabel,
                clickable = true,
                onClick = onOpenScope
            )
            HookStatusRow(
                label = stringResource(R.string.home_field_hook),
                activity = state.hookActivity,
                lastPkg = state.hookLastPkg,
                lastPingMs = state.hookLastPingMs,
                api = state.hookApi
            )
            InterceptStatsRow(
                label = stringResource(R.string.home_field_intercept),
                reads = state.interceptReads,
                bytes = state.interceptBytes,
                lastMs = state.interceptLastMs,
                lastPkg = state.interceptLastPkg
            )

            Spacer(modifier = Modifier.weight(1f))

            // 三个主按钮
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenLibrary,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) { Text(stringResource(R.string.home_pick_audio)) }

                OutlinedButton(
                    onClick = vm::restoreRealMic,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) { Text(stringResource(R.string.home_restore_mic)) }
            }
        }
    }
}

@Composable
private fun StatusHeroCard(
    state: HomeUiState,
    onToggle: (Boolean) -> Unit,
    onTogglePause: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleFloating: () -> Unit
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        cornerRadius = 24.dp
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = if (state.running) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.padding(4.dp))
                Text(
                    text = if (state.running)
                        stringResource(R.string.status_running)
                    else stringResource(R.string.status_off),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = state.running, onCheckedChange = onToggle)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (state.running)
                    stringResource(R.string.status_using_virtual)
                else stringResource(R.string.status_using_real),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            // 打开悬浮窗开关：仅在 GlassMic（功能总开关）开启时显示
            if (state.running) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.home_open_floating),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = state.floatingWindowVisible,
                        onCheckedChange = { onToggleFloating() }
                    )
                }
            }

            if (state.hasFileSource) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.sourceName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalIconButton(onClick = onTogglePause) {
                        Icon(
                            imageVector = if (state.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (state.paused) "继续" else "暂停"
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                // 拖动进度——只有真在播 file 时才有意义
                Slider(
                    value = state.progress.coerceIn(0f, 1f),
                    onValueChange = { v ->
                        if (state.durationMs > 0) onSeek((v * state.durationMs).toLong())
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${formatMs(state.positionMs)} / ${formatMs(state.durationMs)}" +
                        if (state.paused) "  ·  已暂停" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    clickable: Boolean = false,
    onClick: () -> Unit = {}
) {
    val mod = Modifier.fillMaxWidth().padding(vertical = 12.dp)
    Row(
        modifier = if (clickable) mod else mod,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Box(modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatMs(ms: Long): String {
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}

@Composable
private fun InterceptStatsRow(
    label: String,
    reads: Long,
    bytes: Long,
    lastMs: Long,
    lastPkg: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Box(modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            if (reads <= 0) {
                Text(
                    stringResource(R.string.intercept_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            } else {
                Text(
                    stringResource(R.string.intercept_summary, reads, formatBytes(bytes)),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (lastMs > 0) {
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastMs))
                    val detail = buildString {
                        append("最近: $time")
                        if (!lastPkg.isNullOrBlank()) append("  ·  $lastPkg")
                    }
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "${b}B"
    b < 1024 * 1024 -> "%.1fKB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1fMB".format(b / 1024.0 / 1024.0)
    else -> "%.2fGB".format(b / 1024.0 / 1024.0 / 1024.0)
}

@Composable
private fun HookStatusRow(
    label: String,
    activity: HookActivity,
    lastPkg: String?,
    lastPingMs: Long,
    api: Int
) {
    val (statusText, dotColor) = when (activity) {
        HookActivity.ACTIVE -> stringResource(R.string.hook_status_active) to Color(0xFF34C759)
        HookActivity.STALE -> stringResource(R.string.hook_status_stale) to Color(0xFFFFB020)
        HookActivity.NEVER_PINGED -> stringResource(R.string.hook_status_never) to Color(0xFFE5484D)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Box(modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Text(statusText, style = MaterialTheme.typography.bodyMedium)
            }
            if (activity != HookActivity.NEVER_PINGED) {
                val time = if (lastPingMs > 0)
                    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastPingMs))
                else "—"
                val detail = buildString {
                    append(time)
                    if (!lastPkg.isNullOrBlank()) append("  ·  $lastPkg")
                    if (api > 0) append("  ·  API $api")
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}
