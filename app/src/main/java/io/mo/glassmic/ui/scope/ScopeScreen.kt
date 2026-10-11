package io.mo.glassmic.ui.scope

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.mo.glassmic.R
import io.mo.glassmic.ui.common.AppIcon
import io.mo.glassmic.ui.common.BackHeader
import io.mo.glassmic.ui.common.CheckCircle
import io.mo.glassmic.ui.common.Dot
import io.mo.glassmic.ui.common.GlassIconButton
import io.mo.glassmic.ui.common.GlassPage
import io.mo.glassmic.ui.common.GlassToggle
import io.mo.glassmic.ui.common.MonoFamily
import io.mo.glassmic.ui.common.Segmented
import io.mo.glassmic.ui.common.SoftButton
import io.mo.glassmic.ui.common.glass
import io.mo.glassmic.ui.common.frosted

@Composable
fun ScopeScreen(
    onBack: () -> Unit,
    vm: ScopeViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val toast = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val t = glass

    // 当用户从 LSPosed 管理器切换回 GlassMic 时，自动触发静默同步
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.syncFromManager(silent = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            val msg = when (event) {
                is ScopeEvent.Prompted -> context.getString(R.string.scope_event_prompted, event.label)
                is ScopeEvent.Granted -> context.getString(R.string.scope_event_granted, event.label)
                is ScopeEvent.Denied -> context.getString(R.string.scope_event_denied, event.label)
                is ScopeEvent.Unsupported -> context.getString(R.string.scope_event_unsupported, event.label)
                is ScopeEvent.Removed -> context.getString(R.string.scope_event_removed, event.label)
                is ScopeEvent.RemoveFailed -> context.getString(R.string.scope_event_remove_failed, event.label)
                is ScopeEvent.Synced -> context.getString(R.string.scope_event_synced, event.count)
            }
            toast.showSnackbar(msg)
        }
    }

    val allApps = state.audioPolicyBackend && state.audioPolicyAllApps

    GlassPage(toast) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 40.dp)
        ) {
            item {
                BackHeader(stringResource(R.string.scope_title), onBack) {
                    Text(
                        if (allApps) stringResource(R.string.scope_all_apps_selected)
                        else stringResource(R.string.scope_selected_count, state.whitelist.size),
                        fontSize = 13.sp, color = t.ink3
                    )
                    if (!state.audioPolicyBackend) {
                        Spacer(Modifier.width(10.dp))
                        GlassIconButton(
                            Icons.Rounded.Sync,
                            stringResource(R.string.scope_sync_tooltip),
                            { vm.syncFromManager(silent = false) },
                            size = 40.dp
                        )
                    }
                }
            }

            if (state.audioPolicyBackend) {
                item {
                    Column(Modifier.padding(top = 6.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Segmented(
                            options = listOf(
                                true to stringResource(R.string.scope_audio_policy_all_apps),
                                false to stringResource(R.string.scope_mode_whitelist)
                            ),
                            selected = state.audioPolicyAllApps,
                            onSelect = vm::setAudioPolicyAllApps,
                            height = 38.dp,
                            fontSize = 14.sp
                        )
                        Text(
                            stringResource(if (allApps) R.string.scope_audio_policy_all_apps_hint else R.string.backend_scope_hint),
                            fontSize = 12.sp, lineHeight = 18.sp, color = t.ink3,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                    }
                }
            } else {
                item { LsposedBanner(isBound = state.isLsposedServiceBound) }
            }

            item { SearchField(state.query, vm::setQuery, Modifier.padding(bottom = 12.dp)) }

            appList(
                state = state,
                enabled = !allApps,
                onToggleApp = vm::toggleApp,
                onToggleSystem = vm::setShowSystemApps
            )
        }
    }
}

@Composable
private fun LsposedBanner(isBound: Boolean) {
    val t = glass
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isBound) t.okSoft else t.warnSoft)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (isBound) t.ok else t.warn)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (isBound) R.string.scope_service_bound_banner else R.string.scope_service_unbound_banner),
                fontSize = 12.sp, lineHeight = 17.sp,
                color = if (isBound) t.okInk else t.warnInk
            )
        }
        if (!isBound) {
            SoftButton(
                stringResource(R.string.scope_open_lsposed),
                onClick = {
                    if (!openLSPosedManager(ctx)) {
                        Toast.makeText(ctx, ctx.getString(R.string.scope_open_lsposed_failed), Toast.LENGTH_SHORT).show()
                    }
                },
                background = t.bgSolid.copy(alpha = 0.6f),
                textColor = t.warnInk
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val t = glass
    val shape = RoundedCornerShape(23.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .frosted()
            .background(t.card)
            .border(BorderStroke(1.dp, t.border), shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, null, tint = t.ink.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = t.ink),
            cursorBrush = SolidColor(t.primary),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text(stringResource(R.string.scope_search_hint), fontSize = 15.sp, color = t.ink3)
                    inner()
                }
            }
        )
        if (query.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close, null, tint = t.ink3,
                modifier = Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).clickable { onQuery("") }
            )
        }
    }
}

/**
 * 应用列表：每行一个 LazyColumn item，拼成一张圆角卡片（首段圆上角、末段圆下角），
 * 应用多时仍保持懒加载。末尾固定一行「显示系统应用」开关。
 */
private fun LazyListScope.appList(
    state: ScopeUiState,
    enabled: Boolean,
    onToggleApp: (String) -> Unit,
    onToggleSystem: (Boolean) -> Unit
) {
    val visible = if (state.loading) emptyList() else state.filteredApps
    item(key = "list_head") {
        CardSegment(top = true, bottom = false) {
            when {
                state.loading -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = glass.primary, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.scope_loading), fontSize = 12.sp, color = glass.ink3)
                }
                visible.isEmpty() -> Text(
                    stringResource(R.string.scope_empty), fontSize = 14.sp, color = glass.ink3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp)
                )
                else -> Spacer(Modifier.height(4.dp))
            }
        }
    }
    items(visible, key = { it.packageName }) { app ->
        CardSegment(top = false, bottom = false) {
            AppRow(
                app = app,
                checked = app.packageName in state.whitelist,
                enabled = enabled,
                onToggle = { onToggleApp(app.packageName) }
            )
        }
    }
    item(key = "list_tail") {
        CardSegment(top = false, bottom = true) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.scope_show_system_apps), fontSize = 14.sp, color = glass.ink2, modifier = Modifier.weight(1f))
                GlassToggle(state.showSystemApps, onToggleSystem)
            }
        }
    }
}

@Composable
private fun CardSegment(top: Boolean, bottom: Boolean, content: @Composable () -> Unit) {
    val r = 24.dp
    val shape = RoundedCornerShape(
        topStart = if (top) r else 0.dp, topEnd = if (top) r else 0.dp,
        bottomStart = if (bottom) r else 0.dp, bottomEnd = if (bottom) r else 0.dp
    )
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .frosted()
            .background(glass.cardFlat)
            .padding(horizontal = 14.dp)
    ) { content() }
}

@Composable
private fun AppRow(
    app: AppItem,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val t = glass
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onToggle)
                .alpha(if (enabled) 1f else 0.4f)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(packageName = app.packageName, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(app.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.packageName + if (app.isSystem) " · " + stringResource(R.string.scope_system_tag) else "",
                    fontSize = 12.sp, color = t.ink3, fontFamily = MonoFamily,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            CheckCircle(checked)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.divider))
    }
}

/** 尝试启动 LSPosed 管理器；同时兼容老的 EdXposed 入口。 */
private fun openLSPosedManager(ctx: Context): Boolean {
    val candidates = listOf(
        "org.lsposed.manager",
        "io.github.lsposed.manager",
        "de.robv.android.xposed.installer"
    )
    val pm = ctx.packageManager
    for (pkg in candidates) {
        val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(intent) }.isSuccess) return true
    }
    // 退路：尝试用通用 action 唤起
    val action = Intent("android.intent.action.MAIN")
    action.addCategory("de.robv.android.xposed.category.MODULE_SETTINGS")
    action.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { ctx.startActivity(action); true }.getOrDefault(false)
}
