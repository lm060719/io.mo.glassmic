# GlassMic ProGuard rules

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

# Compose
-keep class androidx.compose.runtime.** { *; }

# Room
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class *

# AudioRecord 相关——别被混淆掉
-keep class android.media.AudioRecord { *; }
