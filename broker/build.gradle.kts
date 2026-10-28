plugins {
    alias(libs.plugins.android.application)
}

// Optional companion APK: makes OpenXR apps find the HyperXR runtime on phones without any
// OpenXR broker. Kept out of the main APK because its provider authority may already exist on
// some devices, and a duplicate authority makes Android refuse the whole installation.
android {
    namespace = "com.hyperxr.broker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hypermagic.broker"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
