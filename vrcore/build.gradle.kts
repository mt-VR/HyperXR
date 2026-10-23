import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Companion APK "HyperXR Daydream": takes the package name of Google VR Services, which the GVR SDK
// binds for the Daydream controller and asks for the viewer's lens parameters. Google's other VR
// Services checks verify Google's signature, fail for this APK, and the SDK then uses its own
// built-in code exactly as on a phone without VR Services. Shipped inside the HyperXR APK.
android {
    namespace = "com.hyperxr.vrcore"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.google.vr.vrcore"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-hyperxr"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/hyperxr-debug.jks")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }

    buildFeatures { aidl = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
