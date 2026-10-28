import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// HyperConnect: installed on a second phone, which then works as HyperXR's controller over
// Bluetooth (orientation from its gyroscope + accelerometer, touchscreen as touchpad and buttons).
// Shipped inside the HyperXR APK so it can be shared to the other phone.
android {
    namespace = "com.hyperxr.connect.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hypermagic.connect"
        minSdk = 23
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
}
