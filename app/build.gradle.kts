import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.hyperxr"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.hyperxr"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // One debug key for every build (kept in the repository; it protects nothing), so a new APK
    // installs over the previous one instead of failing with "conflicts with an existing package".
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/hyperxr-debug.jks")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        prefab = true
        aidl = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Compressed native libs: GeckoView's libxul alone is ~150 MB uncompressed.
    packaging { jniLibs { useLegacyPackaging = true } }
    // MediaPipe memory-maps the model from the APK: it must be stored uncompressed.
    androidResources { noCompress += listOf("task", "so", "tflite") }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/hyperx-assets"))
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

/** Packs every directory in /games into a validated .hyperx package bundled as an asset. */
val packHyperx = tasks.register<JavaExec>("packHyperx") {
    group = "hyperxr"
    val gamesDir = rootProject.file("games")
    val outDir = layout.buildDirectory.dir("generated/hyperx-assets/packages")
    inputs.dir(gamesDir)
    outputs.dir(outDir)
    classpath = project(":core").extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("com.hyperxr.sdk.PackToolKt")
    args("packall", gamesDir.absolutePath, outDir.get().asFile.absolutePath)
    dependsOn(":core:classes")
}
tasks.named("preBuild") { dependsOn(packHyperx) }

/**
 * Library the on-device patcher puts into OpenXR / Quest games: the Khronos OpenXR loader, from
 * the same Maven artifact HyperXR links, for 64- and 32-bit ARM. (The VrApi adapter is not
 * bundled: only its sources are in third_party/compatibility-layer, see README there.)
 */
val compatAar: Configuration by configurations.creating
dependencies { compatAar("org.khronos.openxr:openxr_loader_for_android:${libs.versions.openxrLoader.get()}@aar") }
val compatAssets = tasks.register<Sync>("compatAssets") {
    group = "hyperxr"
    from(compatAar.elements.map { files -> files.map { zipTree(it) } }) {
        include("jni/arm64-v8a/libopenxr_loader.so", "jni/armeabi-v7a/libopenxr_loader.so")
        eachFile { path = "compat/" + path.removePrefix("jni/") }
    }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("generated/compat-assets"))
}
android.sourceSets["main"].assets.srcDir(compatAssets)

/** Companion APKs shipped inside HyperXR: HyperXR Daydream (:vrcore) and HyperConnect (:hyperconnect). */
val daydreamAssets = tasks.register<Sync>("daydreamAssets") {
    group = "hyperxr"
    dependsOn(":vrcore:assembleDebug", ":hyperconnect:assembleDebug")
    from(project(":vrcore").layout.buildDirectory.file("outputs/apk/debug/vrcore-debug.apk")) { rename { "hyperxr-daydream.apk" }; into("compat") }
    from(project(":hyperconnect").layout.buildDirectory.file("outputs/apk/debug/hyperconnect-debug.apk")) { rename { "hyperconnect.apk" }; into("compat") }
    into(layout.buildDirectory.dir("generated/daydream-assets"))
}
android.sourceSets["main"].assets.srcDir(daydreamAssets)

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.arcore)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.litert)
    // GPU delegate: the hand mask runs on the GPU while the NPU does hands and depth
    implementation(libs.litert.gpu)
    implementation(libs.litert.gpu.api)
    // native YouTube: NewPipe Extractor built from source, played with Media3 ExoPlayer
    implementation(project(":newpipe"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.openxr.loader)
    // signs games patched on the phone (APK Signature Scheme v1-v3)
    implementation("com.android.tools.build:apksig:8.13.2")
    implementation(libs.webkit)
    // Browser engine (arm64 only; other ABIs fall back to the system WebView at runtime).
    implementation(libs.geckoview)
    implementation(libs.browser)
    testImplementation(libs.junit)
}

// Host JNI bridge tests: ./gradlew :app:testDebugUnitTest -Phyperxr.hostBridge=<dir with libhyperxr.so>
tasks.withType<Test>().configureEach {
    val dir = providers.gradleProperty("hyperxr.hostBridge").orNull
    if (dir != null) {
        systemProperty("hyperxr.hostBridge", dir)
        systemProperty("java.library.path", dir)
    }
}
