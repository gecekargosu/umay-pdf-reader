import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "1.9.10"
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    // v14 (ADIM A tamir) — paket/namespace COM.umay.pdfreader'a DÖNDÜRÜLDÜ:
    // 13 Ekim'de yarım kalan com.umay.campus taşıması 964 derleme hatası
    // üretti (kayıp sınıflar + eksik R). Kurulu kullanıcı + imza zinciri
    // (com.umay.pdfreader) korunur. Compose/Hilt keşfi: _COMPOSE_STAGING/.
    namespace = "com.umay.pdfreader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.umay.pdfreader"
        minSdk = 26
        targetSdk = 36
        // v21 (21 Eki 2026 denetim turu): tarama/aktarma güvenlik + ana sayfa
        // düzeni + OCR kararlılığı — sürüm 5.2 / code 20
        // v22 (22 Eki 2026): tarama ekranı metadata + filtre/sıralama +
        // tembel kapak önizlemesi; "zaten ekli" göstergesi — 5.3 / code 21
        // v23 (28 Eki 2026 onarım turu 2): okuma motoru (Arapça/matematik),
        // alarm melodisi ANR, PdfRenderer çökme, API 35 çökmesi (reversed),
        // ders programı tohum çakışması — 5.4 / code 22
        // v24 (28 Eki 2026, AŞAMA 1): ⭐ Favoriler rafı + bildirim/alarm izin
        // merkezi (HyperOS yönlendirmesi) + kapak negatif önbelleği +
        // ders-kaynak backfill — 5.5 / code 23
        versionCode = 30
        versionName = "5.13"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // v36.6 — GÖMÜLÜ AI ANAHTARI KAYNAKTAN ÇIKARILDI.
        // Kaynak kod herkese açık yayınlanabilsin diye anahtar artık koda
        // yazılmaz: önce ortam değişkeni (UMAY_GEMINI_KEY), yoksa
        // local.properties içindeki `umay.geminiKey` okunur. İkisi de yoksa
        // boş kalır → uygulama çevrimdışı sohbete düşer, kullanıcı kendi
        // ücretsiz anahtarını Ayarlar'dan girebilir.
        val umayLocalProps = Properties()
        try {
            val lp = rootProject.file("local.properties")
            if (lp.exists()) lp.inputStream().use { umayLocalProps.load(it) }
        } catch (_: Throwable) {
        }
        val builtinAiKey = System.getenv("UMAY_GEMINI_KEY")
            ?: umayLocalProps.getProperty("umay.geminiKey")
            ?: ""
        buildConfigField("String", "BUILTIN_API_KEY", "\"$builtinAiKey\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            try {
                val f = file("keystore.properties")
                if (f.exists()) f.inputStream().use { props.load(it) }
            } catch (_: Throwable) {
            }
            storeFile = file(
                System.getenv("UMAY_STORE_FILE")
                    ?: props.getProperty("storeFile")
                    ?: "GUNCELLEME_ANAHTARLARI/umay-release.jks"
            )
            storePassword = System.getenv("UMAY_STORE_PASS")
                ?: props.getProperty("storePassword")
            keyAlias = System.getenv("UMAY_KEY_ALIAS")
                ?: props.getProperty("keyAlias") ?: "umay"
            keyPassword = System.getenv("UMAY_KEY_PASS")
                ?: props.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile?.exists() == true &&
                !rel.storePassword.isNullOrEmpty()
            ) rel else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true           // Mevcut Activity kodu ViewBinding kullanıyor
        buildConfig = true           // DiagnosticTestManager sürüm bilgisini okur
    }

    testOptions {
        // v36 (test onarımı): android.jar "Stub!" istisnaları yerine varsayılan
        // değer dönsün — saf mantık sınıfları (TextUtil, ReadProgressStore…)
        // cihaz olmadan test edilebilsin.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

/**
 * v36 — BİRİM TEST SINIFI YOLU ONARIMI
 *
 * Sorun: `:umay:testDebugUnitTest` her testte `ClassNotFoundException` veriyordu.
 * Ölçüm: `debugUnitTestRuntimeClasspath` içinde test sınıfları hiç yoktu —
 * Kotlin eklentisi test sınıflarını `build/tmp/kotlin-classes/<variant>UnitTest`
 * altına yazıyor (dosyalar orada VAR), ama AGP 8.1.4 + KGP 1.9.10 ikilisi bu
 * klasörü test çalışma zamanı sınıf yoluna eklemiyor.
 *
 * Çözüm: klasörü adından türetip yalnız Test görevlerine ekle (üretim APK'sını
 * etkilemez). Kalıcı çözüm KGP yükseltmesidir; o ayrı bir iş olarak duruyor
 * (30 bin satırı yeniden derleme riski).
 */
tasks.withType<Test>().configureEach {
    val variant = name.removePrefix("test").removeSuffix("UnitTest").replaceFirstChar { it.lowercase() }
    val testClasses = layout.buildDirectory.dir("tmp/kotlin-classes/${variant}UnitTest")
    // Klavuz:
    //  1) AGP 8.1.4 + KGP 1.9.10 bu projede test sınıflarını çalışma zamanı
    //     sınıf yoluna hiç koymuyor → elle ekliyoruz.
    //  2) Gradle 8.x hatası (gradle/gradle#30304): işçiye sınıf yolu UTF-8
    //     yazılan bir @argfile ile veriliyor, JVM onu Windows yerel kodlamasıyla
    //     okuyor. Proje yolu ASCII dışı karakter içerdiği için (“Geliştirmeye
    //     çalıştığım … Dosyaları”) sınıf yolu bozuluyor ve her test
    //     ClassNotFoundException veriyordu. Çözüm: ASCII olmayan bütün sınıf
    //     yolu girdilerini ASCII bir klasöre kopyalayıp onları kullanmak.
    doFirst {
        val mirror = File(
            System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir"),
            "umay-unit-test-cp"
        ).apply { mkdirs() }
        var n = 0
        classpath = files((listOf(testClasses.get().asFile) + classpath.files).map { f ->
            if (f.path.all { it.code < 128 }) f else {
                val dest = File(mirror, "cp${n++}_" + f.name)
                runCatching {
                    if (f.isDirectory) {
                        dest.deleteRecursively()
                        f.copyRecursively(dest, overwrite = true)
                    } else if (!dest.exists() || dest.lastModified() < f.lastModified()) {
                        f.copyTo(dest, overwrite = true)
                    }
                    Unit
                }
                if (dest.exists()) dest else f
            }
        })
    }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    // ── AndroidX Core ──
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    // Recycler view (bindingAdapterPosition API'si için >= 1.2.0 zorunlu)
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // ── Lifecycle & ViewModel ──
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")

    // ── Coroutines ──
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Compose/Hilt/Room keşif bağımlılıkları _COMPOSE_STAGING devreye alınacak.

    // ── Biometric (Gizli Kasa) ──
    implementation(files("libs/biometric-1.1.0.aar"))

    // ── PDF (Apache PDFBox Android portu) ──
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // ── ML Kit (Çeviri, OCR, Sertifika) ──
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // ── OkHttp (Network) ──
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ── Retrofit + Gson (API) ──
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // ── Gson ──
    implementation("com.google.code.gson:gson:2.10.1")

    // ── Test ──
    testImplementation("junit:junit:4.13.2")
    // JVM unit test'te XML pull parser (android.util.Xml mock edilmez)
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // ── WorkManager (v36: boşta ön hazırlık) ──
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ── AdMob ──
    implementation("com.google.android.gms:play-services-ads:23.6.0")

    // ── Play Core ──
    implementation("com.google.android.play:review-ktx:2.0.2")
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // ── Firebase Crashlytics ──
    implementation("com.google.firebase:firebase-crashlytics:18.6.1")
}

