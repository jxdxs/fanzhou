plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.kevin.fanzhou"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.kevin.fanzhou"
        minSdk = 26
        targetSdk = 35
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        versionCode = 61
        versionName = "2.3.43"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.jks")
            storePassword = "fanzhou123"
            keyAlias = "fanzhou"
            keyPassword = "fanzhou123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // libmpv 1.0.0 is compiled with Kotlin 2.2; allow reading its newer metadata.
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint { checkReleaseBuilds = false }
    testOptions { unitTests.isIncludeAndroidResources = true; unitTests.all { it.systemProperty("java.awt.headless", "true"); it.testLogging.showStandardStreams = true } }
    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST")
        // Compress native libs (libmpv + FFmpeg are large) to keep the APK small.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.7.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")
    implementation("androidx.media3:media3-session:1.7.1")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.6.1+2")
    implementation("dev.jdtech.mpv:libmpv:1.0.0") { exclude(group = "org.jetbrains.kotlin") }
    implementation("com.hierynomus:smbj:0.14.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.github.junrar:junrar:7.5.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

// libmpv 1.0.0 declares minCompileSdk 36 in its AAR metadata, but only uses APIs available
// at compileSdk 35 (Context, Surface). AGP 8.7 tops out at 35, so skip that metadata check.
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach { enabled = false }
