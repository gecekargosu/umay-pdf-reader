# Umay PDF Okuyucu — Mimari

> Bu doküman, kodu hızlıca anlamanız için hazırlanmıştır.
> Hedef kitle: geliştiriciler ve kodu inceleyen yapay zekâ ajanları (Claude vb.).

---

## 1. Genel Bakış

**Umay**, Android için **çevrimdışı çalışan** PDF okuyucu + seslendirme + ders asistanı uygulamasıdır.
Ders PDF'lerini okur, seslendirir, taranmış sayfaları OCR ile metne çevirir,
İngilizce PDF'i Türkçe okur ve PDF içine göre soru-cevap yapar.

- **Paket:** `com.umay.pdfreader`
- **Min SDK:** 26 (Android 8.0) — **Target/Compile SDK:** 34
- **Dil:** Kotlin 1.9.10, AGP 8.1.4, Gradle 8.7
- **UI:** View Binding + XML layout (Compose yok)
- **Async:** Kotlin Coroutines + tek iş parçacıklı executor

### Bağımlılıklar ve Görevleri

| Kütüphane | Ne için |
|---|---|
| `com.tom-roush:pdfbox-android` | PDF metin çıkarma |
| `com.google.mlkit:text-recognition` | Taranmış sayfalar için OCR (offline) |
| `com.google.mlkit:translate` | EN→TR çeviri (offline, ~35MB model) |
| `com.squareup.okhttp3:okhttp` | Opsiyonel online sohbet (Gemini/OpenAI) |
| Android `TextToSpeech` | Seslendirme (cihazın yerleşik motoru) |
| Android `PdfRenderer` | Sayfa görüntüsü render + OCR için raster |

---

## 2. Bileşen Haritası (dosya → sorumluluk)

```
umay/src/main/java/com/umay/pdfreader/
├── MainActivity.kt      Kütüphane: PDF import, kitap listesi, arama, devam sorusu
├── ReaderActivity.kt    Okuma ekranı: sayfa render, zoom, kontroller
├── ReadService.kt       Foreground servis: ekran kapalıyken seslendirme (kalp)
├── ChatActivity.kt      Sohbet: offline arama / online AI
├── SettingsActivity.kt  Ayarlar: ses, hız, çeviri, API anahtarı
├── BookStore.kt         ★ Çekirdek: kütüphane + metin çıkarma + OCR motoru
├── TtsManager.kt        TextToSpeech sarmalayıcı (ses seçimi, hız)
├── TrTranslator.kt      ML Kit offline EN→TR çeviri
├── TextUtil.kt          Cümle ayırma, arama, özet, dil algılama (saf fonksiyon)
├── ApiClient.kt         Gemini / OpenAI-uyumlu HTTP istemcisi
└── AppPrefs.kt          SharedPreferences (ayarlar + kitap başına ilerleme)
```

### İletişim Omurgası

```
ExtractionBus (BookStore içinde)  ──onProgress/onFinished──▶  MainActivity, ReaderActivity
ReadService.Listener              ──onState/onInfo────────▶  ReaderActivity
```

- `ExtractionBus.listeners`: `Collections.synchronizedList` — UI kayıtları `onResume`'da ekler,
  `onPause`'da çıkarır.
- `ReadService.listeners`: `CopyOnWriteArrayList`.

---

## 3. Ana Veri Akışları

### 3.1 PDF İçe Aktarma → Metin Çıkarma (arka plan)

```
SAF (OpenDocument) ─▶ BookStore.importPdf()
    1. filesDir/books/<id>/source.pdf'e kopyala
    2. PDDocument.load → pageCount
    3. books.json indeksine ekle
    4. ensureExtracting() → tek iş parçacıklı "umay-extract" executor
         ┌ extractBook(): her sayfa için
         │   a) PDFTextStripper ile metin dene
         │   b) Metin <20 krkt  YA DA '?' oranı >%12  →  OCR gerek
         │   c) OCR: PdfRenderer sayfayı 2x render → ML Kit TextRecognition
         │   d) Sonuç: filesDir/books/<id>/page_NNNN.txt
         └ ExtractionBus.progress(bookId, done, total, stage="text"|"ocr")
```

- Sayfa metni dosyaları **kalıcıdır**; tekrar çıkarma yapılmaz.
- **Boş metin dosyası** (`length()==0`) = OCR başarısız → sonraki açılışta tekrar denenir.
- `BookStore.isExtracting(id)` ile çıkmanın sürdüğü sorgulanır.

### 3.2 Seslendirme (ReadService — en kritik parça)

```
ReaderActivity ──bindService──▶ ReadService
                                   │ openBook(id, page, sentence)
                                   │ play() → startPlaying()
                                   │   • PARTIAL_WAKE_LOCK al
                                   │   • foreground notification (4 aksiyon)
                                   │   • speakCurrentPage()
                                   │        awaitPageText() ── çıkarma sürüyorsa BİTER KADAR BEKLER
                                   │        (gerekirse TrTranslator.translate)
                                   │        TextUtil.speechChunks() → ≤450 krkt parçalar
                                   │        tts.speak(chunk, id="page:idx")
                                   │   onDone(id) → speakChunk(idx+1) → sayfa sonu → nextPage()
                                   └ AppPrefs.saveProgress(page, chunk)  ← "kaldığın yer"
```

- **Hayatta kalma:** `START_STICKY`. Sistem servisi öldürürse `onCreate` →
  `restoreAfterSystemKill()`: `KEY_PLAYING=true` ise son kitaptan kaldığı cümleden
  **otomatik devam** eder.
- **Uyku zamanlayıcı:** dakika bazlı `Handler` veya "bu sayfada bitir" modu.
- **Boş sayfa:** üst üste 30 boş sayfa → kitabı bitmiş say.

### 3.3 Çeviri (İngilizce PDF → Türkçe ses)

```
pageText → TextUtil.needsTranslation(text, enabled)?
    enabled + İngilizce ipuçları (the/and/of ≥3) + Türkçe değil
  → TrTranslator.translate()  (ML Kit, cümle bazlı, ilk seferde model indirir)
  → başarısızsa ORİJİNAL metin okunur (kullanıcıya bilgi verilir)
```

### 3.4 Sohbet

```
ChatActivity.answerFor(soru):
  API anahtarı YOK  → offline: TextUtil.search(pages, soru) + "özet yaz" komutu
  API anahtarı VAR  → TextUtil.search → bağlam parçaları → ApiClient.ask()
                      (AIza* ise Gemini, değilse OpenAI-uyumlu)
                      hataya düşerse offline aramaya FALLBACK
```

---

## 4. Dosya Sistemi Düzeni (cihazda)

```
filesDir/
└── books/
    ├── books.json                    ← kitap indeksi (id, title, pageCount, createdAt)
    └── <bookId>/
        ├── source.pdf                ← orijinal PDF kopyası
        └── page_0000.txt …           ← sayfa metinleri (çıkarım çıktısı)

shared_prefs/umay_prefs.xml           ← ayarlar + prog_page_<id> / prog_sent_<id>
```

---

## 5. Yaşam Döngüsü Notları

| Durum | Davranış |
|---|---|
| MainActivity `onResume` | kitapları yükle, arama filtresi, `ensureExtracting` sürdür, `ExtractionBus` kaydı |
| ReaderActivity `onResume` | `ExtractionBus` kaydı (çıkarma ilerlemesini gösterir) |
| ReaderActivity geri tuşu | okuma sürüyorsa diyalog: "arka planda devam" / "çık ve durdur" |
| Servis `onDestroy` | `KEY_PLAYING=false`, TTS kapat, wakelock bırak |
| Kitap silme | dizini sil + ilerlemeyi temizle + `KEY_LAST_BOOK` temizle |

---

## 6. Bilinen Sınırlar / Gelecek İşler

- Sayfa görüntüsü üzerinde **cümle vurgusu** yok (sadece önizleme metni var).
- Kütüphanede **kapak/önizleme** yok.
- Release imzası şu an **debug anahtarı** (gerçek yayına geçerken `.jks` eklenmeli;
  `.gitignore` zaten `*.jks`'i hariç tutar).
- `app` modülü (Jarvis) bu repo içinde ayrı bir uygulamadır; `umay`'dan bağımsızdır.

Detaylı yol haritası: `docs/ROADMAP.md` · Değişiklik geçmişi: `docs/CHANGELOG.md`
