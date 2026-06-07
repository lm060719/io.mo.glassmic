package io.mo.glassmic.core.model

enum class SourceType { REAL_MIC, FILE, SILENCE }

enum class ScopeMode { GLOBAL, WHITELIST, BLACKLIST }

enum class PlaybackPolicy { SILENCE, LOOP, REAL_MIC }

enum class ThemeMode { FOLLOW_SYSTEM, LIGHT, DARK }

enum class FloatingMode { PILL, PANEL }

enum class FloatingSize { SMALL, STANDARD, LARGE }

enum class LogLevel { OFF, BASIC, VERBOSE, DEBUG }

enum class SafeModeReason {
    SYSTEM_UI_REPEATED_CRASH,
    KEY_SYSTEM_PROC_CRASH,
    MODULE_INIT_FAILURE,
    AUDIO_ENGINE_CONTINUOUS_FAILURE,
    LAST_BOOT_DID_NOT_EXIT_CLEANLY,
    USER_EMERGENCY_STOP
}

data class SafeModeInfo(
    val reason: SafeModeReason,
    val occurredAt: Long
)
