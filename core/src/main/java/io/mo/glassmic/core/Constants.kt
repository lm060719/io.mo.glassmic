package io.mo.glassmic.core

object Constants {
    const val APP_PACKAGE = "io.mo.glassmic"
    const val APP_NAME = "GlassMic"
    const val APP_NAME_ZH = "璃麦"

    // Xposed 激活状态探测
    const val XPOSED_STATUS_PREFS = "xposed_status"
    const val XPOSED_STATUS_LAST_PING = "last_ping"
    const val XPOSED_STATUS_LAST_PACKAGE = "last_package"
    const val XPOSED_STATUS_API = "api"
    const val XPOSED_STATUS_PACKAGES = "package_states"
    const val METHOD_XPOSED_PING = "xposed_ping"

    // AudioRecord 拦截统计：由 Xposed 进程周期写入，App 进程读取展示
    const val AUDIO_STATS_PREFS = "audio_intercept_stats"
    const val AUDIO_STATS_TOTAL_READS = "total_reads"
    const val AUDIO_STATS_TOTAL_BYTES = "total_bytes"
    const val AUDIO_STATS_LAST_INTERCEPT = "last_intercept_ms"
    const val AUDIO_STATS_LAST_PACKAGE = "last_package"
    const val AUDIO_STATS_LAST_SAMPLE_RATE = "last_sample_rate"
    const val AUDIO_STATS_LAST_CHANNELS = "last_channels"
    const val AUDIO_STATS_NATIVE_DIAGNOSTICS = "native_diagnostics"
    const val AUDIO_STATS_PCM_DIAGNOSTICS = "pcm_read_diagnostics"
    const val AUDIO_STATS_TIMELINE = "audio_diagnostic_timeline"
    const val METHOD_PCM_READ_STATS = "pcm_read_stats"
    const val METHOD_AUDIO_INTERCEPT = "audio_intercept"
    const val METHOD_AUDIO_DIAG_EVENT = "audio_diag_event"

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

    // ============ 严格 ROM 兼容：包可见性放行 ============
    // 偏原生/隐私 ROM（LineageOS、部分魅族等）严格执行 Android 11+ 的包可见性过滤，
    // 导致被注入的目标 App 进程「看不到」本模块，无法访问 RuntimeProvider/PcmStreamProvider，
    // 拿不到决策与 PCM。开启后在 system_server 放行对本包的可见性查询。默认关闭。
    //
    // 模块 remote preferences（App 用 MODE_WORLD_READABLE 写、system_server 只读）
    const val REMOTE_PREFS = "glassmic_remote"
    const val KEY_VISIBILITY_COMPAT = "visibility_compat"
    // system_server 只对这些 GlassMic 目标应用解除本模块的包可见性过滤。
    // 该集合与 App 内白名单同步；未命中的调用方保持系统/HMA 原始过滤结果。
    const val KEY_VISIBILITY_ALLOWLIST = "visibility_allowlist"
    // 同一份白名单的 root 文件副本（system:system 0600，每行一个包名）。LSPosed remote preferences
    // 在 system_server 里只在开机时加载一次、之后收不到更新；这个文件按修改时间重读，改范围后立即生效。
    const val VISIBILITY_ALLOWLIST_FILE = "/data/system/glassmic_visibility_allowlist"
    // 调试/高级用户用的 system property 覆盖（重启失效，仅便于排查）
    const val PROP_VISIBILITY_COMPAT = "persist.sys.glassmic_vis"

    // ============ 音量键快捷操作 ============
    // 悬浮窗开着时：双击音量上键=播放，双击音量下键=暂停。
    // 键事件只能在 system_server 的 PhoneWindowManager 里拿到，因此由 Xposed 侧检测、
    // 广播回 App 侧执行。两侧的"握手"走同一份 remote preferences：
    //
    //  - App 写 [KEY_VOLUME_SHORTCUT_ARMED]：= 设置开关打开 && 悬浮窗正在运行。
    //    只有为 true 时 system_server 侧才会去拦音量键——悬浮窗没开时音量键行为
    //    与未装模块完全一致，把误伤面压到最小。
    //  - App 每次 arm 时刷新 [KEY_VOLUME_SHORTCUT_TOKEN]，模块把它带在广播里回传，
    //    App 侧核对后才执行；防止第三方 App 伪造广播遥控播放。
    const val KEY_VOLUME_SHORTCUT_ARMED = "volume_shortcut_armed"
    const val KEY_VOLUME_SHORTCUT_TOKEN = "volume_shortcut_token"

    const val ACTION_VOLUME_SHORTCUT = "io.mo.glassmic.action.VOLUME_SHORTCUT"
    const val EXTRA_SHORTCUT_ACTION = "shortcut_action"
    const val EXTRA_SHORTCUT_TOKEN = "shortcut_token"
    const val SHORTCUT_PLAY = "play"
    const val SHORTCUT_PAUSE = "pause"

    /**
     * 双击判定窗口。
     *
     * 代价说明：为了做到"双击后音量不变"，第一次按键必须先扣住不放行——只有等这段时间
     * 内没等到第二次按键，才把音量调整补上。所以 armed 期间单次调音量会晚这么久生效。
     * 280ms 是"双击不费劲"与"调音量不明显发涩"之间的折中。
     */
    const val VOLUME_DOUBLE_TAP_WINDOW_MS = 280L
}
