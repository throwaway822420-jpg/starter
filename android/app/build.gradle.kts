import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hydrophobiccollapse"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hydrophobiccollapse"
        minSdk = 26
        targetSdk = 35
        // CI run number, so each build installs over the last one
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "2.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // A fixed key checked into the repo, so every build (local or CI) can update the installed app.
    // It is a debug-style key for a personal sideloaded app, not a store release key.
    signingConfigs {
        create("shared") {
            storeFile = file("wallpaper-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("shared") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

android.testOptions.unitTests.isIncludeAndroidResources = true
android.testOptions.unitTests.all { test ->
    // Offline machines can point Robolectric at a pre-downloaded android-all jar
    System.getenv("ROBOLECTRIC_DEPS_DIR")?.let {
        test.systemProperty("robolectric.offline", "true")
        test.systemProperty("robolectric.dependency.dir", it)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
}
