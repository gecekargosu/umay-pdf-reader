package com.umay.pdfreader

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.umay.pdfreader.databinding.DialogOpenWithBinding

/**
 * v11 (GÖREV 1) — DIŞARIDAN GELEN BELGE KARŞILAMA DİYALOĞU.
 *
 * Dosya yöneticisi / WhatsApp / Chrome'dan bir PDF/DOC/DOCX/TXT/EPUB
 * açıldığında Umay seçildiğinde bu Bottom Sheet görünür:
 *
 *   "Umay'da Görüntüle"  → belge kütüphaneye eklenir ve okuma ekranı açılır
 *   "Umay'da Sesli Dinle" → belge eklenir, okuma ekranı açılır ve doğrudan
 *                           TTS sesli okuma + ses dalgası başlatılır
 *
 * Her iki yol da `BookStore.importPdf` üzerinden geçer (desteklenmeyen
 * biçim otomatik PDF'e çevrilir). Hata asla çökertmez.
 */
object OpenWithDialog {

    /**
     * @param autoSpeak true → "Sesli Dinle" seçilmiş gibi davran (hızlı mod)
     */
    fun show(
        ctx: Context,
        uri: Uri,
        fileName: String?,
        autoSpeak: Boolean = false,
        onImported: (bookId: String, speak: Boolean) -> Unit
    ) {
        // Çok hızlı çift tıklama / iki intent → aynı anda iki import engeli
        if (importing) {
            Toast.makeText(ctx, "Belge zaten içe aktarılıyor…", Toast.LENGTH_SHORT).show()
            return
        }

        if (autoSpeak) {
            doImport(ctx, uri, speak = true, onImported = onImported)
            return
        }

        try {
            val dialog = BottomSheetDialog(ctx)
            val b = DialogOpenWithBinding.inflate(LayoutInflater.from(ctx))
            b.owTitle.text = fileName ?: "Belge"
            // v14 (V5.0 §2): Kullanıcının istediği uyarı metni — tam ekran
            // okuma akışını açmadan ÖNCE net soru sorulur.
            b.owAsk.text = ctx.getString(R.string.ow_ask_import)
            b.owView.setOnClickListener {
                dialog.dismiss()
                doImport(ctx, uri, speak = false, onImported = onImported)
            }
            b.owListen.setOnClickListener {
                dialog.dismiss()
                doImport(ctx, uri, speak = true, onImported = onImported)
            }
            // v13 (AGENT 22 — A5): TEK TIKLA RAFA EKLE — "Bu PDF dosyasını
            // Umay'a aktarmak ister misin?" sorusunun evet seçeneği: belge
            // kitaplığa eklenir, okuma ekranı AÇILMAZ (rafa eklendi uyarısı).
            b.owImport.setOnClickListener {
                dialog.dismiss()
                doImport(ctx, uri, speak = false, openAfter = false, onImported = onImported)
            }
            dialog.setContentView(b.root)
            dialog.show()
        } catch (t: Throwable) {
            // Bottom sheet açılamaz (ör. tema uyumsuzluğu) → doğrudan görüntüle
            BookStore.logError(ctx, "OpenWithDialog.show", t)
            doImport(ctx, uri, speak = false, onImported = onImported)
        }
    }

    @Volatile private var importing = false

    private fun doImport(
        ctx: Context,
        uri: Uri,
        speak: Boolean,
        openAfter: Boolean = true,
        onImported: (bookId: String, speak: Boolean) -> Unit
    ) {
        if (importing) return
        importing = true
        // v31 — DÜZELTME: importPdf geri çağrısı arka plan iş parçacığında
        // geliyordu; Toast orada çağrılınca "Can't toast on a thread that has
        // not called Looper.prepare()" çökmesi oluyordu. Tüm bildirimler ana
        // iş parçacığına taşınır.
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post { Toast.makeText(ctx, "Belge içe aktarılıyor…", Toast.LENGTH_SHORT).show() }
        BookStore.importPdf(ctx, uri) { result ->
            importing = false
            result.fold(
                onSuccess = { book ->
                    if (openAfter) {
                        main.post { onImported(book.id, speak) }
                    } else {
                        // v13 (A5): yalnız rafa ekle — okuma ekranı açılmaz
                        main.post {
                            Toast.makeText(
                                ctx, ctx.getString(R.string.pdf_added_to_shelf, book.title),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                onFailure = { e ->
                    main.post {
                        Toast.makeText(
                            ctx,
                            "Belge açılamadı: ${e.message ?: "bilinmeyen hata"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }
    }
}
