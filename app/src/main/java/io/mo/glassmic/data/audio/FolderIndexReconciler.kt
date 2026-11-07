package io.mo.glassmic.data.audio

import io.mo.glassmic.data.db.AudioClipEntity

/** Apply a complete listing without losing edits made while metadata was being read. */
internal object FolderIndexReconciler {
    data class Plan(val upserts: List<AudioClipEntity>, val deleteIds: List<String>)

    fun reconcile(
        current: List<AudioClipEntity>,
        scanned: List<AudioClipEntity>,
        seenUris: Set<String>
    ): Plan {
        val byUri = current.filter { it.sourceUri != null }.associateBy { it.sourceUri }
        val upserts = scanned.map { clip ->
            val existing = byUri[clip.sourceUri]
            if (existing == null) clip else clip.copy(
                id = existing.id, displayName = existing.displayName, hidden = existing.hidden,
                sortOrder = existing.sortOrder, createdAt = existing.createdAt
            )
        }
        // Tombstones survive disappearance/reappearance until the user restores or unlinks them.
        val deleted = current.filter {
            it.sourceUri != null && it.sourceUri !in seenUris && !it.hidden
        }.map { it.id }
        return Plan(upserts, deleted)
    }
}
