# GlassMic

GlassMic 是一个面向 Root Android 设备和 LSPosed 的开源虚拟麦克风与音频链路测试模块。它可以把导入的本地音频通过 AudioRecord / AAudio hook 注入到目标录音应用中，同时保留前台通知、悬浮窗控制和清晰的运行状态。

> GlassMic 仅用于你本人拥有或可控制设备上的本地测试、开发调试、兼容性验证、合法辅助和音频链路研究。请勿用于冒充他人、欺骗通话、骚扰他人、规避平台规则、侵犯隐私或任何违法违规场景。

## 功能

- 导入 MP3、WAV、M4A、AAC、OGG、FLAC 等本地音频文件。
- 管理音频片段和分组。
- 支持播放策略：循环、播放完静音、播放完切回真实麦克风。
- App 进程被清理后，再次开启 GlassMic 可恢复上次选择的音源。
- 恢复音源后默认暂停，避免重新开启时立刻播放。
- 悬浮球支持自定义图标和大小（小/标准/大）。
- 点击悬浮球直接弹出选曲菜单，按分组自由选择并播放音频。
- 播放中点击悬浮球弹出迷你控制条：播放/暂停、进度条拖动、换音源。
- 悬浮球透明度可调节。
- 运行时通过前台服务和常驻通知保持可见。
- LSPosed 推荐作用域限制为 Android / 系统框架。
- 支持 AudioRecord 和 AAudio 输入链路拦截。
- 为不同录音客户端按需转换采样率和声道数。
- 提供诊断包、日志、hook 状态和拦截统计。
- 实验功能包含高增益和更强的噪音环境模拟。
- 文字转语音（TTS）：输入文字即可把合成语音喂给目标 App，测试语音识别 / 语音输入场景比准备音频文件方便。
- TTS 采用「先生成、后播放」两步，播放可重复。
- 开箱即用的离线系统 TTS，另可选在线 AI TTS，兼容 OpenAI、Google Gemini、小米 MiMo 协议，支持自定义接口地址与模型、拉取模型列表、测试连接与效果试听。
- MiMo 支持预置音色、文本描述定制音色（voicedesign）、以及基于音频样本复刻音色（voiceclone，≤30 秒 / ≤2MB）。

## 文字转语音（TTS）

打开悬浮窗菜单，点「🗣 文字转语音」，输入文字后点**生成**合成，再点**播放**把语音喂给正在录音的目标 App。播放可重复，无需重新生成即可再喂一遍。

- **系统 TTS** 完全离线、无需任何配置。
- **AI TTS**（设置 → AI 供应商（TTS)）走在线合成，支持自定义接口地址与模型，兼容三种协议：
  - **OpenAI** —— `/audio/speech`
  - **Google Gemini** —— `generateContent`（AUDIO 模态）
  - **小米 MiMo** —— `chat/completions`，含预置音色、文本描述定制音色（voicedesign）、以及基于参考音频复刻音色（voiceclone，≤30 秒 / ≤2MB）
- 可从供应商接口拉取模型列表、测试连接、并先试听效果（生成后从扬声器播放）再喂给 App。

## 环境要求

- 最低支持 Android 10（API 29），推荐 Android 15 / 16 目标环境。
- Root 权限，例如 Magisk 或 KernelSU。
- LSPosed，并支持 libxposed API。
- 通知、悬浮窗、文件访问、前台服务等 Android 权限。
- 从源码构建需要 JDK 17 和 Android SDK。

## 安装

1. 安装 APK。
2. 在 LSPosed 中启用 GlassMic 模块。
3. 推荐作用域使用 `android` 和 `system`。
4. 重启设备，让模块完整加载。
5. 打开 GlassMic，完成首次引导并授予权限。
6. 导入音频文件，并设置为当前音源。
7. 需要虚拟麦克风时，在主页开启 GlassMic。

## 使用流程

1. 进入音频库，导入一个音频文件。
2. 将音频设为当前音源。
3. 回到主页开启 GlassMic。
4. 打开悬浮窗：默认是胶囊形态，点击胶囊展开控制面板。
5. 在展开面板中播放、暂停或拖动进度条。
6. 在目标录音 App 中测试录音效果。

## 工作原理

项目包含三个模块：

- `app`：Android 应用、Compose UI、前台服务、悬浮窗、ContentProvider、诊断和 PCM 发布器。
- `xposed`：LSPosed / libxposed 入口、AudioRecord hook、native AAudio hook 和 PCM 读取端。
- `core`：跨进程共享的数据模型、常量和作用域匹配逻辑。

GlassMic 开启后，目标应用的录音读取调用会被拦截，并由当前 PCM 流填充。App 侧通过 ContentProvider pipe 发布 PCM 数据，每个录音客户端会收到符合自身采样率和声道数要求的音频。

## 安全设计

GlassMic 的失败策略是优先回到真实麦克风：

- 设备重启后不会自动开启虚拟麦克风。
- 如果上次选择的音频文件不存在，会回退而不是强行使用损坏音源。
- 前台服务运行时始终显示通知。
- App 自身不会被拦截，避免形成回环。
- 检测到重复系统或音频引擎异常时，可进入安全模式。

## 构建

```bash
git clone https://github.com/lm060719/io.mo.glassmic.git
cd io.mo.glassmic
./gradlew assembleDebug
```

Windows：

```powershell
.\gradlew.bat assembleDebug
```

生成的 debug APK 位于：

```text
app/build/outputs/apk/debug/
```

## 仓库简介

开源 LSPosed 虚拟麦克风与 Android 音频链路测试模块，面向 Root 设备。

## 许可协议

GlassMic 使用 GNU General Public License v3.0 开源。详见 [LICENSE](LICENSE)。
