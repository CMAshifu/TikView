plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.tikview.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tikview.app"
        minSdk = 29          // Android 10 and newer
        targetSdk = 34
        // GitHub sets BUILD_NUMBER so every build can be installed over the last one
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("BUILD_NUMBER") ?: "0")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // A fixed key so new builds install as updates (your data is kept).
    signingConfigs {
        create("release") {
            storeFile = file("tikview-release.jks")
            storePassword = "tikview-android"
            keyAlias = "tikview"
            keyPassword = "tikview-android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            install("yt-dlp")   // newest version at build time
            install("certifi")
        }
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-common:$media3")
}
