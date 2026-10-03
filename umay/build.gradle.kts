plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.umay.pdfreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.umay.pdfreader"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // APK boyutu: sadece telefonlarda kullanılan mimariler
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // PDF metin çıkarma (Apache PDFBox Android portu)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Offline çeviri (İngilizce -> Türkçe) ve OCR (taranmış sayfalar)
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Opsiyonel online sohbet (API anahtarı ile)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
