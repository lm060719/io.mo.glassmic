# GlassMic

[简体中文](https://github.com/lm060719/io.mo.glassmic/blob/main/README_zh.md)

GlassMic is an open-source virtual microphone and audio pipeline testing module for rooted Android devices with LSPosed. It lets you route an imported audio file into target recording apps through AudioRecord/AAudio hooks while keeping the module visible, controllable, and easy to disable.

> GlassMic is intended for local testing, development, compatibility checks, and lawful accessibility or debugging scenarios on devices you own or control. Do not use it to impersonate others, deceive people, bypass platform rules, harass anyone, or violate privacy or law.

## Features

- Import local audio files such as MP3, WAV, M4A, AAC, OGG, and FLAC.
- Link local folders to discover and play audio directly, with optional subfolders and automatic refresh when opening the library.
- Manage audio clips and groups.
- Use playback policies: loop, silence after end, or return to the real microphone.
- Restore the last selected audio source after the app process is killed.
- Start restored audio in a paused state so playback is explicit.
- Floating ball with customizable icon and size (small/standard/large).
- Tap the ball to open a song menu and pick audio by group directly.
- While playing, tap the ball for a mini control bar: pause/resume, seek, and switch source.
- Configurable floating ball opacity.
- Runtime routing through a foreground service and always-visible notification.
- LSPosed integration. The scope needs `android` / `system` plus every target app.
- Capture interception on the Java `AudioRecord` path and natively on AAudio, OpenSL ES and `AudioRecord::read`; audio-only `MediaRecorder` sessions are taken over in-app.
- Experimental AudioPolicy (root) backend that injects per app UID without LSPosed hooks, with multi-select and an "all apps" mode.
- Link a local folder: audio files in it are picked up automatically.
- Experimental live source: use another app's playback audio as the virtual microphone in real time (root).
- Injection volume slider (0–300%) in the floating window and settings.
- Per-consumer sample-rate/channel conversion for clients such as WeChat and system recorders.
- Diagnostics, logs, hook status, and interception statistics.
- Optional experimental audio effects such as high gain and stronger noise simulation.
- Text-to-speech (TTS): type text and feed synthesized speech into the target app — handy for testing voice recognition / voice input without preparing audio files.
- Two-step TTS flow: generate first, then play (replayable).
- Offline system TTS out of the box, plus optional online AI TTS with OpenAI, Google Gemini, Xiaomi MiMo, and Fish Audio protocols, custom endpoint URL and model, model listing, connection test, and audition.
- MiMo preset voices, voice design (describe a voice in text), and voice cloning from a short reference audio sample (≤60 s / ≤5 MB).
- Fish Audio voices, with optional LLM auto-tagging that inserts emotion / sound-effect tags before synthesis.
- API keys are stored encrypted with an Android Keystore key.

## Text-to-Speech (TTS)

Open the floating window's menu and tap "🗣 Text-to-Speech". Type your text, tap **Generate** to synthesize, then tap **Play** to feed the audio into whatever app is recording. Play is repeatable, so you can re-feed the same clip without regenerating.

- **System TTS** works fully offline with no configuration.
- **AI TTS** (Settings → AI Provider (TTS)) enables online synthesis with a custom endpoint URL and model, and supports three protocols:
  - **OpenAI** — `/audio/speech`
  - **Google Gemini** — `generateContent` with the AUDIO modality
  - **Xiaomi MiMo** — `chat/completions`, including preset voices, voice design (describe a voice in text), and voice cloning from a reference audio sample (≤60 s / ≤5 MB)
- Fetch the available model list from the provider, run a connection test, and audition the result (generate, then play through the speaker) before feeding it to an app.

## Requirements

- Android 10 (API 29) minimum; Android 15/16 recommended target environment.
- Root access, for example Magisk or KernelSU.
- LSPosed with libxposed API support.
- Android notification, overlay, file access, and foreground service permissions.
- JDK 17 and Android SDK for building from source.

## Installation

1. Install the APK.
2. Enable the GlassMic module in LSPosed.
3. Put `android` and `system` in the LSPosed scope, plus every app that should get the virtual microphone, then fully restart those apps. Apps outside the LSPosed scope are never hooked, whatever GlassMic's own app list says.
4. Reboot the device so the module is loaded cleanly.
5. Open GlassMic, complete onboarding, grant permissions, import an audio file, and select it as the current source.
6. Start GlassMic from the main screen when you want the virtual microphone to be active.

## Linked folders

In **Audio Library → Link folder**, choose a local directory and optionally enable **Include subfolders**. A folder group appears automatically; audio plays from the original files without copying them into the app. The library refreshes when opened or resumed; **Refresh folders** scans on demand. Scanning is bounded to 20,000 entries per folder tree.

**Remove from library** hides a linked clip without deleting its original file, and it stays removed across refreshes. **Manage group → Restore removed clips** brings it back. **Unlink folder** removes only the group and its index. Existing imported groups continue to store their own copies.

If the directory is moved, its permission is revoked, or the app is restored from backup, use **Manage group → Grant access to original folder again** and choose the same directory. Folder links do not back up the external audio files; a restored index alone does not grant access to them. Android restricts selection of some directories, including the storage root and the Download directory itself; select an allowed subfolder instead.

## KernelSU module controls (1.5.1.3)

With the app service stopped, select **Settings → Injection backend → KernelSU module**, then **Connect module** on the microphone tab. This controls the separately installed `glassmic` module, version `0.1.0.9` or a compatible fourth-segment revision; it requires KernelSU root authorization and a working Zygisk runtime. The APK does not install or restart the module.

- The microphone page and floating controls share module playback, pause, seek, real-mic/mute, gain (-24 to +12 dB), speed (0.5–2×), and loop state with the WebUI.
- The library tab reads the module library. Import WAV, MP3 or FLAC (up to 512 MiB) through the system file picker. Imports create module copies without overwriting originals; the APK library remains separate.
- The scope page edits the module targets directly. Newly added targets must be fully restarted. Remove them from the old LSPosed scope and restart them before using KSU; the app also gates off both its LSPosed and AudioPolicy PCM routes in KSU mode.
- Closing the UI or floating window leaves playback running. **Restore mic & disconnect** waits for the module to acknowledge real-microphone mode. Backend switching is blocked if that acknowledgement fails. An app crash or force-stop can leave the independent module playing; its WebUI remains available.
- Module 0.1.0.10 adds **Mix real microphone** with separate microphone volume (0–200%) and accompaniment gain. The app, floating panel and module WebUI share the module settings. Mixing applies to file and live modes: pausing or reaching the end keeps your voice, Silence mutes both sources, and Real microphone bypasses mixing. Restart target apps after updating the module. Older modules keep playback controls but cannot enable mixing.
- APP 1.5.1.3 with module **0.1.0.11** can capture a selected music app and send its playback directly to the module. Choose the source on the KSU control page, then start/stop from that page or the floating panel. The phone keeps playing normally. Keep the APP service running; the source (including shared-UID aliases) cannot also be a recording target. Seek, speed and loop are unavailable for live input. Pausing live input keeps microphone mixing active.
- File playback can outlive the APP. Live input ends on helper exit, socket disconnect or a 2-second PCM stall and restores the real microphone. Closing only the UI does not stop it. OEM AudioPolicy compatibility and actual latency still require device validation.
- APK TTS, audio monitoring and effects are not yet routed into KSU; their controls remain hidden in KSU mode.

This integration uses the installed `glassmicctl` and its root-only control socket; file PCM stays in the module; live PCM goes directly from the owned root capture helper to the module without passing through the APP buffer. Status refresh stops when the screen is off or there are no visible observers. JVM regressions cover backend exclusion, command validation and subprocess bounds; device integration and overlay behavior still require physical-device validation.

## How It Works

GlassMic has three modules:

- `app`: Android app, Compose UI, foreground service, floating window, ContentProviders, diagnostics, and audio publishing.
- `xposed`: LSPosed/libxposed entry points, AudioRecord hooks, native AAudio hook, and PCM pipe reader.
- `core`: shared models, constants, and scope matching helpers.

When enabled, target recording calls are intercepted and filled from the current PCM stream. The app publishes PCM through a ContentProvider pipe, and each consumer receives audio converted to its requested sample rate and channel count.

## Safety Model

GlassMic is designed to fail toward the real microphone:

- Device reboot disables the active virtual microphone state.
- Safe mode can stop the module after repeated system/audio failures.
- If no valid selected audio file exists, GlassMic falls back instead of forcing a broken source.
- The foreground notification remains visible while the service is running.
- The app itself is excluded from interception to avoid feedback loops.

## Build

```bash
git clone https://github.com/<owner>/GlassMic.git
cd GlassMic
./gradlew assembleDebug
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is generated under:

```text
app/build/outputs/apk/debug/
```

## Recommended Repository Description

Open-source LSPosed virtual microphone and Android audio pipeline testing module for rooted devices.

## License

GlassMic is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).
