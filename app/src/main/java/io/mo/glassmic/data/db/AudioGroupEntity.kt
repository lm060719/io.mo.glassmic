package io.mo.glassmic.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.mo.glassmic.core.model.AudioGroup
import io.mo.glassmic.core.model.PlaybackPolicy

@Entity(tableName = "audio_groups")
data class AudioGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String,
    val sortOrder: Int,
    /** null = 跟随全局策略；存 PlaybackPolicy.name */
    val playbackPolicyOverride: String?
) {
    fun toModel(): AudioGroup = AudioGroup(
        id = id,
        name = name,
        emoji = emoji,
        sortOrder = sortOrder,
        playbackPolicyOverride = playbackPolicyOverride?.let {
            runCatching { PlaybackPolicy.valueOf(it) }.getOrNull()
        }
    )
}
