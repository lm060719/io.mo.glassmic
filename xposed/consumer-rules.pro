# 保留 libxposed API 101 入口类，防止 META-INF/xposed/java_init.list 反射加载失败
-keep class io.mo.glassmic.xposed.GlassMicXposedModule { *; }
-keep class io.mo.glassmic.xposed.AudioRecordHook { *; }
-keep class io.mo.glassmic.xposed.XBridge { *; }
-keep class io.mo.glassmic.xposed.XposedPcmReader { *; }
-dontwarn io.github.libxposed.api.**
# 保留 legacy API 82 入口类，防止 assets/xposed_init 反射加载失败
-keep class io.mo.glassmic.xposed.LegacyXposedEntry { *; }
-keep class io.mo.glassmic.xposed.LegacyAudioRecordHook { *; }
-keep class io.mo.glassmic.xposed.XposedHookGate { *; }
-dontwarn de.robv.android.xposed.**
