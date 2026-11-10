plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// yt-dlp + Python + FFmpeg for Android (https://github.com/yausername/youtubedl-android)
val youtubedlAndroid = "0.18.1"

android {
    namespace = "com.rsd.downloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rsd.downloader"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.5.6"

        // Phones only (keeps the APK smaller than bundling x86 emulators too).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        // Required: yt-dlp's Python and FFmpeg run as extracted native executables.
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt",
                "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")
    implementation("androidx.core:core-ktx:1.13.1")
}
