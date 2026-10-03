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
