package io.mo.glassmic.data.audio

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.R
import io.mo.glassmic.data.db.AudioClipEntity
import io.mo.glassmic.data.db.AudioGroupEntity
import io.mo.glassmic.data.db.GlassDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Read-only SAF library. No copies, file writes, or document deletion. */
@Singleton
class AudioFolderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: GlassDatabase
) {
    private val dao get() = database.audioDao()
    private val resolver get() = context.contentResolver
    private val mutex = Mutex()

    suspend fun link(uri: Uri, includeSubfolders: Boolean): AudioGroupEntity = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(DocumentsContract.isTreeUri(uri))
            val root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            val name = resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    check(cursor.moveToFirst()) { context.getString(R.string.library_folder_unavailable) }
                    cursor.getString(0)
                } ?: error(context.getString(R.string.library_folder_unavailable))
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val existing = dao.linkedGroups().firstOrNull { it.folderUri == uri.toString() }
            if (existing != null) {
                dao.setIncludeSubfolders(existing.id, includeSubfolders)
                existing.copy(includeSubfolders = includeSubfolders)
            } else {
                val group = AudioGroupEntity(
                    id = UUID.randomUUID().toString(), name = name, emoji = "📁",
                    sortOrder = dao.maxGroupOrder() + 1, playbackPolicyOverride = null,
                    folderUri = uri.toString(), includeSubfolders = includeSubfolders
                )
                try {
                    dao.upsertGroup(group)
                } catch (failure: Exception) {
                    runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    throw failure
                }
                group
            }
        }
    }

    suspend fun unlink(groupId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val group = dao.findGroup(groupId) ?: return@withLock
            val uri = group.folderUri ?: return@withLock
            dao.deleteGroup(groupId)
            if (dao.linkedGroups().none { it.folderUri == uri }) {
                runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
    }

    suspend fun setIncludeSubfolders(groupId: String, include: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock { dao.setIncludeSubfolders(groupId, include) }
    }

    suspend fun restoreRemoved(groupId: String) = dao.restoreLinkedClips(groupId)

    /** One failed folder never clears its index or prevents other folders from refreshing. */
    suspend fun refreshAll(): Map<String, String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val errors = mutableMapOf<String, String>()
            for (group in dao.linkedGroups()) {
                currentCoroutineContext().ensureActive()
                try {
                    val skipped = scan(group)
                    if (skipped > 0) errors[group.id] = context.getString(R.string.library_folder_skipped, skipped)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    errors[group.id] = if (failure is FolderLimitException) context.getString(R.string.library_folder_too_large)
                        else context.getString(R.string.library_folder_unavailable)
                }
            }
            errors
        }
    }

    private data class Document(val uri: String, val name: String, val mime: String, val size: Long, val modified: Long)

    private suspend fun enumerate(group: AudioGroupEntity): List<Document> {
        val tree = Uri.parse(requireNotNull(group.folderUri))
        val directories = ArrayDeque<String>()
        directories.add(DocumentsContract.getTreeDocumentId(tree))
        val visited = mutableSetOf<String>()
        val documents = linkedMapOf<String, Document>()
        var count = 0
        while (directories.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val parent = directories.removeFirst()
            if (!visited.add(parent)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            // DocumentFile.listFiles() hides provider failures as empty lists. Query directly so
            // permission loss, partial/loading results and offline providers cannot erase the index.
            val cursor = resolver.query(children, PROJECTION, null, null, null)
                ?: error("No directory listing")
            cursor.use {
                check(!it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) { "Directory still loading" }
                check(it.extras.getString(DocumentsContract.EXTRA_ERROR) == null) { "Directory listing failed" }
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    if (++count > MAX_DOCUMENTS) throw FolderLimitException()
                    val id = it.getString(0)
                    val name = it.getString(1) ?: id
                    val mime = it.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (group.includeSubfolders) directories.add(id)
                    } else if (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                        documents[uri] = Document(uri, name, mime, it.getLong(3), it.getLong(4))
                    }
                }
            }
        }
        return documents.values.sortedWith(compareBy<Document> { it.name.lowercase() }.thenBy { it.uri })
    }

    private suspend fun scan(group: AudioGroupEntity): Int {
        val documents = enumerate(group)
        val old = dao.clipsInGroup(group.id).associateBy { it.sourceUri }
        val updates = mutableListOf<AudioClipEntity>()
        var skipped = 0
        var order = (old.values.maxOfOrNull { it.sortOrder } ?: 0) + 1
        for (doc in documents) {
            currentCoroutineContext().ensureActive()
            val previous = old[doc.uri]
            if (previous?.hidden == true) continue
            if (previous != null && doc.modified > 0 && previous.sourceModifiedAt == doc.modified &&
                previous.sizeBytes == doc.size && previous.fileName == doc.name) continue
            try {
                val (duration, sampleRate, channels) = probe(Uri.parse(doc.uri))
                updates += AudioClipEntity(
                    id = previous?.id ?: UUID.randomUUID().toString(), groupId = group.id,
                    displayName = previous?.displayName ?: doc.name.substringBeforeLast('.', doc.name),
                    fileName = doc.name, relativePath = "", mimeType = doc.mime,
                    durationMs = duration, sizeBytes = doc.size, sampleRate = sampleRate, channels = channels,
                    createdAt = previous?.createdAt ?: System.currentTimeMillis(),
                    sortOrder = previous?.sortOrder ?: order++, sourceUri = doc.uri, sourceModifiedAt = doc.modified
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                skipped++ // Keep any existing record; a temporary read error is not a deletion.
            }
        }
        val seen = documents.mapTo(mutableSetOf()) { it.uri }
        database.withTransaction {
            val current = dao.findGroup(group.id) ?: return@withTransaction
            if (current.folderUri != group.folderUri || current.includeSubfolders != group.includeSubfolders) return@withTransaction
            val plan = FolderIndexReconciler.reconcile(dao.clipsInGroup(group.id), updates, seen)
            plan.upserts.forEach { dao.upsertClip(it) }
            plan.deleteIds.forEach { dao.deleteClip(it) }
        }
        return skipped
    }

    private fun probe(uri: Uri): Triple<Long, Int, Int> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track")
            val format = extractor.getTrackFormat(track)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else {
                val metadata = MediaMetadataRetriever()
                try {
                    metadata.setDataSource(context, uri)
                    metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally { metadata.release() }
            }
            return Triple(duration, format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        } finally { extractor.release() }
    }

    private companion object {
        const val MAX_DOCUMENTS = 20_000
        val AUDIO_EXTENSIONS = setOf("mp3", "wav", "aac", "m4a", "ogg", "opus", "flac", "amr", "3gp", "mp4")
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }

    private class FolderLimitException : Exception()
}
