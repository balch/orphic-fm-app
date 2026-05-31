plugins {
    id("orpheus.android.app")
    alias(libs.plugins.play.publisher)
}

android {
    namespace = "org.balch.djapp"

    lint {
        baseline = file("lint-baseline.xml")
    }

    defaultConfig {
        applicationId = "org.balch.djapp"
        base.archivesName = "djapp"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DEURORACK_DIR=${File(System.getProperty("user.home"), "Source/eurorack").absolutePath}"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("debugRelease") {
            manifest.srcFile("src/debug/AndroidManifest.xml")
        }
    }
}

// Google Play Developer API publishing (Gradle Play Publisher).
// Run: ./gradlew :apps:djapp:androidApp:publishReleaseBundle
play {
    // Service-account JSON is gitignored. Drop the key at the path below to enable
    // publishing; if it's absent, GPP falls back to the ANDROID_PUBLISHER_CREDENTIALS
    // env var (CI). Only publish* tasks need it — normal builds are unaffected.
    // Looks for a *.json key (any name — Google's default download is e.g.
    // orphic-dj-<hash>.json) in either the repo-root or module .secrets dir, preferring
    // one named play-service-account.json if present. If none is found GPP falls back to
    // the ANDROID_PUBLISHER_CREDENTIALS env var (CI). Only publish* tasks need it.
    val serviceAccountKey = listOf(
        rootProject.file(".secrets"),
        rootProject.file("apps/djapp/play-store/.secrets"),
    )
        .flatMap { it.listFiles()?.toList().orEmpty() }
        .filter { it.extension == "json" }
        .let { keys -> keys.firstOrNull { it.name == "play-service-account.json" } ?: keys.firstOrNull() }
    if (serviceAccountKey != null) {
        serviceAccountCredentials.set(serviceAccountKey)
    }
    // Default upload target: the internal testing track — no review delay, and it
    // exercises the in-app-update flow for internal testers. Override per-invocation
    // with `-Ptrack=...` is not wired; edit here or pass via a future property if needed.
    track.set("internal")
    // Upload the .aab, never per-ABI APKs.
    defaultToAppBundles.set(true)
}

dependencies {
    implementation(projects.apps.djapp)
    implementation(project(":core:foundation"))
    implementation(project(":ui:widgets"))
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.compose.foundation)
    implementation(project(":core:plugins:pulsar"))
    implementation(project(":core:plugins:dj"))
    implementation(project(":core:plugins:reverb"))
    implementation(project(":core:plugins:horn"))
    implementation(project(":core:plugins:distortion"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.media3.session)
    implementation(libs.kmlogging)
    implementation(libs.metrox.viewmodel.compose)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.glance.appwidget)
    // Glance preview tooling — debug-only so it never ships in release. The
    // @Preview composables live in src/debug (org.balch.djapp.widget).
    debugImplementation(libs.androidx.glance.preview)
    debugImplementation(libs.androidx.glance.appwidget.preview)
    debugImplementation(libs.compose.ui.tooling)
}
