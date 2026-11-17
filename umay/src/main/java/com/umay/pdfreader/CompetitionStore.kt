package com.umay.pdfreader

/**
 * v5.15 — PROJE YARIŞMALARI KATALOĞU (çevrimdışı).
 *
 * Neden yerel katalog? Uygulama çevrimdışı çalışır ve bu kurumların
 * "yayınlanmış tüm yarışmalar" için ortak bir API'si yok. Burada her yıl
 * tekrarlanan, resmî sayfası olan programlar tutulur; kesin takvim/şart
 * için kartın altındaki "Siteyi aç" düğmesi resmî sayfaya gider.
 *
 * KURAL: Buraya UYDURMA tarih/ödül yazılmaz. Tarih bilinmiyorsa
 * "Güncel takvim resmî sitede" yazar.
 */
object CompetitionStore {

    data class Competition(
        val id: String,
        val title: String,
        val org: String,
        val category: String,
        /** Listede görünen 1-2 cümlelik özet. */
        val summary: String,
        /** Detay penceresindeki kapsam açıklaması. */
        val details: String,
        /** "18 Kasım 2026 17:30" veya "Güncel takvim resmî sitede". */
        val deadline: String,
        /** Destek/ödül metni; yoksa boş. */
        val support: String,
        val url: String,
        /** Kapak gradyanı (üst, alt) — ARGB. */
        val colorFrom: Int,
        val colorTo: Int,
        /** Kapak üzerindeki kısa rozet (ör. "2209-A", "TF"). */
        val badge: String
    )

    val CATEGORIES = listOf(
        "Tümü", "Üniversite", "Girişimcilik", "Savunma & Uzay", "Teknoloji & Telekom"
    )

    private const val BLUE = 0xFF1D63D8.toInt()
    private const val BLUE_DARK = 0xFF0E2E6B.toInt()
    private const val ORANGE = 0xFFF2620C.toInt()
    private const val ORANGE_DARK = 0xFF7A2A00.toInt()
    private const val TEAL = 0xFF0E7C86.toInt()
    private const val TEAL_DARK = 0xFF063B40.toInt()
    private const val PURPLE = 0xFF6B3FD4.toInt()
    private const val PURPLE_DARK = 0xFF2A1560.toInt()
    private const val GREEN = 0xFF2E8B57.toInt()
    private const val GREEN_DARK = 0xFF0F3D26.toInt()
    private const val RED = 0xFFC62828.toInt()
    private const val RED_DARK = 0xFF5A0F0F.toInt()

    val ALL: List<Competition> = listOf(
        Competition(
            id = "tubitak-2209a",
            title = "2209-A Üniversite Öğrencileri Araştırma Projeleri",
            org = "TÜBİTAK BİDEB",
            category = "Üniversite",
            summary = "Lisans/ön lisans öğrencisinin kendi araştırma projesine malzeme, seyahat ve hizmet desteği.",
            details = "Bireysel veya takım halinde başvurulur; akademik danışman onayı gerekir. " +
                "Başvuru TÜBİTAK Yönetim Bilgi Sistemi (TYBS) üzerinden yapılır. " +
                "Araştırma önerisi formu sistemde doldurulur, ayrıca yüklenmez.",
            deadline = "2026 çağrısı: 18 Kasım 2026 17:30 (uzatıldı)",
            support = "Makine/teçhizat, sarf, seyahat ve hizmet alımı",
            url = "https://tubitak.gov.tr/tr/burslar/lisans-onlisans/destek-programlari/2209-universite-ogrencileri-arastirma-projeleri-destekleme-programi",
            colorFrom = BLUE, colorTo = BLUE_DARK, badge = "2209-A"
        ),
        Competition(
            id = "tubitak-2209b",
            title = "2209-B Sanayiye Yönelik Araştırma Projeleri",
            org = "TÜBİTAK BİDEB",
            category = "Üniversite",
            summary = "Sanayide çalışan/ staj yapan öğrencinin firma problemi üzerine kurduğu proje desteği.",
            details = "Proje, sanayi kuruluşunda görevli bir danışman ve akademik danışmanla yürütülür. " +
                "Başvuru 2209-A ile aynı takvimde, TYBS üzerinden alınır.",
            deadline = "2026 çağrısı: 18 Kasım 2026 17:30 (uzatıldı)",
            support = "Proje giderleri (sarf, hizmet, seyahat)",
            url = "https://tubitak.gov.tr/tr/burslar/lisans-onlisans/destek-programlari/2209-b-universite-ogrencileri-sanayiye-yonelik-arastirma-projeleri-destegi-programi",
            colorFrom = BLUE, colorTo = TEAL_DARK, badge = "2209-B"
        ),
        Competition(
            id = "tubitak-2242",
            title = "2242 Üniversite Öğrencileri Araştırma Proje Yarışmaları",
            org = "TÜBİTAK + TEKNOFEST",
            category = "Üniversite",
            summary = "9 ana alanda (yazılım, savunma, sağlık, enerji, sosyal yenilikçilik…) proje yarışması.",
            details = "Ön lisans ve lisans öğrencileri bireysel veya en fazla 5 kişilik takımla başvurur. " +
                "Ön değerlendirmeyi geçenler proje raporunu E-BİDEB'e yükler; finale kalanlar sunum yapar.",
            deadline = "Güncel takvim resmî sitede",
            support = "Ödül + TEKNOFEST finalistliği",
            url = "https://tubitak.gov.tr/tr/yarismalar/2242-universite-ogrencileri-arastirma-proje-yarismalari",
            colorFrom = ORANGE, colorTo = ORANGE_DARK, badge = "2242"
        ),
        Competition(
            id = "teknofest",
            title = "TEKNOFEST Teknoloji Yarışmaları",
            org = "T3 Vakfı · Sanayi ve Teknoloji Bakanlığı",
            category = "Teknoloji & Telekom",
            summary = "Türkiye'nin en büyük teknoloji festivali; 50'den fazla yarışma kategorisi.",
            details = "İnsansız hava aracı, roket, elektrikli araç, yapay zekâ, tarım, sağlık, " +
                "finansal teknolojiler gibi kategorilerde takım halinde yarışılır. " +
                "Takım başvurusu, rapor ve finale kalma aşamaları vardır.",
            deadline = "Güncel takvim ve kategoriler resmî sitede",
            support = "Para ödülü, malzeme desteği, finalist eğitimleri",
            url = "https://www.teknofest.org",
            colorFrom = TEAL, colorTo = TEAL_DARK, badge = "TF"
        ),
        Competition(
            id = "tubitak-bigg",
            title = "1512 BiGG — Girişimcilik Destek Programı",
            org = "TÜBİTAK",
            category = "Girişimcilik",
            summary = "Teknoloji tabanlı iş fikrini şirketleştirmek isteyen girişimciye hibe + mentorluk.",
            details = "Üç aşamalı program: iş planı, şirket kuruluşu ve yatırım öncesi destek. " +
                "Başvuru BiGG portalından yapılır; değerlendirme sunumla ilerler.",
            deadline = "Dönemler halinde; güncel çağrı sitede",
            support = "Hibe + mentorluk + ofis imkânları",
            url = "https://bigg.tubitak.gov.tr",
            colorFrom = GREEN, colorTo = GREEN_DARK, badge = "BiGG"
        ),
        Competition(
            id = "tubitak-1512",
            title = "1512 Girişimcilik Destek Programı",
            org = "TÜBİTAK TEYDEB",
            category = "Girişimcilik",
            summary = "Erken aşama teknoloji girişimlerine sermaye ve danışmanlık desteği.",
            details = "Şirket kurmuş ya da kurmak üzere olan girişimciler başvurur. " +
                "Proje başvurusu PRODİS üzerinden, iş planı ve teknik eklerle yapılır.",
            deadline = "Güncel çağrı takvimi resmî sitede",
            support = "Sermaye desteği + danışmanlık",
            url = "https://tubitak.gov.tr/tr/destekler/sanayi/ulusal-destek-programlari/1512-girisimcilik-destek-programi",
            colorFrom = GREEN, colorTo = TEAL_DARK, badge = "1512"
        ),
        Competition(
            id = "tubitak-1812",
            title = "1812 Yatırım Tabanlı Girişimcilik Desteği",
            org = "TÜBİTAK TEYDEB",
            category = "Girişimcilik",
            summary = "Yatırımcıdan fon alan girişimlere, alınan yatırımla orantılı ek destek.",
            details = "Bağımsız yatırımcıdan yatırım almış teknoloji girişimleri başvurur; " +
                "TÜBİTAK yatırım tutarına göre destek verir.",
            deadline = "Güncel çağrı takvimi resmî sitede",
            support = "Yatırım oranlı hibe",
            url = "https://tubitak.gov.tr/tr/destekler/sanayi/ulusal-destek-programlari/1812-yatirim-tabanli-girisimcilik-destek-programi",
            colorFrom = PURPLE, colorTo = PURPLE_DARK, badge = "1812"
        ),
        Competition(
            id = "kosgeb",
            title = "KOSGEB Girişimcilik Destekleri",
            org = "KOSGEB",
            category = "Girişimcilik",
            summary = "İş kurma, gelişme ve uluslararasılaşma aşamalarına yönelik KOBİ destekleri.",
            details = "Girişimci eğitimini tamamlayanlar iş planıyla başvurur. " +
                "İmalat, bilişim ve Ar-Ge odaklı işlerde destek kalemleri genişler.",
            deadline = "Dönemsel; güncel çağrı sitede",
            support = "Hibe + faizsiz kredi",
            url = "https://www.kosgeb.gov.tr",
            colorFrom = ORANGE, colorTo = RED_DARK, badge = "KOSGEB"
        ),
        Competition(
            id = "aselsan",
            title = "ASELSAN Lisans / Ar-Ge Proje Yarışmaları",
            org = "ASELSAN",
            category = "Savunma & Uzay",
            summary = "Savunma elektroniği odaklı öğrenci proje yarışmaları ve staj/kariyer programları.",
            details = "ASELSAN Akademi kapsamında lisans ve lisansüstü öğrencilere yönelik " +
                "proje yarışmaları, bitirme projesi destekleri ve staj programları duyurulur.",
            deadline = "Dönemsel duyuru; resmî sayfada",
            support = "Ödül, staj ve işe alım avantajı",
            url = "https://www.aselsan.com/tr/kariyer",
            colorFrom = RED, colorTo = RED_DARK, badge = "AS"
        ),
        Competition(
            id = "tusas",
            title = "TUSAŞ Havacılık ve Uzay Projeleri",
            org = "TUSAŞ (Turkish Aerospace)",
            category = "Savunma & Uzay",
            summary = "Havacılık/uzay mühendisliği odaklı öğrenci yarışmaları, staj ve bitirme projeleri.",
            details = "TUSAŞ, üniversitelerle ortak bitirme projeleri ve öğrenci yarışmaları düzenler; " +
                "duyurular kariyer sayfasında yayınlanır.",
            deadline = "Dönemsel duyuru; resmî sayfada",
            support = "Ödül, staj, proje desteği",
            url = "https://www.tusas.com",
            colorFrom = BLUE_DARK, colorTo = TEAL_DARK, badge = "TUSAŞ"
        ),
        Competition(
            id = "roketsan",
            title = "ROKETSAN Öğrenci Projeleri",
            org = "ROKETSAN",
            category = "Savunma & Uzay",
            summary = "Roket/füze teknolojileri ve malzeme alanında öğrenci proje ve staj programları.",
            details = "Üniversite öğrencilerine yönelik bitirme projesi, staj ve teknik yarışma " +
                "duyuruları kurumsal kariyer sayfasından yayınlanır.",
            deadline = "Dönemsel duyuru; resmî sayfada",
            support = "Ödül, staj, proje desteği",
            url = "https://www.roketsan.com.tr",
            colorFrom = RED_DARK, colorTo = 0xFF1A1A1A.toInt(), badge = "RKS"
        ),
        Competition(
            id = "havelsan",
            title = "HAVELSAN Teknoloji Programları",
            org = "HAVELSAN",
            category = "Savunma & Uzay",
            summary = "Komuta kontrol, simülasyon ve yapay zekâ alanlarında öğrenci programları.",
            details = "HAVELSAN; hackathon, bitirme projesi ve staj programlarını dönemsel olarak duyurur.",
            deadline = "Dönemsel duyuru; resmî sayfada",
            support = "Ödül, staj, mentorluk",
            url = "https://www.havelsan.com.tr",
            colorFrom = TEAL_DARK, colorTo = PURPLE_DARK, badge = "HVL"
        ),
        Competition(
            id = "baykar",
            title = "Baykar Teknoloji ve Girişimcilik",
            org = "Baykar",
            category = "Savunma & Uzay",
            summary = "İHA teknolojileri, girişim hızlandırma ve öğrenci yarışma programları.",
            details = "Baykar; TEKNOFEST kategorileri, hızlandırma programları ve staj " +
                "duyurularını kendi kanallarında yayınlar.",
            deadline = "Dönemsel duyuru; resmî sayfada",
            support = "Ödül, hızlandırma, staj",
            url = "https://www.baykartech.com",
            colorFrom = 0xFF0B1220.toInt(), colorTo = TEAL_DARK, badge = "BYK"
        ),
        Competition(
            id = "turkcell",
            title = "Turkcell Girişimcilik ve Teknoloji Programları",
            org = "Turkcell",
            category = "Teknoloji & Telekom",
            summary = "5G, yapay zekâ ve dijital servis odaklı hackathon / hızlandırma programları.",
            details = "Turkcell; girişim hızlandırma, hackathon ve öğrenci programlarını " +
                "dönemsel çağrılarla duyurur.",
            deadline = "Dönemsel duyuru; resmî sayfada",
            support = "Ödül, yatırım görüşmesi, mentorluk",
            url = "https://www.turkcell.com.tr",
            colorFrom = 0xFFF5B700.toInt(), colorTo = ORANGE_DARK, badge = "TC"
        ),
        Competition(
            id = "vodafone",
            title = "Vodafone Girişimcilik Programları",
            org = "Vodafone Türkiye",
            category = "Teknoloji & Telekom",
            summary = "Dijital servis, IoT ve sosyal etki odaklı girişim/hackathon çağrıları.",
            details = "Vodafone; kadın girişimci programları, hackathon ve teknoloji " +
                "iş birliklerini dönemsel olarak duyurur.",
            deadline = "Dönemsel duyuru; resmî sayfada",
            support = "Ödül, mentorluk, pilot çalışma",
            url = "https://www.vodafone.com.tr",
            colorFrom = RED, colorTo = 0xFF2B0A0A.toInt(), badge = "VF"
        ),
        Competition(
            id = "turktelekom",
            title = "Türk Telekom Girişimcilik ve Ar-Ge",
            org = "Türk Telekom",
            category = "Teknoloji & Telekom",
            summary = "Ar-Ge, bulut ve dijital dönüşüm odaklı girişim programları.",
            details = "Türk Telekom Ventures ve Ar-Ge birimleri; hızlandırma, " +
                "pilot ve yatırım programlarını dönemsel çağrılarla açar.",
            deadline = "Dönemsel duyuru; resmî sayfada",
            support = "Yatırım, pilot, mentorluk",
            url = "https://www.turktelekom.com.tr",
            colorFrom = BLUE, colorTo = PURPLE_DARK, badge = "TT"
        ),
        Competition(
            id = "bilisim-vadisi",
            title = "Bilişim Vadisi Girişim Programları",
            org = "Bilişim Vadisi",
            category = "Girişimcilik",
            summary = "Teknoloji girişimleri için kuluçka, hızlandırma ve uluslararasılaşma programları.",
            details = "Bilişim Vadisi; kuluçka başvuruları, demo day ve yatırımcı buluşmaları düzenler.",
            deadline = "Dönemsel; güncel çağrı sitede",
            support = "Ofis, mentorluk, yatırımcı ağı",
            url = "https://bilisimvadisi.com.tr",
            colorFrom = TEAL, colorTo = BLUE_DARK, badge = "BV"
        ),
        Competition(
            id = "teknopark-istanbul",
            title = "Teknopark İstanbul Kuluçka",
            org = "Teknopark İstanbul",
            category = "Girişimcilik",
            summary = "Ar-Ge şirketleri ve öğrenci girişimleri için kuluçka ve mentorluk.",
            details = "Kuluçka programı başvuruları dönemsel açılır; " +
                "üniversite öğrencisi girişimlerine özel kontenjanlar bulunur.",
            deadline = "Dönemsel; güncel çağrı sitede",
            support = "Ofis, mentorluk, laboratuvar",
            url = "https://www.teknoparkistanbul.com.tr",
            colorFrom = PURPLE, colorTo = BLUE_DARK, badge = "TP"
        ),
        Competition(
            id = "girisimcilik-vakfi",
            title = "Türkiye Girişimcilik Vakfı Programları",
            org = "Girişimcilik Vakfı",
            category = "Girişimcilik",
            summary = "Genç girişimcilere eğitim, mentorluk ve ağ erişimi.",
            details = "Vakıf; girişimcilik eğitimleri, bootcamp ve mentorluk programlarını duyurur.",
            deadline = "Dönemsel; güncel çağrı sitede",
            support = "Eğitim, mentorluk, ağ",
            url = "https://www.girisimcilikvakfi.org",
            colorFrom = ORANGE, colorTo = ORANGE_DARK, badge = "GV"
        ),
        Competition(
            id = "genc-basari",
            title = "Genç Başarı Girişimcilik Yarışmaları",
            org = "Genç Başarı Eğitim Vakfı",
            category = "Girişimcilik",
            summary = "Öğrenci takımlarının şirket kurup ürün geliştirdiği girişimcilik yarışması.",
            details = "Takımlar dönem boyunca mini şirket kurar, ürün geliştirir ve " +
                "jüri karşısında sunum yapar.",
            deadline = "Dönemsel; güncel çağrı sitede",
            support = "Ödül, mentorluk, uluslararası finale katılım",
            url = "https://www.gencbasari.org",
            colorFrom = GREEN, colorTo = BLUE_DARK, badge = "GB"
        )
    )

    /** Arama: başlık, kurum, kategori ve özette büyük/küçük harf duyarsız. */
    fun search(query: String, category: String = "Tümü"): List<Competition> {
        val q = query.trim().lowercase()
        return ALL.filter { c ->
            (category == "Tümü" || c.category == category) &&
                (q.isEmpty() ||
                    c.title.lowercase().contains(q) ||
                    c.org.lowercase().contains(q) ||
                    c.category.lowercase().contains(q) ||
                    c.summary.lowercase().contains(q) ||
                    c.badge.lowercase().contains(q))
        }
    }

    fun byId(id: String): Competition? = ALL.firstOrNull { it.id == id }

    // ── v5.16 — SON BAŞVURU TARİHİNE GÖRE SIRALAMA ───────────────────

    /** Türkçe/İngilizce ay adları → 1..12 (ASCII'ye indirgenmiş anahtarlar). */
    private val MONTHS = mapOf(
        "ocak" to 1, "subat" to 2, "mart" to 3, "nisan" to 4, "mayis" to 5,
        "haziran" to 6, "temmuz" to 7, "agustos" to 8, "eylul" to 9, "ekim" to 10,
        "kasim" to 11, "aralik" to 12,
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5,
        "june" to 6, "july" to 7, "august" to 8, "september" to 9, "october" to 10,
        "november" to 11, "december" to 12
    )

    private fun ascii(s: String): String = s.lowercase()
        .replace("ı", "i").replace("ş", "s").replace("ç", "c")
        .replace("ö", "o").replace("ü", "u").replace("ğ", "g")

    /**
     * "18 Kasım 2026 17:30" gibi metinden zaman damgası çıkarır.
     * Tarihi olmayan ("Güncel takvim resmî sitede") kayıtlar için null döner;
     * bu kayıtlar sıralamada en sona düşer, hiçbir zaman uydurulmaz.
     */
    fun deadlineMillis(c: Competition): Long? {
        val m = Regex("(\\d{1,2})\\s+([\\p{L}]+)\\s+(\\d{4})(?:\\s+(\\d{1,2}):(\\d{2}))?")
            .find(c.deadline) ?: return null
        val day = m.groupValues[1].toIntOrNull() ?: return null
        val month = MONTHS[ascii(m.groupValues[2])] ?: return null
        val year = m.groupValues[3].toIntOrNull() ?: return null
        val hour = m.groupValues[4].toIntOrNull() ?: 23
        val minute = m.groupValues[5].toIntOrNull() ?: 59
        return java.util.Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis
    }

    /** Son başvuru tarihi yakın olan önce; tarihi bilinmeyenler alfabetik sonda. */
    fun sortedByDeadline(list: List<Competition> = ALL): List<Competition> =
        list.sortedWith(
            compareBy({ deadlineMillis(it) ?: Long.MAX_VALUE }, { it.title })
        )

    /**
     * Kart üzerinde gösterilecek KISA tarih: "18 Kasım 2026 17:30".
     * Metnin başındaki "2026 çağrısı:" gibi ifadeler atılır; tarih yoksa
     * "Takvim sitede" yazar (uydurma tarih üretilmez).
     */
    fun deadlineLabel(c: Competition): String {
        val m = Regex("(\\d{1,2})\\s+([\\p{L}]+)\\s+(\\d{4})(?:\\s+(\\d{1,2}:\\d{2}))?")
            .find(c.deadline) ?: return ""
        val time = m.groupValues[4]
        return m.groupValues[1] + " " + m.groupValues[2] + " " + m.groupValues[3] +
            if (time.isNotBlank()) " $time" else ""
    }

    /** Resmî sitede arama yapmak için hazır URL (tüm yarışmaları canlı görmek). */
    fun webSearchUrl(query: String): String {
        val q = query.trim().ifEmpty { "girişimcilik proje yarışmaları 2026" }
        return "https://www.google.com/search?q=" +
            java.net.URLEncoder.encode("$q başvuru", "UTF-8")
    }
}
