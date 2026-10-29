package com.umay.pdfreader

import java.io.File
import java.util.Locale

/**
 * v13 (AGENT 22 — A1) — ÇEVRİMDIŞI ÖNBELLEK BÜTÇESİ (100 MB GÜVENLİ TAMPON).
 *
 * Eski sürümde Edge Neural ses parçaları yalnız "1 saat" kuralıyla temizlenir,
 * çevrimdışı önbellek (çeviri modeli + ses tamponu) için toplam tavan ~35 MB
 * sayılıydı; 1000+ sayfalık devasa akademik PDF'lerde (tez, kod dokümanı)
 * parça üretimi/temizliği arasında bellek (OutOfMemory) ve okuma takılması
 * yaşanabiliyordu.
 *
 * Artık önbellek dosyaları 100 MB'lık BÜTÇE içinde yönetilir:
 *  • 0 baytlık (yarım inmiş) dosyalar anında silinir;
 *  • en yeni dosyalar korunur, eskiler bütçe aşılırsa sırayla silinir;
 *  • temizlik ARKA PLANDA (IO coroutine) çalışır — okuma akışı ASLA beklemez.
 *
 * Okuma akışı asla durmaz: bütçe aşılırsa en eski parça silinir ve gerekirse
 * EdgeTts parçayı yeniden üretir (taze indirme her zaman mümkündür).
 */
object CacheKeeper {

    /** v13: 35 MB → 100 MB (devasa PDF'lerde taşma/OOM koruması). */
    const val MAX_CACHE_BYTES = 100L * 1024L * 1024L

    /**
     * Bayt → okunabilir metin ("42.3 MB").
     * Locale.ROOT zorunlu: cihaz TR/DE/FR ayarındayken varsayılan format
     * "1,0 MB" üretir (B.7 turu CacheKeeperTest bunu yakaladı) — teknik
     * göstergeler her dilde NOKTA ile yazılsın diye sabitlendi.
     */
    fun human(bytes: Long): String = when {
        bytes >= 1024L * 1024L ->
            String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L ->
            String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /**
     * Verilen önekli önbellek dosyalarını bütçe içinde tutar (en yeniler kalır).
     * @return bütçe sonrası kalan toplam boyut (bayt)
     */
    fun trim(dir: File?, prefix: String, maxBytes: Long = MAX_CACHE_BYTES): Long {
        if (dir == null) return 0L
        val listed = try {
            dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }?.toList()
                ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        // Yarım inmiş (0 bayt) dosyalar anında gider
        val alive = ArrayList<File>(listed.size)
        for (f in listed) {
            try {
                if (f.length() == 0L) f.delete() else alive.add(f)
            } catch (_: Throwable) {
            }
        }
        // En yeniler korunur; toplam bütçe aşılırsa EN ESKİDEN başlanarak
        // silinir (artan zaman sıralaması → baştan silme = eskisi önce gider)
        alive.sortBy { it.lastModified() }
        var total = alive.sumOf { it.length() }
        val it = alive.iterator()
        while (it.hasNext() && total > maxBytes) {
            val f = it.next()
            val len = f.length()
            try {
                if (f.delete()) total -= len
            } catch (_: Throwable) {
            }
        }
        return total
    }

    /** Bir önekin toplam önbellek kullanımı (bayt) — tanılama/ayarlar için. */
    fun usageBytes(dir: File?, prefix: String): Long = try {
        dir?.listFiles { f -> f.isFile && f.name.startsWith(prefix) }
            ?.sumOf { it.length() } ?: 0L
    } catch (_: Throwable) {
        0L
    }
}
