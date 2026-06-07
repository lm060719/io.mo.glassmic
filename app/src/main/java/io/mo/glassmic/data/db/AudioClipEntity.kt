package io.mo.glassmic.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.mo.glassmic.core.model.AudioClip

@Entity(
    tableName = "audio_clips",
    foreignKeys = [
        ForeignKey(
            entity = AudioGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("groupId")]
)
data class AudioClipEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val displayName: String,
    val fileName: String,
    val relativePath: String,
    val mimeType: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val sampleRate: Int,
    val channels: Int,
    val createdAt: Long,
    val sortOrder: Int
) {
    fun toModel(): AudioClip = AudioClip(
        id = id,
        groupId = groupId,
        displayName = displayName,
        fileName = fileName,
        relativePath = relativePath,
        mimeType = mimeType,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        sampleRate = sampleRate,
        channels = channels,
        createdAt = createdAt,
        sortOrder = sortOrder
    )
}
