# Umay PDF Okuyucu — proguard / R8 kuralları (v6: minify AÇIK)

# ── Genel korumalar ────────────────────────────────────────────
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses,PermittedSubclasses
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Apache PDFBox (metin çıkarma; kaynak adları sınıf içinde saklanır) ──
-keep class com.tom_roush.pdfbox.** { *; }
-keep class org.apache.pdfbox.** { *; }
-dontwarn org.apache.pdfbox.**
-dontwarn com.tom_roush.pdfbox.**

# ── ML Kit (çeviri + OCR) — kendi consumer kuralları vardır; emin olmak için ──
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ── OkHttp / Okio (ApiClient) ────────────────────────────────
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Google Play Services (Ads + Play Core) ───────────────────
-dontwarn com.google.android.gms.**
-keep class com.google.android.play.core.** { *; }
-dontwarn com.google.android.play.core.**

# ── Firebase / Crashlytics ───────────────────────────────────
-dontwarn com.google.firebase.**
-keep class com.google.firebase.** { *; }

# ── ViewModel/View Binding & özel view'lar (XML'den referans) ──
-keep class com.umay.pdfreader.ProgressRingView { *; }
-keep class com.umay.pdfreader.WaveformView { *; }
-keep class * extends android.view.View { *; }

# ── org.json (Android platform sınıfı, sdcard gölge kopyası olabilir) ──
-dontwarn org.json.**
