import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val upstreamMain = rootProject.file("third_party/byebyedpi/app/src/main")
val generatedSources = layout.buildDirectory.dir("generated/byedpi/main")
val generatedJniLibs = layout.buildDirectory.dir("generated/byedpi/jniLibs")

/**
 * Keep the upstream source pinned as a submodule, while applying one small host-app
 * safety patch: ByeByeDPI's standalone "close app" action must close only its own
 * screen, not kill the Lampa process hosting the embedded library.
 */
val prepareByeDpiSources by tasks.registering(Sync::class) {
    from(upstreamMain) {
        include("java/**", "res/**", "assets/**")
    }
    into(generatedSources)

    inputs.file(upstreamMain.resolve("java/io/github/romanvht/byedpi/activities/MainActivity.kt"))
    doLast {
        val mainActivity = generatedSources.get()
            .file("java/io/github/romanvht/byedpi/activities/MainActivity.kt")
            .asFile
        check(mainActivity.isFile) { "ByeByeDPI MainActivity source was not copied" }
        val source = mainActivity.readText(Charsets.UTF_8)
        val unsafeExit = Regex(
            """finishAffinity\(\)\s*android\.os\.Process\.killProcess\(android\.os\.Process\.myPid\(\)\)\s*exitProcess\(0\)"""
        )
        val patched = source.replace(unsafeExit, "finish()")
        check(patched != source) {
            "ByeByeDPI close-action patch anchor changed upstream; review before updating the pinned source."
        }
        mainActivity.writeText(patched, Charsets.UTF_8)
    }
}

/**
 * The tunnel companion is built with the upstream ndk-build files. ByeDPI's main
 * JNI library is built separately by CMake; both .so files are packaged together.
 */
val buildByeDpiTunnel by tasks.registering(Exec::class) {
    val sourceJni = upstreamMain.resolve("jni")
    val ndkDirectory = android.ndkDirectory
    val ndkBuild = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        File(ndkDirectory, "ndk-build.cmd")
    } else {
        File(ndkDirectory, "ndk-build")
    }

    workingDir(rootProject.projectDir)
    commandLine(
        ndkBuild.absolutePath,
        "NDK_PROJECT_PATH=${layout.buildDirectory.dir("ndkBuild").get().asFile.absolutePath}",
        "NDK_LIBS_OUT=${generatedJniLibs.get().asFile.absolutePath}",
        "APP_BUILD_SCRIPT=${sourceJni.resolve("Android.mk").absolutePath}",
        "NDK_APPLICATION_MK=${sourceJni.resolve("Application.mk").absolutePath}"
    )

    inputs.file(sourceJni.resolve("Android.mk"))
    inputs.file(sourceJni.resolve("Application.mk"))
    inputs.dir(sourceJni.resolve("hev-socks5-tunnel"))
    outputs.dir(generatedJniLibs)
}

android {
    namespace = "io.github.romanvht.byedpi"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        buildConfigField("String", "APPLICATION_ID", "\"io.github.romanvht.byedpi\"")
        buildConfigField("String", "VERSION_NAME", "\"1.8.2\"")

        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_PLATFORM=android-28")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir(generatedSources.map { it.dir("java") })
            res.srcDir(generatedSources.map { it.dir("res") })
            assets.srcDir(generatedSources.map { it.dir("assets") })
            jniLibs.srcDir(generatedJniLibs)
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }

    externalNativeBuild {
        cmake {
            path = upstreamMain.resolve("cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareByeDpiSources)
    dependsOn(buildByeDpiTunnel)
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.code.gson:gson:2.14.0")
}
