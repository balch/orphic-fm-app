import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Convention plugin for Kotlin Multiplatform library modules with KSP and Metro support.
 * This plugin configures:
 * - Kotlin Multiplatform with Android library, JVM targets
 * - KSP for annotation processing
 * - Metro for dependency injection
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.google.devtools.ksp")
    id("dev.zacsweers.metro")
}

// Access version catalog
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    // Android configuration using the new androidLibrary block for AGP 9.0
    androidLibrary {
        compileSdk = libs.findVersion("android-compileSdk").get().requiredVersion.toInt()
        minSdk = libs.findVersion("android-minSdk").get().requiredVersion.toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.findLibrary("kmlogging").get())
            implementation(libs.findLibrary("metro-runtime").get())
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}



// Exclude libremidi-panama from test configurations (requires JVM 22+, we use JVM 21)
configurations.matching { it.name.contains("test", ignoreCase = true) }.all {
    exclude(group = "dev.atsushieno", module = "libremidi-panama")
}
