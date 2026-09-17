plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.visionhelper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.visionhelper"
        minSdk = 29          // Quest 2 为 Android 10(API29)，Quest 3/Pico 4 为 Android 12/12L，取 29 可全兼容
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0"
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

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

// 依赖策略（调研结论）：
//   - 唯一第三方依赖是 ONNX Runtime Android：POM 无传递依赖，AAR 自带各 ABI 的 native 库，
//     因此本工程仍然保持零 AndroidX、零反射框架，可在任意 Android 系头显上构建安装。
//   - LLM 走自研 HTTP 客户端、UI 用原生 View、持久化用文件系统，均不引入额外依赖。
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // 后处理数学是纯 Kotlin（无 Android 依赖），因此可以在 JVM 上直接单测
    testImplementation("junit:junit:4.13.2")
}