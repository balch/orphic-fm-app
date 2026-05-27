plugins {
    id("orpheus.android.app")
}

android {
    namespace = "org.balch.orpheus"

    defaultConfig {
        applicationId = "org.balch.orpheus"
        minSdk = 35

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
    implementation(projects.apps.orpheus)
    implementation(project(":core:foundation"))
    implementation(project(":core:mediapipe"))
    implementation(project(":features:mediapipe"))
    implementation(project(":core:plugins:delay"))
    implementation(project(":core:plugins:distortion"))
    implementation(project(":core:plugins:resonator"))
    implementation(project(":core:plugins:bender"))
    implementation(project(":core:plugins:stereo"))
    implementation(project(":core:plugins:vibrato"))
    implementation(project(":core:plugins:warps"))
    implementation(project(":core:plugins:grains"))
    implementation(project(":core:plugins:drum"))
    implementation(project(":core:plugins:duolfo"))
    implementation(project(":core:plugins:flux"))
    implementation(project(":core:plugins:looper"))
    implementation(project(":core:plugins:perstringbender"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.media3.session)
    implementation(libs.kmlogging)
    implementation(libs.metrox.viewmodel.compose)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
