plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.mo.glassmic.xposed"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-fvisibility=hidden",
                    "-fvisibility-inlines-hidden",
                    "-fno-exceptions",
                    "-fno-rtti",
                    "-Wall"
                )
                arguments += listOf(
                    // 静态 STL：只有 libglassmic_native.so 用到 libc++（shadowhook 是纯 C），
                    // 静态链接后不再打包 4KB 对齐的 libc++_shared.so，满足 Android 15+ 16KB 页要求
                    "-DANDROID_STL=c++_static"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // 稳定版 CMake，与 NDK r26b 搭配；本项目 AGP 为 9.x（见 gradle/libs.versions.toml）
            version = "3.22.1"
        }
    }

    // 稳定版 NDK r26b，规避 rc 版潜在 toolchain bug，并消除 CXX5304（SDK XML v4）警告
    ndkVersion = "26.1.10909125"

    buildFeatures {
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    compileOnly(libs.libxposed.api)
    compileOnly(libs.legacy.xposed.api)

    // shadowhook：含 AAR + arm64-v8a 的 libshadowhook.so + prefab 头
    implementation(libs.shadowhook)
}
