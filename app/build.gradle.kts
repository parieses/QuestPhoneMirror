plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qpm.mirror"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qpm.mirror"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        // 侧载场景下复用 debug 密钥签名 release，使 CI 的 assembleRelease 可产出可用 APK。
        // 正式发布前请替换为正式密钥（storeFile/storePassword/keyAlias/keyPassword）。
        create("release") {
            val d = signingConfigs.getByName("debug")
            storeFile = d.storeFile
            storePassword = d.storePassword
            keyAlias = d.keyAlias
            keyPassword = d.keyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":protocol"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
