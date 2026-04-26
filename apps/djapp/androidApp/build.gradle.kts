plugins {
    id("orpheus.android.app")
}

android {
    namespace = "org.balch.djapp"

    lint {
        baseline = file("lint-baseline.xml")
    }

    defaultConfig {
        applicationId = "org.balch.djapp"

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
}

dependencies {
    implementation(projects.apps.djapp)
    implementation(project(":core:foundation"))
    implementation(project(":core:plugins:pulsar"))
    implementation(project(":core:plugins:dj"))
    implementation(project(":core:plugins:reverb"))
    implementation(project(":core:plugins:horn"))
    implementation(project(":core:plugins:distortion"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.media)
    implementation(libs.kmlogging)
    implementation(libs.metrox.viewmodel.compose)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
