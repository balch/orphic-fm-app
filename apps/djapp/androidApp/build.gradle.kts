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
    // Service-account JSON is gitignored. Looks for a *.json key (any name — Google's
    // default download is e.g. orphic-dj-<hash>.json) in the repo-root or module .secrets
    // dir, preferring one named play-service-account.json. If none is found GPP falls back
    // to the ANDROID_PUBLISHER_CREDENTIALS env var (CI). Only publish* tasks need it —
    // normal builds are unaffected.
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
    // Upload target track, overridable per-invocation with -PplayTrack=<id>
    // (e.g. closed-beta-1, alpha, beta, production). Defaults to the internal testing
    // track — no review delay; exercises the in-app-update flow for internal testers.
    // To move an EXISTING release between tracks without re-uploading, use the promote
    // task instead — it has its own flags and needs no config here:
    //   ./gradlew :apps:djapp:androidApp:promoteReleaseArtifact \
    //       --from-track internal --promote-track closed-beta-1
    track.set((findProperty("playTrack") as String?) ?: "internal")
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
