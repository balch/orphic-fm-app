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

        androidResources {
            enable = true
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.findLibrary("compose-runtime").get())
            implementation(libs.findLibrary("compose-foundation").get())
            implementation(libs.findLibrary("compose-material3").get())
            implementation(libs.findLibrary("ui").get())
            implementation(libs.findLibrary("compose-components-resources").get())
            implementation(libs.findLibrary("compose-material-icons").get())
            implementation(libs.findLibrary("androidx-lifecycle-viewmodelCompose").get())
            implementation(libs.findLibrary("androidx-lifecycle-runtimeCompose").get())
            implementation(libs.findLibrary("metrox-viewmodel-compose").get())
            implementation(libs.findLibrary("compose-ui-tooling-preview").get())
            implementation(libs.findLibrary("kmlogging").get())
            implementation(libs.findLibrary("liquid").get())

            if (project.path.startsWith(":features")) {
                implementation(project(":core:audio"))
                implementation(project(":core:foundation"))
                implementation(project(":ui:widgets"))
                implementation(project(":ui:theme"))
            }

        }
        
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// For Compose previews with AGP 9.0 and the android KMP library plugin
dependencies {
    androidRuntimeClasspath(libs.findLibrary("compose-ui-tooling").get())
}
// Exclude libremidi-panama from test configurations (requires JVM 22+, we use JVM 21)
configurations.matching { it.name.contains("test", ignoreCase = true) }.all {
    exclude(group = "dev.atsushieno", module = "libremidi-panama")
}
