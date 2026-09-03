// This included build asks for jvmToolchain(21) of its own, and an included build does
// not inherit the root's toolchain resolver — without this it auto-provisions with no
// repository declared, which Gradle 10 turns from a warning into an error.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "convention"
