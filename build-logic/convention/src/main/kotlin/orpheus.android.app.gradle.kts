import java.util.Properties

/**
 * Convention plugin for Android application modules (Orpheus, DJ App, etc.).
 * Configures shared build settings: compileSdk, signing, R8, NDK, build types.
 */
plugins {
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.zacsweers.metro")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun execGit(vararg args: String): String? = try {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}

// Git-derived version is used for distributable variants only (release, benchmark).
// Debug variants pin to versionCode = 1 so installs aren't invalidated by every commit
// or by switching branches where the rev-list count goes backwards.
val gitVersionCode = execGit("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val gitVersionName = execGit("describe", "--tags", "--always", "--dirty") ?: "1.0"

// Build types that share debug semantics: pinned version + ".debug" applicationId
// suffix so they coexist on-device with release.
val debugLikeBuildTypes = setOf("debug", "debugRelease")

android {
    compileSdk = libs.findVersion("android-compileSdk").get().requiredVersion.toInt()

    defaultConfig {
        minSdk = libs.findVersion("android-minSdk").get().requiredVersion.toInt()
        targetSdk = libs.findVersion("android-targetSdk").get().requiredVersion.toInt()

        versionCode = 1
        versionName = "dev"

        // Opt-in extra ABIs for local installs, e.g. -PextraAbis=armeabi-v7a for a
        // Chromecast with Google TV (32-bit only). Distribution builds stay 64-bit.
        val extraAbis = (findProperty("extraAbis") as String?)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            .orEmpty()

        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64") + extraAbis
        }
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    val hasKeystoreProps = keystorePropsFile.exists()

    if (hasKeystoreProps) {
        val keystoreProps = Properties().apply {
            keystorePropsFile.inputStream().use { load(it) }
        }
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasKeystoreProps) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("debugRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/DEPENDENCIES"
        }
    }

    // debugRelease inherits debug's resource overrides (app label, launcher tint, etc.)
    // so the two build types render identically on the launcher.
    sourceSets {
        getByName("debugRelease") {
            res.directories.add("src/debug/res")
        }
    }
}

// JVM toolchain for the app's Kotlin compilation. AGP 9's built-in Kotlin registers the
// project `kotlin` extension (KotlinAndroidProjectExtension), but no type-safe `kotlin {}`
// accessor is generated for this precompiled script plugin. A bare nested `kotlin { }` block
// compiles (it binds to Project.kotlin via outer scope) yet the IDE flags `jvmToolchain` as
// unresolved — configure it through the typed extension API so compiler and IDE both resolve.
extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
    jvmToolchain(17)
}

androidComponents {
    onVariants { variant ->
        if (variant.buildType !in debugLikeBuildTypes) {
            variant.outputs.forEach { output ->
                output.versionCode.set(gitVersionCode)
                output.versionName.set(gitVersionName)
            }
        }
    }
}

val versionTag = gitVersionName.removePrefix("v").replace(Regex("-\\d+-g[0-9a-f]+(-dirty)?"), "")
afterEvaluate {
    val archivesBase = base.archivesName.get()
    base.archivesName.set("$archivesBase-v$versionTag")
}
