// NewPipe Extractor (GPL-3.0-or-later), built from the vendored sources in
// third_party/NewPipeExtractor: YouTube search, trending, channels and stream URLs for the
// native XR YouTube app, without a web page.
plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.0"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val upstream = rootProject.file("third_party/NewPipeExtractor")

sourceSets {
    main {
        java.setSrcDirs(listOf(upstream.resolve("extractor/src/main/java"), upstream.resolve("timeago-parser/src/main/java"), file("src/main/java"),
            layout.buildDirectory.dir("generated/sources/proto/main/java")))
        resources.setSrcDirs(listOf(upstream.resolve("extractor/src/main/resources")))
    }
}

sourceSets.named("main") {
    extensions.getByName<SourceDirectorySet>("proto").srcDir(upstream.resolve("extractor/src/main/proto"))
}

tasks.named("compileJava") { dependsOn("generateProto") }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:none")
}

dependencies {
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.google.protobuf:protobuf-javalite:4.36.2")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.mozilla:rhino-engine:1.8.1")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
    generateProtoTasks {
        all().forEach { task -> task.builtins { named("java") { option("lite") } } }
    }
}

tasks.register<JavaExec>("ytSmoke") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.hyperxr.youtube.Smoke")
}
