package com.umay.pdfreader

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Microsoft Edge'in çevrimiçi neural TTS servisi (ücretsiz, API anahtarı gerekmez).
 *
 * Resmi olmayan bir uç noktadır; edge-tts (Python) protokolünün birebir
 * Kotlin uygulamasıdır. Başarısız olursa TtsManager yerleşik Android TTS'e
 * düşer, okuma asla durmaz.
 *
 * Akış:
 *  1. Sec-MS-GEC token'ı = SHA256(WinEpoch'a göre 5 dk'ya yuvarlanmış tick + sabit)
 *  2. WSS aç (TrustedClientToken + ConnectionId + token)
 *  3. speech.config mesajı (outputFormat: 24khz mp3)
 *  4. SSML mesajı (ses adı, rate)
 *  5. Gelen binary chunk'ları mp3 dosyasına yaz; turn.end'de bitir
 */
object EdgeTts {

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    // v5.8 — Microsoft Ekim 2026'da v1 token'ı reddetti (403 Forbidden).
    // "2-" öneki v2 token biçimini seçer; token EdgeToken.generateV2() üretir.
    private const val SEC_MS_GEC_VERSION = "2-143.0.3650.75"
    private const val BASE =
        "speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private const val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

    // Türkçe neural sesler (voices/list doğrulandı: 200 OK)
    const val VOICE_TR_FEMALE = "tr-TR-EmelNeural"
    const val VOICE_TR_MALE = "tr-TR-AhmetNeural"
    const val VOICE_MULTI = "en-US-EmmaMultilingualNeural"
    // v5.12 — ARAPÇA: meal/tefsir okuması. Türkçe ses Arapça harfleri
    // okuyamadığı için (veya telaffuzu bozuk olduğu için) ayrı ses gerekir.
    const val VOICE_AR_MALE = "ar-SA-HamedNeural"
    const val VOICE_AR_FEMALE = "ar-SA-ZariyahNeural"

    private val client = OkHttpClient.Builder()
        // v10 (M3 — MOTOR KİLİTLENME FIX): Yavaş ağlarda / uzun parçalarda
        // 12sn bağlantı + 60sn okuma sınırı "yanıt vermedi" hatalarına yol
        // açıyordu → cömert timeout'lar (Edge neural synth uzun chunk'ta 60sn'yi aşabiliyor)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * v5.8 — edge-tts drm.py (v7) ile birebir aynı **v2** token üretimi.
     * Eski 64 haneli SHA-256 token'ı 403 alıyordu; artık CS64+SHA-256 biçimi
     * `<16 hex>-<64 hex>` üretilir (algoritma `EdgeToken` içinde, testli).
     */
    private fun secMsGec(): String = EdgeToken.generateV2()

    /** edge-tts date_to_string(): "Sat Oct 03 2026 17:00:00 GMT+0000 (Coordinated...)" */
    private fun dateToString(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.ENGLISH)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    /** XML'e giren metni temizler (servis bazı karakterleri reddeder). */
    private fun clean(text: String): String = buildString {
        for (ch in text) {
            val c = ch.code
            when {
                c in 0..8 || c in 11..12 || c in 14..31 -> append(' ')
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    /**
     * Metni mp3 dosyasına çevirir. Hata fırlatırsa çağıran yerleşik sese düşer.
     *
     * @param ratePct SSML rate yüzdesi: 0, -25, +25 …
     * @param pitchRatio ses perdesi: 1.0 normal, 0.8 derin, 1.3 tiz
     */
    @Throws(Exception::class)
    suspend fun synthesize(
        text: String,
        voice: String,
        ratePct: Int,
        pitchRatio: Float = 1f,
        outFile: File
    ): File {
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val url = "wss://$BASE" +
            "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=${secMsGec()}" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

        val rate = if (ratePct >= 0) "+$ratePct%" else "$ratePct%"
        // Perde: orana göre Hz ofseti (±50Hz güvenli sınır — servis reddetmesin)
        val pitchHz = ((pitchRatio - 1f) * 100f).toInt().coerceIn(-50, 50)
        val pitch = if (pitchHz >= 0) "+${pitchHz}Hz" else "${pitchHz}Hz"
        // v5.12 — Konuşma dili SES ADINDAN türetilir (tr-TR / ar-SA / en-US).
        // Eskiden sabit 'en-US' idi; Arapça seste SSML dili uyuşmadığı için
        // servis yanıtı bozuk/düşük kaliteli olabiliyordu.
        val lang = voice.split('-').take(2).joinToString("-")
            .ifBlank { "en-US" }
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
            "xml:lang='$lang'><voice name='$voice'>" +
            "<prosody pitch='$pitch' rate='$rate' volume='+0%'>${clean(text)}</prosody>" +
            "</voice></speak>"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", CHROME_UA)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${randomHex()};")
            .build()

        return suspendCancellableCoroutine { cont ->
            var completed = false
            outFile.parentFile?.mkdirs()
            if (outFile.exists()) outFile.delete()

            // DİKKAT: local fun'lar tanım noktasından sonra görünür;
            // listener'dan önce tanımlanmalı.
            fun finish(ws: WebSocket, file: File, error: Throwable?) {
                if (completed) return
                completed = true
                try { ws.close(1000, null) } catch (_: Exception) {}
                if (error != null) {
                    if (file.exists() && file.length() == 0L) file.delete()
                    if (cont.isActive) cont.resumeWithException(error)
                } else {
                    if (file.exists() && file.length() > 0L) {
                        if (cont.isActive) cont.resume(file)
                    } else {
                        file.delete()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Ses üretilemedi (boş yanıt)"))
                    }
                }
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        "X-Timestamp:${dateToString()}\r\n" +
                            "Content-Type:application/json; charset=utf-8\r\n" +
                            "Path:speech.config\r\n\r\n" +
                            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                            "{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"}," +
                            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                    )
                    webSocket.send(
                        "X-RequestId:$requestId\r\n" +
                            "Content-Type:application/ssml+xml\r\n" +
                            "X-Timestamp:${dateToString()}Z\r\n" +
                            "Path:ssml\r\n\r\n$ssml"
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        finish(webSocket, outFile, null)
                    }
                    // Path: response / turn.start / audio.metadata → yok sayılır
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    if (bytes.size < 2) return
                    val arr = bytes.toByteArray()
                    val headerLen = ((arr[0].toInt() and 0xff) shl 8) or (arr[1].toInt() and 0xff)
                    // v5.1: headerLen == arr.size durumunda substring(headerLen + 2, size)
                    // IllegalArgumentException fırlatıyordu (WebSocket callback'inde
                    // → çökme). Bayt gövdesi için header + 2 kadar yer KALMALI.
                    if (headerLen + 2 > arr.size) return
                    val headers = String(arr, 0, headerLen, Charsets.ISO_8859_1)
                    if (!headers.contains("Path:audio")) return
                    if (!headers.contains("audio/mpeg")) return   // Content-Type'suz bitiş mesajı
                    val body = bytes.substring(headerLen + 2, bytes.size)
                    if (body.size == 0) return
                    try {
                        outFile.appendBytes(body.toByteArray())
                    } catch (e: Exception) {
                        finish(webSocket, outFile, e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    finish(webSocket, outFile, t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!completed) finish(webSocket, outFile, IllegalStateException("Bağlantı kapandı: $reason"))
                }
            }

            val ws = client.newWebSocket(request, listener)
            cont.invokeOnCancellation {
                try { ws.cancel() } catch (_: Exception) {}
            }
        }
    }

    private fun randomHex(): String = UUID.randomUUID().toString()
        .replace("-", "").take(32).uppercase(Locale.ROOT)

    @Suppress("unused")
    private fun unusedBufferGuard() = okio.Buffer()

    /** Kullanıcının seçimine göre ses adı. */
    fun voiceFor(genderMode: String, text: String): String {
        // v5.12 — Arapça metin: Arapça neural ses (Kur'an meali / tefsir).
        // Öncelik sırası önemli: Arapça blok varsa "Türkçe değil" kuralından
        // ÖNCE buraya düşmeli, yoksa multilingual ses anlaşılmaz okur.
        if (TextUtil.arabicRatio(text) >= 0.15) {
            return if (genderMode == "male") VOICE_AR_MALE else VOICE_AR_FEMALE
        }
        // Türkçe olmayan metinde multilingual ses daha doğru okur
        if (!TextUtil.looksTurkish(text)) return VOICE_MULTI
        return when (genderMode) {
            "male" -> VOICE_TR_MALE
            "female" -> VOICE_TR_FEMALE
            else -> VOICE_TR_FEMALE
        }
    }
}
