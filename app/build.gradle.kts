import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Адрес сервера (хост:порт) и токен проектора по умолчанию зашиваются в APK; на проекторе их
// меняют только через adb (ConfigReceiver, install.sh). versionCode растёт с
// каждой сборкой (его задаёт сборка релиза (.github/workflows/release.yml)): по нему проектор обновляется с сервера.
val server = providers.gradleProperty("screennode.server").get()
val projectorToken = providers.gradleProperty("screennode.token").getOrElse("")
val buildVersion = providers.gradleProperty("screennode.versionCode").getOrElse("1").toInt()
require(Regex("^[A-Za-z0-9.-]+:[0-9]{1,5}$").matches(server)) { "screennode.server должен быть хост:порт: $server" }
require(Regex("^[A-Za-z0-9._~-]*$").matches(projectorToken)) { "screennode.token: только [A-Za-z0-9._~-]" }

android {
    namespace = "space.akiba.screen_node"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "space.akiba.screen_node"
        minSdk = 24
        targetSdk = 36
        versionCode = buildVersion
        versionName = "0.2.$buildVersion"
        buildConfigField("String", "SERVER", "\"$server\"")
        buildConfigField("String", "PROJECTOR_TOKEN", "\"$projectorToken\"")
        // Проекторы и ТВ-приставки — ARM; x86-сборки libjingle нужны только эмуляторам.
        // Меньше APK — надёжнее установка по Wi-Fi ADB.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        getByName("debug") {
            // Ключ лежит в keystore/ (в сборке релиза — из секрета репозитория) — подпись стабильна.
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    // WebSocket-клиент общий со службой пульта (модуль remote).
    sourceSets.getByName("main").java.srcDir("../shared/src/main/java")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Сборка libwebrtc от Stream: регулярно обновляется вслед за Chromium, пакет org.webrtc.
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.16.0")
}
