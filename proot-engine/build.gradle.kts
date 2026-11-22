plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.or.oo.pr.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("../third_party/oonid-pr/android/proot-engine/src/main/AndroidManifest.xml")
            java.srcDir("../third_party/oonid-pr/android/proot-engine/src/main/java")
            jniLibs.srcDir("../third_party/oonid-pr/android/proot-engine/src/main/jniLibs")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../third_party/oonid-pr/android/proot-engine/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
