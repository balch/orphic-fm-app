plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.metro)
}

android {
    namespace = "org.balch.orpheus"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.balch.orpheus"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/DEPENDENCIES"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(projects.composeApp)
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
    implementation(libs.androidx.media)
    implementation(libs.kmlogging)
    implementation(libs.metrox.viewmodel.compose)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
