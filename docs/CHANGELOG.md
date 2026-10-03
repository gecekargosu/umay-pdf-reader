# Değişiklik Günlüğü — Umay PDF Okuyucu

Format: [Keep a Changelog](https://keepachangelog.com/tr/1.1.0/) · Sürümler: SemVer

## [1.1.0] — 2026-10-03

### Eklendi
- Kütüphanede **arama**: anlık, Türkçe karakter duyarsız filtreleme
- Okuma ekranında **pinch-to-zoom** (1x–8x), sürükleme, çift dokunuşla sıfırlama
- `TextUtil` için **birim test paketi** (14 test: cümle ayırma, arama, dil, özet)
- CI'ya **release build + test** adımları; `umay-pdf-reader-release` artifact'i
- Doküman paketi: `README.md`, `CLAUDE.md`, `docs/ARCHITECTURE.md`,
  `docs/ROADMAP.md`, `docs/TEST_REPORT.md`, `docs/logs/`

### Düzeltildi
- **OCR ilerleme aşaması** hiç yayınlanmıyordu (`stage="ocr"`) — taranmış sayfalarda
  ekran yanlış/boş ilerleme gösteriyordu; artık `OCR: n / m sayfa`
- **Boş sayfa kilidi**: OCR başarısız olduğunda boş dosya yazılıp bir daha hiç
  denenmiyordu; boş metin dosyaları artık yeniden denenir
- **İlerleme sızıntısı**: kitap silinince okuma ilerlemesi ve `last_book` temizlenmiyordu
- **Servis öldürülmesi**: `START_NOT_STICKY` idi — sistem servisi öldürünce okuma
  susuyordu; artık `START_STICKY` + kaldığı cümleden otomatik devam
- **Metin bekleme**: sabit 120 sn timeout yerine çıkarma bitene kadar bekleme (12 dk
  üst sınır) + 60 sn tampon
- **Arama normalizasyonu**: "İstanbul" gibi İ ile başlayan kelimeler eşleşmiyordu
  (kök neden: `lowercase(ROOT)` birleşik nokta üretiyordu)

### Değişti
- `versionName` 1.0 → **1.1**, `versionCode` 1 → **2**
- Release build artık **debug anahtarıyla imzalı** (kurulabilir); gerçek yayında
  `.jks` ile değiştirilecek

## [1.0.0] — 2026-10-02

İlk çalışan sürüm (çekirdek tamam):

- Kütüphane: SAF PDF import, kitap listesi, devam sorusu, uzun basıp silme
- Metin çıkarma: PDFBox + taranmış sayfalarda ML Kit OCR, arka plan ilerlemesi
- Okuma ekranı: sayfa render, seekbar, sayfaya git, hız, ses, çeviri butonu
- Seslendirme servisi: foreground + wakelock, bildirim aksiyonları, uyku zamanlayıcı
- Offline EN→TR çeviri (ML Kit)
- Sohbet: offline arama/özet + opsiyonel Gemini/OpenAI
- Ayarlar: ses, hız, çeviri, API anahtarı, ses testi

## [1.1.1] — 2026-10-03 (hotfix)

### Düzeltildi
- **PDFBox açılış crash'i** (cihazda ilk denemede yakalandı):
  `ExceptionInInitializerError: GlyphList 'com/tom_roush/pdfbox/resources/glyphlist/glyphlist.txt' not found`
  - `UmayApp` (Application) eklendi: `PDFBoxResourceLoader.init(context)` çağrılmadığı için
    resource dosyaları hiç hazırlanmıyordu → ilk metin çıkarmada çöküyordu.
  - `BookStore.extractBook` artık `Throwable` yakalıyor: `ExceptionInInitializerError` bir
    `Exception` **değil**, `Error`'dı — eski `catch (e: Exception)` yakalamıyordu, servis
    thread'i uygulamayı çökertiyordu. Artık çıkarma başarısız olsa bile uygulama yaşar ve
    bir sonraki açılışta tekrar denenir.
  - Çıkarmadan hemen önce savunma olarak `initPdfBox()` tekrar çağrılır (idempotent).
