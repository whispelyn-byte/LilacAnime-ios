plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lilac.anime.android"
    compileSdk = 36
    defaultConfig {
        // Its own id: installs beside the original Android app (com.lilac.anime).
        applicationId = "com.lilac.anime.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = file("../iosApp/version.txt").readText().trim()
        // Phones and tablets (arm64) and the emulator (x86_64).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        // llama.cpp is built optimized in every variant: a debug build runs a model many times slower.
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared"); cppFlags += "-std=c++17" } }
    }
    // 로컬 AI: llama.cpp b11490 with the iOS app's wrapper (iosApp/Native/Sources/LilacLocalAI), see src/main/cpp.
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    packaging { jniLibs.pickFirsts += "**/libc++_shared.so" }
    // The iOS app's page scripts (flix-*.js, ohli-capture.js) and shaders, shared as assets.
    sourceSets["main"].assets.srcDirs("src/main/assets", "../iosApp/LilacAnime/Resources")
    buildTypes {
        release { isMinifyEnabled = false }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/DEPENDENCIES") }
}
kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    implementation(project(":androidWhisper"))
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    // Chromecast (iOS: google-cast-ios-sdk).
    implementation("com.google.android.gms:play-services-cast-framework:22.1.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.core:core-ktx:1.16.0")
    // mpv for Android (the iOS app plays with mpv too).
    implementation("dev.jdtech.mpv:libmpv:1.0.0")
    // Subtitle archives (iOS uses libarchive): 7z and RAR4; ZIP is the platform's own.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.junrar:junrar:7.5.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
