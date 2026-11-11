# AGENTS.md

## Project

GlassMic — open-source LSPosed virtual microphone module for rooted Android devices. Intercepts AudioRecord/AAudio calls and fills them from imported audio files.

## Code search

**Prefer the `codebase-memory` MCP knowledge graph over grep/ripgrep for structural questions.** This repo is already indexed. Graph queries return precise results in ~500 tokens where grep would burn tens of thousands.

- Who calls X / what does X call → `trace_path` (inbound / outbound / both)
- Find symbols by name → `search_graph(name_pattern=...)`
- Read a symbol's source → `get_code_snippet(qualified_name=...)`
- Architecture / modules at a glance → `get_architecture`
- Impact of local edits → `detect_changes`

Fall back to Grep/Glob only for plain-text matches (comments, strings, resources) or when the graph lacks the answer. The index is a snapshot stored outside the repo (`~/.cache/codebase-memory-mcp/`), so **re-index after changing code** (`index_repository`, `fast` mode is fine); a new conversation reuses the existing index without re-indexing.

## Build

```bash
.\gradlew.bat assembleDebug
```

Output: `app/build/outputs/apk/debug/`

Requirements: JDK 17, Android SDK platforms 35 and 37 (`platforms;android-37.0`), Build-Tools 36.0.0 (what CI uses). Native builds use CMake 3.22.1 and NDK r26b (26.1.10909125), pinned in `xposed/build.gradle.kts`.

## Tests

There are no instrumented tests and no lint/typecheck tasks. Regression checks are plain Java/C++ harnesses under `tests/`:

```powershell
.\gradlew.bat :app:testDebugUnitTest   # all JVM regressions (tests/audio + tests/hooks) via app/src/test/java/RegressionSuiteTest.java
.\tests\audio\run.ps1                  # same suites without the Gradle test task
.\tests\hooks\run.ps1
.\tests\native\run.ps1                 # pcm_regression.cpp — compiles glass_aaudio.cpp on the host; needs MSVC (Visual Studio C++ tools)
```

`app/build.gradle.kts` adds `tests/audio` and `tests/hooks` to the `test` source set; a new harness needs a matching `@Test` in `RegressionSuiteTest`. Harnesses must not touch Android framework classes at runtime (android.jar is a stub in JVM tests). `tests/native/stubs/` replaces Android/POSIX headers with aborting stubs so the native code builds on Windows; add a stub there whenever `glass_aaudio.cpp` starts using a new system header or call. CI (`build-apk.yml`) runs the JVM tests on Linux and the native suite on a Windows runner.

## Module Structure

Three Gradle modules, all built into a single APK:

| Module | Purpose | Key Quirks |
|--------|---------|------------|
| `app` | Compose UI, services, ContentProviders, audio pipeline | Application class, foreground service, floating window, Room DB, Hilt DI |
| `xposed` | LSPosed/libxposed entry points, AudioRecord hooks, native AAudio hook via shadowhook | **arm64-v8a only**, C++ native code under `src/main/cpp/` |
| `core` | Shared models, constants, ScopeMatcher | **Zero heavy dependencies** — must stay loadable in xposed (injected into other processes). Do NOT add androidx or Hilt here. |

Dependency flow: `app` → `core`, `app` → `xposed` → `core`. The `xposed` module is an `implementation` dependency of `app` (bundled into the APK), not a separate install.

## Key Architecture Decisions

- **Per-process hook via LSPosed scope**: LSPosed loads modules into each app process *after* fork, and only for packages in the module's scope — there is no "hook once in zygote, every app inherits it" path (`initZygote` is just a name). `GlassMicXposedModule` / `LegacyXposedEntry` hook `Application.attach` inside each scoped process, then install the Java and native audio hooks. Checking an app on the Scope page requests LSPosed scope via `libxposed:service` (`LsposedServiceManager.requestScope`). Protected processes (systemui, launcher, GMS, input methods, and GlassMic itself) are still blocklisted in `XposedHookGate.kt` in case they get scoped.
- **PCM pipe via ContentProvider**: `PcmStreamProvider` serves PCM data; `XposedPcmReader` (Java path) and `NativeAAudioHook` (native path) read it. The provider only hands out a pipe if `EffectiveSourceResolver` does not resolve the Binder-verified `callingPackage` to `REAL_MIC` (GlassMic's own UID is exempt for the self-probe), clamps `sr` to 8k–192k and `ch` to 1–2, and caps consumers per package. `RuntimeProvider` likewise trusts `callingPackage`, never the package name a caller sends.
- **Broadcast loop**: `SharedPcmPublisher` decodes on a dedicated `URGENT_AUDIO` thread and suspends (no polling, no WakeLock) while there are no consumers; the partial WakeLock is held only while some app is reading PCM. Source switches swap the reference first and release the old source afterwards — `FileAudioSource.read/reset/seekTo/release` share one lock, so a release waits for an in-flight decode instead of crashing it.
- **Idle cost in hooked processes**: every scoped process runs the native PCM worker and the Kotlin poller. The worker blocks in `poll()` on the pipe plus an eventfd; the poller ticks every 80 ms only around active capture and otherwise blocks in `nativeWaitCapture` (woken by the first audio callback, 5 s heartbeat). Don't reintroduce fixed-interval sleeps here.
- **Dual Xposed API**: Both libxposed API 101 and legacy API 82 are supported. Hook logic is written once against `MethodHooker` (`HookAdapter.kt`: `ModernHooker` / `LegacyHooker`); `AudioRecordHook` and `MediaRecorderHook` expose `install(api, …)` and `installLegacy(…)`. Don't fork per-API copies again. `XposedHookGate` prevents duplicate installation when both entry points fire.
- **Native resampling**: `glass_aaudio.cpp` low-passes source PCM (4th-order Butterworth at 0.45× the target rate) before downsampling and carries the fractional interpolation phase (plus the previous block's last frame) across callbacks, reading exactly the source frames that phase needs. `StreamResampleState` is kept per (stream, path) under `g_buffer_mutex` and reset together with the ring buffer. Upsampling and 48k pass-through keep the stateless path.
- **Secrets at rest**: `AppConfigSerializer` encrypts `tts.ai.api_key` / `llm_api_key` with an Android Keystore AES-GCM key (`SecretCipher`, `enc1:` prefix) on write and decrypts on read; in-memory `AppConfig` is always plaintext. New credential fields must go through `mapSecrets`.
- **Native AAudio hook**: `glass_aaudio.cpp` uses shadowhook (ByteDance) for inline hooking. Links against NDK's `libaaudio.so`.
- **公平运行内存 (ITGSA / HyperOS fair memory)**: `app/.../memory/` adapts the ITGSA memory-fairness contract. `FairMemoryController` registers a runtime receiver for `itgsa.intent.action.TRIM` (+ `.KILL`) on a dedicated HandlerThread, releases memory, and replies over the intent's callback binder (`transact(FIRST_CALL_TRANSACTION, …)`, one-way) inside the system's 3s window. `Application.onTrimMemory`/`onLowMemory` feed the same path. Components that hold droppable memory implement `MemoryReleasable` and register with `MemoryPressureBus` themselves (registration, not injection — injecting them would force the whole audio pipeline to be constructed at process start). **The live audio path is never trimmed**: `PlaybackController` keeps the TTS PCM buffer whenever it's the current source or a consumer is attached.
- **Protobuf Lite**: DataStore uses Proto format. Proto files at `app/src/main/proto/`. Generated Java Lite + Kotlin Lite classes.

## Critical Constraints

- `core` module must remain dependency-free (no androidx, no Hilt). It loads inside hooked processes via the xposed module.
- KSP 2.3 (KSP2 only) with AGP 9 built-in Kotlin and the new DSL — modules do not apply `org.jetbrains.kotlin.android`. `gradle.properties` carries no AGP 9 compatibility opt-outs; don't add them back to paper over a plugin issue.
- Room exports schemas to `app/schemas/`; commit the new JSON with every database version bump.
- `jniLibs.pickFirsts` for `libshadowhook.so` and `libshadowhook_nothing.so` — the .so comes from both the `:xposed` module and the shadowhook AAR; content is identical.
- Resource configurations: only `zh-rCN` and `en` are bundled.
- `minSdk = 29` (Android 10), `targetSdk = 35` (Android 15). `compileSdk` is 37 for `app` (required by current AndroidX) and 35 for `core` / `xposed`.
- Release builds use ProGuard with minification and resource shrinking enabled.

## LSPosed Scope

Default `scope.list` is `system` + `android` (system_server — needed for the package-visibility and volume-key hooks). **Every target app must also be in the LSPosed scope**, and be fully restarted after granting; GlassMic's own whitelist (`ScopeMatcher`) is a second, independent gate.

`MediaRecorder` captures audio inside the system media service, not the app process, so hooking `AudioRecord` alone cannot replace it. `MediaRecorderHook` (installed by both the API 101 and legacy API 82 entries) takes over audio-only `MediaRecorder` sessions and re-encodes from the hooked in-app `AudioRecord`. The Snapchat analysis behind this lives in `docs/`, which is git-ignored, so it is not available to other contributors.
