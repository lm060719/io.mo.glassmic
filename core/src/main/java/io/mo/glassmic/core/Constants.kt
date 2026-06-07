package io.mo.glassmic.core

object Constants {
    const val APP_PACKAGE = "io.mo.glassmic"
    const val APP_NAME = "GlassMic"
    const val APP_NAME_ZH = "璃麦"

    // 旧版共享配置名；当前 Xposed 侧优先通过 RuntimeProvider 实时查询
    const val XSHARED_PREFS_NAME = "glassmic_shared"

    // Xposed 激活状态探测
    const val XPOSED_STATUS_PREFS = "xposed_status"
    const val XPOSED_STATUS_LAST_PING = "last_ping"
    const val XPOSED_STATUS_LAST_PACKAGE = "last_package"
    const val XPOSED_STATUS_API = "api"
    const val METHOD_XPOSED_PING = "xposed_ping"

    // AudioRecord 拦截统计：由 Xposed 进程周期写入，App 进程读取展示
    const val AUDIO_STATS_PREFS = "audio_intercept_stats"
    const val AUDIO_STATS_TOTAL_READS = "total_reads"
    const val AUDIO_STATS_TOTAL_BYTES = "total_bytes"
    const val AUDIO_STATS_LAST_INTERCEPT = "last_intercept_ms"
    const val AUDIO_STATS_LAST_PACKAGE = "last_package"
    const val AUDIO_STATS_LAST_SAMPLE_RATE = "last_sample_rate"
    const val AUDIO_STATS_LAST_CHANNELS = "last_channels"
    const val METHOD_AUDIO_INTERCEPT = "audio_intercept"

    // ContentProvider authorities
    const val PROVIDER_RUNTIME = "io.mo.glassmic.provider.runtime"
    const val PROVIDER_PCM = "io.mo.glassmic.provider.pcm"

    // 通知
    const val NOTIF_CHANNEL_ID = "glassmic_running"
    const val NOTIF_ID = 0x6C696D /* 'lim' */

    // 文件名
    const val SAFE_MODE_FLAG = "safe_mode.flag"
    const val RUNNING_SENTINEL = "running.lock"
    const val BOOT_GATE_PREFS = "boot_gate"
    const val BOOT_GATE_KEY_ENABLED = "enabled_for_boot"

    // 共享 PCM
    const val SHARED_PCM_FILE = "glass_pcm_shared.bin"
    const val SHARED_PCM_HEADER_BYTES = 64
    const val SHARED_PCM_DATA_BYTES = 1 shl 20  // 1 MiB
    const val SHARED_PCM_MAGIC = 0x474D5043       // 'GMPC'

    // Xposed scope 文件名保留给后续导出/诊断使用
    const val XPOSED_SCOPE_FILE = "xposed_scope.txt"
}
