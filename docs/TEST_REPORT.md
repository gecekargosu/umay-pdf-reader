# Build & Test Raporu — Umay PDF Okuyucu 1.1

> Tarih: 3 Ekim 2026 · Ortam: Linux aarch64, JDK 21, Android SDK 34, Gradle 8.7

## Sonuç Özeti

| Kontrol | Sonuç |
|---|---|
| `:umay:testDebugUnitTest` | ✅ **14/14 test geçti** (0 başarısız) |
| `:umay:assembleDebug` | ✅ BUILD SUCCESSFUL |
| `:umay:assembleRelease` | ✅ BUILD SUCCESSFUL (lintVital dahil) |
| Build logu | `docs/logs/build-20261003-1634.txt` |

## APK Çıktıları

| Dosya | Boyut | SHA256 |
|---|---|---|
| `umay-release.apk` → telefona `umay-pdf-reader-1.1.apk` | 59 MB | `cdade8c424579ee3467cf158e3aa8dbecf83ac39130f469993a64668800caf33` |
| `umay-debug.apk` → `umay-pdf-reader-1.1-debug.apk` | 61 MB | — |

Telefondaki konum: `/storage/emulated/0/FreebuffProject/umay-pdf-reader-1.1.apk`

## Test Listesi (14)

| # | Test | Durum |
|---|---|---|
| 1 | `speechChunks_kisaCumleleriBirlestirir` | ✅ |
| 2 | `speechChunks_bosMetin_bosDoner` | ✅ |
| 3 | `speechChunks_uzunCumle450KarakteriGecmez` | ✅ |
| 4 | `looksTurkish_turkceMetniTanimlar` | ✅ |
| 5 | `looksTurkish_ingilizceMetniTanimlamaz` | ✅ |
| 6 | `needsTranslation_kapaliyken_hicDonmez` | ✅ |
| 7 | `needsTranslation_acikIngilizceMetni_ister` | ✅ |
| 8 | `search_ilgiliCumleleriBulur` | ✅ |
| 9 | `search_sayfaNumarasiDogru` | ✅ |
| 10 | `search_turkceKarakterDuyarsiz` | ✅ |
| 11 | `search_bosSoru_bosDoner` | ✅ |
| 12 | `summarize_doluListe_ozetDoner` | ✅ |
| 13 | `summarize_bosListe_mesajDoner` | ✅ |
| 14 | `normalize_turkceKarakterleriSadelestirir` | ✅ |

## İlk Turda Yakalanan ve Düzeltilenler

İlk test çalıştırmasında **4/14** düştü:

1. `speechChunks` — test beklentisi yanlıştı (kısa cümleler 450 krkt'a kadar birleşir;
   davranışın kendisi doğru) → test düzeltildi.
2. **`normalize` gerçek bug çıktı**: `lowercase(Locale.ROOT)` kullanınca "İstanbul"
   → `i` + birleşik nokta oluyordu ve arama eşleşmiyordu. `Locale.forLanguageTag("tr-TR")`
   ile düzeltildi. → **üretim kodu düzeldi.**
3. `search_sayfaNumarasiDogru` — testteki cümle 24 karakterdi, kod 25 altındaki
   cümleleri gürültü diye atlıyor (kasıtlı davranış) → test uzatıldı.
4. `summarize` — test, özetlenen ilk cümlelerde olmayan kelimeyi arıyordu →
   gerçek davranışa göre düzeltildi.

Yani test paketi, gerçek bir arama bug'ını üretim kodundan yakalayıp düzelttirdi.

## Elle Denenmesi Gerekenler (cihazda)

- [ ] PDF içeri aktar → ilerleme çubuğu "Metin çıkarılıyor: n/m" ve OCR'da "OCR: n/m"
- [ ] Okumayı başlat → ekranı kapat → 5 dk bekle → bildirim hâlâ var mı, devam ediyor mu
- [ ] Servisi (uygulamayı zorla) öldür → okuma otomatik devam ediyor mu
- [ ] Taranmış/görsel PDF → OCR metni seslendiriliyor mu
- [ ] Kitap sil → tekrar ekle → "kaldığın yer" sorusu çıkmamalı
- [ ] Zoom: çift parmak, çift dokunuş, sayfa değişiminde sıfırlama
