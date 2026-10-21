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
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging { jniLibs { useLegacyPackaging = false } }

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

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.arcore)
    implementation(libs.tflite)
    implementation(libs.tflite.gpu)
    implementation(libs.tflite.gpu.api)
    implementation(libs.openxr.loader)
    implementation(libs.webkit)
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
