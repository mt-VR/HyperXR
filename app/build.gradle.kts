import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** The ABIs this APK is built for (-PhxAbi=arm64-v8a,...). */
val hxAbis: List<String> = project.findProperty("hxAbi")?.toString()?.split(",") ?: listOf("arm64-v8a", "x86_64")

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
        applicationId = "com.hypermagic"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.0"
        // -PhxAbi=arm64-v8a builds a phone-only APK (x86_64 is for emulators); -PhxAbi=armeabi-v7a
        // the APK for phones with a 32-bit Android (they refuse an arm64-only APK as "invalid package")
        ndk { abiFilters += hxAbis }
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
    androidResources { noCompress += listOf("task", "so", "tflite", "onnx") }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/hyperx-assets"))
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
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

// Robolectric's Android jar comes through Gradle (its own downloader can't use the proxy)
val robolectricSdk: Configuration by configurations.creating

/** Companion APKs shipped inside HyperXR: HyperXR Daydream (:vrcore), HyperConnect (:hyperconnect) and the OpenXR broker (:broker). */
val daydreamAssets = tasks.register<Sync>("daydreamAssets") {
    group = "hyperxr"
    dependsOn(":vrcore:assembleDebug", ":hyperconnect:assembleDebug", ":broker:assembleDebug")
    from(project(":vrcore").layout.buildDirectory.file("outputs/apk/debug/vrcore-debug.apk")) { rename { "hyperxr-daydream.apk" }; into("compat") }
    from(project(":hyperconnect").layout.buildDirectory.file("outputs/apk/debug/hyperconnect-debug.apk")) { rename { "hyperconnect.apk" }; into("compat") }
    from(project(":broker").layout.buildDirectory.file("outputs/apk/debug/broker-debug.apk")) { rename { "hyperxr-broker.apk" }; into("compat") }
    into(layout.buildDirectory.dir("generated/daydream-assets"))
}
android.sourceSets["main"].assets.srcDir(daydreamAssets)

dependencies {
    implementation(project(":core"))
    // Minecraft Java Edition (QuestCraft / GorillaCraft): Pojlib port, see minecraft/
    implementation(project(":minecraft"))
    // Pessoas (Supabase): REST + Realtime websocket (calls use GeckoView's own WebRTC)
    implementation(libs.okhttp)
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
    // the hand segmenter's prompt decoder (EfficientViT-SAM) runs from ONNX, exactly as exported
    implementation(libs.onnxruntime.android)
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
    // Browser engine, one build per ABI: arm64, or 32-bit ARM for the 32-bit APK (an x86_64
    // emulator build falls back to the system WebView at runtime).
    if ("armeabi-v7a" in hxAbis && "arm64-v8a" !in hxAbis) implementation(libs.geckoview.v7a) else implementation(libs.geckoview)
    implementation(libs.browser)
    testImplementation(libs.junit)
    // panel previews (UiPreviewTest): real Android Canvas drawing on the PC
    testImplementation("org.robolectric:robolectric:4.16")
    robolectricSdk("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
}

// Host JNI bridge tests: ./gradlew :app:testDebugUnitTest -Phyperxr.hostBridge=<dir with libhyperxr.so>
tasks.withType<Test>().configureEach {
    // panel previews: ./gradlew :app:testDebugUnitTest --tests '*UiPreviewTest' -Phyperxr.previews=<out dir>
    providers.gradleProperty("hyperxr.previews").orNull?.let {
        systemProperty("hyperxr.previews", it)
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", robolectricSdk.singleFile.parentFile.absolutePath)
    }
    val dir = providers.gradleProperty("hyperxr.hostBridge").orNull
    if (dir != null) {
        systemProperty("hyperxr.hostBridge", dir)
        systemProperty("java.library.path", dir)
    }
}
