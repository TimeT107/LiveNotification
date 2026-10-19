plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.livenotification"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.livenotification"
        minSdk = 35
        targetSdk = 36
        versionCode = 18
        versionName = "9.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 用本机默认调试签名给 release 包签名。
    // 手机上现装的 com.example.livenotification 就是 debug 签名（SHA-256 8dcf3a4c…），
    // 所以 release 包可以直接覆盖安装，不用先卸载（沿用锁屏岛模块 tools/deploy.ps1 的做法）。
    val selfTestKeyStore = rootProject.file(
        System.getProperty("user.home") + "/.android/debug.keystore"
    )
    signingConfigs {
        if (selfTestKeyStore.exists()) {
            create("selfTest") {
                storeFile = selfTestKeyStore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            if (selfTestKeyStore.exists()) {
                signingConfig = signingConfigs.getByName("selfTest")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // 锁屏岛的设置页用 BuildConfig.VERSION_NAME 显示模块版本
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // 中文文本识别（Bundled 版本，模型包含在 APK 中）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.compose.material:material-icons-extended")

    // ===== 锁屏岛（合并自 LockScreenIsland 模块）=====
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Xposed API 只在编译期需要；运行期由 LSPosed 框架提供（绝不能打进 APK）
    compileOnly("de.robv.android.xposed:api:82")

    ksp("androidx.room:room-compiler:2.8.3")
}