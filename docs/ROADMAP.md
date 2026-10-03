# Yol Haritası — Umay PDF Okuyucu

> Son güncelleme: 3 Ekim 2026

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

## Faz 4 — 🔜 Sıradaki (öneri sırası)
- [ ] **Cümle vurgusu** — okunan cümle sayfa görüntüsü üzerinde highlights
- [ ] **Kütüphane kapak/önizlemesi** — ilk sayfadan thumbnail (PdfRenderer 1 sayfa)
- [ ] **Gerçek release anahtarı** — `.jks` üretip `signingConfigs.release`'e bağla
      (git'e girmez; CI'da secret olarak sakla)
- [ ] **Ses seçimi arayüzü** — okuma ekranında ses listesi (şu an sadece Ayarlar'da)
- [ ] **Kitap içi arama** — "hangi sayfada geçiyor?" gezinme
- [ ] **Widget / MediaStyle bildirim** — Android 13+ media kontrolleri
- [ ] **Entegrasyon testi** — cihazda gerçek PDF ile uçtan uca senaryo
- [ ] **Play Store hazırlığı** — gizlilik politikası, içerik derecelendirmesi
