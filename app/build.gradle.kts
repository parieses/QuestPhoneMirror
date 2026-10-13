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
        versionCode = (project.findProperty("VERSION_CODE") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("VERSION_NAME") as String?) ?: "0.1"
    }

    signingConfigs {
        // 专用 release 密钥（非 debug 签名）。
        // 重要：澎湃OS / MIUI 会拒绝 debug 签名应用获取无障碍、悬浮窗等敏感权限，
        // 因此 release 必须使用独立密钥，绝不能复用 debug 密钥（否则装到小米/红米上
        // 无障碍服务连开关都开不了）。详见 README “小米/澎湃OS 无障碍”章节。
        // 密钥文件: app/release.keystore（由开发者本地用 keytool 生成，已被 .gitignore 忽略，绝不入库），
        // 密码等通过 RELEASE_* 属性提供：CI 从 GitHub Secrets 注入，本地放 local.properties 或命令行 -P。
        create("release") {
            val ksFile = rootProject.file(
                (project.findProperty("RELEASE_STORE_FILE") as String?) ?: "app/release.keystore"
            )
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = (project.findProperty("RELEASE_STORE_PASSWORD") as String?) ?: ""
                keyAlias = (project.findProperty("RELEASE_KEY_ALIAS") as String?) ?: "qpm"
                keyPassword = (project.findProperty("RELEASE_KEY_PASSWORD") as String?) ?: ""
            } else {
                // 未生成专用密钥时回退到 debug 密钥：仅保证本地/CI 能构建，
                // 但产物会被澎湃OS 拒绝授予无障碍权限，仅供调试。
                val d = signingConfigs.getByName("debug")
                storeFile = d.storeFile
                storePassword = d.storePassword
                keyAlias = d.keyAlias
                keyPassword = d.keyPassword
            }
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
