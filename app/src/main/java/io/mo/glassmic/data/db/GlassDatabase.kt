package io.mo.glassmic.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AudioGroupEntity::class, AudioClipEntity::class],
    version = 1,
    exportSchema = false
)
abstract class GlassDatabase : RoomDatabase() {
    abstract fun audioDao(): AudioDao

    companion object {
        const val DB_NAME = "glassmic.db"
    }
}
