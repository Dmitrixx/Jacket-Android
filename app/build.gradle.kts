plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val jacketSigningStoreFile = System.getenv("JACKET_SIGNING_STORE_FILE")
val jacketSigningStorePassword = System.getenv("JACKET_SIGNING_STORE_PASSWORD")
val jacketSigningKeyAlias = System.getenv("JACKET_SIGNING_KEY_ALIAS")
val jacketSigningKeyPassword = System.getenv("JACKET_SIGNING_KEY_PASSWORD")
val hasJacketReleaseSigning = listOf(
    jacketSigningStoreFile,
    jacketSigningStorePassword,
    jacketSigningKeyAlias,
    jacketSigningKeyPassword
).all { !it.isNullOrBlank() }

val buildLampaWebAssets by tasks.registering(Exec::class) {
    val lampaRoot = rootProject.file("third_party/lampa-source")
    workingDir(rootProject.projectDir)
    commandLine("node", rootProject.file("tools/build-lampa-web.mjs").absolutePath)
    inputs.dir(lampaRoot.resolve("src"))
    inputs.dir(lampaRoot.resolve("public"))
    inputs.file(lampaRoot.resolve("package.json"))
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
}

android {
    namespace = "app.jacket.mobile"
    compileSdk = 36

    defaultConfig {
        // Keep the existing package ID for an in-place update that preserves app data.
        applicationId = "app.jacket.mobile"
        minSdk = 28
        targetSdk = 35
        versionCode = 25
        versionName = "3.0.0"
        buildConfigField("boolean", "PERSISTENT_SIGNING_CONFIGURED", hasJacketReleaseSigning.toString())
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

    signingConfigs {
        if (hasJacketReleaseSigning) {
            create("jacketRelease") {
                storeFile = file(requireNotNull(jacketSigningStoreFile))
                storePassword = jacketSigningStorePassword
                keyAlias = jacketSigningKeyAlias
                keyPassword = jacketSigningKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasJacketReleaseSigning) {
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
