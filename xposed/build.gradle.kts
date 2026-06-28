plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.mo.glassmic.xposed"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
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
                    "-DANDROID_STL=c++_shared"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // 当前装的是 CMake 4.1.2
            version = "4.1.2"
        }
    }

    // 当前装的是 NDK 30.0.14904198 rc1
    // 注：rc 版本可能有未发现的 toolchain bug；遇到诡异编译错误时建议补装一个 r26/r27 稳定版替换此行
    ndkVersion = "30.0.14904198"

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
