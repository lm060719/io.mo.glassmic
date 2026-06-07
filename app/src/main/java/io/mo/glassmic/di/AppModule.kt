package io.mo.glassmic.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.db.GlassDatabase
import javax.inject.Singleton

/**
 * Hilt App 模块。
 *
 * 仅声明无法用 @Inject constructor 自动构造的平台对象。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): GlassDatabase =
        Room.databaseBuilder(ctx, GlassDatabase::class.java, GlassDatabase.DB_NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideAudioDao(db: GlassDatabase): AudioDao = db.audioDao()
}
