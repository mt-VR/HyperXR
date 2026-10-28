plugins {
    alias(libs.plugins.android.library)
}

// Minecraft Java Edition launcher: a port of Pojlib (QuestCraft's launcher library, LGPLv3) without
// Unity. Java code and the native glue (JVM launcher, GLFW/EGL bridge) are built from source here;
// the Java runtime, LWJGL natives and the GL translation layer are downloaded on the phone (see
// com.hypermagic.minecraft.McRuntime), so the APK carries no third-party binaries for this.
android {
    namespace = "com.hypermagic.minecraft"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += (project.findProperty("hxAbi")?.toString()?.split(",") ?: listOf("arm64-v8a", "x86_64")) }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.gson)
    implementation(libs.commons.io)
    implementation(libs.commons.codec)
    implementation(libs.androidx.annotation)
}
