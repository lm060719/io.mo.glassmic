package io.mo.glassmic.data.config

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import io.mo.glassmic.proto.AppConfig
import io.mo.glassmic.proto.Appearance
import io.mo.glassmic.proto.Experimental
import io.mo.glassmic.proto.FloatingMode
import io.mo.glassmic.proto.FloatingSize
import io.mo.glassmic.proto.FloatingWindow
import io.mo.glassmic.proto.LogLevel
import io.mo.glassmic.proto.Logging
import io.mo.glassmic.proto.PlaybackPolicy
import io.mo.glassmic.proto.ScopeMode
import io.mo.glassmic.proto.ThemeMode
import java.io.InputStream
import java.io.OutputStream

object AppConfigSerializer : Serializer<AppConfig> {

    override val defaultValue: AppConfig = AppConfig.newBuilder()
        .setVersion(1)
        .setGlobalSwitch(false)                          // 默认关闭
        .setScopeMode(ScopeMode.GLOBAL)
        .setPlaybackPolicy(PlaybackPolicy.LOOP)
        .setOnboardingCompleted(false)
        .setShowSystemApps(false)
        .setFloatingWindow(
            FloatingWindow.newBuilder()
                .setEnabled(true)
                .setDefaultMode(FloatingMode.PILL)
                .setOpacity(0.85f)
                .setSize(FloatingSize.STANDARD)
        )
        .setAppearance(
            Appearance.newBuilder()
                .setTheme(ThemeMode.FOLLOW_SYSTEM)
                .setGlassEffect(true)
                .setReduceMotion(false)
        )
        .setLogging(
            Logging.newBuilder()
                .setEnabled(true)
                .setLevel(LogLevel.BASIC)
        )
        .setExperimental(
            Experimental.newBuilder()
                .setUnlocked(false)        // 实验功能默认锁定
                .setLimiterEnabled(true)   // 限幅保护默认开
                .setReverbAmount(0.5f)     // 混响强度默认中等
                .setSpeedFactor(1.0f)      // 变速默认原速
        )
        .build()

    override suspend fun readFrom(input: InputStream): AppConfig =
        try { AppConfig.parseFrom(input) }
        catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("AppConfig 损坏", e)
        }

    override suspend fun writeTo(t: AppConfig, output: OutputStream) =
        t.writeTo(output)
}
