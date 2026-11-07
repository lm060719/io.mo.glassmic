package io.mo.glassmic.ui.library

import android.media.MediaPlayer
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.R
import io.mo.glassmic.data.audio.AudioFolderRepository
import io.mo.glassmic.core.model.PlaybackPolicy
import io.mo.glassmic.data.audio.AudioFileResolver
import io.mo.glassmic.data.audio.AudioImportRepository
import io.mo.glassmic.data.audio.PlaybackController
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.db.AudioClipEntity
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.db.AudioGroupEntity
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class LibraryUiState(
    val groups: List<AudioGroupEntity> = emptyList(),
    val selectedGroupId: String? = null,
    val clips: List<AudioClipEntity> = emptyList(),
    val currentClipId: String? = null,
    val previewClipId: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val importer: AudioImportRepository,
    private val resolver: AudioFileResolver,
    private val playback: PlaybackController,
    private val folders: AudioFolderRepository,
    @ApplicationContext private val context: Context,
    configStore: ConfigStore,
    private val audioDao: AudioDao
) : ViewModel() {

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    private val _previewClipId = MutableStateFlow<String?>(null)
    private val _error = MutableStateFlow<String?>(null)
    private var previewPlayer: MediaPlayer? = null
    private var previewJob: Job? = null
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _folderErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val folderErrors = _folderErrors.asStateFlow()
    private var refreshPending = false

    fun refreshFolders() {
        if (_refreshing.value) {
            refreshPending = true
            return
        }
        _refreshing.value = true
        viewModelScope.launch {
            try {
                do {
                    refreshPending = false
                    _folderErrors.value = folders.refreshAll()
                    reconcileRemovedClips()
                } while (refreshPending)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _error.value = context.getString(R.string.library_folder_unavailable)
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun linkFolder(uri: Uri, includeSubfolders: Boolean) {
        viewModelScope.launch {
            try {
                val group = folders.link(uri, includeSubfolders)
                groupsFlow.first { groups -> groups.any { it.id == group.id } }
                selectGroup(group.id)
                refreshFolders()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _error.value = context.getString(R.string.library_folder_unavailable)
            }
        }
    }

    fun setIncludeSubfolders(group: AudioGroupEntity) {
        viewModelScope.launch {
            folders.setIncludeSubfolders(group.id, !group.includeSubfolders)
            refreshFolders()
        }
    }

    fun restoreRemoved(groupId: String) {
        viewModelScope.launch {
            folders.restoreRemoved(groupId)
            refreshFolders()
        }
    }

    private suspend fun reconcileRemovedClips() {
        val preview = _previewClipId.value?.let { audioDao.findClip(it) }
        if (preview == null || preview.hidden) stopPreview()
        playback.clearRemovedClip()
    }

    private val groupsFlow = audioDao.observeGroups()
    private val currentClipIdFlow = configStore.flow
        .map { it.currentAudioId.takeIf { id -> id.isNotBlank() } }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val clipsFlow = _selectedGroupId.flatMapLatest { gid ->
        if (gid == null) flowOf(emptyList()) else audioDao.observeClipsInGroup(gid)
    }

    val state: StateFlow<LibraryUiState> = combine(
        combine(groupsFlow, _selectedGroupId, clipsFlow) { g, s, c -> Triple(g, s, c) },
        combine(currentClipIdFlow, _previewClipId, _error) { cur, pv, err -> Triple(cur, pv, err) }
    ) { left, right ->
        val (groups, selected, clips) = left
        val (current, preview, err) = right
        val effectiveSelected = selected?.takeIf { id -> groups.any { it.id == id } }
            ?: groups.firstOrNull()?.id
        if (effectiveSelected != selected) _selectedGroupId.value = effectiveSelected
        LibraryUiState(
            groups = groups,
            selectedGroupId = effectiveSelected,
            clips = clips.filter { it.groupId == effectiveSelected },
            currentClipId = current,
            previewClipId = preview,
            errorMessage = err
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    /** 全部片段数，标题右侧的统计用。 */
    val totalClips: StateFlow<Int> = audioDao.observeAllClips().map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 一次性提示（设为当前音源成功等），与错误分开。 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun consumeNotice() { _notice.value = null }

    /** 设为当前虚拟麦克风音源。 */
    fun setCurrent(clip: AudioClipEntity, okMessage: String, failMessage: String) {
        viewModelScope.launch {
            _notice.value = if (playback.setCurrentClip(clip.id)) okMessage else failMessage
        }
    }

    fun selectGroup(id: String) {
        if (_selectedGroupId.value != id) stopPreview()
        _selectedGroupId.value = id
    }

    fun createGroup(name: String, emoji: String) {
        viewModelScope.launch {
            val g = importer.createGroup(name, emoji, null)
            groupsFlow.first { groups -> groups.any { it.id == g.id } }
            _selectedGroupId.value = g.id
        }
    }

    fun renameGroup(group: AudioGroupEntity, newName: String, newEmoji: String) {
        viewModelScope.launch {
            importer.updateGroup(group.copy(name = newName.ifBlank { group.name }, emoji = newEmoji.ifBlank { group.emoji }))
        }
    }

    fun setGroupPolicy(group: AudioGroupEntity, policy: PlaybackPolicy?) {
        viewModelScope.launch {
            importer.updateGroup(group.copy(playbackPolicyOverride = policy?.name))
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            stopPreview()
            if (audioDao.findGroup(groupId)?.folderUri != null) folders.unlink(groupId)
            else importer.deleteGroup(groupId)
            reconcileRemovedClips()
            if (_selectedGroupId.value == groupId) _selectedGroupId.value = null
        }
    }

    fun importUri(uri: Uri) {
        val groupId = _selectedGroupId.value ?: return
        viewModelScope.launch {
            runCatching { importer.importFromUri(uri, groupId) }
                .onFailure {
                    GlassLog.b("Library") { "导入失败: ${it.message}" }
                    _error.value = it.message ?: "未知错误"
                }
        }
    }

    fun importUris(uris: List<Uri>) {
        val groupId = _selectedGroupId.value ?: return
        viewModelScope.launch {
            uris.forEach { uri ->
                runCatching { importer.importFromUri(uri, groupId) }
                    .onFailure {
                        GlassLog.b("Library") { "导入失败 $uri: ${it.message}" }
                        _error.value = it.message ?: "未知错误"
                    }
            }
        }
    }

    fun renameClip(clip: AudioClipEntity, newName: String) {
        viewModelScope.launch { importer.renameClip(clip.id, newName) }
    }

    fun deleteClip(clipId: String) {
        viewModelScope.launch {
            if (_previewClipId.value == clipId) stopPreview()
            importer.deleteClip(clipId)
            reconcileRemovedClips()
        }
    }

    fun togglePreview(clip: AudioClipEntity) {
        if (_previewClipId.value == clip.id) {
            stopPreview()
            return
        }
        stopPreview()
        _previewClipId.value = clip.id
        previewJob = viewModelScope.launch {
            val player = MediaPlayer()
            var handedOff = false
            try {
                withContext(Dispatchers.IO) { resolver.configurePreview(player, clip) }
                handedOff = true
                previewPlayer = player
                player.setOnPreparedListener { if (previewPlayer === it) it.start() }
                player.setOnCompletionListener { if (previewPlayer === it) stopPreview() }
                player.setOnErrorListener { failed, _, _ ->
                    if (previewPlayer === failed) {
                        _error.value = context.getString(R.string.library_set_current_failed)
                        stopPreview()
                    }
                    true
                }
                player.prepareAsync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _error.value = context.getString(R.string.library_set_current_failed)
                stopPreview()
            } finally {
                if (!handedOff) player.release()
            }
        }
    }

    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewPlayer?.runCatching { if (isPlaying) stop() }
        previewPlayer?.runCatching { release() }
        previewPlayer = null
        _previewClipId.update { null }
    }

    fun consumeError() { _error.value = null }

    override fun onCleared() {
        stopPreview()
        super.onCleared()
    }
}
