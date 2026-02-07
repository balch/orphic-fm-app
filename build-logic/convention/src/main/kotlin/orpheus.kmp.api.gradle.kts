import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Convention plugin for Kotlin Multiplatform API-only modules.
 * Lightweight version without Metro DI or KSP - suitable for shared interfaces and types.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
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

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.findLibrary("kmlogging").get())
            implementation(libs.findLibrary("kotlinx-serialization-json").get())
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
