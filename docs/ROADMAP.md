# Yol Haritası — Umay PDF Okuyucu

> Son güncelleme: 4 Ekim 2026

## Faz 0 — ✅ Güvenlik (tamam)
- [x] İlk git commit (74 dosya kayıtlı)
- [x] Repo yapılandırması incelendi (CI, script'ler, ignore)

## Faz 1 — ✅ Kritik düzeltmeler (tamam, 3 Ekim 2026)
- [x] **OCR ilerleme aşaması** — `stage="ocr"` hiç yayınlanmıyordu, ekranlar yanlış metni
      gösteriyordu; `ocr_yapiliyor` formatı `n / m sayfa` oldu (MainActivity + ReaderActivity)
- [x] **Boş sayfa kilitlenmesi** — OCR başarısızsa boş dosya yazılıp bir daha hiç
      denenmiyordu; artık `needsOcr` boş dosyayı da fark eder ve tekrar dener
- [x] **İlerleme sızıntısı** — kitap silinince `AppPrefs` kaydı ve `last_book` temizleniyor
- [x] **Servis hayatta kalma** — `START_NOT_STICKY` → `START_STICKY` +
      `restoreAfterSystemKill()`: sistem öldürürse kaldığı cümelden otomatik devam
- [x] **Bekleme süresi** — sayfa metni artık çıkarma bitene kadar bekler (12 dk üst sınır)
      + 60 sn tampon (eski: sabit 120 sn)

## Faz 2 — ✅ Deneyim ilaveleri (tamam, 3 Ekim 2026)
- [x] **Kütüphanede arama** — Türkçe karakter duyarsız, anlık filtre
- [x] **Yakınlaştırma** — pinch-to-zoom (1x–8x), sürükleme, çift dokunuşla sıfırlama,
      sayfa değişince otomatik sıfır
- [x] **`normalize` bug'ı** — "İstanbul" araması Türkçe locale dışında eşleşmiyordu
      (test yakaladı)

## Faz 3 — ✅ Kalite & Yayın (tamam, 3 Ekim 2026)
- [x] Birim test altyapısı: **14/14 test geçiyor**
- [x] Release build + imzalama yapılandırması (v1.1, versionCode 2)
- [x] CI: test + release APK artifact adımları eklendi
- [x] Doküman paketi: README, CLAUDE.md, ARCHITECTURE, CHANGELOG, loglar

## Faz 3.5 — ✅ 4 Ekim 2026 (kullanıcı istekleri)
- [x] **Doğal ses** — Edge Neural TTS (ücretsiz, anahtarsız); çevrimdışı/ hatada
      yerleşik sese otomatik düşüş, prefetch ile bekleme kısaltma
- [x] **Sembol okunuşu** — matematik/yazılım sembolleri Türkçe karşılığıyla okunur
      (`TextUtil.forSpeech`, 8 test)
- [x] **Fotoğraf → PDF** — sayfa fotoğrafları tek PDF'e birleşir + otomatik OCR
      (EXIF yön desteği)
- [x] **DOCX/TXT/MD/CSV → PDF** — Word ve metin dosyaları da okunabilir
- [x] Kritik bug: resimden PDF'te `doc.save` eksikti (dosya hiç yazılmıyordu)
- [x] Sürüm 1.2 (versionCode 3)

## Faz 3.6 — ✅ 4 Ekim 2026 (geri bildirim turu 2)
- [x] **Resim→PDF çökme onarımı** — görsel başına izolasyon, bellek cap'i,
      PDF doğrulama, `last_error.txt` hata günlüğü
- [x] **Sayfa senkronu** — "Bu sayfadan okumaya başla" butonu +
      servis/ekran eşitleme ("Oku o sayfadan başlamıyordu" bug'ı)
- [x] **Yavaşlatma** — 0.5x–2.0x −/+ butonları + prefetch iptal bug'ı
- [x] **Kütüphane** — devam kartı, sıralama çipleri, kapak önizlemeleri
- [x] **Karaoke cümle vurgusu** — BoxStripper + OCR satır kutuları
- [x] **Kitap içi arama** — büyüteç, sonuca tıkla → sayfaya git
- [x] **Ses Renkleri paneli** — perde, hazır ayarlar, ekolayzır (Edge)
- [x] **Material 3 koyu tema**
- [x] Sürüm 1.3 (versionCode 4), 26/26 test

## Faz 3.7 — ✅ 5 Ekim 2026 (geri bildirim turu 3 — "uygulama açılmıyor")
- [x] **`ic_search.xml` pathData crash'i** — `C` komutunda 6'nın katı olmayan
      22 koordinat → `ReaderActivity` açılışta `InflateException` ile çöküyordu.
      12 vektörün tamamı tarandı, yol geometrisi doğrulandı, APK içine bakıldı.
- [x] **Gemini sohbeti çalışır hale getirildi** — model otomatik keşfi + 404'te
      yedek zincir, `thinkingBudget=0`, 2048 token, Türkçe hata mesajları,
      Ayarlar'da "Bağlantıyı test et" butonu.
- [x] Sürüm 1.3.1 (versionCode 5), 26/26 test

## Faz 3.8 — ✅ 5 Ekim 2026 (geri bildirim turu 4)
- [x] **Galeri → PDF "hiçbiri okunamadı"** — `loadBitmapForPdf` içindeki
      `?: return null` tuzağı (`inJustDecodeBounds` null döner) → her resim
      atlanıyordu. Çözücü onarıldı + detaylı hata + kalıcı URI izni.
- [x] **Kamerayla kitap tarama** — `ScannerActivity` (sayfa ızgarası, sil,
      PDF yap → kütüphane → okuma ekranı), FileProvider, CAMERA izni gerekmez.
- [x] **Sayfa numarasına göre doğal sıralama** — `TextUtil.naturalKey`
- [x] **Ayarlar → Ekolayzır** paneli (anında uygulama)
- [x] **Sohbet bağlamı** — `TextUtil.contextPages` (kısmi eşleşmeli sayfa puanlama)
- [x] Sürüm 1.4 (versionCode 6), 32/32 test

## Faz 4 — 🔜 Sıradaki (öneri sırası)
- [ ] **Cihazda doğrulama** — Edge Neural ses protokolü, resim→PDF akışı,
      karaoke vurgu koordinatları (y eksenı) gerçek telefonda denenmeli
- [ ] **Vurgu konumu iyileştirmesi** — cihazda kayarsa BoxStripper
      koordinat düzeltmesi (mediaBox/cropBox, döndürme)
- [ ] **Resme kitap adı verme** — "Fotoğraflar (N sayfa)" yerine kullanıcıya ad sor
- [ ] **Kamera ile anlık çekim** — seçici yerine doğrudan kameradan çoklu çekim
- [ ] **Eski .doc / .odt / .rtf** desteği (şimdilik bilinçli olarak yok, net hata verir)
- [ ] **Cümle vurgusu** — okunan cümle sayfa görüntüsü üzerinde highlights
- [ ] **Kütüphane kapak/önizlemesi** — ilk sayfadan thumbnail (PdfRenderer 1 sayfa)
- [ ] **Gerçek release anahtarı** — `.jks` üretip `signingConfigs.release`'e bağla
      (git'e girmez; CI'da secret olarak sakla)
- [ ] **Ses seçimi arayüzü** — okuma ekranında ses listesi (şu an sadece Ayarlar'da)
- [ ] **Kitap içi arama** — "hangi sayfada geçiyor?" gezinme
- [ ] **Widget / MediaStyle bildirim** — Android 13+ media kontrolleri
- [ ] **Entegrasyon testi** — cihazda gerçek PDF ile uçtan uca senaryo
- [ ] **Play Store hazırlığı** — gizlilik politikası, içerik derecelendirmesi
