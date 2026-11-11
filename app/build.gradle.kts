plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "io.mo.glassmic"
    // 最新 AndroidX（core 1.19 / lifecycle 2.11 / Compose 1.12）要求以 API 37 编译；
    // targetSdk 不变，运行时行为不受影响。
    compileSdk = 37

    defaultConfig {
        applicationId = "io.mo.glassmic"
        minSdk = 29
        targetSdk = 35
        versionCode = 30
        versionName = "1.5.0"
        // 只打包中英文资源；resourceConfigurations 在 AGP 9 新 DSL 中已弃用，改用 localeFilters
        androidResources.localeFilters += listOf("zh-rCN", "en")
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 仓库根目录 tests/ 下的回归检查（纯 JVM，不依赖设备）作为单元测试源码编译，
    // 由 src/test 里的 JUnit 入口逐个调用：.\gradlew.bat :app:testDebugUnitTest
    sourceSets.getByName("test").java.srcDirs("../tests/audio", "../tests/hooks")

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES"
        )
        // shadowhook 的 .so 会同时从 :xposed 子模块 与 shadowhook AAR 两条路径进来；
        // 两者内容一致，让 merge 任务取其一即可（不能用 excludes，那样最终 apk 里就没有了）
        jniLibs {
            pickFirsts += setOf(
                "**/libshadowhook.so",
                "**/libshadowhook_nothing.so"
            )
        }
    }
}

kotlin { jvmToolchain(17) }

// Room 导出每个数据库版本的 schema（app/schemas/，需提交），迁移改动可以对照历史 diff，
// 也能用 MigrationTestHelper 验证。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ============== Proto Lite ==============
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.36.2"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                // java lite
                create("java") { option("lite") }
                // kotlin lite (官方支持，依赖 protobuf-kotlin-lite)
                create("kotlin") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    // xposed 模块作为 library 打入同一个 APK——libxposed API 101 通过 META-INF/xposed/java_init.list 找入口
    implementation(project(":xposed"))
    compileOnly(libs.libxposed.api)
    compileOnly(libs.legacy.xposed.api)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.documentfile)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.navigation)
    debugImplementation(libs.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore + Proto
    implementation(libs.datastore)
    implementation(libs.protobuf.javalite)
    implementation(libs.protobuf.kotlin.lite)

    // 协程
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)

    // libxposed service（用于向 LSPosed 动态申请应用作用域）
    implementation(libs.libxposed.service)
}
