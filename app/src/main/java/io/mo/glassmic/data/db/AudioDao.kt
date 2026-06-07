package io.mo.glassmic.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioDao {

    // ============ groups ============
    @Query("SELECT * FROM audio_groups ORDER BY sortOrder, name")
    fun observeGroups(): Flow<List<AudioGroupEntity>>

    @Query("SELECT * FROM audio_groups WHERE id = :id")
    suspend fun findGroup(id: String): AudioGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: AudioGroupEntity)

    @Update
    suspend fun updateGroup(group: AudioGroupEntity)

    @Query("DELETE FROM audio_groups WHERE id = :id")
    suspend fun deleteGroup(id: String)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM audio_groups")
    suspend fun maxGroupOrder(): Int

    // ============ clips ============
    @Query("SELECT * FROM audio_clips ORDER BY createdAt DESC")
    fun observeAllClips(): Flow<List<AudioClipEntity>>

    @Query("SELECT * FROM audio_clips WHERE groupId = :groupId ORDER BY sortOrder, createdAt")
    fun observeClipsInGroup(groupId: String): Flow<List<AudioClipEntity>>

    @Query("SELECT * FROM audio_clips WHERE id = :id")
    suspend fun findClip(id: String): AudioClipEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClip(clip: AudioClipEntity)

    @Query("DELETE FROM audio_clips WHERE id = :id")
    suspend fun deleteClip(id: String)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM audio_clips WHERE groupId = :groupId")
    suspend fun maxClipOrderInGroup(groupId: String): Int

    @Query("UPDATE audio_clips SET displayName = :name WHERE id = :id")
    suspend fun renameClip(id: String, name: String)
}
