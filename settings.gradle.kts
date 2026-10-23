pluginManagement {
    repositories {
        google()
        // Google-hosted mirror of Maven Central (avoids repo1 rate limiting); Maven Central stays as fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google-hosted mirror of Maven Central (avoids repo1 rate limiting); Maven Central stays as fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        // GeckoView (Mozilla's Firefox engine, the engine Wolvic is built on).
        maven("https://maven.mozilla.org/maven2/") { content { includeGroup("org.mozilla.geckoview") } }
        // nanojson (TeamNewPipe fork), built from source by JitPack, for the YouTube extractor.
        maven("https://jitpack.io") { content { includeGroup("com.github.TeamNewPipe") } }
    }
}
rootProject.name = "HyperXR"
include(":core", ":app", ":broker", ":newpipe", ":vrcore", ":hyperconnect")
