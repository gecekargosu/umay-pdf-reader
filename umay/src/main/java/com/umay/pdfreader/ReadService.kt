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

        /** ADIM 3: tek seferlik seslendirme (sayfa özeti) sürüyor mu? */
        fun onExtraSpeaking(active: Boolean) {}
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

    /** ADIM 3: sayfa özeti gibi okuma dışı tek seferlik seslendirme sürüyor mu? */
    @Volatile var extraSpeaking: Boolean = false
        private set

    /** v5 (ADIM D): özet seslendirmesi duraklatıldı mı? (Devam/Duraklat butonu) */
    @Volatile var summaryPaused: Boolean = false
        private set

    /** Özet metninin parça listesi ve kaldığı yer. */
    @Volatile private var extraChunks: List<String> = emptyList()
    @Volatile private var extraIndex = 0

    var pageCount: Int = 0
        private set

    /** Bildirimde kitap adı her güncellemede diske düşmesin diye önbellek. */
    @Volatile private var bookTitle: String? = null

    private var chunks: List<String> = emptyList()
    private var chunkIndex: Int = 0

    // ── v32 — "OYNAT BASILDI AMA SES YOK" KORUMASI ────────────────────
    // Kullanıcı raporu: ileri sayfaya geçince oynat düğmesi bazen hiçbir şey
    // yapmıyordu (OCR/ağ hazırlığı sayfayı kilitliyordu). Artık 12 sn içinde
    // hiç ses parçası başlamazsa okuma kendini yeniden başlatır; kullanıcıya
    // da bilgi verilir. Böylece okuma sessizce "ölü" kalmaz.
    @Volatile private var speechStartedAt = 0L
    private var playRetries = 0
    private var playWatchdog: Runnable? = null

    // ── v5.12 — ÖNDEN HAZIRLAMA (read-ahead) ──────────────────
    // Sayfa geçişinde "takılma" oluyordu: yeni sayfanın metni diskten
    // okunuyor, gerekirse çevrilip parçalara bölünüyor ve ilk ses ancak
    // ondan sonra isteniyordu. Artık bir sonraki sayfa, o sayfa okunurken
    // arka planda hazırlanır; geçişte hazır liste varsa doğrudan kullanılır.
    private var aheadPage: Int = -1
    private var aheadChunks: List<String> = emptyList()
    private var aheadPrepared: Boolean = false
    private var aheadJob: Job? = null

    private var tts: TtsManager? = null

    // ── v21 — GERÇEK EKOLAYZIR KÖPRÜSÜ ──────────────────────────
    /** Çalan mp3'ün anlık genliği (0..1); ses yoksa -1. UI bunu okur. */
    fun audioLevel(): Float = try { tts?.audioLevel() ?: -1f } catch (_: Throwable) { -1f }
    fun isEdgePlaying(): Boolean = try { tts?.isEdgePlaying() == true } catch (_: Throwable) { false }
    private var wantPlay = false
    // v33 — play() başlatma yeniden denemeleri (motor ısınırken)
    private var playRetry = 0
    private var speakJob: Job? = null
    private var consecutiveSkips = 0

    // v8 (KATEGORİ 1): parça hata sayacı — atlama yok, en fazla 2 tekrar → duraklat
    private var chunkErrorCount = 0

    // v30: ses motoru çalışırken ağ/token durumunun son bilinen goruntu.
    // startPlaying / sayfa okuma noktalarinda kullanilir.
    private var _lastNetworkHealthy = true
    val lastNetworkHealthy: Boolean get() = _lastNetworkHealthy

    // v8 (KATEGORİ 1): ses odağı (audio focus) isteği
    private var audioFocusReq: android.media.AudioFocusRequest? = null
    private var focusGranted = false
    // v9 (GÖREV 2.1): yalnız GEÇİCİ odak kaybından kaynaklanan duraklatma mı?
    // (GAIN geri geldiğinde otomatik devam — kullanıcı okumayı elle
    //  durdurmadiysa kitap sesizce kalmaz)
    @Volatile private var pausedByTransientFocusLoss = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * v16 (GÖREV 3) — `ACTION_AUDIO_BECOMING_NOISY` ALICISI.
     *
     * Kulaklık çıkarıldığında / bluetooth kopunca sistem BU broadcast'i gönderir.
     * Okuma hoparlörden devam ederse: ses patlar, komşu duyar, pil + veri boşa
     * harcanır. Bu alıcı okumayı otomatik **duraklatır**.
     * (Android 14+ / targetSdk 36 → ContextCompat.RECEIVER_NOT_EXPORTED zorunlu.)
     */
    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            try {
                if (intent?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
                    playing
                ) {
                    emitInfo(getString(R.string.kulaklik_cikti))
                    pause()
                }
            } catch (t: Throwable) {
                logServiceError("noisyReceiver", t)
            LocalCheckpointManager.logEvent(
                LocalCheckpointManager.CheckpointType.NETWORK_DOWN,
                "noisyReceiver kulaklık çıkışı hatası: ${t.message ?: "(bilinmiyor)"}"
            )
            }
        }
    }
    private var noisyRegistered = false
    private var sleepRunnable: Runnable? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationShown = false
    /**
     * v36.1 — "metin katmanı bozuk, AR modunu aç" ipucu bir kez gösterilir.
     * Ölçüm: Kur'an mealinde 699 sayfanın 269'unda font eşlemesi bozuk;
     * AR modu KAPALIYKEN kullanıcı bunu hiç bilmiyordu (sessizce süzülüyordu).
     */
    private var brokenHintShown = false

    // ── Lifecycle ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannel()

        // v16 (GÖREV 3) — kulaklık çıkarılınca okumayı duraklat (Android 14+ uyumlu)
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                noisyReceiver,
                android.content.IntentFilter(
                    android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY
                ),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyRegistered = true
        } catch (t: Throwable) {
            logServiceError("registerNoisyReceiver", t)
            LocalCheckpointManager.logEvent(
                LocalCheckpointManager.CheckpointType.NETWORK_DOWN,
                "registerNoisyReceiver kulaklık alıcısı kaydı doğrulaması başarısız: ${t.message ?: "(bilinmiyor)"}"
            )
        }

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
            // v8 (KATEGORİ 1): HATA ≠ BİTİŞ. Eski kod motor hatasını da "bitti"
            // gibi işleyip sıradaki parçaya geçiyordu; Edge/yerleşik motor art arda
            // hata verince sayfa sesizce baştan sona atlıyordu ("Oku → atlama" bug'ı).
            onError = { id -> mainHandler.post { id?.let { onUtteranceError(it) } } }
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
        // v16 (GÖREV 3) — alıcıyı bırak (sızıntı/IllegalStateException koruması)
        if (noisyRegistered) {
            try { unregisterReceiver(noisyReceiver) } catch (_: Throwable) {}
            noisyRegistered = false
        }
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
        BookStore.loadBooks(this).firstOrNull { it.id == id }?.let { b ->
            pageCount = b.pageCount
            bookTitle = b.title
        } ?: run { pageCount = 0 }
        currentPage = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        chunkIndex = sentence.coerceAtLeast(0)
        chunks = emptyList()
        consecutiveSkips = 0
        chunkErrorCount = 0        // ---- v30 checkpoint: motor başlamadan önce kullanıcıya bilgi ----
        // eski kod sadece notifyState(null) diyordu -> kullanıcı sessiz kalıyordu
        if (pageCount <= 0) {
            emitInfo("Sayfa sayisi bilinmiyor — okuma duraklatildi.")
            LocalCheckpointManager.logEvent(
                LocalCheckpointManager.CheckpointType.API_TOKEN_FRESHNESS,
                "openBook pageCount=0 regresif:${bookId ?: "(yok)"}"
            )
            pause()
            // sayfa motoru yok -> kullanıcıya bilgi verildi, sessiz kalma riski kapandı
            return
        }
        notifyState(null)
    }

    fun play() {
        if (bookId == null) return
        wantPlay = true
        pausedByTransientFocusLoss = false   // elle başlatma: odak devam izni sıfırlanır
        if (tts?.isReady() == true) {
            wantPlay = false
            startPlaying()
            return
        }
        // v33 — "Play'e bastım, hiçbir şey olmadı" düzeltmesi.
        // Eskiden yalnız onReady beklenıyordu; motor bir kez başlatılamadıysa
        // (onReady(ok=false)) istek sonsuza kadar asılı kalıyordu. Artık
        // kısa aralıklarla yeniden denenir, son çare olarak bilgi verilir.
        playRetry++
        if (playRetry > 4) {
            wantPlay = false
            playRetry = 0
            emitInfo("Ses motoru başlatılamadı — Ayarlar → Ses testi ile kontrol et.")
            return
        }
        emitInfo(getString(R.string.ses_motoru_hazirlaniyor))
        mainHandler.postDelayed({
            if (!playing && wantPlay) play()
        }, 700L)
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
        if (pageCount <= 0) {
            // Sayfa sayısı bilinmiyor / kutupsuz durum: sayfa geçişi güvenliğine
            // kapat, ama kullanıcıyı bilgilendir (ikinci sayfa hatası şüphesi).
            emitInfo("Sayfa sayisi bilinmiyor — okuma duraklatildi.")
            pause()
            return
        }
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
        // v33 — SAYFA DEĞİŞTİRİNCE OKUMANIN DURMASI / "play çalışmıyor":
        // eski sayfanın hazırlama işi (buildChunks) ya da bekleyen parça işi
        // iptal edilmezse yeni sayfanın durumunu ezebiliyordu. Artık hepsi
        // temizlenir; okuma niyeti (playing) korunur ve yeni sayfa başlatılır.
        try { speakJob?.cancel() } catch (_: Throwable) { }
        speakJob = null
        currentPage = page
        chunkIndex = 0
        consecutiveSkips = 0
        chunkErrorCount = 0
        playRetries = 0
        aheadPrepared = false
        aheadPage = -1
        aheadJob?.cancel()
        chunks = emptyList()
        tts?.stop()
        // Bazı motorlar stop sonrası hız/sesi sıfırlar → yeniden uygula
        tts?.applyRate(rate)
        tts?.applyVoice(voiceMode)
        saveProgressNow()
        if (playing) {
            speakCurrentPage()
            armPlayWatchdog()
        } else {
            notifyState(null)
            // Okuma niyeti varsa (play'e basıldı ama motor henüz hazır değildi)
            if (wantPlay) play()
        }
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

    /** Ses perdesi (Ses Renkleri paneli): 0.5 derin – 2.0 tiz. */
    fun applyPitch(p: Float) {
        tts?.applyPitch(p)   // TtsManager kalıcı olarak kaydeder
    }

    /** Ekolayzır kazançları (yalnız Edge/mp3 modunda etkili). */
    fun setEqualizer(on: Boolean, low: Float, mid: Float, high: Float) {
        tts?.setEqualizer(on, low, mid, high)
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

    // ── ADIM 3: Tek seferlik seslendirme (Yapay zekâ sayfa özeti) ──

    /**
     * Verilen metni okuma akışından BAĞIMSIZ olarak bir kez seslendirir
     * (okuma ekranındaki "▶ Dinle" butonu).
     *
     * - Okuma sürüyorsa önce duraklatılır (ikisi üst üste binmesin).
     * - `utteranceId` = "summary" olduğu için parça ilerlemesine karışmaz;
     *   bitince `onExtraSpeaking(false)` yayınlanır ve dalga çizgisi düz çizgiye döner.
     * - Ekran açıkken kullanılmak üzere tasarlandı; servis öldürülürse
     *   zaten yeni oturumda yeniden başlatılır.
     */
    fun speakSummary(text: String) {
        if (text.isBlank()) return
        wantPlay = false
        if (playing) pauseInternal()
        val engine = tts ?: return
        if (!engine.isReady()) {
            emitInfo("Ses motoru hazırlanıyor — biraz sonra tekrar dene.")
            return
        }
        engine.applyRate(rate)
        extraSpeaking = true
        summaryPaused = false
        // v5 (ADIM D): cümle parçalarına böl → duraklat/devam mümkün olur
        extraChunks = TextUtil.speechChunks(text)
        extraIndex = 0
        notifyExtraSpeaking(true)
        speakSummaryChunk()
    }

    /** v5 (ADIM D): özetin sıradaki parçasını okur (prefetch ile bekleme kısalır). */
    private fun speakSummaryChunk() {
        if (!extraSpeaking || summaryPaused) return
        val engine = tts ?: return
        if (extraIndex >= extraChunks.size) {
            clearExtraSpeaking()
            return
        }
        engine.speak(extraChunks[extraIndex], ID_SUMMARY)
        engine.prefetch(extraChunks.getOrNull(extraIndex + 1))
    }

    /** v5 (ADIM D): ÖZETİ DURDUR — başa dönüş, paneldeki dinle ile yeniden başlar. */
    fun stopSummary() {
        if (!extraSpeaking) return
        tts?.stop()
        summaryPaused = false
        extraChunks = emptyList()
        extraIndex = 0
        clearExtraSpeaking()
    }

    /** v5 (ADIM D): ÖZETİ DURAKLAT — kaldığı parçada bekler. */
    fun pauseSummary() {
        if (!extraSpeaking || summaryPaused) return
        summaryPaused = true
        tts?.stop()
    }

    /** v5 (ADIM D): ÖZETE DEVAM — kaldığı parçadan sürdürür. */
    fun resumeSummary() {
        if (!extraSpeaking || !summaryPaused) return
        summaryPaused = false
        speakSummaryChunk()
    }

    private fun clearExtraSpeaking() {
        if (!extraSpeaking) return
        extraSpeaking = false
        summaryPaused = false
        extraChunks = emptyList()
        extraIndex = 0
        notifyExtraSpeaking(false)
    }

    private fun notifyExtraSpeaking(active: Boolean) {
        mainHandler.post {
            listeners.toList().forEach { it.onExtraSpeaking(active) }
        }
    }

    // ── Okuma akışı ───────────────────────────────────────────

    private fun startPlaying() {
        if (bookId == null) return
        if (playing) return
        if (!networkAvailableForSpeech()) {
            emitInfo("İnternet erişimi yok — ses motoru çevrimdışı kipte devam edecek.")
            LocalCheckpointManager.logEvent(
                LocalCheckpointManager.CheckpointType.NETWORK_DOWN,
                "seslendirme başlangıcı: ağ yok"
            )
        }
        if (edgeTokenStaleOrMissing()) {
            emitInfo("Edge ses anahtari tazeleniyor…")
            LocalCheckpointManager.logEvent(
                LocalCheckpointManager.CheckpointType.API_TOKEN_FRESHNESS,
                "edge token tazelema tetikledi"
            )
        }

        playing = true
        playRetry = 0
        AppPrefs.putBool(this, AppPrefs.KEY_PLAYING, true)
        requestAudioFocus()   // v8: ses odağını al (başka uygulama çalıyorsa bile)
        acquireWakeLock()
        startAsForeground()
        scheduleSleepTimer()
        startStatsTick()   // v12: okuma süresi sayacı (istatistikler için)
        tts?.applyRate(rate)
        tts?.applyVoice(voiceMode)
        playRetries = 0
        speakCurrentPage()
        armPlayWatchdog()
        updateNotification()
    }

    /** 12 sn içinde ses başlamazsa sayfayı yeniden hazırlar (en fazla 2 kez). */
    private fun armPlayWatchdog() {
        cancelPlayWatchdog()
        val startedAt = android.os.SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                if (!playing) return
                if (speechStartedAt >= startedAt) return   // ses başladı → sorun yok
                if (playRetries >= 2) {
                    emitInfo(getString(R.string.okuma_baslatilamadi))
                    return
                }
                playRetries++
                android.util.Log.w("UmayRead", "ses başlamadı (12 sn) → yeniden deneme $playRetries")
                emitInfo(getString(R.string.okuma_yeniden_deneniyor))
                speakCurrentPage()
                mainHandler.postDelayed(this, 12_000L)
            }
        }
        playWatchdog = r
        mainHandler.postDelayed(r, 12_000L)
    }

    private fun cancelPlayWatchdog() {
        playWatchdog?.let { mainHandler.removeCallbacks(it) }
        playWatchdog = null
    }

    // ── v12 (YENİ ÖZELLİK) — Okuma süresi sayacı ──────────
    // Her 60 saniyede bir AnalyticsStore'a 1 dakika yazar.
    // Servis thread'inde çalışır (disk I/O ana thread'de DEĞİL — ANR yok).
    private var statsTickMinutes = 0
    private val statsTickRunnable = object : Runnable {
        override fun run() {
            if (!playing) return
            statsTickMinutes++
            try { AnalyticsStore.addMinutes(this@ReadService, 1) } catch (_: Throwable) {}
            // v13 (AGENT 22): okuma serisi (streak) — her tik bir "bugün okudum" damgası
            try { StreakStore.recordDay(this@ReadService) } catch (_: Throwable) {}
            mainHandler.postDelayed(this, 60_000L)
        }
    }

    private fun startStatsTick() {
        statsTickMinutes = 0
        mainHandler.removeCallbacks(statsTickRunnable)
        mainHandler.postDelayed(statsTickRunnable, 60_000L)
    }

    private fun stopStatsTick() {
        mainHandler.removeCallbacks(statsTickRunnable)
    }

    private fun pauseInternal() {
        if (!playing && !notificationShown) {
            // yine de kaydet
        }
        playing = false
        AppPrefs.putBool(this, AppPrefs.KEY_PLAYING, false)
        stopStatsTick()   // v12: istatistik sayacını durdur
        abandonAudioFocus()   // v8: odak bırakılsın (müzik vb. geri gelebilsin)
        speakJob?.cancel()
        speakJob = null
        tts?.stop()
        clearExtraSpeaking()   // özet seslendirmesi de dursun (dalga çizgisine yansır)
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

    /**
     * v5.12 — Bir sayfanın okunacak parçalarını üretir:
     * metin oku → (gerekiyorsa) Türkçe'ye çevir → parçala → yazı sistemine böl.
     * IO thread'inde çalışır; hiçbir adım istisna sızdırmaz.
     */
    private suspend fun buildChunks(bid: String, page: Int): List<String>? {
        val text = try {
            awaitPageText(bid, page)
        } catch (t: Throwable) {
            logServiceError("awaitPageText", t)
            LocalCheckpointManager.logEvent(
                LocalCheckpointManager.CheckpointType.NETWORK_DOWN,
                "awaitPageText disk okuma/çeviri hatası: ${t.message ?: "(bilinmiyor)"}"
            )
            null
        }
        var body = text ?: ""

        // v5.12 — ARAPÇA OCR MODU: meal/tefsir PDF'lerinde Arapça satırlar
        // metin katmanında yok. Mod açıksa sayfa görüntüsünden transkript
        // kullanılır (önbellek varsa API'ye gitmez).
        val ocrOn = try { AppPrefs.ocrRead(this, bid) } catch (_: Throwable) { false }
        if (ocrOn) {
            // v31 — OCR artık okumayı BEKLETMEZ: önbellekte yoksa en fazla 6 sn
            // beklenir (metin katmanı bozuksa), aksi hâlde arka planda hazırlanır.
            // Eski davranış yavaş bağlantıda sayfa başını 60 sn'ye kadar kilitliyordu.
            val bodyLooksBroken = body.isBlank() || TextUtil.looksBrokenExtraction(body)
            val ocr = try {
                ArabicOcr.cachedOrBuild(
                    this, bid, page,
                    waitMs = if (bodyLooksBroken) 3_000L else 0L
                )
            } catch (t: Throwable) {
                // İptal (kullanıcı sayfa değiştirdi) hata değil → günlüğe yazma
                if (t !is kotlinx.coroutines.CancellationException) logServiceError("arabicOcr", t)
                null
            }
            if (!ocr.isNullOrBlank()) {
                // v31 — SEÇİM KURALI (eski kural "OCR daha uzunsa" idi; bozuk
                // metin katmanı uzun olduğu için OCR'ı EZİYORDU → Arapça hiç
                // okunmuyordu). Yeni kural: katman bozuksa ya da Arapça OCR'da
                // var / katmanda yoksa OCR kullanılır.
                val bodyBroken = bodyLooksBroken
                val ocrArabic = ocr.any { TextUtil.isArabicChar(it) }
                val bodyArabic = body.any { TextUtil.isArabicChar(it) }
                body = when {
                    body.isBlank() -> ocr
                    bodyBroken -> ocr
                    ocrArabic && !bodyArabic -> ocr
                    ocr.length > body.length -> ocr
                    else -> body
                }
            } else if (TextUtil.looksBrokenExtraction(body)) {
                // OCR alınamadı (çevrimdışı / kota): çöp satırları okuma.
                emitInfo(getString(R.string.arapca_ocr_cevrimdisi))
            }
        } else if (!brokenHintShown && TextUtil.looksBrokenExtraction(body)) {
            // v36.1 — AR modu KAPALI ve sayfa bozuk: sessizce süzüp geçmek
            // yerine bir kez söyle (kullanıcı modu açarsa sayfa görüntüsünden
            // okunur). Her sayfada tekrarlamıyoruz — okumayı bölmesin.
            brokenHintShown = true
            emitInfo(getString(R.string.arapca_modu_onerisi))
        }

        // v31 — ÇÖP SÖZCÜK SÜZGECİ: font eşlemesi bozuk sayfalarda metin
        // katmanı "0.,f� ::; r� > Q.1." gibi sözcükler üretiyor; bunlar sesli
        // okumada anlamsız çıkıyordu. Gerçek sözcükler/sayılar korunur.
        body = try {
            // Süzgeç yalnız GERÇEKTEN bozuk sayfalarda çalışır: font eşlemesi
            // izi (U+FFFD / özel kullanım) varsa ya da sözcüklerin %45'i
            // okunamazsa. Kod/İngilizce sayfaları (bol sembol) korunur.
            // v33 — Arapça metinde bu süzgeç çalışmaz: meal/tefsir sayfasını
            // "çöp sözcük" sanıp SİLİYORDU (Arapça okumanın bozulma nedeni).
            val arabicPage = TextUtil.arabicRatio(body) >= 0.15
            val broken = !arabicPage && (TextUtil.hasFontBreakage(body) ||
                TextUtil.garbageTokenShare(body) >= 0.45)
            if (broken) {
                android.util.Log.i(
                    "UmayRead",
                    "page=$page bozuk-font → cop-sözcük=${(TextUtil.garbageTokenShare(body) * 100).toInt()}% süzülüyor"
                )
                TextUtil.dropGarbageWords(body)
            } else body
        } catch (_: Throwable) { body }

        // v33 — Arapça harfleri NFKC ile normalleştir (sunum biçimi/tatweel
        // sesli okumada "çöp" heceleme üretiyordu).
        body = TextUtil.normalizeArabic(body)

        // null = sayfada metin yok → çağıran boş sayfa akışını işletir
        if (body.isBlank()) return null

        // v5.12 — Arapça sayfayı "İngilizce" sanıp çevirmeye çalışmak metni
        // bozuyordu (meal/tefsir). Arapça ağırlıklı metinde çeviri atlanır.
        val arabicHeavy = TextUtil.arabicRatio(body) >= 0.15
        if (!arabicHeavy && needsTranslation(body)) {
            emitInfo("İngilizce metin Türkçe'ye çevriliyor…")
            body = try {
                TrTranslator.translate(body)
            } catch (t: Throwable) {
                logServiceError("translate", t)
                LocalCheckpointManager.logEvent(
                    LocalCheckpointManager.CheckpointType.API_TOKEN_FRESHNESS,
                    "translate çeviri hatası: ${t.message ?: "(bilinmiyor)"}"
                )
                body
            }
            if (needsTranslation(body)) {
                emitInfo("Çeviri modeli indirilemedi (internet gerekli) — orijinal metin okunuyor.")
            }
        }

        val raw = try {
            TextUtil.speechChunks(body)
        } catch (t: Throwable) {
            logServiceError("speechChunks", t)
            LocalCheckpointManager.logEvent(
                LocalCheckpointManager.CheckpointType.API_TOKEN_FRESHNESS,
                "speechChunks metin parçalama hatası: ${t.message ?: "(bilinmiyor)"}"
            )
            emptyList()
        }
        // v5.12 — Arapça ve Türkçe blokları AYRI parçalara böl (her parça tek
        // yazı sisteminde olsun; ses seçimi parça başına doğru yapılabilsin).
        val out = TextUtil.splitByScript(raw)
        android.util.Log.i(
            "UmayRead",
            "page=${page + 1} parca=${out.size} ocr=$ocrOn metin=${body.length}" +
                " arapca=${body.any { TextUtil.isArabicChar(it) }}"
        )
        return out
    }

    /** Bir sonraki sayfayı sessizce hazırlar (geçişte bekleme olmasın). */
    private fun prepareAhead(page: Int) {
        val bid = bookId ?: return
        if (!playing) return
        if (page < 0 || page >= pageCount) return
        if (page == aheadPage) return
        aheadJob?.cancel()
        aheadJob = scope.launch {
            val list = try { buildChunks(bid, page) } catch (_: Throwable) { null }
            withContext(Dispatchers.Main) {
                if (playing) {
                    aheadPage = page
                    aheadChunks = list ?: emptyList()
                    aheadPrepared = true
                }
            }
        }
    }

    private fun speakCurrentPage() {
        if (!playing) return
        val bid = bookId ?: return
        val page = currentPage

        speakJob = scope.launch {
            // v5.12: bir sonraki sayfa hazırsa disk/çeviri beklemesi YOK.
            val cached = (page == aheadPage && aheadPrepared)
            val list = if (cached) aheadChunks else buildChunks(bid, page)
            if (!playing || currentPage != page) return@launch

            // Sayfada hiç metin yok (boş sayfa): duraklatma, sıradakine geç.
            if (list == null) {
                withContext(Dispatchers.Main) {
                    if (playing && currentPage == page) handleEmptyPage()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                if (!playing || currentPage != page) return@withContext
                if (list.isEmpty()) {
                    // Boş parça listesi: sayfa düzgün okunmayacak.
                    // İlk sayfa için bunu doğal olarak "kullanici tarafinda bos sayfa"
                    // ile karıştırmamak için onsöyle bir bilgi ver, arada bir hata
                    // durumunu izole ettikten sonra durumu kaydet.
                    android.util.Log.w("UmayRead", "page=$page bos parca listesi → duraklatildi")
                    emitInfo("Sayfa bos veya metin cozmedi — okuma burada duraklatildi.")
                    // checkpoint: token/internet kaynakli mi diye izole et.
                    if (!networkAvailableForSpeech()) {
                        LocalCheckpointManager.checkIn(
                            LocalCheckpointManager.CheckpointType.NETWORK_DOWN,
                            "sayfa bos (ag yok)"
                        )
                        LocalCheckpointManager.checkOut(
                            LocalCheckpointManager.CheckpointType.NETWORK_DOWN,
                            false, "network unavailable during page read"
                        )
                    } else if (edgeTokenStaleOrMissing()) {
                        LocalCheckpointManager.checkIn(
                            LocalCheckpointManager.CheckpointType.API_TOKEN_FRESHNESS,
                            "sayfa bos (token tazelema)"
                        )
                        LocalCheckpointManager.checkOut(
                            LocalCheckpointManager.CheckpointType.API_TOKEN_FRESHNESS,
                            false, "token stale during page read"
                        )
                    }
                    pause()
                    return@withContext
                }
                consecutiveSkips = 0
                chunkErrorCount = 0
                chunks = list
                val start = chunkIndex.coerceIn(0, list.size - 1)
                speakChunk(start)
                // v5.12 — sıradaki sayfayı şimdiden hazırla (okuma sürerken)
                if (aheadPage != page + 1) prepareAhead(page + 1)
            }
        }
    }

    /** v5.1: Servis içi beklenmeyen hatayı cihaz günlüğüne ve last_error.txt'ye yazar. */
    private fun logServiceError(where: String, t: Throwable) {
        t.printStackTrace()
        try {
            BookStore.logError(this, "ReadService.$where", t)
        } catch (_: Exception) {
        }
    }

    private fun needsTranslation(text: String) =
        TextUtil.needsTranslation(text, translateEnabled)

    /** Sayfa metnini bekler; çıkarım arka planda sürüyorsa bite kadar bekler. */
    private suspend fun awaitPageText(bid: String, page: Int): String? {
        // 1) Çıkarma/OCR sürüyorsa bitmesini bekle (metin birazdan gelir)
        // v31 — BEKLEME SINIRI: eskiden çıkarma sürerken 12 dakikaya kadar
        // bekliyordu (kullanıcı için "okuma takıldı" görünümü). Artık en fazla
        // ~60 sn beklenir; sonrasında sayfa boş sayılır ve okuma devam eder.
        var guard = 0
        while (BookStore.isExtracting(this, bid) && guard < 150) {
            BookStore.pageText(this, bid, page)?.let { return it }
            delay(400)
            guard++
        }
        if (guard >= 150) {
            android.util.Log.w("UmayRead", "page=${page + 1} metin çıkarma bekledi (60 sn) → devam")
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
        // v32 — okunan sayfa "okundu" sayılır (ekran kapalıyken de yüzde artsın)
        if (index == 0) {
            try { ReadProgressStore.markRead(this, bookId ?: "", currentPage, pageCount) } catch (_: Throwable) { }
        }
        chunkIndex = index
        speechStartedAt = android.os.SystemClock.uptimeMillis()
        saveProgressNow()
        val text = chunks[index]
        // v9 (GÖREV 2.1): Boş/whitespace parçayı motora VERME — motor boş
        // metin için bitiş olayı üretmeyebilir → parça takılır, okuma
        // sessizce sayfa sonuna atlar. Boş parça "okundu" sayılır.
        if (text.isBlank()) {
            onUtteranceDone("$currentPage:$index")   // main thread: speakChunk main'de
            return
        }
        notifyState(text)
        tts?.speak(text, "$currentPage:$index")
        // Sıradaki parçayı önceden indir (doğal seste bekleme kısalır)
        val nextChunk = chunks.getOrNull(index + 1)
        if (nextChunk != null) {
            tts?.prefetch(nextChunk)
        } else {
            // v5.12 — sayfanın son parçası: SONRAKİ SAYFANIN ilk parçasını
            // hazırla → sayfa geçişinde ses boşluğu kalmaz.
            if (aheadPage != currentPage + 1) prepareAhead(currentPage + 1)
            tts?.prefetch(aheadChunks.firstOrNull())
        }
    }

    private fun onUtteranceDone(id: String) {
        // ADIM 3/5: özet parça parça okunur → bir parçası bitince sıradakine geç
        if (id == ID_SUMMARY) {
            if (!extraSpeaking || summaryPaused) return
            extraIndex++
            if (extraIndex >= extraChunks.size) clearExtraSpeaking()
            else speakSummaryChunk()
            return
        }
        if (!playing) return
        val parts = id.split(":")
        if (parts.size != 2) return
        val page = parts[0].toIntOrNull() ?: return
        val idx = parts[1].toIntOrNull() ?: return
        if (page != currentPage) return
        chunkErrorCount = 0   // başarılı okuma → hata sayacı sıfırlanır
        speakChunk(idx + 1)
    }

    /**
     * v8 (KATEGORİ 1): TTS/MediaPlayer HATASI — asla "bitti" gibi işlenmez.
     *
     * Eski davranış: hata → bir sonraki parça → … → sayfa sessizce biterdi
     * (ses hiç çalmadan sayfa sonuna atlama). Yeni davranış:
     *  1. Aynı parça en fazla 2 kez tekrar denenir (400 ms arayla).
     *  2. 3. hatada okuma DURAKLATILIR ve kullanıcıya net mesaj gösterilir
     *     — atlama yok, sessizlik yok, çökme yok.
     */
    private fun onUtteranceError(id: String) {
        if (id == ID_SUMMARY) {
            clearExtraSpeaking()
            emitInfo("Seslendirme hatası: özet okunamadı. Tekrar deneyebilirsin.")
            return
        }
        if (!playing) return
        val parts = id.split(":")
        if (parts.size != 2) return
        val page = parts[0].toIntOrNull() ?: return
        val idx = parts[1].toIntOrNull() ?: return
        if (page != currentPage) return   // bayat hata bildirimi

        chunkErrorCount++
        if (chunkErrorCount <= 2) {
            emitInfo("Ses motoru yanıt vermedi — tekrar deneniyor ($chunkErrorCount/2)…")
            mainHandler.postDelayed({
                if (playing && currentPage == page) speakChunk(idx)
            }, 400L)
        } else {
            // v5.4 — YABANCI KARAKTER / BOZUK PARÇA: okumayı DURDURMA.
            // Eskiden 3. hatada okuma tamamen duraklıyordu; Kur'an-ı Kerim gibi
            // Arapça sayfalarda, formül/kod içeren sayfalarda kullanıcı "okuma
            // durdu" diyordu. Artık parça atlanıp devam edilir; 6 ardışık
            // atlamada (gerçekten motor ölüyse) duraklatılır ve bilgi verilir.
            consecutiveSkips++
            chunkErrorCount = 0
            if (consecutiveSkips <= 6) {
                emitInfo("Bu parça seslendirilemedi — sonraki parçaya geçiliyor.")
                mainHandler.postDelayed({
                    if (playing && currentPage == page) speakChunk(idx + 1)
                }, 150L)
            } else {
                consecutiveSkips = 0
                emitInfo("Seslendirme başarısız — okuma duraklatıldı. Ayarlardan ses motorunu kontrol edebilirsin.")
                pause()
            }
        }
    }

    // ── v8 (KATEGORİ 1): Ses odağı (audio focus) ──────────────────────

    /**
     * Okuma başlarken ses odağı iste; kaybedilince okuma kendiliğinden duraklar.
     *
     * v9 (GÖREV 2.1 — AUDIO FOCUS KÖK ÇÖZÜMÜ):
     *  - KALICI kayıp (LOSS): duraklat + odağı tamamen bırak (kullanıcı elle
     *    devam etmeli).
     *  - GEÇİCİ kayıp (LOSS_TRANSIENT / LOSS_TRANSIENT_CAN_DUCK — arama,
     *    bildirim sesi, navigasyon anonsu): duraklat ama odağı BIRAKMA ve
     *    `pausedByTransientFocusLoss` izini bırak → sistem GAIN geri
     *    verdiğinde okuma OTOMATİK sürer (eskisiyle "kitap ortada kalıyordu").
     */
    private fun requestAudioFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = android.media.AudioFocusRequest.Builder(
                android.media.AudioManager.AUDIOFOCUS_GAIN
            )
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { change ->
                    // Dinleyici AudioManager iş parçacığından gelir → servis
                    // durumunu main thread üzerinden güncelle.
                    mainHandler.post {
                        when (change) {
                            android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                                if (playing) {
                                    pausedByTransientFocusLoss = false
                                    emitInfo("Ses odağı kalıcı kaybedildi — okuma duraklatıldı.")
                                    pause()
                                }
                            }

                            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                                if (playing) {
                                    pausedByTransientFocusLoss = true
                                    emitInfo("Ses odağı kısa süreliğine kayboldu — duraklatıldı, geri gelince devam edecek.")
                                    pauseInternal()   // odağı BIRAKMA → GAIN'de otomatik devam
                                }
                            }

                            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                                // Geçici kayıp sonrası odak geri geldi: kaldığı
                                // parçadan otomatik devam (elle duraklatmadıysa).
                                if (pausedByTransientFocusLoss && !playing && bookId != null) {
                                    pausedByTransientFocusLoss = false
                                    emitInfo("Ses odağı geri geldi — okuma devam ediyor.")
                                    play()
                                }
                            }
                        }
                    }
                }
                .build()
            audioFocusReq = req
            focusGranted = am.requestAudioFocus(req) ==
                android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (t: Throwable) {
            // Odak desteklenmiyorsa okuma yine de çalışır
            logServiceError("requestAudioFocus", t)
            LocalCheckpointManager.logEvent(
                LocalCheckpointManager.CheckpointType.NETWORK_DOWN,
                "requestAudioFocus ses odağı hatası: ${t.message ?: "(bilinmiyor)"}"
            )
            focusGranted = false
        }
    }

    private fun abandonAudioFocus() {
        if (!focusGranted) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioFocusReq?.let { am.abandonAudioFocusRequest(it) }
        } catch (_: Throwable) {
        }
        focusGranted = false
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
        // v16 (GÖREV 3) — SAYFA ARALIĞI: örn. 15→40 arası seçildiyse 40'ıncı
        // sayfa bitince okuma otomatik biter ("müzik çalar" akışı gibi).
        // to == 0 → sınırsız (varsayılan).
        val rangeTo = bookId?.let {
            AppPrefs.ttsRangeTo(this, it)
        } ?: 0
        if (rangeTo > 0 && currentPage + 1 >= rangeTo) {
            emitInfo(getString(R.string.sayfa_araligi_bitti, rangeTo))
            finishBook()
            return
        }
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
        saveProgressNow()   // boş sayfa atlanınca da "kaldığın yer" tazelenir
        updateNotification()
        speakCurrentPage()
    }

    private fun finishBook() {
        emitInfo("Kitap bitti 🎉")
        // v12: bitirilen kitap rozeti (istatistikler ekranında görünür)
        try { bookId?.let { AnalyticsStore.markFinished(this, it) } } catch (_: Throwable) {}
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
        val title = bookTitle ?: "Umay PDF Okuyucu"
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

        /** ADIM 3: tek seferlik (özet) seslendirme parçacık kimliği. */
        private const val ID_SUMMARY = "summary"

    }

    // ── Ağ / ses motoru sağlığı ─────────────────────────────────

    /** Cihazda kullanılabilir bir ağ var mı? (Edge neural ses için gerekli) */
    private fun networkAvailableForSpeech(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Edge neural ses anahtarsız çalışır (ücretsiz uç nokta), bu yüzden
     * eksik API anahtarı diye bir durum yok. Yalnızca ağ yoksa "tazele"
     * uyarısı anlamlıdır.
     */
    private fun edgeTokenStaleOrMissing(): Boolean = !networkAvailableForSpeech()
}
