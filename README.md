# Umay PDF Okuyucu 📖🔊

**Android için çevrimdışı PDF okuyucu + seslendirme + ders asistanı.**
Ders PDF'ini içeri al, arka planda seslendir, ekrana bakma; taranmış sayfalar OCR ile
okunsun, İngilizce kitap Türkçe seslendirilsin, PDF'e soru sor.

| | |
|---|---|
| Paket | `com.umay.pdfreader` |
| Sürüm | **1.4** (versionCode 6) |
| Min SDK | 26 (Android 8.0) — Target 34 |
| Dil | Kotlin 1.9.10 · AGP 8.1.4 · Gradle 8.7 |
| Kurulum | `umay-pdf-reader-1.4.apk` (release, debug anahtarıyla imzalı) |

> Bu depoda ikinci bir uygulama daha var: `app/` → **Jarvis Mobile** (HUD/lockscreen).
> PDF okuyucu **`umay/`** modülüdür.

## Özellikler

- 📷 **Kitap tarama** — kamerayla sayfa sayfa çek, **sayfa numarasına göre
  sıralanıp tek PDF olsun**, kütüphaneye kitap olarak girsin ve okunsun
- 🖼 **Fotoğraftan PDF** — galeriden seçtiğin fotoğraflar otomatik sıralanıp
  A4 sayfalara dönüşür, OCR'e girer
- 📚 **Kütüphane** — SAF ile PDF seç, kitap listesi, arama, "kaldığın yerden devam et"
- 🔊 **Seslendirme** — ekran kapalıyken bile devam eder (foreground servis + wakelock),
  bildirimden oynat/duraklat/ileri/geri
- 🎚 **Hız & ses** — 0.5x–2.0x, kadın/erkek/otomatik ses seçimi, ses testi,
  **ekolayzır** (Ayarlar paneli + okuma ekranındaki Ses paneli)
- 🖨 **OCR** — taranmış (görsel) sayfalar ML Kit ile offline okunur, ilerleme gösterilir
- 🌐 **Çeviri** — İngilizce PDF'i Türkçe oku (ML Kit offline, ~35MB model bir kez iner)
- 💬 **Sohbet** — API anahtarı yoksa PDF içinde offline arama + özet; varsa
  ücretsiz Gemini / OpenAI-uyumlu gerçek sohbet
- ⏱ **Uyku zamanlayıcı** — 10–90 dk veya "bu sayfada bitir"
- 🔍 **Yakınlaştırma** — çift parmak zoom, sürükle, çift dokunuşla sıfırla

## APK kurulumu (telefonda)

Release APK bu ortamda şuraya kopyalanır:

```
/storage/emulated/0/FreebuffProject/umay-pdf-reader-1.4.apk
```

Dosya yöneticisinden APK'ya bas → "Yükle". (Termux'tan: `termux-open
/storage/emulated/0/FreebuffProject/umay-pdf-reader-1.4.apk`)

Bilinmeyen kaynak izni ilk kurulumda sorulur — izin ver.

## Derleme

```bash
export ANDROID_HOME=/opt/android-sdk
bash gradlew :umay:testDebugUnitTest :umay:assembleDebug :umay:assembleRelease --no-daemon
```

> `chmod +x gradlew` bu dosya sisteminde etkili olmaz; **her zaman `bash gradlew`**.

CI (`.github/workflows/build.yml`) push'ta test + debug + release üretir;
artifact adları: `umay-pdf-reader-release`, `umay-pdf-reader-debug`.

## Test

`umay/src/test/.../TextUtilTest.kt` — 32 test: cümle ayırma, dil algılama,
çevrimdışı arama, özet, normalizasyon, sembol→konuşma, karaoke eşleşmesi,
doğal dosya sıralaması, sohbet bağlamı seçimi.

```bash
bash gradlew :umay:testDebugUnitTest
```

## Depo Haritası

```
├── CLAUDE.md                 ← AI ajanlar için hızlı bağlam
├── README.md                 ← bu dosya
├── docs/
│   ├── ARCHITECTURE.md       ← mimari + veri akışları
│   ├── ROADMAP.md            ← yol haritası ve durum
│   ├── CHANGELOG.md          ← sürüm geçmişi
│   ├── TEST_REPORT.md        ← build/test kanıtları, SHA256
│   └── logs/                 ← build logları (commit edilmiş)
├── umay/                     ← 📖 PDF okuyucu uygulaması
│   └── src/main/java/com/umay/pdfreader/   (11 dosya, ~2.600 satır)
└── app/                      ← Jarvis Mobile (bağımsız)
```

## Lisans

Kişisel/özel proje.
