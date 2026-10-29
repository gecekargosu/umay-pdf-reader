package com.umay.pdfreader.domain.repository

import java.io.File

/**
 * v5.2 (ADIM C temizliği) — Önbellek bütçesi (100 MB) domain sözleşmesi.
 * Altında yatan: CacheKeeper.
 */
interface CacheBudgetRepository {

    /** Bütçe tavanı (bayt). */
    val maxBytes: Long

    /** Önekli dosyaları bütçe içinde tutar; kalan toplam boyutu döndürür. */
    fun trim(dir: File?, prefix: String): Long

    /** Bayt → okunabilir metin ("42.3 MB") — locale-bağımsız. */
    fun human(bytes: Long): String
}
