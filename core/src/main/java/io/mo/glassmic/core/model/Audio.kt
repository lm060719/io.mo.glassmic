package io.mo.glassmic.core.model

/**
 * 音频片段。relativePath 相对于 AudioFileResolver.audioRoot()。
 * 不在这里持有 File 引用——core 模块不能依赖 android.content。
 */
data class AudioClip(
    val id: String,
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
)

data class AudioGroup(
    val id: String,
    val name: String,
    val emoji: String,
    val sortOrder: Int,
    /** null 表示跟随全局策略 */
    val playbackPolicyOverride: PlaybackPolicy?
)
