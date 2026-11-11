plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pureair.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pureair.tv"
        minSdk = 24
        targetSdk = 29 // 深度针对 Android 10 (索尼 X91J) 优化，权限与后台服务策略极度友好
        versionCode = 18
        versionName = "1.2.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    // 本地协议测试只跳过 Android 日志等平台空实现，密码学和网络逻辑照常执行。
    testOptions.unitTests.isReturnDefaultValues = true

    lint {
        // 此项目仅侧载 APK，保留现有 targetSdk；Google Play 上架版本要求不适用。
        disable.add("ExpiredTargetSdkVersion")
    }
}

dependencies {
    // AndroidX & UI
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Kotlin 协程异步框架
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // mDNS / Bonjour 局域网广播服务
    implementation("org.jmdns:jmdns:3.5.8")

    // 加密套件 (BouncyCastle 用于 Curve25519, Ed25519, ChaCha20-Poly1305, AES-128-CBC)
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")

    // Plist 解析库 (iOS AirPlay 数据通信常用 plist 格式)
    implementation("com.googlecode.plist:dd-plist:1.27")

    // 单元测试
    testImplementation("junit:junit:4.13.2")
}
