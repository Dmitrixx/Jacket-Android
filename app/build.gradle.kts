plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningStoreFile = System.getenv("JACKET_SIGNING_STORE_FILE")
val releaseSigningStorePassword = System.getenv("JACKET_SIGNING_STORE_PASSWORD")
val releaseSigningKeyAlias = System.getenv("JACKET_SIGNING_KEY_ALIAS")
val releaseSigningKeyPassword = System.getenv("JACKET_SIGNING_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseSigningStoreFile,
    releaseSigningStorePassword,
    releaseSigningKeyAlias,
    releaseSigningKeyPassword
).all { !it.isNullOrBlank() }

val hevNativeLibraries = layout.buildDirectory.dir("generated/byedpi/jniLibs")

val buildHevSocks5Tunnel by tasks.registering(Exec::class) {
    val hevRoot = rootProject.file("third_party/hev-socks5-tunnel")
    val ndkDirectory = android.ndkDirectory
    val ndkBuild = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        File(ndkDirectory, "ndk-build.cmd")
    } else {
        File(ndkDirectory, "ndk-build")
    }

    val ndkProjectDirectory = layout.buildDirectory.dir("hev-ndk-project").get().asFile
    val ndkOutputDirectory = layout.buildDirectory.dir("hev-ndk-obj").get().asFile
    workingDir(rootProject.projectDir)
    doFirst {
        ndkProjectDirectory.mkdirs()
        ndkOutputDirectory.mkdirs()
    }
    commandLine(
        ndkBuild.absolutePath,
        "NDK_PROJECT_PATH=${ndkProjectDirectory.absolutePath}",
        "NDK_OUT=${ndkOutputDirectory.absolutePath}",
        "NDK_LIBS_OUT=${hevNativeLibraries.get().asFile.absolutePath}",
        "APP_PLATFORM=android-28",
        "APP_BUILD_SCRIPT=${hevRoot.resolve("Android.mk").absolutePath}",
        "NDK_APPLICATION_MK=${hevRoot.resolve("Application.mk").absolutePath}"
    )

    inputs.dir(hevRoot)
    outputs.dir(hevNativeLibraries)
}

val buildLampaWebAssets by tasks.registering(Exec::class) {
    val lampaRoot = rootProject.file("third_party/lampa-source")
    workingDir(rootProject.projectDir)
    commandLine("node", rootProject.file("tools/build-lampa-web.mjs").absolutePath)
    inputs.dir(lampaRoot.resolve("src"))
    inputs.dir(lampaRoot.resolve("plugins"))
    inputs.dir(lampaRoot.resolve("public"))
    inputs.file(lampaRoot.resolve("package.json"))
    inputs.file(lampaRoot.resolve("gulpfile.js"))
    inputs.file(lampaRoot.resolve("LICENSE"))
    inputs.file(rootProject.file("lampa-android/modification.js"))
    inputs.file(rootProject.file("tools/build-lampa-web.mjs"))
    outputs.dir(layout.projectDirectory.dir("src/main/assets/lampa"))
    doFirst {
        check(lampaRoot.resolve("node_modules").isDirectory) {
            "Lampa dependencies are missing. Run npm install in third_party/lampa-source."
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(buildLampaWebAssets)
    dependsOn(buildHevSocks5Tunnel)
}

android {
    namespace = "app.jacket.mobile"
    compileSdk = 36

    defaultConfig {
        // Keep the existing package ID for an in-place update that preserves app data.
        applicationId = "app.jacket.mobile"
        minSdk = 28
        targetSdk = 35
        versionCode = 65
        versionName = "3.0.40"
        buildConfigField("boolean", "PERSISTENT_SIGNING_CONFIGURED", hasReleaseSigning.toString())
        vectorDrawables { useSupportLibrary = true }
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_PLATFORM=android-28")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(hevNativeLibraries)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("jacketRelease") {
                storeFile = file(requireNotNull(releaseSigningStoreFile))
                storePassword = releaseSigningStorePassword
                keyAlias = releaseSigningKeyAlias
                keyPassword = releaseSigningKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("jacketRelease")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":proot-engine"))

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
