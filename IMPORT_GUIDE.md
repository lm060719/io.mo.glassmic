# Android Studio 导入指南

## 环境要求

- **Android Studio**: Ladybug (2024.2.1) 或更新（必须支持 Kotlin 2.1）
- **JDK**: 17（项目用 `jvmToolchain(17)`，Android Studio 自带的 JBR 即可）
- **Android SDK**:
  - 必装：API 35 (Android 15)
  - 推荐：API 36 (Android 16)
  - Build-Tools 35.0.0
  - Platform-Tools 最新版

## 导入步骤

1. **打开 Android Studio** → `File → Open` → 选择 `GlassMic` 目录（不是任何子目录）。
2. **等待 Gradle Sync**：第一次会下载 Gradle 8.11.1 与所有依赖，耗时 5-15 分钟（视网络）。
3. **Sync 完成后**，Build Variants 面板里会看到三个 module：`app`、`xposed`、`core`。
4. **Run** → 选择 `app` → 部署到设备/模拟器。

## 常见 Sync 报错与解决

### ❌ `Could not resolve io.github.libxposed:api:101.0.1`

确认 `settings.gradle.kts` 的 `repositories` 中包含 `mavenCentral()`。libxposed API 101 已发布到 Maven Central，本项目通过：

```kotlin
compileOnly(libs.libxposed.api)
```

引用它。不要再手动引入旧版 Xposed API 82 或旧入口文件。


