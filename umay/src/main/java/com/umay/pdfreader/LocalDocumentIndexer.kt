package com.umay.pdfreader

import java.io.File

/**
 * v5.2 (ADIM C.2 / #22 + #25) — YEREL DOSYA İNDEKSLEYİCİ.
 *
 * Cihazdaki klasörlerdeki (Download/Documents vb.) desteklenen belgeleri
 * tarar ve kütüphanede OLMAYAN (aday) dosyaları listeler. Amaç:
 *  - #22: OCR/plan gibi akışların ÖNCESİ için güncel dosya envanteri,
 *  - #25: arka plan senkronizasyonunun kaynağı (dosya sistemi ↔ kütüphane farkı).
 *
 * Bu tur yalnızca ALTYAPI: tarama + fark (diff). Otomatik IMPORT yok —
 * kullanıcı onaysız kitaplık doldurulmaz; fark bir sonraki turda
 * "İçe aktar?" kartı olarak sunulacak.
 */
object LocalDocumentIndexer {

    private const val TAG = "LocalDocIndexer"

    /** Desteklenen uzantılar (DocumentConverter + EPUB ile hizalı). */
    val INDEXED_EXTS: Set<String> = setOf(
        "pdf", "epub", "docx", "doc", "pptx", "txt", "md"
    )

    /**
     * Bulunan aday adlardan kütüphanedekiler çıkarılır → yeni görünen dosyalar.
     * Saf — test edilebilir. Bilinen ve bulunan kesişiminde küçük/büyük harf
     * duyarsız eşleşme uygulanır.
     */
    fun diff(known: Set<String>, found: List<String>): List<String> {
        val knownLower = known.map { it.lowercase() }.toSet()
        return found.filter { it.lowercase() !in knownLower }.distinct()
    }

    /**
     * Kök dizinleri özyinelemeli tarar; adları `knownNames` içinde OLMAYAN
     * destekli dosyaları döndürür (en fazla `limit` adet, en yeni önce).
     *
     * Güvenlik ağıları: okunamayan dizinler atlanır, semboller/süre aşımı
     * yok sayılır, derinlik sınırı vardır (zip içinde gezinme yok).
     */
    fun scanNewFiles(
        roots: List<File>,
        knownNames: Set<String>,
        limit: Int = 50,
        maxDepth: Int = 4
    ): List<File> {
        val out = ArrayList<File>()
        for (root in roots) {
            walk(root, 0, maxDepth, knownNames, out)
            if (out.size >= limit) break
        }
        // En yeni önce → en fazla `limit`
        return out.sortedByDescending { it.lastModified() }.take(limit)
    }

    private fun walk(
        dir: File?,
        depth: Int,
        maxDepth: Int,
        knownNames: Set<String>,
        out: ArrayList<File>
    ) {
        if (dir == null || depth > maxDepth || out.size > 500) return
        val children = try {
            dir.listFiles()
        } catch (_: Throwable) {
            null
        } ?: return
        val knownLower = knownNames.map { it.lowercase() }.toSet()
        for (f in children) {
            try {
                if (f.isDirectory) {
                    if (!f.name.startsWith(".")) walk(f, depth + 1, maxDepth, knownLower, out)
                } else if (f.isFile) {
                    val ext = f.name.substringAfterLast('.', "").lowercase()
                    if (ext in INDEXED_EXTS && f.length() > 0L &&
                        f.name.lowercase() !in knownLower
                    ) {
                        out.add(f)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }
}
