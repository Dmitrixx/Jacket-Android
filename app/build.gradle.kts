plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.jacket.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.jacket.mobile"
        minSdk = 28
        targetSdk = 36
        versionCode = 12
        versionName = "2.8.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Keep per-ABI APKs small while retaining a single universal APK for sideloading.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    packaging {
        jniLibs {
            // PRoot's loader and helper executables must be extracted to nativeLibraryDir
            // so Android's SELinux W^X policy permits execution from the app sandbox.
            useLegacyPackaging = true
        }
        resources {
            // Keep META-INF/media3-ffmpeg-decoder/**: it carries the FFmpeg LGPL
            // text, NOTICE, build provenance and source-rebuild information.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // The PRoot execution engine is ARM64-only in embedded mode; remote Lampac remains available elsewhere.
    implementation(project(":proot-engine"))

    // Compose 2026.06 remains pinned to the app's stable compile SDK (API 36).
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)

    // 1.19.x requires compileSdk 37; this release is pinned to compileSdk 36, so retain 1.17.0.
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("org.jsoup:jsoup:1.24.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // In-app playback for direct, licensed media streams and the matching
    // LGPL-only FFmpeg audio extension (AC-3/E-AC-3/DTS/MLP/TrueHD fallback).
    val media3Version = "1.11.1"
    implementation("io.put:media3-ffmpeg-decoder:1.11.1+1")
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    val libtorrentVersion = "2.1.0-39"
    implementation("org.libtorrent4j:libtorrent4j:$libtorrentVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$libtorrentVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$libtorrentVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-x86:$libtorrentVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$libtorrentVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
