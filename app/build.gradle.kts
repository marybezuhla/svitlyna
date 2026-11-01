import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
// The version people see (major.minor.patch), set by hand before each release in version.txt.
val appVersion = rootProject.file("version.txt").readText().trim()

android {
    namespace = "ua.svitlyna"
    compileSdk = 36

    defaultConfig {
        applicationId = "ua.svitlyna"
        // Android 11: the system trash and write requests for photos exist from here on.
        minSdk = 30
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = appVersion
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            // Unused code and resources are left out (the icon set alone is large), but names are
            // kept as they are (no obfuscation): see proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Built unsigned: CI signs it in a separate step with the Android SDK's apksigner.
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
        // The OCR models are read as they are; compressing them only slows loading.
        noCompress += "onnx"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // The interface comes from Iris Gallery (Apache 2.0): its image loading (Coil, Apache 2.0) without
    // the network module, GIF and SVG support, AVIF photos on Android 11 (libavif, BSD), appcompat
    // for the in-app language choice.
    implementation("io.coil-kt.coil3:coil-compose:3.1.0")
    implementation("io.coil-kt.coil3:coil-gif:3.1.0")
    implementation("io.coil-kt.coil3:coil-svg:3.1.0")
    implementation("org.aomedia.avif.android:avif:1.1.1.14d8e3c4")
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    // App lock with the fingerprint or the phone's PIN (as in Tarilka)
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric alone brings an old fragment library that rejects the request codes of Android's
    // permission and confirmation windows: a current one is required (as appcompat brings in Tarilka).
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Text on photos: PP-OCRv5 models (in assets/ocr) run with ONNX Runtime (MIT), as in Pysanka
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    // Search: the text found on photos is kept in an encrypted SQLite database (SQLCipher, BSD-style)
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite:2.4.0")

    // Video playback (Apache 2.0); no network module
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    testImplementation("junit:junit:4.13.2")
    // Real Android classes for tests that write photo metadata with ExifInterface (tests only)
    testImplementation("org.robolectric:robolectric:4.14.1")
    // The search database's queries, checked on plain SQLite in tests (the encrypted one needs the phone)
    testImplementation("androidx.sqlite:sqlite-framework:2.4.0")
}
