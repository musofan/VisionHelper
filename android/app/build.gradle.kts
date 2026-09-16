plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.visionhelper.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.visionhelper.probe"
        minSdk = 29          // Quest 2 为 Android 10(API29)，Quest 3/Pico 4 为 Android 12/12L，取 29 可全兼容
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-spike"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// 探针 App 故意零第三方依赖：只用 Android Framework(Camera2 / AudioRecord)，
// 以验证「最小可安装 APK」在最坏网络环境下也能构建成功。
dependencies { }