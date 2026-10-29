package com.umay.pdfreader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * GERÇEK SES ZARFI (v21 — ekolayzır onarımı).
 *
 * Çalan mp3'ün PCM örneklerini MediaCodec ile çözer ve 96 eşit zaman
 * diliminde RMS (ses şiddeti) değerleri üretir. ReaderActivity bu zarfla
 * MediaPlayer'ın GERÇEK konumunu eşleyerek çubukları sürer:
 *
 *   • Ses çalmıyorsa çubuk hareket etmez.
 *   • Konuşma sessizse çubuklar söner (mp3'teki gerçek sessizlik).
 *   • Vurgulu hecede çubuklar yükselir.
 *
 * Böylece animasyon "uydurma" değil, sesin kendisinden gelir. Mikrofon
 * (RECORD_AUDIO/Visualizer) KULLANILMAZ — gizlilik ve izin gereksinimi yok.
 */
object AudioEnvelope {

    private const val BUCKETS = 96

    /** Dosya başına zarf (LRU 6 dosya ≈ birkaç yüz KB). */
    private val cache = object : android.util.LruCache<String, FloatArray>(6) {}

    /** Zarf hazırsa döner; değilse null (çağıran taraf düz çizgi çizer). */
    fun forFile(file: File): FloatArray? {
        val key = "${file.absolutePath}:${file.length()}"
        cache.get(key)?.let { return it }
        val arr = try {
            decode(file)
        } catch (t: Throwable) {
            null
        } ?: return null
        cache.put(key, arr)
        return arr
    }

    private fun decode(file: File): FloatArray? {
        if (!file.exists() || file.length() < 1024) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            val fmt = format
            if (trackIndex < 0 || fmt == null) return null
            extractor.selectTrack(trackIndex)
            var mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            var c = try {
                MediaCodec.createDecoderByType(mime)
            } catch (_: Throwable) {
                // Bazı cihazlarda "audio/mpeg" yerine "audio/mp3" istenir
                mime = "audio/mpeg"
                MediaCodec.createDecoderByType(mime)
            }
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            val pcm = ByteArrayOutputStream(1 shl 18)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val timeoutUs = 10_000L
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)
                        if (buf == null) {
                            inputDone = true
                        } else {
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                c.queueInputBuffer(
                                    inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                c.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, timeoutUs)
                when {
                    outIdx >= 0 -> {
                        val out = c.getOutputBuffer(outIdx)
                        if (out != null && info.size > 1) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            out.get(chunk)
                            pcm.write(chunk)
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // codec bekliyor: sonsuz döngüye girmesin
                        if (inputDone && pcm.size() == 0) outputDone = true
                    }
                }
                // güvenlik: 5 MB'tan fazla PCM biriktirmeyelim (≈ 80 sn mono)
                if (pcm.size() > 5 shl 20) outputDone = true
            }
            val bytes = pcm.toByteArray()
            if (bytes.size < 4) return null
            buildEnvelope(bytes)
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    /** PCM 16-bit LE → BUCKETS adet RMS değeri (0..1, tepe normalize). */
    private fun buildEnvelope(pcm: ByteArray): FloatArray {
        val sampleCount = pcm.size / 2
        if (sampleCount < BUCKETS) return FloatArray(BUCKETS) { 0.15f }
        val out = FloatArray(BUCKETS)
        val per = max(1, sampleCount / BUCKETS)
        var peak = 0f
        for (b in 0 until BUCKETS) {
            var sum = 0.0
            var n = 0
            val start = b * per
            val end = minOf(sampleCount, start + per)
            var i = start
            while (i < end) {
                val lo = pcm[i * 2].toInt() and 0xFF
                val hi = pcm[i * 2 + 1].toInt()
                val s = ((hi shl 8) or lo).toShort().toInt()
                sum += (s * s).toDouble()
                n++
                i += 2   // stereo/kanal fark etmeksizin örnekleme (hız için 2)
            }
            val rms = if (n > 0) kotlin.math.sqrt(sum / n) / 32768.0 else 0.0
            out[b] = rms.toFloat()
            if (out[b] > peak) peak = out[b]
        }
        if (peak <= 0.0001f) return FloatArray(BUCKETS) { 0.12f }
        // Normalize + hafif sıkıştırma (küçük sesler de görünsün)
        for (b in 0 until BUCKETS) {
            val v = (out[b] / peak).coerceIn(0f, 1f)
            out[b] = Math.pow(v.toDouble(), 0.65).toFloat().coerceIn(0.05f, 1f)
        }
        return out
    }

    /** Bayt dizisinin tepe genliği (test/önizleme için). */
    fun peakOf(pcm: ByteArray): Float {
        var p = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val v = abs(((hi shl 8) or lo).toShort().toInt())
            if (v > p) p = v
            i += 2
        }
        return p / 32768f
    }
}
