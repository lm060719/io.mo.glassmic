# KSU 实时音频接入验证日志 — 1.5.1.3 / versionCode 36

2026-11-16（Asia/Shanghai），关联 io.mo.glassmic #40，KSU 模块 0.1.0.11。
正式版本基准已核对 GitHub Release：1.5.1（33-1.5.1）；此前已生成 .1/.2，故使用 .3。

变更：选择音乐 APP（含普通应用 UID 的系统音乐应用），root AudioPolicy 采集进程直接向模块输出 PCM16 LE / 48 kHz / 单声道；主页面与悬浮窗共用开始/停止、暂停/继续、增益及人声混音。校验音源与录音目标共享 UID 冲突，实时模式禁用文件专用控件。APP 通过 stdin 心跳持有采集进程；模块关闭连接会结束采集，EOF 或两秒无 PCM 时模块恢复真麦。实时会话持有 WakeLock，退出时释放。修正旧错误通知不能结束新会话的竞态。

执行：
```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease --console=plain
```
BUILD SUCCESSFUL in 54s。231 actionable tasks: 29 executed, 202 up-to-date。
RegressionSuiteTest：tests=6，failures=0，errors=0，skipped=0。
新增 KsuLiveRegression：原生请求 ABI/偏移、64 位会话标识、包名及参数边界、有界握手响应、截断/超长响应。

配套模块：生产 Reader 与 LiveInput MSVC 回归通过；WebUI 的实时暂停/恢复、文件专用控件限制、断开/文件状态恢复和原有混音回归通过（Playwright 本地模拟桥接）。新增 Android socket 集成用例已编译，未执行。

安装包（Release 未签名）：
```text
app/build/outputs/apk/debug/app-debug.apk
SHA-256: 938f5cb2b8625c1d4088f42f2586e301ae478c2031689889ce4e2bc0e49e69a5
app/build/outputs/apk/release/app-release-unsigned.apk
SHA-256: 57870b72bceb91e3143541cd061305c5034533cc7abffd1c7dcb824f183eecb8
```
KSU ZIP SHA-256：5818bc6b3db9f88f45376829874b67d09ef58542d8c3f4c9428b68099d718e5e。
模块提交：f98781f；模块日志：glassmic/docs/logs/0.1.0.11.txt。

限制：adb devices -l 无设备。未做真机 AudioPolicy 取音、权限、混音听感、延迟、长时间运行或悬浮窗验证；未启动模拟器，未安装或重启设备。
原始本机日志：build/ksu-live-final-build.log；JUnit XML：app/build/test-results/testDebugUnitTest/TEST-RegressionSuiteTest.xml（构建产物不入库）。
