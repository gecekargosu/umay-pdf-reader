plugins {
    id("com.android.application") version "8.1.4" apply false
    id("org.jetbrains.kotlin.android") version "1.9.10" apply false
    // v6 (Aşama 6) — Firebase Crashlytics için google-services eklentisi
    // (yalnızca umay modülünde uygulanır)
    id("com.google.gms.google-services") version "4.4.1" apply false
    // v7 (CRASH FIX) — Firebase CRASHLYTICS GRADLE EKLENTİSİ.
    // Eklenti yoksa release APK'da build-id üretemez ve uygulama açılışta
    //   java.lang.IllegalStateException: The Crashlytics build ID is missing
    //   → FirebaseInitProvider crash ile çöker.
    id("com.google.firebase.crashlytics") version "2.9.9" apply false
}
