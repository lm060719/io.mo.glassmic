package io.mo.glassmic.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.mo.glassmic.R
import io.mo.glassmic.core.model.PlaybackPolicy
import io.mo.glassmic.data.db.AudioClipEntity
import io.mo.glassmic.data.db.AudioGroupEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    vm: LibraryViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var showCreateGroup by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<AudioGroupEntity?>(null) }
    var renamingGroup by remember { mutableStateOf<AudioGroupEntity?>(null) }
    var editingClip by remember { mutableStateOf<AudioClipEntity?>(null) }
    var confirmDeleteGroup by remember { mutableStateOf<AudioGroupEntity?>(null) }
    var confirmDeleteClip by remember { mutableStateOf<AudioClipEntity?>(null) }
    var policyEditingGroup by remember { mutableStateOf<AudioGroupEntity?>(null) }
    var renamingClip by remember { mutableStateOf<AudioClipEntity?>(null) }

    // 批量导入：优先调用系统自带〖文件〗(DocumentsUI)，支持多选
    val importContract = remember { OpenMultipleAudioViaFiles() }
    val importLauncher = rememberLauncherForActivityResult(
        contract = importContract
    ) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbar.showSnackbar(it)
            vm.consumeError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(snackbarData = it) } },
        floatingActionButton = {
            if (state.selectedGroupId != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        importLauncher.launch(arrayOf(
                            "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav",
                            "audio/aac", "audio/mp4", "audio/m4a", "audio/x-m4a",
                            "audio/ogg", "audio/flac", "audio/x-flac", "audio/*"
                        ))
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.library_import)) }
                )
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            GroupChipsRow(
                groups = state.groups,
                selectedId = state.selectedGroupId,
                onSelect = vm::selectGroup,
                onNewGroup = { showCreateGroup = true },
                onLongPress = { editingGroup = it }
            )
            HorizontalDivider()
            when {
                state.groups.isEmpty() -> EmptyState(stringResource(R.string.library_empty_groups))
                state.selectedGroupId == null -> EmptyState(stringResource(R.string.library_select_a_group))
                state.clips.isEmpty() -> EmptyState(stringResource(R.string.library_empty_clips))
                else -> ClipList(
                    clips = state.clips,
                    currentClipId = state.currentClipId,
                    previewClipId = state.previewClipId,
                    onPreview = vm::togglePreview,
                    onSetCurrent = vm::setAsCurrent,
                    onMore = { editingClip = it }
                )
            }
        }
    }

    if (showCreateGroup) {
        GroupEditDialog(
            initialName = "",
            initialEmoji = "🎵",
            onDismiss = { showCreateGroup = false },
            onConfirm = { name, emoji ->
                vm.createGroup(name, emoji)
                showCreateGroup = false
            }
        )
    }

    editingGroup?.let { g ->
        GroupActionsSheet(
            group = g,
            onDismiss = { editingGroup = null },
            onRename = { editingGroup = null; renamingGroup = g },
            onPolicy = { editingGroup = null; policyEditingGroup = g },
            onDelete = { editingGroup = null; confirmDeleteGroup = g }
        )
    }

    renamingGroup?.let { g ->
        GroupEditDialog(
            initialName = g.name,
            initialEmoji = g.emoji,
            onDismiss = { renamingGroup = null },
            onConfirm = { name, emoji ->
                vm.renameGroup(g, name, emoji); renamingGroup = null
            }
        )
    }

    policyEditingGroup?.let { g ->
        PolicyPicker(
            current = g.playbackPolicyOverride?.let { runCatching { PlaybackPolicy.valueOf(it) }.getOrNull() },
            onDismiss = { policyEditingGroup = null },
            onConfirm = { policy ->
                vm.setGroupPolicy(g, policy)
                policyEditingGroup = null
            }
        )
    }

    confirmDeleteGroup?.let { g ->
        AlertDialog(
            onDismissRequest = { confirmDeleteGroup = null },
            title = { Text(stringResource(R.string.library_delete)) },
            text = { Text(stringResource(R.string.library_delete_group_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteGroup(g.id); confirmDeleteGroup = null
                }) { Text(stringResource(R.string.library_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteGroup = null }) {
                    Text(stringResource(R.string.library_cancel))
                }
            }
        )
    }

    editingClip?.let { clip ->
        ClipActionsSheet(
            clip = clip,
            isPreviewing = state.previewClipId == clip.id,
            onDismiss = { editingClip = null },
            onPreview = { editingClip = null; vm.togglePreview(clip) },
            onSetCurrent = { editingClip = null; vm.setAsCurrent(clip) },
            onRename = { editingClip = null; renamingClip = clip },
            onDelete = { editingClip = null; confirmDeleteClip = clip }
        )
    }

    renamingClip?.let { clip ->
        ClipRenameDialog(
            initial = clip.displayName,
            onDismiss = { renamingClip = null },
            onConfirm = { newName ->
                vm.renameClip(clip, newName); renamingClip = null
            }
        )
    }

    confirmDeleteClip?.let { clip ->
        AlertDialog(
            onDismissRequest = { confirmDeleteClip = null },
            title = { Text(stringResource(R.string.library_delete)) },
            text = { Text(stringResource(R.string.library_delete_clip_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteClip(clip.id); confirmDeleteClip = null
                }) { Text(stringResource(R.string.library_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteClip = null }) {
                    Text(stringResource(R.string.library_cancel))
                }
            }
        )
    }
}

// ============ 顶部组 chips ============
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupChipsRow(
    groups: List<AudioGroupEntity>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onNewGroup: () -> Unit,
    onLongPress: (AudioGroupEntity) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(groups, key = { it.id }) { g ->
            ElevatedFilterChip(
                selected = g.id == selectedId,
                onClick = { onSelect(g.id) },
                label = { Text("${g.emoji} ${g.name}") },
                trailingIcon = {
                    IconButton(onClick = { onLongPress(g) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                    }
                }
            )
        }
        item {
            AssistChip(
                onClick = onNewGroup,
                label = { Text(stringResource(R.string.library_new_group)) },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                colors = AssistChipDefaults.assistChipColors()
            )
        }
    }
}

// ============ 片段列表 ============
@Composable
private fun ClipList(
    clips: List<AudioClipEntity>,
    currentClipId: String?,
    previewClipId: String?,
    onPreview: (AudioClipEntity) -> Unit,
    onSetCurrent: (AudioClipEntity) -> Unit,
    onMore: (AudioClipEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(clips, key = { it.id }) { clip ->
            ClipRow(
                clip = clip,
                isCurrent = clip.id == currentClipId,
                isPreviewing = clip.id == previewClipId,
                onPreview = { onPreview(clip) },
                onSetCurrent = { onSetCurrent(clip) },
                onMore = { onMore(clip) }
            )
        }
    }
}

@Composable
private fun ClipRow(
    clip: AudioClipEntity,
    isCurrent: Boolean,
    isPreviewing: Boolean,
    onPreview: () -> Unit,
    onSetCurrent: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPreview) {
            Icon(
                if (isPreviewing) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = stringResource(
                    if (isPreviewing) R.string.library_preview_stop else R.string.library_preview
                )
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = clip.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isCurrent) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        Icons.Default.CheckCircle, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        stringResource(R.string.library_current_marker),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                text = clipMeta(clip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        if (!isCurrent) {
            TextButton(onClick = onSetCurrent) {
                Text(stringResource(R.string.library_set_current))
            }
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Default.MoreVert, contentDescription = null)
        }
    }
}

private fun clipMeta(clip: AudioClipEntity): String {
    val sec = clip.durationMs / 1000
    val dur = "%d:%02d".format(sec / 60, sec % 60)
    val sr = if (clip.sampleRate > 0) "${clip.sampleRate} Hz" else "—"
    val ch = if (clip.channels > 0) "${clip.channels}ch" else "—"
    return "$dur · $sr · $ch · ${formatSize(clip.sizeBytes)}"
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

// ============ 弹窗 ============
@Composable
private fun GroupEditDialog(
    initialName: String,
    initialEmoji: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var emoji by remember { mutableStateOf(initialEmoji) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_new_group)) },
        text = {
            Column {
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it.take(2) },
                    label = { Text("Emoji") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.library_group_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim(), emoji.ifBlank { "🎵" }) }
            ) { Text(stringResource(R.string.library_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_cancel))
            }
        }
    )
}

@Composable
private fun ClipRenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text(stringResource(R.string.library_clip_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) }
            ) { Text(stringResource(R.string.library_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_cancel))
            }
        }
    )
}

@Composable
private fun PolicyPicker(
    current: PlaybackPolicy?,
    onDismiss: () -> Unit,
    onConfirm: (PlaybackPolicy?) -> Unit
) {
    var sel by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_change_policy)) },
        text = {
            Column {
                PolicyOption(stringResource(R.string.library_policy_follow_global), sel == null) { sel = null }
                PolicyOption(stringResource(R.string.library_policy_loop), sel == PlaybackPolicy.LOOP) { sel = PlaybackPolicy.LOOP }
                PolicyOption(stringResource(R.string.library_policy_silence), sel == PlaybackPolicy.SILENCE) { sel = PlaybackPolicy.SILENCE }
                PolicyOption(stringResource(R.string.library_policy_real_mic), sel == PlaybackPolicy.REAL_MIC) { sel = PlaybackPolicy.REAL_MIC }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(sel) }) {
                Text(stringResource(R.string.library_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_cancel))
            }
        }
    )
}

@Composable
private fun PolicyOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupActionsSheet(
    group: AudioGroupEntity,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onPolicy: () -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            SheetTitle("${group.emoji} ${group.name}")
            SheetRow(Icons.Default.Edit, stringResource(R.string.library_rename), onRename)
            SheetRow(Icons.Default.PlayArrow, stringResource(R.string.library_change_policy), onPolicy)
            SheetRow(Icons.Default.Delete, stringResource(R.string.library_delete), onDelete, danger = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipActionsSheet(
    clip: AudioClipEntity,
    isPreviewing: Boolean,
    onDismiss: () -> Unit,
    onPreview: () -> Unit,
    onSetCurrent: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            SheetTitle(clip.displayName)
            SheetRow(
                if (isPreviewing) Icons.Default.Stop else Icons.Default.PlayArrow,
                stringResource(if (isPreviewing) R.string.library_preview_stop else R.string.library_preview),
                onPreview
            )
            SheetRow(Icons.Default.CheckCircle, stringResource(R.string.library_set_current), onSetCurrent)
            SheetRow(Icons.Default.Edit, stringResource(R.string.library_rename), onRename)
            SheetRow(Icons.Default.Delete, stringResource(R.string.library_delete), onDelete, danger = true)
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)
    )
}

@Composable
private fun SheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

