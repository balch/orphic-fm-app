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
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Access version catalog
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    // Silences the "expect/actual classes are in Beta" warning project-wide.
    // See https://youtrack.jetbrains.com/issue/KT-61573 — the feature is stable in
    // practice; we accept it explicitly so builds aren't polluted with the warning.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        compileSdk = libs.findVersion("android-compileSdk").get().requiredVersion.toInt()
        minSdk = libs.findVersion("android-minSdk").get().requiredVersion.toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    iosArm64()
    iosSimulatorArm64()

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



// Exclude libremidi-panama from test configurations (requires JVM 22+ with Panama FFI)
configurations.matching { it.name.contains("test", ignoreCase = true) }.all {
    exclude(group = "dev.atsushieno", module = "libremidi-panama")
}
