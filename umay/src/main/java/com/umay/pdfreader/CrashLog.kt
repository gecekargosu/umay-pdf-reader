package com.umay.pdfreader

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v6 — AŞAMA 6.2: Çökme günlükleme altyapısı (Firebase Crashlytics + Vitals).
 *
 * Görevleri:
 *  1. Firebase Crashlytics sınıfı build'e eklendiyse **non-fatal** olayları oraya yazar.
 *  2. Aynı olayı cihazda `filesDir/logs/crash_log.txt` dosyasına da yazar
 *     (Crashlytics gönderim öncesi/ötesinde kanıt kalsın; Play Vitals
 *     çökme oranını düşürmek için ilk 8 sn'deki açılış çökmesi kritiktir).
 *  3. Global uncaught exception handler'ı sarar: uygulama düşmeden ÖNCE
 *     son yığın izini diske yazar (`last_crash.txt`) — sonraki açılışta
 *     `BootDiag` bunu loglar.
 *
 * Tüm çağrılar kendi içinde try-catch'lidir: günlükleme asla ikinci bir
 * çökme kaynağı olmaz.
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val LOG_DIR = "logs"

    @Volatile private var installed = false

    /** UmayApp.onCreate'den bir kez çağrılır. Idempotent. */
    fun init(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        installHandler(app)
        log(app, "app_start v=${BuildInfo.versionName}")
    }

    /** Global yakalayıcıyı kur: diske yaz → sonra eski davranışı sürdür. */
    private fun installHandler(app: Context) {
        try {
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    write(app, "last_crash.txt", buildString {
                        appendLine("time=${now()} thread=${thread.name}")
                        appendLine(throwable.toString())
                        appendLine(throwable.stackTraceToString().take(8000))
                    })
                } catch (_: Throwable) {
                }
                try {
                    firebaseRecord(throwable)
                } catch (_: Throwable) {
                }
                prev?.uncaughtException(thread, throwable)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Handler kurulamadı", t)
        }
    }

    /** Bilgi/uyarı seviyesinde olay (dosya + Crashlytics breadcrumb). */
    fun log(ctx: Context, message: String) {
        try {
            append(ctx, "crash_log.txt", "${now()} I $message")
        } catch (_: Throwable) {
        }
        try {
            firebaseLog(message)
        } catch (_: Throwable) {
        }
    }

    /** Non-fatal hata: dosya + Crashlytics. */
    fun record(ctx: Context, where: String, t: Throwable) {
        try {
            append(
                ctx, "crash_log.txt",
                "${now()} E $where :: ${t.toString()}\n${
                    t.stackTraceToString().take(4000)
                }"
            )
        } catch (_: Throwable) {
        }
        // v17 (ADIM 3 — ONARIM): Hata artık KULLANICININ GÖRDÜĞÜ
        // Documents/UmayPDF/umay_error.log dosyasına da düşer. Eski kodda
        // yalnız dahili crash_log.txt/last_error.txt'ye yazılıyordu → kullanıcı
        // "loglar temiz" sanıyordu ama gerçek hata hiçbir yerde görünmüyordu.
        try {
            publicLogFile(ctx)?.let { f ->
                if (f.length() > 512 * 1024) f.delete()   // 512 KB taşma koruması
                f.appendText(
                    "${now()} | $where | HATA: ${t.javaClass.name}: " +
                        "${t.message ?: "(mesaj yok)"}\n" +
                        (t.stackTrace.take(8).joinToString("\n") { "    at $it" }) + "\n"
                )
            }
        } catch (_: Throwable) {
        }
        try {
            firebaseLog("$where: $t")
            firebaseRecord(t)
        } catch (_: Throwable) {
        }
    }

    // ── v17 (GÖREV 2) — KULLANICIYA AÇIK, KOPYALANABİLİR HATA GÜNLÜĞÜ ──
    //
    // Hedef: kullanıcı hata anında Ayarlar'dan tek dokunuşla bu dosyayı
    // kopyalayıp Freebuff ajanına verebilsin; ajan kaynağı anında görsün.
    // Yer: Documents/UmayPDF/umay_error.log (istemci görünür dizin)
    // Yedek: uygulama-harici dizin → olmazsa dahili filesDir/logs/.

    private const val PUBLIC_LOG_NAME = "umay_error.log"

    /** Kullanıcının görebileceği (kopyalanabilir) hata günlüğü dosyası. */
    fun publicLogFile(ctx: Context): File? = try {
        val docs = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS
        )
        val dir = File(docs, "UmayPDF")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, PUBLIC_LOG_NAME)
        if (f.exists() || f.createNewFile()) f else null
    } catch (_: Throwable) {
        try {
            ctx.getExternalFilesDir(null)?.let { dir ->
                val f = File(dir, PUBLIC_LOG_NAME)
                if (f.exists() || f.createNewFile()) f else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** v17 (GÖREV 2) — Yapılandırılmış hata kaydı (evrensel parser vb.).
     *
     * Her kayıt: zaman + nerede (`where`) + dosya türü (`fileType`) +
     * hatanın kendisi + ilk stack frame'ler — ajanın kaynağını hemen
     * görmesi için tek satırlık net özet + ayrıntı bloğu.
     * Dahili `crash_log.txt` (mevcut akış korunur) + kamu `umay_error.log`
     * olmak üzere İKİ yere yazar; ikisi de sessizce yutulur (loglama asla
     * ikinci bir çökme kaynağı olmaz). */
    fun recordParse(ctx: Context, where: String, fileType: String, t: Throwable) {
        val block = buildString {
            appendLine("${now()} | $where | tur=$fileType")
            appendLine("  HATA: ${t.javaClass.name}: ${t.message ?: "(mesaj yok)"}")
            t.cause?.let { appendLine("  KOK:  $it") }
            t.stackTrace.take(10).forEach { appendLine("    at $it") }
        }
        try { append(ctx, "crash_log.txt", block.trimEnd()) } catch (_: Throwable) {}
        try {
            publicLogFile(ctx)?.let { f ->
                if (f.length() > 512 * 1024) f.delete()   // 512 KB taşma
                f.appendText(block)
            }
        } catch (_: Throwable) {}
        try {
            firebaseLog("$where($fileType): $t")
            firebaseRecord(t)
        } catch (_: Throwable) {}
    }

    /** v17 — Bilgi/nötr olayı da umay_error.log'a yaz (teşhis bütünlüğü). */
    fun notePublic(ctx: Context, where: String, msg: String) {
        try {
            publicLogFile(ctx)?.let { f ->
                if (f.length() > 512 * 1024) f.delete()
                f.appendText("${now()} | $where | NOT: $msg\n")
            }
        } catch (_: Throwable) {}
    }

    /** Son açılıştaki çökme kaydı varsa okur (diagnostik amaçlı). */
    fun lastCrash(ctx: Context): String? = try {
        val f = File(File(ctx.applicationContext.filesDir, LOG_DIR), "last_crash.txt")
        if (f.exists() && f.length() > 0) f.readText().take(4000) else null
    } catch (_: Throwable) {
        null
    }

    // ── Firebase köprüsü (refleksiyon: sınıf yoksa sessizce atlanır) ──

    private fun firebaseInstance(): Any? = try {
        val cls = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
        cls.getMethod("getInstance").invoke(null)
    } catch (_: Throwable) {
        null
    }

    private fun firebaseLog(msg: String) {
        firebaseInstance()?.let { inst ->
            runCatching {
                inst.javaClass.getMethod("log", String::class.java).invoke(inst, msg)
            }
        }
    }

    private fun firebaseRecord(t: Throwable) {
        firebaseInstance()?.let { inst ->
            runCatching {
                inst.javaClass
                    .getMethod("recordException", Throwable::class.java)
                    .invoke(inst, t)
            }
        }
    }

    // ── Dosya yardımcıları ──────────────────────────────────────

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun logDir(ctx: Context): File {
        val dir = File(ctx.applicationContext.filesDir, LOG_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun append(ctx: Context, name: String, line: String) {
        val f = File(logDir(ctx), name)
        if (f.length() > 512 * 1024) f.delete() // 512 KB taşma koruması
        f.appendText(line + "\n")
    }

    private fun write(ctx: Context, name: String, content: String) {
        File(logDir(ctx), name).writeText(content)
    }
}

/** Sürüm bilgisi (tek kaynak: BuildConfig yerine basit sabit). */
object BuildInfo {
    // v22 senkronu: build.gradle.kts versionName/versionCode ile HİZALI olmalı
    // (v21 turunda gradle 5.2/20'ye geçmişti ama burası 5.1/19'da kalmıştı).
    const val versionName = "5.3"
    const val versionCode = 21
}
