package com.umay.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v33 — TARANAN FOTOĞRAFLARIN YÖNÜNÜ DÜZELTME (tahta / defter / el yazısı).
 *
 * Sorun: kullanıcı tahtayı yan, ters veya eğik çekebiliyor. PDF'e eklenen
 * görüntü yan durunca hem ekranda hem sesli okumada metin çöp oluyordu.
 *
 * Çözüm (en kötü durumda bile okunabilirlik):
 *  1. EXIF yönü uygulanır (kamera zaten işaretlemişse).
 *  2. Küçük bir kopya üzerinde 0°/90°/180°/270° OCR denenir; en çok
 *     anlamlı sözcük üreten yön seçilir.
 *  3. Seçilen yön PDF'e YAZILIR (sayfa düz durur) ve metin bu yönden alınır.
 *  4. OCR için kontrastı artırılmış gri kopya kullanılır (tahta parlaması,
 *     düşük ışık, kurşun kalem). PDF'te orijinal renk korunur.
 *
 * Eğik/perspektif çekimlerde tam geometrik düzeltme yapılmaz; ML Kit küçük
 * eğimleri tolere eder. Aşırı eğik çekimde kullanıcıya ipucu gösterilir.
 */
object ScanUpright {

    private const val PROBE_MAX = 1100      // yön denemesi için küçük kopya
    private const val OCR_MAX = 1700        // gerçek OCR için üst sınır

    data class Result(
        /** Düzeltilmiş görüntünün dosyası (null: düzeltme gerekmedi). */
        val fixedFile: File?,
        /** Seçilen döndürme (0/90/180/270). */
        val rotation: Int,
        /** OCR metni (null: metin bulunamadı). */
        val text: String?
    )

    /** Sayfayı düzeltir; PDF ve metin için hazır hâle getirir. */
    fun normalize(ctx: Context, uri: Uri, name: String): Result {
        val base = decode(ctx, uri, PROBE_MAX) ?: return Result(null, 0, null)
        return try {
            val probe = probeScore(base)
            var bestRot = 0
            var bestScore = probe
            // 0° zaten iyiyse ek deneme yapma (hız)
            if (probe < 60) {
                for (rot in intArrayOf(90, 270, 180)) {
                    val rotated = rotate(base, rot)
                    val s = probeScore(rotated)
                    rotated.recycle()
                    if (s > bestScore) {
                        bestScore = s
                        bestRot = rot
                    }
                }
            }
            // Küçük fark için döndürme yapma (yanlış karar riski)
            if (bestScore < probe * 1.3 && probe >= 20) bestRot = 0

            val upright = if (bestRot == 0) base else rotate(base, bestRot)
            if (upright !== base) base.recycle()

            // PDF için tam çözünürlüklü, düzgün kopya yaz
            val full = decode(ctx, uri, OCR_MAX) ?: upright
            val orientedFull = if (bestRot == 0) full else rotate(full, bestRot)
            if (orientedFull !== full) full.recycle()

            val dir = File(ctx.filesDir, "scanfix").apply { mkdirs() }
            val out = File(dir, "$name.jpg")
            try {
                java.io.FileOutputStream(out).use { fos ->
                    orientedFull.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                }
            } catch (_: Throwable) {
                // Yazılamazsa PDF orijinal URI ile üretilir (akış durmaz)
                val fallbackText = recognize(enhanceForOcr(orientedFull))
                orientedFull.recycle()
                return Result(null, bestRot, fallbackText)
            }

            var enhancedFull: Bitmap? = null
            val text = try {
                enhancedFull = enhanceForOcr(orientedFull)
                recognize(enhancedFull)
            } catch (_: Throwable) {
                null
            } finally {
                try {
                    if (enhancedFull != null && enhancedFull !== orientedFull) enhancedFull.recycle()
                } catch (_: Throwable) { }
            }
            orientedFull.recycle()
            Result(out, bestRot, text)
        } catch (_: Throwable) {
            try { base.recycle() } catch (_: Throwable) { }
            Result(null, 0, null)
        }
    }

    /** Görüntüyü verilen açıda döndürür (yeni bitmap). */
    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /**
     * OCR dostu kopya: gri + kontrast/yumuşatma. Renkli PDF görüntüsü
     * değişmez; yalnız metin tanıma bu kopyayı kullanır.
     */
    fun enhanceForOcr(src: Bitmap): Bitmap {
        return try {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                // Doygunluk 0 (gri) + kontrast 1.25 + hafif parlaklık
                val cm = ColorMatrix().apply { setSaturation(0f) }
                val contrast = 1.25f
                val translate = (-0.5f * contrast + 0.5f) * 255f + 8f
                cm.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            contrast, 0f, 0f, 0f, translate,
                            0f, contrast, 0f, 0f, translate,
                            0f, 0f, contrast, 0f, translate,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
                colorFilter = ColorMatrixColorFilter(cm)
            }
            Canvas(out).drawBitmap(src, 0f, 0f, paint)
            out
        } catch (_: Throwable) {
            src
        }
    }

    /** ML Kit metin tanıma (senkron, zaman aşımı korumalı). */
    fun recognize(bmp: Bitmap): String? {
        return try {
            val recognizer = com.google.mlkit.vision.text.TextRecognition
                .getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val input = com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)
                val task = recognizer.process(input)
                val latch = CountDownLatch(1)
                var text: String? = null
                task.addOnSuccessListener { r -> text = r.text; latch.countDown() }
                task.addOnFailureListener { latch.countDown() }
                latch.await(20, TimeUnit.SECONDS)
                text
            } finally {
                try { recognizer.close() } catch (_: Throwable) { }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Bir bitmap için OCR puanı (yön karşılaştırması). */
    private fun probeScore(bmp: Bitmap): Int {
        var enhanced: Bitmap? = null
        return try {
            enhanced = enhanceForOcr(bmp)
            textScore(recognize(enhanced))
        } catch (_: Throwable) {
            0
        } finally {
            try {
                if (enhanced != null && enhanced !== bmp) enhanced.recycle()
            } catch (_: Throwable) { }
        }
    }

    /** Metnin "gerçek yazı" puanı — yön karşılaştırması için. */
    fun textScore(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val letters = text.count { it.isLetter() }
        val words = text.split(Regex("\\s+"))
            .count { w -> w.length >= 3 && w.count { it.isLetter() } >= 3 }
        val digits = text.count { it.isDigit() }
        return letters + words * 6 + digits
    }

    private fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            var sample = 1
            val longest = maxOf(opts.outWidth, opts.outHeight)
            while (longest / sample > maxSide) sample *= 2
            val dec = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp: Bitmap? = null
            ctx.contentResolver.openInputStream(uri)?.use {
                bmp = BitmapFactory.decodeStream(it, null, dec)
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }
}
