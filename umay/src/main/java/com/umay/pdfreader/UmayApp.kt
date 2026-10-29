package com.umay.pdfreader

import android.app.Application
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * Uygulama genelinde bir kez çalışan başlatma.
 *
 * PDFBox-Android kullanmadan ÖNCE `PDFBoxResourceLoader.init(context)`
 * çağrılmalıdır; aksi hâlde resource dosyaları (glyphlist.txt, afm vb.)
 * hazırlanmaz ve ilk metin çıkarmada çöker:
 *   ExceptionInInitializerError:
 *   GlyphList 'com/tom_roush/pdfbox/resources/glyphlist/glyphlist.txt' not found
 */
class UmayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initPdfBox(this)
        // v6 (Aşama 4) — AdMob: G sınıfı güvenli reklam yapılandırması + SDK init
        // (hata olsa bile uygulama açılmaya devam eder)
        try {
            AdMobManager.configureRequestConfiguration()
            AdMobManager.initialize(this)
        } catch (t: Throwable) {
            Log.e("UmayApp", "AdMob init başarısız", t)
        }
        // v5.2 (ADIM C.3 / #25) — günlük kütüphane senkronu (pil dostu, inexact)
        try {
            SyncScheduler.scheduleDaily(this)
        } catch (t: Throwable) {
            Log.e("UmayApp", "SyncScheduler planlanamadı", t)
        }
        // v36 — BOŞTA ÖN HAZIRLIK ZAMANLAYICISI (Faz 1).
        // Yalnız şarjda + cihaz boşta iken metin çıkarma/kalite denetimi yapar;
        // ağ gerektirmez. Kullanıcı kapatabilir (PrepStore.setEnabled).
        // Hata açılışı asla düşürmez.
        try {
            PrepScheduler.schedule(this)
        } catch (t: Throwable) {
            Log.e("UmayApp", "PrepScheduler planlanamadı", t)
        }
        // v6 (Aşama 6) — Firebase Crashlytics (varsa) + oturum günlüğü
        try {
            CrashLog.init(this)
        } catch (t: Throwable) {
            Log.e("UmayApp", "CrashLog init başarısız", t)
        }
        // v13 (T9a): tema modu (Gece/Gündüz/Sistem) — ilk Activity'den önce
        AppPrefs.applyThemeMode(this)
        // v13 (AGENT 22 — A3): seçilen dil (TR/EN) — tüm arayüz ilk Activity'den
        // önce bu dili görür; tercih kalıcıdır (AppPrefs.KEY_LANG).
        LangHelper.applyToApp(this)
        // v14 (V5.0 §1) — Özel tema: kayıtlı renkleri yükle ve HER ekran
        // açıldığında uygula (lifecycle callback — tüm Activity'ler otomatik).
        try {
            ThemeStudio.load(this)
            registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacksAdapter() {
                override fun onActivityResumed(activity: android.app.Activity) {
                    ThemeStudio.apply(activity)
                }
            })
        } catch (t: Throwable) {
            Log.e("UmayApp", "ThemeStudio init başarısız", t)
        }
        // v9 (M7 — AÇILIŞ KORUMASI): Kitaplık DB'sini arka planda ISIT:
        //  • Şema oluşturma/yükseltme (onCreate/onUpgrade) ve bozuk DB'de
        //    self-heal (openSafe) ilk Activity'den ÖNCE çalışır → kullanıcı
        //    güncelleme sonrası ilk açılışta çökmeyle KARŞILAŞMAZ.
        //  • Ana thread disk I/O yapmaz (ANR koruması — M10 ile aynı prensip).
        //  • DB açılamazsa zaten BookStore books.json yedeğine düşer.
        try {
            Thread {
                try {
                    LibraryDb.get(this).readableDatabase?.close()
                    Log.d("UmayApp", "LibraryDb ısıtma/self-heal OK")
                    // v5.4 (ONARIM 3): iki sütunlu sayfalarda eski metinler
                    // karışıktı → bir kez yeniden çıkarılır (arka planda).
                    try { BookStore.migratePageTextV2(this) } catch (_: Throwable) {}
                    // v21.2 — file:// ile eklenen kitapların "source.pdf"
                    // kalan adlarını gerçek dosya adıyla değiştir (bir kez).
                    try { BookStore.repairGenericTitles(this) } catch (_: Throwable) {}
                    // v11 (GÖREV 1) — Ders programı tohum verisi: DB hazır olunca
                    // (sıcaklaştırma sonrası) bir kez programa yazılır; alarmlar
                    // 20 dk önceden kurulur. Hata asla açılışı düşürmez.
                    ScheduleSeed.seedOnce(this)
                    // v5.5 (ONARIM 10): mevcut kitapları derslere bir kez bağla
                    // (ders kartlarındaki "0 kaynak" sorunu — H12.6)
                    try { CourseStore.backfillAutoLink(this) } catch (_: Throwable) {}
                } catch (t: Throwable) {
                    // Açılışta asla fırlatılmaz; kitaplık JSON yedeğine düşer
                    Log.e("UmayApp", "LibraryDb ısıtma başarısız (yedek devrede)", t)
                    try { CrashLog.record(this, "db_warmup", t) } catch (_: Throwable) {}
                }
            }.apply {
                name = "umay-db-warmup"
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
                start()
            }
        } catch (t: Throwable) {
            Log.e("UmayApp", "DB ısıtma thread'i başlatılamadı", t)
        }
    }

    companion object {
        /** PDFBox hazırlığını yapar (idempotent). Application onCreate'de bir kez çalışır. */
        fun initPdfBox(ctx: android.content.Context) {
            try {
                if (!PDFBoxResourceLoader.isReady()) {
                    PDFBoxResourceLoader.init(ctx.applicationContext)
                }
            } catch (t: Throwable) {
                // PDFBox yine de kullanılamazsa uygulama açılmaya devam etsin;
                // çıkarma bir sonraki açılışta tekrar denenir.
                Log.e("UmayApp", "PDFBoxResourceLoader.init başarısız", t)
            }
        }
    }
}
