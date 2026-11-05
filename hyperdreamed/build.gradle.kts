import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// HyperDreamed Patcher: patches the user's own Daydream and Google VR Services APKs for phones
// that were never Daydream-ready (Android 11 to 16), and turns a second phone into a Daydream
// controller over Bluetooth LE. A separate app: it does not need HyperMagic.
android {
    namespace = "com.hypermagic.hyperdreamed"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hypermagic.hyperdreamed"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/hyperxr-debug.jks")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }

    buildFeatures { compose = true; aidl = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    // signs the patched APKs (APK Signature Scheme v1-v3)
    implementation("com.android.tools.build:apksig:8.13.2")
    // Shizuku: an optional helper (shell UID) that reads the crash log and installs silently
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
