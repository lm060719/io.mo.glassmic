# KSU 控制与混音验证日志 — 1.5.1.2 / versionCode 35

2026-11-15，关联 io.mo.glassmic #40，KSU 模块项目 glassmic 0.1.0.10。

变更：新增模块连接/退出、模块音频库导入及播放、目标应用选择、KSU 悬浮窗；KSU 模式排除 APK 两条 PCM 注入路径；增加真实麦克风混音开关及独立人声音量；模块命令白名单、超时和待恢复状态保护。

验证命令：
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease --console=plain
结果：BUILD SUCCESSFUL in 2m 29s，231 actionable tasks。
RegressionSuiteTest：tests=5，failures=0，errors=0，skipped=0。
覆盖：音频、按键时序、目录索引、Hook 诊断、KSU 命令边界/进程退出/后端隔离。
Release 为未签名 APK；Debug APK versionName=1.5.1.2，versionCode=35。

模块验证：生产 Reader 六种采样率、多声道、PCM16/float、人声增益、限幅、欠载、静音及透传回归通过；Playwright 模拟 WebUI 状态同步、音量边界、旧模块提示、断线恢复及翻译通过。

限制：adb devices -l 无设备；未做真机录音、权限或悬浮窗运行验证，未启动模拟器。
原始本机日志：build/ksu-mix-build.log（构建产物目录不入库）。
