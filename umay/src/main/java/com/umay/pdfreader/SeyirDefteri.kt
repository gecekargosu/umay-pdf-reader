package com.umay.pdfreader

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v42 — SEYİR DEFTERİ (kaptanın günlüğü).
 *
 * Kullanıcı isteği: "program ne zaman açıldı, kullanıcı ne yaptıysa oraya
 * işlesin; kitap mı sildim, raf mı sildim, dosya mı ekledim… hepsi yazılsın.
 * Bir de 'sil dedim ama silinmedi' gibi durumlar not edilsin."
 *
 * Tasarım:
 *  • Ayda bir dosya: `files/journal/seyir-YYYY-MM.log` (satır = olay).
 *  • Her satır: `tarih saat | ALAN | eylem | ayrıntı`.
 *  • Yazma **hataya dayanıklı**: disk dolu/izin yoksa sessizce yutulur,
 *    uygulama asla bu yüzden çökmez.
 *  • Dizin **en fazla 12 dosya** tutar (eskiler silinir) — depolama şişmez.
 *  • Doğrulamalı eylemler: [verified] ile "başarılı/başarısız" yazılır.
 *
 * Not: Bu defter kullanıcıya aittir; otomatik temizlenmez. Kullanıcı
 * Ayarlar → Seyir Defteri ekranından okuyabilir, kopyalayabilir, silebilir.
 */
object SeyirDefteri {

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale("tr", "TR"))
    private val shortStamp = SimpleDateFormat("dd MMM HH:mm:ss", Locale("tr", "TR"))
    private val month = SimpleDateFormat("yyyy-MM", Locale("tr", "TR"))
    private const val MAX_FILES = 12

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "journal").apply { if (!exists()) mkdirs() }

    private fun fileOf(ctx: Context, at: Long = System.currentTimeMillis()): File =
        File(dir(ctx), "seyir-${month.format(Date(at))}.log")

    /** Tek olay yaz. Hata durumunda sessiz kalır (uygulamayı etkilemez). */
    @Synchronized
    fun yaz(ctx: Context?, alan: String, eylem: String, ayrinti: String = "") {
        if (ctx == null) return
        try {
            val line = buildString {
                append(stamp.format(Date()))
                append(" | ")
                append(alan)
                append(" | ")
                append(eylem)
                if (ayrinti.isNotBlank()) {
                    append(" | ")
                    append(ayrinti.replace('\n', ' ').take(400))
                }
                append('\n')
            }
            fileOf(ctx).appendText(line)
            prune(ctx)
        } catch (_: Throwable) {
            // defter yazamadı diye uygulama durmaz
        }
    }

    /**
     * SONUCU DOĞRULA ve yaz: "sil dedim, silinmedi" gibi durumlar burada
     * görünür. [ok] false ise satır `BAŞARISIZ` etiketiyle yazılır.
     */
    fun dogrula(
        ctx: Context?,
        alan: String,
        eylem: String,
        ok: Boolean,
        ayrinti: String = ""
    ) {
        yaz(
            ctx, alan,
            if (ok) "$eylem ✓ doğrulandı" else "$eylem ✗ BAŞARISIZ (değişiklik uygulanmadı)",
            ayrinti
        )
    }

    /** Ay sonu dosyaları biriktirmesin: en yeni 12 dosya kalsın. */
    private fun prune(ctx: Context) {
        try {
            val files = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() } ?: return
            if (files.size <= MAX_FILES) return
            files.drop(MAX_FILES).forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }

    /** Okunabilir son kayıtlar (en yeni en altta). */
    fun son(ctx: Context?, limit: Int = 300): String {
        if (ctx == null) return ""
        return try {
            val files = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".log") }
                ?.sortedBy { it.name } ?: return ""
            if (files.isEmpty()) return ""
            val all = ArrayList<String>()
            for (f in files) {
                try {
                    f.forEachLine { all.add(it) }
                } catch (_: Throwable) {
                }
            }
            all.takeLast(limit).joinToString("\n")
        } catch (_: Throwable) {
            ""
        }
    }

    /** Kayıt sayısı + toplam boyut (Ayarlar ekranında gösterilir). */
    fun ozet(ctx: Context?): Pair<Int, Long> {
        if (ctx == null) return 0 to 0L
        return try {
            val files = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".log") }
                ?: return 0 to 0L
            var lines = 0
            var bytes = 0L
            files.forEach { f ->
                bytes += f.length()
                try {
                    f.forEachLine { lines++ }
                } catch (_: Throwable) {
                }
            }
            lines to bytes
        } catch (_: Throwable) {
            0 to 0L
        }
    }

    fun temizle(ctx: Context?): Boolean {
        if (ctx == null) return false
        return try {
            dir(ctx).listFiles()?.forEach { it.delete() }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Kısa zaman damgası (arayüzde satır başına). */
    fun kisa(ts: Long): String = shortStamp.format(Date(ts))
}
