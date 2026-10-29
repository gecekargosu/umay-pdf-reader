package com.umay.pdfreader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v12 (YENİ ÖZELLİK) — ÖZET DIŞA AKTARMA + ALINTI KARTI.
 *
 * 1) `exportSummary`: AI/çıkarım özet metnini Documents/UmayPDF/ altına
 *    zaman damgalı `.md` dosyası olarak kaydeder ve paylaşım menüsünü açar.
 * 2) `shareQuoteCard`: Okunan beğeniğin cümlesinden şık bir PNG kart
 *    üretir (koyu lacivert degrade zemin + turkuaz/turuncu kenar + metin)
 *    ve paylaşım menüsüne koyar (Instagram/WhatsApp/Notlar…).
 *
 * GÜVENLİK: FileProvider `res/xml/file_paths.xml` üzerinden yalnız
 *    Documents/UmayPDF ve cache'ı paylaşır (path traversal yok).
 *    Her işlem try/catch — asla uygulamayı düşürmez.
 */
object ExportManager {

    /** Özet metnini Documents/UmayPDF/ altına .md olarak kaydeder. */
    fun exportSummary(ctx: Context, bookTitle: String, summary: String): File? {
        return try {
            if (summary.isBlank()) return null
            val dir = File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS
                ), "UmayPDF"
            ).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeTitle = bookTitle.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim()
                .take(40).ifBlank { "ozet" }
            val f = File(dir, "ozet_${safeTitle}_$stamp.md")
            f.writeText(
                "# $bookTitle — Sayfa Özeti\n\n" +
                    "> $stamp · Umay PDF Okuyucu\n\n$summary\n"
            )
            BookStore.logMessage(ctx, "ExportManager", "Özet kaydedildi: ${f.name}")
            f
        } catch (t: Throwable) {
            BookStore.logError(ctx, "ExportManager.exportSummary", t)
            null
        }
    }

    /** Dosyayı paylaşım menüsüne koyar (FileProvider üzerinden). */
    fun shareFile(ctx: Context, file: File, mime: String = "text/markdown") {
        try {
            // DİKKAT: Manifest'teki yetki adı `${applicationId}.fileprovider`
            val uri = FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(intent, file.name))
        } catch (t: Throwable) {
            BookStore.logError(ctx, "ExportManager.shareFile", t)
        }
    }

    /**
     * Alıntıdan PNG kart üretir ve paylaşır.
     * Kart: 1080x1080 kare (Instagram/Stories uyumlu), lacivert degrade,
     * turkuaz→turuncu kenar, büyük metin + "— UMAY" imzası.
     */
    fun shareQuoteCard(ctx: Context, quote: String, sourceTitle: String): File? {
        return try {
            val text = quote.trim().take(400)
            if (text.isBlank()) return null

            val size = 1080
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            // Zemin: dikey degrade (Gece Laciverti → panel)
            val bgPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, size.toFloat(),
                    0xFF0B0E14.toInt(), 0xFF1E293B.toInt(),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

            // Kenarlık: turkuaz → turuncu degrade (4dp)
            val border = Paint().apply {
                strokeWidth = 10f
                style = Paint.Style.STROKE
                shader = LinearGradient(
                    0f, 0f, size.toFloat(), size.toFloat(),
                    0xFF00E5FF.toInt(), 0xFFFF6B00.toInt(),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(20f, 20f, size - 20f, size - 20f, border)

            // Tırnak işareti (büyük, soluk turkuaz)
            val quotePaint = Paint().apply {
                color = 0x5500E5FF.toInt()
                textSize = 260f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            canvas.drawText("“", 90f, 300f, quotePaint)

            // Alıntı metni — kelimeleri satırlara böl (1080px kart, 64px metin)
            val textPaint = Paint().apply {
                color = 0xFFE6EDF3.toInt()
                textSize = 62f
                isAntiAlias = true
            }
            val maxWidth = size - 200
            val lines = wrapText(text, textPaint, maxWidth)
            var y = 400f
            val lineHeight = textPaint.textSize * 1.35f
            for (line in lines.take(7)) {
                canvas.drawText(line, 100f, y, textPaint)
                y += lineHeight
            }
            if (lines.size > 7) {
                canvas.drawText("…", 100f, y, textPaint)
                y += lineHeight
            }

            // Kaynak + imza
            val sigPaint = Paint().apply {
                color = 0xFF00E5FF.toInt()
                textSize = 44f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            canvas.drawText("— $sourceTitle", 100f, size - 200f, sigPaint)
            val brandPaint = Paint().apply {
                color = 0xFFFF6B00.toInt()
                textSize = 40f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            canvas.drawText("UMAY · Akıllı PDF Asistanı", 100f, size - 120f, brandPaint)

            // cache/alinti_kart_<ts>.png → paylaş
            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            val f = File(dir, "alinti_kart_${System.currentTimeMillis()}.png")
            f.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bmp.recycle()

            shareFile(ctx, f, "image/png")
            f
        } catch (t: Throwable) {
            BookStore.logError(ctx, "ExportManager.shareQuoteCard", t)
            null
        }
    }

    /** Basit kelime kelime satır sarma (kelime taşarsa yeni satır). */
    private fun wrapText(text: String, paint: Paint, maxWidth: Int): List<String> {
        val out = ArrayList<String>()
        val words = text.split(Regex("\\s+"))
        var line = StringBuilder()
        for (w in words) {
            val candidate = if (line.isEmpty()) w else "$line $w"
            if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                out.add(line.toString())
                line = StringBuilder(w)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return out
    }
}
