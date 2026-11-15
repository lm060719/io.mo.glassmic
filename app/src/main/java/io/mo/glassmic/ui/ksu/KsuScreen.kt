package io.mo.glassmic.ui.ksu

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.mo.glassmic.R
import io.mo.glassmic.root.KsuController
import io.mo.glassmic.root.KsuState
import io.mo.glassmic.ui.common.*
import java.util.Locale

enum class KsuPage { CONTROLS, LIBRARY, TARGETS }

@Composable
fun KsuScreen(page: KsuPage = KsuPage.CONTROLS, onBack: (() -> Unit)? = null,
              onOpenTargets: () -> Unit = {}, vm: KsuViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val runtime by vm.runtimeState.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val sourceApps by vm.sourceApps.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val switching by vm.switching.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snapshot = state.snapshot
    val enabled = runtime.enabled && state.connected && !state.busy && !state.stopping && !switching
    var query by remember { mutableStateOf("") }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importAudio)
    }
    val selectedLive by vm.controller.selectedLivePackage.collectAsStateWithLifecycle()
    var livePicker by remember { mutableStateOf(false) }
    LaunchedEffect(page) { vm.loadApps() }
    if (livePicker) {
        var search by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(onDismissRequest = { livePicker = false },
            title = { Text(stringResource(R.string.ksu_live_choose)) },
            text = {
                Column {
                    GlassTextField(search, { search = it }, placeholder = stringResource(R.string.ksu_search))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(sourceApps.filter { (it.label.contains(search, true) || it.packageName.contains(search, true)) &&
                            it.packageName !in snapshot?.targets.orEmpty() }, key = { it.packageName }) { app ->
                            Column(Modifier.fillMaxWidth().clickable {
                                vm.controller.selectLivePackage(app.packageName);livePicker = false
                            }.padding(vertical = 10.dp)) { Text(app.label);Text(app.packageName, fontSize = 11.sp) }
                        }
                    }
                }
            }, confirmButton = { SoftButton(stringResource(android.R.string.cancel), { livePicker = false }) })
    }

    GlassPage {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                if (onBack != null) BackHeader(stringResource(R.string.scope_title), onBack)
                else Text(stringResource(if (page == KsuPage.LIBRARY) R.string.ksu_library else R.string.backend_ksu),
                    fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ksu_hint), color = glass.ink2, fontSize = 13.sp)
                    Text(stringResource(R.string.ksu_independent), color = glass.ink3, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    SoftButton(stringResource(if (runtime.enabled) R.string.ksu_disconnect else R.string.ksu_connect),
                        vm::toggleEnabled, enabled = !switching && !state.busy && !state.stopping)
                    if (runtime.enabled) {
                        Spacer(Modifier.height(8.dp))
                        SoftButton(stringResource(R.string.ksu_refresh), vm.controller::refreshNow,
                            enabled = !state.busy && !state.stopping)
                    }
                    KsuStatus(state)
                    error?.let { Text(it, color = glass.err, fontSize = 12.sp) }
                }
            }
            if (page == KsuPage.CONTROLS) {
                item { GlassCard { KsuPlaybackControls(state, runtime.enabled, vm.controller) } }
                item {
                    GlassCard {
                        Text(stringResource(R.string.ksu_live_title), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.ksu_live_hint), color = glass.ink3, fontSize = 12.sp)
                        Text(sourceApps.firstOrNull { it.packageName == selectedLive }?.label ?: selectedLive, color = glass.ink2)
                        SoftButton(stringResource(R.string.ksu_live_choose), { livePicker = true }, enabled = enabled)
                    }
                }
                item {
                    GlassCard {
                        SoftButton(stringResource(if (runtime.floatingWindowVisible) R.string.ksu_hide_float else R.string.ksu_show_float), {
                            if (Settings.canDrawOverlays(context)) vm.toggleFloating()
                            else context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        }, enabled = runtime.enabled)
                        Spacer(Modifier.height(10.dp))
                        SoftButton(stringResource(R.string.scope_title), onOpenTargets)
                        Text(snapshot?.targets?.joinToString(" · ").orEmpty(), color = glass.ink3, fontSize = 12.sp)
                    }
                }
            }
            if (page == KsuPage.LIBRARY) {
                item {
                    GlassCard {
                        Text(stringResource(R.string.ksu_library_hint), color = glass.ink2, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        SoftButton(stringResource(R.string.ksu_import), { importer.launch(arrayOf("audio/*")) }, enabled = enabled)
                    }
                }
                items(snapshot?.files.orEmpty(), key = { it }) { name ->
                    GlassCard(Modifier.fillMaxWidth(), onClick = if (enabled) ({ vm.controller.command("load", listOf(name)) }) else null) {
                        Text(name, color = if (name == snapshot?.selected) glass.primaryInk else glass.ink)
                        if (name == snapshot?.selected) Text(stringResource(R.string.ksu_selected), color = glass.ink3, fontSize = 12.sp)
                    }
                }
                if (snapshot?.files?.isEmpty() == true) item { Text(stringResource(R.string.ksu_empty), color = glass.ink3) }
            }
            if (page == KsuPage.TARGETS) {
                item {
                    Text(stringResource(R.string.ksu_scope_hint), color = glass.ink2, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    GlassTextField(query, { query = it }, placeholder = stringResource(R.string.ksu_search))
                }
                val targets = snapshot?.targets.orEmpty()
                val all = (apps + targets.filter { target -> apps.none { it.packageName == target } }.map { KsuApp(it, it) })
                    .filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
                    .sortedWith(compareByDescending<KsuApp> { it.packageName in targets }.thenBy { it.label.lowercase() })
                items(all, key = { it.packageName }) { app ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { vm.toggleTarget(app.packageName) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(app.packageName in targets, { vm.toggleTarget(app.packageName) }, enabled = enabled)
                        Column(Modifier.weight(1f)) {
                            Text(app.label)
                            Text(app.packageName, color = glass.ink3, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun KsuStatus(state: KsuState) {
    Spacer(Modifier.height(8.dp))
    Text(stringResource(when {
        state.stopping -> R.string.ksu_stopping
        state.busy -> R.string.ksu_working
        state.connected -> R.string.ksu_connected
        else -> R.string.ksu_disconnected
    }), color = glass.ink3, fontSize = 12.sp)
    state.error?.let {
        Text(stringResource(R.string.ksu_error, it), color = glass.err, fontSize = 12.sp)
    }
}

@Composable
fun KsuPlaybackControls(state: KsuState, running: Boolean, controller: KsuController) {
    val snapshot = state.snapshot
    val enabled = running && state.connected && !state.busy && !state.stopping
    Text(when (snapshot?.mode) {
        1 -> if (snapshot.live) stringResource(R.string.ksu_live_source, snapshot.liveSource) else snapshot.selected
        2 -> stringResource(R.string.source_silence)
        else -> stringResource(R.string.source_real_mic)
    }, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SoftButton(stringResource(if (snapshot?.playing == true) R.string.ksu_pause else R.string.ksu_play),
            { controller.command(if (snapshot?.playing == true) "pause" else "play") },
            enabled = enabled && (snapshot?.live == true || !snapshot?.selected.isNullOrBlank()))
        SoftButton(stringResource(R.string.ksu_real), { controller.command("real") }, enabled = enabled)
        SoftButton(stringResource(R.string.ksu_silence), { controller.command("silence") }, enabled = enabled)
    }
    val selectedLive by controller.selectedLivePackage.collectAsStateWithLifecycle()
    SoftButton(stringResource(if (snapshot?.live == true) R.string.ksu_live_stop else R.string.ksu_live_start),
        { if (snapshot?.live == true) controller.command("real") else controller.startLive() },
        enabled = enabled && snapshot?.liveSupported == true && (snapshot.live || selectedLive.isNotBlank()))
    if (snapshot != null && !snapshot.liveSupported) Text(stringResource(R.string.ksu_live_upgrade), color = glass.ink3, fontSize = 12.sp)
    if (snapshot != null) {
        if (!snapshot.live) KsuSlider(stringResource(R.string.ksu_progress, time(snapshot.positionMs), time(snapshot.durationMs)),
            if (snapshot.durationMs > 0) snapshot.positionMs.toFloat() / snapshot.durationMs else 0f,
            0f..1f, enabled && !snapshot.live && snapshot.durationMs > 0) {
            controller.command("seek", listOf((it * snapshot.durationMs).toLong().toString()))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ksu_mix), Modifier.weight(1f))
            GlassToggle(snapshot.mix, { controller.command("mix", listOf(if (it) "1" else "0")) },
                enabled && snapshot.mixSupported)
        }
        Text(stringResource(if (!snapshot.mixSupported || snapshot.mixNeedsRestart) R.string.ksu_mix_upgrade else R.string.ksu_mix_hint),
            color = glass.ink3, fontSize = 12.sp)
        if (snapshot.mixSupported) {
            KsuSlider(stringResource(R.string.ksu_mic_level, snapshot.micLevel), snapshot.micLevel.toFloat(),
                0f..200f, enabled && snapshot.mix) { controller.command("mic-level", listOf(it.toInt().toString())) }
        }
        KsuSlider(stringResource(R.string.ksu_gain, String.format(Locale.ROOT, "%.1f", snapshot.gainDb)),
            snapshot.gainDb, -24f..12f, enabled) { controller.command("gain", listOf(it.toString())) }
        KsuSlider(stringResource(R.string.ksu_speed, String.format(Locale.ROOT, "%.2f", snapshot.speed)),
            snapshot.speed, .5f..2f, enabled && !snapshot.live) { controller.command("speed", listOf(it.toString())) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.library_policy_loop), Modifier.weight(1f))
            GlassToggle(snapshot.loop, { controller.command("loop", listOf(if (it) "1" else "0")) }, enabled && !snapshot.live)
        }
    }
}

@Composable
private fun KsuSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
                      commit: (Float) -> Unit) {
    // Preserve a drag across incoming status snapshots; send one command on release.
    var draft by remember { mutableStateOf<Float?>(null) }
    Text(label, fontSize = 12.sp, color = glass.ink2)
    Slider(value = draft ?: value.coerceIn(range), onValueChange = { draft = it }, enabled = enabled,
        valueRange = range, onValueChangeFinished = { draft?.let(commit); draft = null })
}

private fun time(ms: Long): String = "%d:%02d".format(Locale.ROOT, ms / 60000, ms / 1000 % 60)
