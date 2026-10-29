# UMAY PDF Reader

Telefonundaki PDF, slayt, doküman ve resimleri **açan**, **sesli okuyan**,
içindekileri **yapay zekâ ile açıklayan** ve kitaplığını raflara ayıran
Android uygulaması. Türkçe arayüz, çevrimdışı çalışma, ekran kapalıyken
okumaya devam.

## ⬇️ İndir (APK)

**Doğrudan indirme:**
[UmayPdfReader.apk](https://github.com/gecekargosu/umay-pdf-reader/releases/latest/download/UmayPdfReader.apk)

Telefonda bu bağlantıya dokun → dosya iner → üzerine dokun → **Yükle**.
Uyarı çıkarsa **"Yine de yükle"**.

Sürüm: **5.13** (kod 30) · Android 8.0 ve üzeri · ~70 MB

## Kurulum (kısa)

Program Google Play'de olmadığı için Android "bilinmeyen kaynak" uyarısı verir.
Bu normaldir; izin verip devam edilir.

| Telefon | Yapılacak |
|---|---|
| **Samsung** | Ayarlar → Güvenlik ve gizlilik → Diğer güvenlik ayarları → *Bilinmeyen uygulamaları yükle* → izin ver. One UI 6.1+ ise **Otomatik engelleme**'yi kapat. |
| **Xiaomi / Redmi / POCO** | Ayarlar → Gizlilik koruması → Özel izinler → *Bilinmeyen kaynaklardan yükleme* → izin ver. Kurulumdan sonra **Otomatik başlatma** aç, pil → *Kısıtlama yok*. |
| **Diğer Android** | Ayarlar → Uygulamalar → Özel erişim → *Bilinmeyen uygulamaları yükle* → Dosyalar/Chrome → izin ver. |
| **Tüm cihazlar** | Play Protect "tanımıyor" derse **Yine de yükle**. |

Ayrıntılı rehberler: [`docs/kurulum/`](docs/kurulum)
- [Önce bunu oku](docs/kurulum/00-ONCE-BUNU-OKU.txt)
- [Samsung](docs/kurulum/01-SAMSUNG-KURULUM.txt)
- [Xiaomi / Redmi / POCO](docs/kurulum/02-XIAOMI-REDMI-KURULUM.txt)
- [Diğer Android markaları](docs/kurulum/03-DIGER-ANDROID-KURULUM.txt)
- [WhatsApp / Telegram ile gönderme](docs/kurulum/04-WHATSAPP-TELEGRAM.txt)

### İlk açılışta önerilen izinler
- **Bildirim** — sesli okuma kontrolü, ders/sınav hatırlatmaları
- **Tüm dosyalara erişim** — cihazdaki belgeleri kütüphaneye ekleme
- **Pil → Kısıtlama yok** — ekran kapalıyken okumanın sürmesi
- **Tam alarm** — ders/sınav alarmlarının zamanında çalması

## Özellikler

- **Kitaplık ve raflar**: ders/dosya adına göre otomatik yerleştirme, her raf
  farklı neon çerçeve, yatay kaydırma, alfabetik / en son eklenen sıralaması
- **Okuma**: PDF, DOC/DOCX, PPTX, TXT, EPUB görüntüleme; sayfa bazlı metin
  çıkarma; taranmış sayfalar için OCR; Arapça OCR modu; karaoke vurgusu
- **Sesli okuma**: Edge Neural sesler, yerleşik TTS'e otomatik düşüş, gerçek
  PCM ekolayzır, 700+ sayfalık kitaplarda takılmadan ilerleme, ekran kapalıyken
  devam, İngilizce metni Türkçeye çevirerek okuma
- **Yapay zekâ**: kitap içinden kaynaklı yanıt (sayfa numarasıyla), konu bazlı
  sohbet geçmişi, sayfa özeti
- **Dersler**: program, sınav türleri (vize/final/bütünleme/quiz), yüzdeler,
  notlar, ANO/AGNO, ders ses kayıtları ve video rafları
- **Gizli kasa**: PIN + parmak izi, AES-256 şifreli resim/video/belge rafları
- **Arka plan ön hazırlık**: şarjdayken ve boştayken kitapları okumaya hazırlar,
  sorunlu sayfaları raporlar
- **Durum lambaları**: kırmızı/sarı/yeşil; sorun varsa yanıp söner ve sesli
  uyarı verir; yeşil lamba 2 dakikada bir kendi kendini denetler

## Kaynaktan derleme

Gereksinim: **JDK 17** (JDK 21/25 ile AGP 8.1.4 derlemez), Android SDK 36.

```bash
export JAVA_HOME=/yol/jdk-17
./gradlew :umay:assembleDebug
# çıktı: umay/build/outputs/apk/debug/umay-debug.apk
```

Gömülü yapay zekâ anahtarı **depoda yoktur** (güvenlik). Kendi ücretsiz
Gemini anahtarını iki yoldan biriyle ver:

```properties
# UmayWork/local.properties
umay.geminiKey=AIza...
```
veya ortam değişkeni: `export UMAY_GEMINI_KEY=AIza...`

Anahtar yoksa uygulama yine çalışır; sohbet çevrimdışı PDF aramasına düşer,
kullanıcı anahtarını uygulama içinden Ayarlar'dan girebilir.

Yayın imzası için `umay/keystore.properties` + `umay/GUNCELLEME_ANAHTARLARI/umay-release.jks`
gerekir; ikisi de depoda **yoktur** ve paylaşılmaz.

## Gizlilik

Kitaplar ve notlar cihazda kalır. Yalnız sesli okuma (Edge TTS) ve yapay zekâ
özellikleri ağ kullanır; ilgili metin parçaları istek anında gönderilir.
