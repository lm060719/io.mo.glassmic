package io.mo.glassmic.ui.library

import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val playback: PlaybackController,
    private val resolver: AudioFileResolver,
    configStore: ConfigStore,
    audioDao: AudioDao
) : ViewModel() {

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    private val _previewClipId = MutableStateFlow<String?>(null)
    private val _error = MutableStateFlow<String?>(null)
    private var previewPlayer: MediaPlayer? = null

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
            clips = clips,
            currentClipId = current,
            previewClipId = preview,
            errorMessage = err
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun selectGroup(id: String) {
        if (_selectedGroupId.value != id) stopPreview()
        _selectedGroupId.value = id
    }

    fun createGroup(name: String, emoji: String) {
        viewModelScope.launch {
            val g = importer.createGroup(name, emoji, null)
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
            importer.deleteGroup(groupId)
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
        }
    }

    fun setAsCurrent(clip: AudioClipEntity) {
        viewModelScope.launch {
            stopPreview()
            val ok = playback.setCurrentClip(clip.id)
            if (!ok) _error.value = "无法设为当前音源"
        }
    }

    fun togglePreview(clip: AudioClipEntity) {
        if (_previewClipId.value == clip.id) {
            stopPreview()
            return
        }
        stopPreview()
        val file = resolver.fileFor(clip.relativePath)
        if (!file.exists()) {
            _error.value = "文件丢失"
            return
        }
        runCatching {
            previewPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stopPreview() }
                setOnErrorListener { _, _, _ -> stopPreview(); true }
                prepare()
                start()
            }
            _previewClipId.value = clip.id
        }.onFailure {
            _error.value = "试听失败: ${it.message}"
            stopPreview()
        }
    }

    fun stopPreview() {
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
