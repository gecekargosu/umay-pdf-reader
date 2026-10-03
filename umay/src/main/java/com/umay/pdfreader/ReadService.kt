package com.umay.pdfreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Seslendirme servisi. Ekran kapalıyken / uygulama arka plandayken
 * okumaya devam eder (foreground servis + partial wakelock).
 *
 * Sen durdurana veya kitap bitene kadar çalışır.
 */
class ReadService : Service() {

    interface Listener {
        fun onState(page: Int, pageCount: Int, playing: Boolean, sentence: String?)
        fun onInfo(msg: String)
        fun onBookFinished()
    }

    inner class LocalBinder : Binder() {
        val service: ReadService get() = this@ReadService
    }

    private val binder = LocalBinder()
    val listeners = CopyOnWriteArrayList<Listener>()

    // ── Durum ─────────────────────────────────────────────────
    @Volatile var bookId: String? = null
        private set
    @Volatile var currentPage: Int = 0          // 0 tabanlı
    @Volatile var playing: Boolean = false
        private set
    @Volatile var rate: Float = 1.0f
    @Volatile var voiceMode: String = "auto"
    @Volatile var translateEnabled: Boolean = false
    @Volatile var sleepMinutes: Int = 0          // 0 = kapalı
    @Volatile var sleepAtPageEnd: Boolean = false

    var pageCount: Int = 0
        private set

    private var chunks: List<String> = emptyList()
    private var chunkIndex: Int = 0

    private var tts: TtsManager? = null
    private var wantPlay = false
    private var speakJob: Job? = null
    private var consecutiveSkips = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationShown = false

    // ── Lifecycle ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannel()

        rate = AppPrefs.getFloat(this, AppPrefs.KEY_RATE, 1.0f)
        voiceMode = AppPrefs.getString(this, AppPrefs.KEY_VOICE, "auto")
        translateEnabled = AppPrefs.getBool(this, AppPrefs.KEY_TRANSLATE, false)

        tts = TtsManager(
            this,
            onReady = { ok ->
                if (ok && wantPlay) {
                    wantPlay = false
                    mainHandler.post { startPlaying() }
                } else if (!ok) {
                    emitInfo("Ses motoru başlatılamadı. Ayarlardan test edebilirsin.")
                }
            },
            onDone = { id -> mainHandler.post { onUtteranceDone(id) } },
            onError = { id -> mainHandler.post { id?.let { onUtteranceDone(it) } } }
        )

        // Sistem servisi öldürmüşse (ekran kapalıyken okuma sırasında)
        // kaldığımız sayfadan otomatik devam et.
        restoreAfterSystemKill()
    }

    /** Sistem servisi öldürdüyse son durumu geri yükler ve okumayı sürdürür. */
    private fun restoreAfterSystemKill() {
        if (bookId != null) return
        if (!AppPrefs.getBool(this, AppPrefs.KEY_PLAYING, false)) return
        val last = AppPrefs.getString(this, AppPrefs.KEY_LAST_BOOK)
        if (last.isBlank()) return
        if (BookStore.loadBooks(this).none { it.id == last }) return
        val (page, sentence) = AppPrefs.loadProgress(this, last) ?: Pair(0, 0)
        openBook(last, page, sentence)
        play()   // TTS hazır olunca onReady üzerinden başlar
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> if (playing) pause() else play()
            ACTION_NEXT -> nextPage()
            ACTION_PREV -> prevPage()
            ACTION_STOP -> { pause(); bookId = null; stopSelf() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pauseInternal()
        cancelSleepTimer()
        AppPrefs.putBool(this, AppPrefs.KEY_PLAYING, false)
        try { tts?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── Dış komutlar (Activity + bildirim) ───────────────────

    fun openBook(id: String, page: Int, sentence: Int) {
        if (playing) pauseInternal()   // başka kitaba geçerken okumayı durdur
        bookId = id
        pageCount = BookStore.loadBooks(this).firstOrNull { it.id == id }?.pageCount ?: 0
        currentPage = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        chunkIndex = sentence.coerceAtLeast(0)
        chunks = emptyList()
        consecutiveSkips = 0
        notifyState(null)
    }

    fun play() {
        if (bookId == null) return
        wantPlay = true
        if (tts?.isReady() == true) {
            wantPlay = false
            startPlaying()
        }
        // hazır değilse onReady'de başlar
    }

    fun pause() {
        wantPlay = false
        pauseInternal()
    }

    fun toggle() = if (playing) pause() else play()

    fun gotoPage(pageNumber1Based: Int) {
        if (bookId == null) return
        val p = (pageNumber1Based - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        jumpTo(p)
    }

    fun nextPage() {
        if (bookId == null) return
        if (currentPage + 1 >= pageCount) {
            if (playing) finishBook()
            return
        }
        jumpTo(currentPage + 1)
    }

    fun prevPage() {
        if (bookId == null) return
        jumpTo((currentPage - 1).coerceAtLeast(0))
    }

    private fun jumpTo(page: Int) {
        currentPage = page
        chunkIndex = 0
        consecutiveSkips = 0
        chunks = emptyList()
        tts?.stop()
        saveProgressNow()
        if (playing) speakCurrentPage() else notifyState(null)
    }

    fun applyRate(r: Float) {
        rate = r
        AppPrefs.putFloat(this, AppPrefs.KEY_RATE, r)
        tts?.applyRate(r)
    }

    fun setVoice(mode: String) {
        voiceMode = mode
        AppPrefs.putString(this, AppPrefs.KEY_VOICE, mode)
        tts?.applyVoice(mode)
    }

    fun setTranslate(enabled: Boolean) {
        translateEnabled = enabled
        AppPrefs.putBool(this, AppPrefs.KEY_TRANSLATE, enabled)
    }

    fun applySleepMinutes(minutes: Int) {
        sleepMinutes = minutes
        sleepAtPageEnd = false
        cancelSleepTimer()
        if (minutes > 0 && playing) scheduleSleepTimer()
    }

    fun applySleepAtPageEnd(enabled: Boolean) {
        sleepAtPageEnd = enabled
        if (enabled) {
            sleepMinutes = 0
            cancelSleepTimer()
        }
    }

    // ── Okuma akışı ───────────────────────────────────────────

    private fun startPlaying() {
        if (bookId == null) return
        if (playing) return
        playing = true
        AppPrefs.putBool(this, AppPrefs.KEY_PLAYING, true)
        acquireWakeLock()
        startAsForeground()
        scheduleSleepTimer()
        tts?.applyRate(rate)
        tts?.applyVoice(voiceMode)
        speakCurrentPage()
        updateNotification()
    }

    private fun pauseInternal() {
        if (!playing && !notificationShown) {
            // yine de kaydet
        }
        playing = false
        AppPrefs.putBool(this, AppPrefs.KEY_PLAYING, false)
        speakJob?.cancel()
        speakJob = null
        tts?.stop()
        cancelSleepTimer()
        releaseWakeLock()
        saveProgressNow()
        if (notificationShown) {
            try {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {}
            notificationShown = false
        }
        notifyState(null)
    }

    private fun speakCurrentPage() {
        if (!playing) return
        val bid = bookId ?: return
        val page = currentPage

        speakJob = scope.launch {
            val text = awaitPageText(bid, page)
            if (!playing || currentPage != page) return@launch

            var body = text ?: ""
            if (body.isBlank()) {
                withContext(Dispatchers.Main) { handleEmptyPage() }
                return@launch
            }

            if (needsTranslation(body)) {
                emitInfo("İngilizce metin Türkçe'ye çevriliyor…")
                body = TrTranslator.translate(body)
                if (needsTranslation(body)) {
                    emitInfo("Çeviri modeli indirilemedi (internet gerekli) — orijinal metin okunuyor.")
                }
            }

            val list = TextUtil.speechChunks(body)
            withContext(Dispatchers.Main) {
                if (!playing || currentPage != page) return@withContext
                if (list.isEmpty()) {
                    handleEmptyPage()
                    return@withContext
                }
                consecutiveSkips = 0
                chunks = list
                val start = chunkIndex.coerceIn(0, list.size - 1)
                speakChunk(start)
            }
        }
    }

    private fun needsTranslation(text: String) =
        TextUtil.needsTranslation(text, translateEnabled)

    /** Sayfa metnini bekler; çıkarım arka planda sürüyorsa bite kadar bekler. */
    private suspend fun awaitPageText(bid: String, page: Int): String? {
        // 1) Çıkarım/OCR sürüyorsa bitmesini bekle (metin birazdan gelir)
        var guard = 0
        while (BookStore.isExtracting(this, bid) && guard < 1800) { // en fazla 12 dk
            BookStore.pageText(this, bid, page)?.let { return it }
            delay(400)
            guard++
        }
        // 2) Çıkarım bitti: metin hâlâ yoksa kısa bir tampon süresi daha
        repeat(150) { // ~60 sn
            val t = BookStore.pageText(this, bid, page)
            if (t != null) return t
            delay(400)
        }
        return null
    }

    private fun speakChunk(index: Int) {
        if (!playing) return
        if (index >= chunks.size) {
            onPageEnd()
            return
        }
        chunkIndex = index
        saveProgressNow()
        val text = chunks[index]
        notifyState(text)
        tts?.speak(text, "$currentPage:$index")
    }

    private fun onUtteranceDone(id: String) {
        if (!playing) return
        val parts = id.split(":")
        if (parts.size != 2) return
        val page = parts[0].toIntOrNull() ?: return
        val idx = parts[1].toIntOrNull() ?: return
        if (page != currentPage) return
        speakChunk(idx + 1)
    }

    private fun onPageEnd() {
        if (sleepAtPageEnd) {
            sleepAtPageEnd = false
            emitInfo("Uyku zamanlayıcısı: sayfa tamamlandı, durdum.")
            pause()
            return
        }
        nextPageOrFinish()
    }

    private fun nextPageOrFinish() {
        if (currentPage + 1 >= pageCount) {
            finishBook()
        } else {
            currentPage++
            chunkIndex = 0
            consecutiveSkips = 0
            chunks = emptyList()
            saveProgressNow()
            updateNotification()
            speakCurrentPage()
        }
    }

    /** Sayfada metin yoksa (ör. boş sayfa) sonrakine geç. */
    private fun handleEmptyPage() {
        if (!playing) return
        consecutiveSkips++
        if (consecutiveSkips > 30 || currentPage + 1 >= pageCount) {
            finishBook()
            return
        }
        currentPage++
        chunkIndex = 0
        chunks = emptyList()
        updateNotification()
        speakCurrentPage()
    }

    private fun finishBook() {
        emitInfo("Kitap bitti 🎉")
        listeners.toList().forEach { it.onBookFinished() }
        saveProgressNow()
        pause()
    }

    // ── Yardımcılar ──────────────────────────────────────────

    private fun saveProgressNow() {
        val bid = bookId ?: return
        AppPrefs.saveProgress(this, bid, currentPage, chunkIndex)
    }

    private fun notifyState(sentence: String?) {
        val pc = pageCount
        val p = currentPage
        val pl = playing
        listeners.toList().forEach { it.onState(p, pc, pl, sentence) }
    }

    private fun emitInfo(msg: String) {
        val m = msg
        mainHandler.post {
            listeners.toList().forEach { it.onInfo(m) }
        }
    }

    private fun scheduleSleepTimer() {
        cancelSleepTimer()
        if (sleepMinutes <= 0) return
        val r = Runnable {
            emitInfo("Uyku zamanlayıcısı doldu — duraklatıldı.")
            pause()
        }
        sleepRunnable = r
        mainHandler.postDelayed(r, sleepMinutes * 60_000L)
    }

    private fun cancelSleepTimer() {
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "umay:read").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
    }

    // ── Bildirim ─────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Okuma servisi",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Seslendirme sırasında gösterilir" }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    private fun startAsForeground() {
        val notif = buildNotification()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            notificationShown = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateNotification() {
        if (!notificationShown) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val bid = bookId
        val book = bid?.let { id -> BookStore.loadBooks(this).firstOrNull { it.id == id } }
        val title = book?.title ?: "Umay PDF Okuyucu"
        val text = "Sayfa ${currentPage + 1}/$pageCount" +
            (if (playing) " • okunuyor" else " • duraklatıldı")

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_BOOK_ID, bid ?: "")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun action(actionId: Int, label: String, action: String): PendingIntent =
            PendingIntent.getService(
                this, actionId,
                Intent(this, ReadService::class.java).apply { putExtra(EXTRA_ACTION, action) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val pauseLabel = getString(if (playing) R.string.bildirim_duraklat else R.string.bildirim_devam)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_prev, getString(R.string.onceki), action(1, "prev", ACTION_PREV))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                pauseLabel,
                action(2, "toggle", ACTION_TOGGLE)
            )
            .addAction(R.drawable.ic_next, getString(R.string.sonraki), action(3, "next", ACTION_NEXT))
            .addAction(R.drawable.ic_settings, getString(R.string.bildirim_durdur), action(4, "stop", ACTION_STOP))
            .build()
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val ACTION_PLAY = "play"
        const val ACTION_PAUSE = "pause"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_NEXT = "next"
        const val ACTION_PREV = "prev"
        const val ACTION_STOP = "stop"
        const val CHANNEL_ID = "umay_read"
        const val NOTIF_ID = 4711
    }
}
