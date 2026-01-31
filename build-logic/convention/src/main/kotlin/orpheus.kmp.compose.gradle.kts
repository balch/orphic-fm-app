import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Convention plugin for Kotlin Multiplatform modules with Compose support.
 * This plugin configures:
 * - Kotlin Multiplatform with Android library, JVM targets
 * - Compose Multiplatform
 * - Common dependencies for Compose-based feature modules
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
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

        androidResources {
            enable = true
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
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
