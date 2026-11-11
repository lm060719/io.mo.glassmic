# GlassMic ProGuard rules

# app_process loads this entry point by name from the installed APK, including release builds.
-keep class io.mo.glassmic.root.AudioPolicyMain { *; }
-keep class io.mo.glassmic.root.PlaybackCaptureMain { *; }

# 保留 libxposed API 101 入口
-keep class io.mo.glassmic.xposed.** { *; }
-dontwarn io.github.libxposed.api.**
-dontwarn de.robv.android.xposed.**

# 保留 ContentProvider
-keep class io.mo.glassmic.provider.** { *; }

# 保留 Proto 生成类
-keep class io.mo.glassmic.proto.** { *; }

# Hilt
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# Compose / Room 自带 consumer 规则，无需整包 keep（整包 keep 会让 R8 无法裁剪、包体变大）。
# 实体类保留类名，方便诊断导出与数据库排查时对照。
-keep @androidx.room.Entity class *
