package io.mo.glassmic.data.runtime

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.Constants
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

data class AudioInterceptStats(
    val totalReads: Long,
    val totalBytes: Long,
    val lastInterceptMs: Long,
    val lastPackage: String?,
    val lastSampleRate: Int,
    val lastChannels: Int
) {
    val hasEverIntercepted: Boolean get() = totalReads > 0
}

/**
 * 读取 Xposed 进程通过 RuntimeProvider.call("audio_intercept") 写入的拦截统计。
 *
 * Xposed 端每 N 次 read 上报一次（节流），写到 [Constants.AUDIO_STATS_PREFS]。
 */
@Singleton
class AudioStatsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(Constants.AUDIO_STATS_PREFS, Context.MODE_PRIVATE)
    }

    val flow: Flow<AudioInterceptStats> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(snapshot())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.onStart { emit(snapshot()) }.distinctUntilChanged()

    fun snapshot(): AudioInterceptStats = AudioInterceptStats(
        totalReads = prefs.getLong(Constants.AUDIO_STATS_TOTAL_READS, 0L),
        totalBytes = prefs.getLong(Constants.AUDIO_STATS_TOTAL_BYTES, 0L),
        lastInterceptMs = prefs.getLong(Constants.AUDIO_STATS_LAST_INTERCEPT, 0L),
        lastPackage = prefs.getString(Constants.AUDIO_STATS_LAST_PACKAGE, null)?.takeIf { it.isNotBlank() },
        lastSampleRate = prefs.getInt(Constants.AUDIO_STATS_LAST_SAMPLE_RATE, 0),
        lastChannels = prefs.getInt(Constants.AUDIO_STATS_LAST_CHANNELS, 0)
    )

    fun reset() {
        prefs.edit()
            .remove(Constants.AUDIO_STATS_TOTAL_READS)
            .remove(Constants.AUDIO_STATS_TOTAL_BYTES)
            .remove(Constants.AUDIO_STATS_LAST_INTERCEPT)
            .remove(Constants.AUDIO_STATS_LAST_PACKAGE)
            .remove(Constants.AUDIO_STATS_LAST_SAMPLE_RATE)
            .remove(Constants.AUDIO_STATS_LAST_CHANNELS)
            .apply()
    }
}
