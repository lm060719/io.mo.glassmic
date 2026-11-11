package io.mo.glassmic.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AudioGroupEntity::class, AudioClipEntity::class],
    version = 2,
    exportSchema = true
)
abstract class GlassDatabase : RoomDatabase() {
    abstract fun audioDao(): AudioDao

    companion object {
        const val DB_NAME = "glassmic.db"
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audio_groups ADD COLUMN folderUri TEXT")
                db.execSQL("ALTER TABLE audio_groups ADD COLUMN includeSubfolders INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE audio_clips ADD COLUMN sourceUri TEXT")
                db.execSQL("ALTER TABLE audio_clips ADD COLUMN sourceModifiedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE audio_clips ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
