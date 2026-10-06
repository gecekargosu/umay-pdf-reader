# CLAUDE.md — Umay PDF Okuyucu (JarvisMobile deposu)

Bu depo **iki bağımsız Android uygulaması** içerir:
- **`umay/`** → **Umay PDF Okuyucu** (`com.umay.pdfreader`) — asıl ürün, aktif geliştirme.
- **`app/`** → Jarvis Mobile (`com.jarvis.mobile`) — HUD/lockscreen uygulaması, bağımsız.

> **📖 İKİNCİ BAĞIMSIZ PROJE — UMAY CAMPUS (5 Ekim 2026)**
> Yeni ürün **UMAY Campus** (öğrenci sosyal ağı + akademik arşiv + AI ders asistanı)
> **bu deposun DIŞINDA**, `/storage/emulated/0/FreebuffProject/UMAY_CAMPUS/`
> klasöründe ve **tamamen bağımsızdır**.
> - Master spec: `UMAY_CAMPUS/docs/PRODUCT_SPEC.md` (37 bölüm) · Kurallar: `UMAY_CAMPUS/CLAUDE.md`
> - Durum: `UMAY_CAMPUS/STATUS.md` (Aşama 0 — dokümantasyon tam, kod yok)
> - **Zorunlu:** UMAY Campus işleri için `umay/` ve `app/` kaynakları **DEĞİŞTİRİLMEZ**;
>   yeni kod bu depoya karıştırılmaz. Sadece inceleme amaçlı okuma yapılır.

## Komutlar

```bash
# DERLEME — dikkat: chmod burada çalışmaz, her zaman bash ile çalıştır:
export ANDROID_HOME=/opt/android-sdk          # local.properties sdk.dir'ı da yazar
bash gradlew :umay:assembleDebug :umay:assembleRelease --no-daemon

# TESTLER
bash gradlew :umay:testDebugUnitTest --no-daemon
#   rapor: umay/build/reports/tests/testDebugUnitTest/index.html

# APK çıktıları
#   umay/build/outputs/apk/debug/umay-debug.apk
#   umay/build/outputs/apk/release/umay-release.apk
```

- JDK 21 kurulu; AGP 8.1.4 ile uyumlu. Gradle 8.7 (wrapper).
- `local.properties` git'e girmez; `sdk.dir=/opt/android-sdk` gerekir.
- Build logları `docs/logs/*.txt` altına kaydedilir ve commit edilir.

## Kod Konvansiyonları

- View Binding (`ActivityXBinding`), XML layout; Compose **kullanılmıyor**.
- Türkçe yorumlar; ekran metinleri `strings.xml` (`umay_*` renk adları).
- Async: Coroutines (`scope` + `Dispatchers.IO`) ve tek iş parçacıklı
  `Executors.newSingleThreadExecutor` (`umay-extract`).
- Durum paylaşımı: `ExtractionBus` (çıkarım olayları) ve `ReadService.Listener` (okuma).
- Ayarlar/ilerleme: tek `SharedPreferences` (`AppPrefs`), test edilebilir saf mantık
  `TextUtil` içinde.

## Sık dokunulan yerler

| İş | Dosya |
|---|---|
| Yeni ekran | layout XML + AndroidManifest activity + `umay/src/main/java/.../XxxActivity.kt` |
| Çıkarım/OCR davranışı | `BookStore.kt` |
| Okuma akışı / servis | `ReadService.kt` |
| Ses/hız | `TtsManager.kt` |
| Arama/özet | `TextUtil.kt` (+ `TextUtilTest.kt`) |

## Kritik bilgiler

- Servis `START_STICKY`: öldürülürse `restoreAfterSystemKill()` kaldığı yerden devam eder
  (`AppPrefs.KEY_PLAYING`).
- Sayfa metni `page_NNNN.txt`; **boş dosya = başarısız OCR = tekrar denenmeli**.
- GitHub Actions (`.github/workflows/build.yml`): test + debug + release APK üretir,
  artifact `umay-pdf-reader-release`.
- Daha fazlası: `docs/ARCHITECTURE.md`, `docs/ROADMAP.md`, `README.md`.
