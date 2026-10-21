import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

/** `./gradlew :core:hyperx --args="inspect file.hyperx"` runs the SDK command line tool. */
tasks.register<JavaExec>("hyperx") {
    group = "hyperxr"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.hyperxr.sdk.PackToolKt")
}
