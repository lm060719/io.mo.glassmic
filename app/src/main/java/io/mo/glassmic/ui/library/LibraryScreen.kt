package io.mo.glassmic.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.mo.glassmic.R
import io.mo.glassmic.core.model.PlaybackPolicy
import io.mo.glassmic.data.db.AudioClipEntity
import io.mo.glassmic.data.db.AudioGroupEntity
import io.mo.glassmic.ui.common.EmptyHint
import io.mo.glassmic.ui.common.GlassCard
import io.mo.glassmic.ui.common.GlassPage
import io.mo.glassmic.ui.common.GlassSheet
import io.mo.glassmic.ui.common.GlassTextField
import io.mo.glassmic.ui.common.MonoFamily
import io.mo.glassmic.ui.common.PrimaryButton
import io.mo.glassmic.ui.common.SheetAction
import io.mo.glassmic.ui.common.glass

private val AUDIO_MIME_TYPES = arrayOf(
    "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav",
    "audio/aac", "audio/mp4", "audio/m4a", "audio/x-m4a",
    "audio/ogg", "audio/flac", "audio/x-flac", "audio/*"
)

@Composable
fun LibraryScreen(vm: LibraryViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val totalClips by vm.totalClips.collectAsState()
    val notice by vm.notice.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val folderErrors by vm.folderErrors.collectAsState()
    val toast = remember { SnackbarHostState() }
    val context = LocalContext.current
    val t = glass

    var showCreateGroup by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<AudioGroupEntity?>(null) }
    var renamingGroup by remember { mutableStateOf<AudioGroupEntity?>(null) }
    var editingClip by remember { mutableStateOf<AudioClipEntity?>(null) }
    var confirmDeleteGroup by remember { mutableStateOf<AudioGroupEntity?>(null) }
    var confirmDeleteClip by remember { mutableStateOf<AudioClipEntity?>(null) }
    var renamingClip by remember { mutableStateOf<AudioClipEntity?>(null) }
    var showLinkFolder by rememberSaveable { mutableStateOf(false) }
    var includeSubfolders by rememberSaveable { mutableStateOf(false) }
    val folderContract = remember { OpenAudioFolderViaFiles() }
    val folderLauncher = rememberLauncherForActivityResult(folderContract) { uri ->
        if (uri != null) vm.linkFolder(uri, includeSubfolders)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshFolders()
            if (event == Lifecycle.Event.ON_STOP) vm.stopPreview()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stopPreview()
        }
    }

    // 批量导入：优先调用系统自带〖文件〗(DocumentsUI)，支持多选
    val importContract = remember { OpenMultipleAudioViaFiles() }
    val importLauncher = rememberLauncherForActivityResult(contract = importContract) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { toast.showSnackbar(it); vm.consumeError() }
    }
    LaunchedEffect(notice) {
        notice?.let { toast.showSnackbar(it); vm.consumeNotice() }
    }

    val selectedGroup = state.groups.firstOrNull { it.id == state.selectedGroupId }
    val setCurrent: (AudioClipEntity) -> Unit = { clip ->
        vm.setCurrent(
            clip,
            context.getString(R.string.library_set_current_done, clip.displayName),
            context.getString(R.string.library_set_current_failed)
        )
    }

    GlassPage(toast) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        stringResource(R.string.library_title),
                        fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.library_count, state.groups.size, totalClips),
                        fontSize = 13.sp, color = t.ink3, modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }

            item {
                LiveSourceCard(onNotice = { toast.showSnackbar(it) })
            }

            item {
                GroupChipsRow(
                    groups = state.groups,
                    selectedId = state.selectedGroupId,
                    onSelect = vm::selectGroup,
                    onLongPress = { editingGroup = it },
                    onNewGroup = { showCreateGroup = true }
                )
            }

            item {
                TextButton(
                    onClick = { includeSubfolders = false; showLinkFolder = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                ) { Text(stringResource(R.string.library_folder_add)) }
            }

            if (selectedGroup != null) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.library_policy_prefix, policyLabel(selectedGroup.playbackPolicyOverride)),
                            fontSize = 13.sp, color = t.ink3, modifier = Modifier.weight(1f)
                        )
                        Text(
                            stringResource(R.string.library_manage_group),
                            fontSize = 13.sp, color = t.primaryInk,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { editingGroup = selectedGroup }
                                .padding(vertical = 8.dp, horizontal = 2.dp)
                        )
                    }
                }
            }

            if (selectedGroup?.folderUri != null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.library_folder_hint), fontSize = 13.sp, color = t.ink3)
                        folderErrors[selectedGroup.id]?.let { Text(it, fontSize = 13.sp, color = t.err) }
                        TextButton(enabled = !refreshing, onClick = vm::refreshFolders) {
                            Text(stringResource(R.string.library_folder_refresh))
                        }
                    }
                }
            }
            if (refreshing) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.library_folder_scanning), fontSize = 12.sp, color = t.ink3)
                    }
                }
            }

            if (state.clips.isEmpty()) item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    radius = 26.dp,
                    contentPadding = PaddingValues(6.dp)
                ) {
                    when {
                        state.groups.isEmpty() -> EmptyHint(stringResource(R.string.library_empty_groups))
                        state.selectedGroupId == null -> EmptyHint(stringResource(R.string.library_select_a_group))
                        else -> EmptyHint(stringResource(if (selectedGroup?.folderUri != null) R.string.library_folder_empty else R.string.library_empty_clips))
                    }
                }
            }
            items(state.clips, key = { it.id }) { clip ->
                GlassCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    radius = 26.dp, contentPadding = PaddingValues(6.dp)
                ) {
                    ClipRow(
                        clip = clip,
                        isCurrent = clip.id == state.currentClipId,
                        isPreviewing = clip.id == state.previewClipId,
                        onSelect = { setCurrent(clip) },
                        onPreview = { vm.togglePreview(clip) },
                        onMore = { editingClip = clip }
                    )
                }
            }

            if (selectedGroup != null && selectedGroup.folderUri == null) {
                item {
                    PrimaryButton(
                        text = stringResource(R.string.library_import),
                        onClick = { importLauncher.launch(AUDIO_MIME_TYPES) },
                        leading = Icons.Rounded.Add,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    )
                }
            }
        }
    }

    if (showLinkFolder) {
        AlertDialog(
            onDismissRequest = { showLinkFolder = false },
            title = { Text(stringResource(R.string.library_folder_add)) },
            text = {
                Column {
                    Text(stringResource(R.string.library_folder_description))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeSubfolders, onCheckedChange = { includeSubfolders = it })
                        Text(stringResource(R.string.library_folder_recursive))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLinkFolder = false; folderLauncher.launch(null) }) {
                    Text(stringResource(R.string.library_folder_choose))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLinkFolder = false }) { Text(stringResource(R.string.library_cancel)) }
            }
        )
    }

    if (showCreateGroup) {
        GroupEditDialog(
            title = stringResource(R.string.library_new_group),
            initialName = "",
            initialEmoji = "🎵",
            onDismiss = { showCreateGroup = false },
            onConfirm = { name, emoji -> vm.createGroup(name, emoji); showCreateGroup = false }
        )
    }

    editingGroup?.let { g ->
        val current = g.playbackPolicyOverride?.let { runCatching { PlaybackPolicy.valueOf(it) }.getOrNull() }
        GlassSheet(
            title = "${g.emoji} ${g.name}",
            meta = stringResource(R.string.library_policy_prefix, policyLabel(g.playbackPolicyOverride)),
            onDismiss = { editingGroup = null }
        ) {
            listOf(
                null to stringResource(R.string.library_policy_follow_global),
                PlaybackPolicy.LOOP to stringResource(R.string.library_policy_loop),
                PlaybackPolicy.SILENCE to stringResource(R.string.library_policy_silence),
                PlaybackPolicy.REAL_MIC to stringResource(R.string.library_policy_real_mic)
            ).forEach { (policy, label) ->
                SheetAction(label, selected = current == policy, onClick = {
                    vm.setGroupPolicy(g, policy); editingGroup = null
                })
            }
            SheetAction(stringResource(R.string.library_rename), onClick = { editingGroup = null; renamingGroup = g })
            if (g.folderUri != null) {
                SheetAction(stringResource(R.string.library_folder_recursive), selected = g.includeSubfolders, onClick = {
                    vm.setIncludeSubfolders(g); editingGroup = null
                })
                SheetAction(stringResource(R.string.library_folder_reauthorize), onClick = {
                    includeSubfolders = g.includeSubfolders
                    editingGroup = null
                    folderLauncher.launch(Uri.parse(g.folderUri))
                })
                SheetAction(stringResource(R.string.library_folder_restore), onClick = {
                    vm.restoreRemoved(g.id); editingGroup = null
                })
            }
            SheetAction(stringResource(if (g.folderUri != null) R.string.library_folder_unlink else R.string.library_delete_group), color = t.err, onClick = { editingGroup = null; confirmDeleteGroup = g })
        }
    }

    renamingGroup?.let { g ->
        GroupEditDialog(
            title = stringResource(R.string.library_rename),
            initialName = g.name,
            initialEmoji = g.emoji,
            onDismiss = { renamingGroup = null },
            onConfirm = { name, emoji -> vm.renameGroup(g, name, emoji); renamingGroup = null }
        )
    }

    confirmDeleteGroup?.let { g ->
        ConfirmDialog(
            text = stringResource(if (g.folderUri != null) R.string.library_folder_unlink_confirm else R.string.library_delete_group_confirm),
            title = stringResource(if (g.folderUri != null) R.string.library_folder_unlink else R.string.library_delete),
            onDismiss = { confirmDeleteGroup = null },
            onConfirm = { vm.deleteGroup(g.id); confirmDeleteGroup = null }
        )
    }

    editingClip?.let { clip ->
        val previewing = state.previewClipId == clip.id
        GlassSheet(title = clip.displayName, meta = clipMeta(clip), onDismiss = { editingClip = null }) {
            SheetAction(
                stringResource(if (previewing) R.string.library_preview_stop else R.string.library_preview),
                onClick = { editingClip = null; vm.togglePreview(clip) }
            )
            if (clip.id != state.currentClipId) {
                SheetAction(stringResource(R.string.library_set_current), onClick = { editingClip = null; setCurrent(clip) })
            }
            SheetAction(stringResource(R.string.library_rename), onClick = { editingClip = null; renamingClip = clip })
            SheetAction(stringResource(if (clip.sourceUri != null) R.string.library_folder_remove else R.string.library_delete), color = t.err, onClick = { editingClip = null; confirmDeleteClip = clip })
        }
    }

    renamingClip?.let { clip ->
        ClipRenameDialog(
            initial = clip.displayName,
            onDismiss = { renamingClip = null },
            onConfirm = { newName -> vm.renameClip(clip, newName); renamingClip = null }
        )
    }

    confirmDeleteClip?.let { clip ->
        ConfirmDialog(
            text = stringResource(if (clip.sourceUri != null) R.string.library_folder_remove_confirm else R.string.library_delete_clip_confirm),
            title = stringResource(if (clip.sourceUri != null) R.string.library_folder_remove else R.string.library_delete),
            onDismiss = { confirmDeleteClip = null },
            onConfirm = { vm.deleteClip(clip.id); confirmDeleteClip = null }
        )
    }
}

@Composable
private fun policyLabel(override: String?): String = stringResource(
    when (override?.let { runCatching { PlaybackPolicy.valueOf(it) }.getOrNull() }) {
        PlaybackPolicy.LOOP -> R.string.library_policy_loop
        PlaybackPolicy.SILENCE -> R.string.library_policy_silence
        PlaybackPolicy.REAL_MIC -> R.string.library_policy_real_mic
        else -> R.string.library_policy_follow_global
    }
)

// ============ 顶部组 chips ============
@Composable
private fun GroupChipsRow(
    groups: List<AudioGroupEntity>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onLongPress: (AudioGroupEntity) -> Unit,
    onNewGroup: () -> Unit
) {
    val t = glass
    LazyRow(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(groups, key = { it.id }) { g ->
            val on = g.id == selectedId
            val shape = RoundedCornerShape(20.dp)
            Row(
                Modifier
                    .height(40.dp)
                    .clip(shape)
                    .background(if (on) t.primary else t.cardFlat)
                    .border(BorderStroke(1.dp, if (on) t.primary else t.border), shape)
                    .clickable { if (on) onLongPress(g) else onSelect(g.id) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${g.emoji} ${g.name}",
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = if (on) Color.White else t.ink
                )
            }
        }
        item {
            val dash = t.dash
            Row(
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .drawBehind {
                        drawRoundRect(
                            dash,
                            cornerRadius = CornerRadius(20.dp.toPx()),
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                        )
                    }
                    .clickable(onClick = onNewGroup)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Add, null, tint = t.ink2, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.library_new_group), fontSize = 14.sp, color = t.ink2)
            }
        }
    }
}

// ============ 片段行 ============
@Composable
private fun ClipRow(
    clip: AudioClipEntity,
    isCurrent: Boolean,
    isPreviewing: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onMore: () -> Unit
) {
    val t = glass
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (isCurrent) t.primarySoft else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (isPreviewing) t.primary else t.fill)
                .clickable(onClick = onPreview),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isPreviewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (isPreviewing) R.string.library_preview_stop else R.string.library_preview),
                tint = if (isPreviewing) Color.White else t.ink,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    clip.displayName,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.library_current_marker),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        modifier = Modifier
                            .background(t.primary, RoundedCornerShape(8.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
            Text(clipMeta(clip), fontSize = 12.sp, color = t.ink3, fontFamily = MonoFamily, maxLines = 1)
        }
        Box(
            Modifier.size(40.dp, 44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onMore),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.MoreVert, null, tint = t.ink3, modifier = Modifier.size(20.dp))
        }
    }
}

private fun clipMeta(clip: AudioClipEntity): String {
    val sec = clip.durationMs / 1000
    val dur = "%02d:%02d".format(sec / 60, sec % 60)
    val sr = if (clip.sampleRate > 0) "${clip.sampleRate} Hz" else "—"
    val ch = if (clip.channels > 0) "${clip.channels}ch" else "—"
    return "$dur · $sr · $ch · ${formatSize(clip.sizeBytes)}"
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

// ============ 弹窗 ============
@Composable
private fun GroupEditDialog(
    title: String,
    initialName: String,
    initialEmoji: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var emoji by remember { mutableStateOf(initialEmoji) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassTextField(
                    value = emoji,
                    onValueChange = { emoji = it.take(2) },
                    modifier = Modifier.width(64.dp)
                )
                Spacer(Modifier.width(10.dp))
                GlassTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    placeholder = stringResource(R.string.library_group_name_hint),
                    modifier = Modifier.weight(1f)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim(), emoji.ifBlank { "🎵" }) }) {
                Text(stringResource(R.string.library_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        }
    )
}

@Composable
private fun ClipRenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.library_rename), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            GlassTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                placeholder = stringResource(R.string.library_clip_name_hint)
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) {
                Text(stringResource(R.string.library_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        }
    )
}

@Composable
private fun ConfirmDialog(text: String, title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = { Text(text, fontSize = 14.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.library_confirm), color = glass.err) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        }
    )
}
