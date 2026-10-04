import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Служба веб-пульта — отдельное приложение без экранов (см. RemoteService). Адрес сервера и
// токен проектора по умолчанию те же, что у akiba (на проекторе адрес меняют через adb).
// updateKey — открытый ключ подписи обновлений сервера: служба пульта ставит только
// обновления, подписанные этим ключом (см. Updater). versionCode задаёт сборка релиза (.github/workflows/release.yml).
val server = providers.gradleProperty("screennode.server").get()
val projectorToken = providers.gradleProperty("screennode.token").getOrElse("")
val updateKey = providers.gradleProperty("screennode.updateKey").getOrElse("")
val buildVersion = providers.gradleProperty("screennode.versionCode").getOrElse("1").toInt()
require(Regex("^[A-Za-z0-9.-]+:[0-9]{1,5}$").matches(server)) { "screennode.server должен быть хост:порт: $server" }
require(Regex("^[A-Za-z0-9._~-]*$").matches(projectorToken)) { "screennode.token: только [A-Za-z0-9._~-]" }
require(Regex("^[A-Za-z0-9+/=]*$").matches(updateKey)) { "screennode.updateKey: base64" }

android {
    namespace = "space.akiba.remote"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "space.akiba.remote"
        minSdk = 24
        targetSdk = 36
        versionCode = buildVersion
        versionName = "0.2.$buildVersion"
        buildConfigField("String", "SERVER", "\"$server\"")
        buildConfigField("String", "PROJECTOR_TOKEN", "\"$projectorToken\"")
        buildConfigField("String", "UPDATE_KEY", "\"$updateKey\"")
    }

    signingConfigs {
        getByName("debug") {
            // Тот же ключ, что у akiba (keystore/debug.keystore).
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

    // WebSocket-клиент общий с akiba.
    sourceSets.getByName("main").java.srcDir("../shared/src/main/java")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.16.0")
}
