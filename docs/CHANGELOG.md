# Değişiklik Günlüğü — Umay PDF Okuyucu

Format: [Keep a Changelog](https://keepachangelog.com/tr/1.1.0/) · Sürümler: SemVer

## [1.4.0] — 2026-10-05 (geri bildirim turu 4 — resim/PDF, tarama, ekolayzır, sohbet)

### Düzeltildi
- **"Seçilen resimlerin hiçbiri okunamadı" — galeri → PDF hiç çalışmıyordu**
  - Kök neden Kotlin `?:` tuzağıydı: `inJustDecodeBounds = true` ile
    `BitmapFactory.decodeStream` **null** döner; `… ?: return null` yazıldığı için
    `loadBitmapForPdf()` **her resimde** erken dönüyor, hepsi "çözülemedi"
    sayılıp atlanıyordu.
  - Boyut okuması ayrı ifadeye alındı; decode OOM durumunda örneklemeyi iki kez
    artırıp yeniden deneniyor; format çözülemiyorsa sebebiyle birlikte raporlanıyor.
  - Artık hata metni nedeni gösteriyor: `Resimler çevrilemedi (3/3 hata). İlk hata: …`
  - `takePersistableUriPermission` ile kalıcı okuma izni alınıyor.

### Eklendi
- **Kitap tarama (kamera): `ScannerActivity`** — Ana sayfa → **"Kitap Tara
  (Kamera)"**: sayfa sayfa çek, ızgarada sayfa numarasıyla gör ve × ile sil,
  **"PDF Yap → Kütüphaneye Ekle"** ile tek PDF'e dönüşür → kütüphaneye kitap olarak
  girer → OCR başlar → **doğrudan okuma ekranı açılır** (kuyruğa alınır).
  Altyapı: `FileProvider` + `res/xml/file_paths.xml`; harici kamera uygulaması
  kullanıldığı için **CAMERA izni gerekmez**.
- **Sayfa numarasına göre otomatik sıralama** — seçilen fotoğraflar dosya adına
  göre **doğal sıralanır** (`sayfa_9` → `sayfa_10`); `TextUtil.naturalKey`.
- **Ayarlar → Ekolayzır** — anahtar + **Bas / Orta / Tiz** kaydırıcıları
  (−100…+100, ekranda sayısal değer). Açık bir okuma varsa `ReadService` üzerinden
  **anında** uygulanır; okuma ekranındaki Ses paneli de duruyor.

### Değişti
- **Sohbet bağlamı (Gemini) artık gerçekten kitaptan okuyor**: yeni
  `TextUtil.contextPages()` sayfa bazlı, kısmi eşleşmeli puanlama yapıyor
  (eski: yalnız cümle bazlı eşleşen parçalar + ilk 3 sayfa → model "bu kitapta
  geçmiyor" diyordu). Eşleşme yoksa ilk metinli sayfalar gönderilir; metin henüz
  çıkarılmamışsa (OCR sürüyor) açık uyarı verilir. Offline modda da cümle eşleşmezse
  sayfa bazlı sonuç gösteriliyor.

### Test
- 26 → **32 birim testi** (`naturalKey_*`, `contextPages_*`), 32/32 geçiyor.

### Kontrol
- `gradlew :umay:testDebugUnitTest :umay:assembleDebug :umay:assembleRelease`
  → **BUILD SUCCESSFUL**; log `docs/logs/build-20261005-1113.txt`;
  APK `umay-pdf-reader-1.4.apk` (versionCode 6).

## [1.3.1] — 2026-10-05 (cihaz geri bildirimi — uygulama hiç açılmıyordu)

### Düzeltildi
- **Kitap açılırken çökme: `InflateException` → `Resources$NotFoundException`
  → `IllegalArgumentException`** (okuma ekranı hiç açılmıyordu)
  - Kök neden: `res/drawable/ic_search.xml` içindeki `android:pathData` bozuktu.
    `C` (kübik Bézier) komutundan sonra 6'nın katı sayıda koordinat gerekirken
    **22** sayı vardı — dördüncü çeyrek dairede iki çift eksikti
    (`3,13.09 5.91,16`). `PathParser` bunu `C needs to be followed by a multiple
    of 6 floats` olarak reddediyor ve `activity_reader.xml` satır 48'deki
    `btnSearch` `ImageButton` inflate edilirken `ReaderActivity.onCreate`
    (satır 117) çöküyordu.
  - Düzeltme: eksik iki koordinat tamamlandı, her kübik segment açık `C`
    etiketiyle ayrıldı (özel/immersif tüm Android sürümlerinde güvenli).
  - Doğrulama: 12 vektörün hepsi programatik tarandı → tek hatalı dosya
    `ic_search.xml` idi; yol geometrisi de kontrol edildi (halka merkez
    (9.5,9.5) r=6.5, iç delik r=4.5, segmentler bitişik, zıt sarmal delik için).
    APK içindeki sürümlü dosyaya `aapt2 dump xmltree` ile bakıldı.

### Güçlendirildi — Yapay zekâ sohbet (Gemini anahtarı)
- **Model otomatik keşfi + yedek zinciri**: Ayarlar'da model boşsa
  `/v1beta/models` ucundan `generateContent` destekleyen modeller bulunur ve
  hızlı `flash` adayları sırayla denenir; Google bir modeli emekliye ayırınca
  (404 NOT_FOUND) sıradakine geçilir — sohbet tek model adına kilitli değil.
- **Boş yanıt düzeltmesi**: 2.5+ modellerde "thinking" token'ları
  `maxOutputTokens`'ı yiyordu → `thinkingConfig.thinkingBudget = 0` ve
  700 → 2048 token.
- **Türkçe hata mesajları**: geçersiz anahtar, yetkisiz anahtar, kota (429),
  bağlantı yok, engellenen istek — hepsi sohbette okunur şekilde görünür ve
  her hata sonrası otomatik olarak offline PDF aramasına düşülür.
- **Ayarlar → "Bağlantıyı test et"** butonu: kaydetmeden girilen anahtarla
  gerçek istek atılıp sonucu toast'ta gösterilir (ağ ana thread'de değil).

### Kontrol
- `gradlew :umay:testDebugUnitTest :umay:assembleDebug :umay:assembleRelease`
  → **BUILD SUCCESSFUL**, 26/26 test; log: `docs/logs/build-20261005-*.txt`

## [1.3.0] — 2026-10-04 (kullanıcı geri bildirimiyle)

### Düzeltildi
- **Resim → PDF çökmesi**: artık her resim AYRI ayrı korunur — bozuk/
  desteklenmeyen bir resim (CMYK JPEG, HEIC…) tüm aktarımı durdurmaz,
  atlanır ("N resim atlandı" bilgisiyle). Bellek disiplini sıkılaştı
  (çözünürlük 2200→1600px, JPEG 78), kayıttan sonra PDF geri açılıp
  doğrulanır, yarım dosyalar temizlenir ve tüm hatalar
  `filesDir/logs/last_error.txt`'ye yazılır (cihazda teşhis için).
- **"Oku" sayfadan başlamıyordu**: `ReaderActivity.onServiceConnected`
  aynı kitap açıkken `openBook`'u atlıyordu → servis eski sayfada kalıyor,
  okuma ya baştan ya da kayıtlı yerden başlıyordu. Artık: taze açılışta
  kullanıcı'nın seçtiği sayfa her zaman uygulanır; servis bağlanmadan
  yapılan atlamalar biriktirilip bağlanınca uygulanır; oynat tuşu
  ekrandaki sayfadan başlar (güvenlik ağı).
- **Yavaşlatma yoktu**: hız döngüsü yerine **− / + butonları**
  (0.5x – 2.0x, Ayarlar'da da 0.5x eklendi). Ayrıca `prefetch` bug'ı:
  hız/ses değişince önceden indirilen mp3 ESKİ hızla çalınıyordu →
  artık iptal edilir.
- **Bildirimden okuma ekranı açılınca sayfa sıfırlanmıyordu** (niyet
  extra'sı yoksa servisin durumu korunur).

### Eklendi
- **Karaoke cümle vurgusu** — okunan cümlenin satırları sayfa
  görüntüsünde vurgulanır. Altyapı: `BoxStripper` (PDFBox ile metinle
  aynı geçişte satır kutuları) + ML Kit OCR satır kutuları →
  `boxes_NNNN.txt` (0..1 normalize). Eski kitaplarda kutular ilk açılışta
  arka planda üretilir. Çeviri modunda eşleşme yoksa vurgusuz devam eder.
- **Kitap içi arama** — okuma ekranında büyüteç: arama arka planda,
  sonuç satırına tıklayınca o sayfaya atlama.
- **Kütüphane yenilendi** — üstte **"Kaldığın yerden devam" kartı**
  (kapak + sayfa + tek dokunuşla devam), altında sıralama çipleri:
  *Son okunan* / *Alfabetik* (Türkçe collator) / *Eklenme*. Kitaplar
  artık **kapak önizlemeli** (1. sayfadan otomatik üretilir, eski
  kitaplarda da arka planda).
- **Ses Renkleri paneli** (Ses butonu → panel):
  - **Perde (pitch)** 0.5–2.0: hem yerleşik TTS hem Edge SSML'de çalışır
  - **Hazır ayarlar**: Doğal / Derin / Çocuk / Vurgulu
  - **Ekolayzır** (Bas/Orta/Tiz): yalnız Edge mp3 modunda, MediaPlayer
    ses oturumuna `AudioEffect.Equalizer` ile takılır; yerleşik seste
    panel uyarısıyla devre dışı
  - Ses seçimi (Otomatik/Kadın/Erkek) artık panelde
- **Material 3 (koyu) tema** — M3 renk nitelikleri, koyu dialoglar,
  yuvarlak köşeli kapaklar.

### Test
- 22 → **26 birim testi** (`matchingLines_*` karaoke senaryoları), 26/26 geçiyor.

## [1.2.0] — 2026-10-04

### Eklendi
- **Doğal ses (Edge Neural TTS)** — `EdgeTts.kt`: Microsoft Edge'in ücretsiz
  neural sesleri (tr-TR-Emel/Neural). Ayarlar → "Doğal ses" **varsayılan açık**;
  ağ yoksa veya servis 2 kez hata verirse otomatik olarak yerleşik sese döner,
  okuma asla durmaz. Sıradaki cümle önceden indirilir (`prefetch`), bekleme kısalır;
  indirilen mp3'ler çalar çalmaz ve 1 saatten sonra temizlenir.
- **Fotoğraftan PDF** — "Resimlerden PDF Yap": seçilen sayfa fotoğrafları sırayla
  A4 sayfalara yerleşip tek PDF olur (EXIF yönü uygulanır), kütüphaneye kitap olarak
  eklenip otomatik OCR'e girer → ebook gibi arşivlenir.
- **Word / metin dosyaları** — `DocumentConverter.kt`: DOCX, TXT, MD, CSV, LOG
  dosyaları PDF'e çevrilip aynı okuma akışına girer (Roboto ile sayfa çizimi,
  Türkçe tam destekli; DOCX için zip+XML çözümleyici, ek kütüphane yok).
- **Sembol → konuşma normalizasyonu** — `TextUtil.forSpeech()`: matematik
  (∑ ∫ √ ≤ ≥ ∞ π ² ³ …), yazılım (== != && || -> => { } [ ] ` ` \ `), para birimleri,
  HTML etiketleri, LaTeX parçacıkları ve kısaltmalar (vb. vs. Dr.) Türkçe okunuşuna
  çevrilir. Sadece TTS'e uygulanır, ekrandaki metin değişmez.

### Düzeltildi
- **Resimden PDF hiç kaydedilmiyordu** (`importImages` içinde `doc.save(dest)`
  eksikti): kitap listeye giriyor ama `source.pdf` hiç yazılmıyordu → çıkarma
  sonsuza kadar sürüyordu. Kompanlama uyarısı (`dest is never used`) da bunu ele
  veriyordu.
- **Varsayılan ses artık doğal ses** — "robot gibi okuyor" şikâyetinin çözümü.

### Test
- 14 → **22 birim testi** (`forSpeech_*` senaryoları), 22/22 geçiyor.

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

## [1.1.2] — 2026-10-03 (hotfix 2 — cihaz geri bildirimiyle)

### Düzeltildi
- **"Oku"ya basınca uygulamanın kapanması** (cihazdan gelen stack trace ile):
  `ReaderActivity.<init>` → `NullPointerException` @ `ScaleGestureDetector.<init>`
  - Neden: zoom için eklediğimiz `ScaleGestureDetector(this, …)` **sınıf seviyesinde**
    tanımlıydı; Activity henüz context almadan çalışıyordu.
  - Çözüm: `by lazy` ile ilk dokunuşa (onCreate sonrasına) ertelendi. Tarama yapıldı,
    benzer kalıptan başka örnek yok.
- **ChatActivity sızıntısı**: `scope`/`pendingJob` hiçbir yerde cancel edilmiyordu;
  `onDestroy` eklendi. Sayfa metinleri (büyük kitaplarda yüzlerce dosya) artık ana
  thread'de değil IO thread'inde okunuyor.
- **`:app` (Jarvis) modülü hiç derlenmiyordu** (ilk günden beri, CI'ı da kırıyordu):
  - `line.split("\s+")` → kaçış hatası (2 dosya) `"\\s+"` yapıldı
  - `state.duration` → `PlaybackState`'te yok; `MediaMetadata` METADATA_KEY_DURATION
    üzerinden süre okunacak şekilde düzeltildi
  - `mediaSessionManager?.activeSessions` → method çağrısı `getActiveSessions(null)`

### Kontrol
- `gradlew testDebugUnitTest assembleDebug assembleRelease` → **BUILD SUCCESSFUL**
  (iki modül, 14/14 test)
