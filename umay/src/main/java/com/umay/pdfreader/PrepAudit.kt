package com.umay.pdfreader

/**
 * v36 — ARKA PLAN ÖN HAZIRLIK: SAYFA NİTELİK DENETİMİ (saf çekirdek).
 *
 * Kullanıcının fikri: "Program boşta dururken kitapları okumaya hazır hâle
 * getirsin, sorun bulursa söylesin." (bkz. `33-ARKA-PLAN-HAZIRLIK-MODULU.md`)
 *
 * Buradaki hesap Android'den bağımsızdır: sayfa metinlerini alır, hangi
 * sayfanın okunamaz olduğunu ve **neden** olduğunu söyler. Böylece birim
 * testi yazılabiliyor; `PrepWorker` yalnız dosya/iş/zamanlama işini yapar.
 *
 * Kural: bir sayfa "sorunlu" damgası yiyorsa bunun **ölçülebilir** bir
 * nedeni olmalı (boş, font eşlemesi bozuk, yalnız kod, aşırı Arapça ama
 * Arapça sesi yok gibi). Tahminle suçlama yapılmaz.
 */
object PrepAudit {

    /** Sayfa başına sorun türü. */
    enum class Kind(val label: String) {
        EMPTY("boş sayfa"),
        BROKEN("bozuk metin katmanı — AR/OCR gerekli"),
        CODE_ONLY("yalnız kod satırları"),
        ARABIC("Arapça ağırlıklı sayfa")
    }

    data class Issue(val page: Int, val kind: Kind, val note: String)

    data class Report(
        val bookId: String,
        val total: Int,
        val empty: Int,
        val broken: Int,
        val codeOnly: Int,
        val arabic: Int,
        val readable: Int,
        val issues: List<Issue>
    ) {
        /** Okunabilir sayfa oranı (0..100). */
        val readablePercent: Int
            get() = if (total <= 0) 0 else (readable * 100 / total).coerceIn(0, 100)

        /** Bozuk metin katmanı olan sayfalar (AR/OCR ile kurtarılabilir). */
        val brokenPages: List<Int>
            get() = issues.filter { it.kind == Kind.BROKEN }.map { it.page }

        /**
         * AR (Arapça OCR) önbelleğinde karşılığı olan bozuk sayfa sayısı.
         * "269 bozuk" tek başına moral bozuyor; kaçının kurtarıldığı önemli.
         */
        fun recoveredCount(ocrCachedPages: Set<Int>): Int =
            brokenPages.count { ocrCachedPages.contains(it) }

        /** Kullanıcıya gösterilecek tek satırlık özet. */
        fun summary(): String = buildString {
            append("$readable/$total sayfa okunabilir (%$readablePercent)")
            if (empty > 0) append(" · $empty boş")
            if (broken > 0) append(" · $broken bozuk")
            if (codeOnly > 0) append(" · $codeOnly kod")
            if (arabic > 0) append(" · $arabic Arapça")
        }
    }

    /** Yalnız kod sayılmak için sayfadaki en az satır sayısı. */
    private const val MIN_CODE_LINES = 3
    /** Arapça sayılmak için gereken oran. */
    private const val ARABIC_THRESHOLD = 0.35
    /** Bu uzunluktan kısa sayfa "boş" sayılır (sayfa numarası/aside). */
    private const val MIN_TEXT = 12

    /**
     * @param pages 0 tabanlı sayfa metinleri; `null`/boş = metin hiç yok
     */
    fun audit(bookId: String, pages: List<String>): Report {
        var empty = 0
        var broken = 0
        var codeOnly = 0
        var arabic = 0
        var readable = 0
        val issues = ArrayList<Issue>()

        pages.forEachIndexed { i, raw ->
            val text = raw.trim()
            if (text.length < MIN_TEXT) {
                empty++
                issues.add(Issue(i, Kind.EMPTY, "metin yok (${text.length} karakter)"))
                return@forEachIndexed
            }
            if (TextUtil.hasFontBreakage(text)) {
                broken++
                issues.add(
                    Issue(
                        i, Kind.BROKEN,
                        "font eşlemesi bozuk — okunamaz sözcükler; AR (Arapça OCR) modu kurtarır"
                    )
                )
                return@forEachIndexed
            }
            val lines = text.lines().filter { it.isNotBlank() }
            val codeLines = lines.count { TextUtil.looksLikeCodeLine(it) }
            if (lines.size >= MIN_CODE_LINES && codeLines * 100 / lines.size >= 70) {
                codeOnly++
                issues.add(Issue(i, Kind.CODE_ONLY, "${codeLines}/${lines.size} satır kod"))
                return@forEachIndexed
            }
            if (TextUtil.arabicRatio(text) >= ARABIC_THRESHOLD) {
                arabic++
                issues.add(Issue(i, Kind.ARABIC, "Arapça ağırlıklı — Arapça ses/OCR gerekir"))
                return@forEachIndexed
            }
            readable++
        }

        return Report(
            bookId = bookId,
            total = pages.size,
            empty = empty,
            broken = broken,
            codeOnly = codeOnly,
            arabic = arabic,
            readable = readable,
            issues = issues
        )
    }
}
