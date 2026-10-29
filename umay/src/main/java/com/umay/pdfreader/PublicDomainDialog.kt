package com.umay.pdfreader

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import com.umay.pdfreader.databinding.DialogPublicDomainBinding

/**
 * v11 (GÖREV 5) — KAMU MALI KİTAP ARAMA DİYALOĞU.
 *
 * Ana ekrandaki "Kamu Malı Kitaplık" butonuna basınca açılır.
 * Kullanıcı kitap adı / yazar / konu yazar; Open Library'de telif hakkı
 * dolmuş ücretsiz kitaplar listelenir. Seçilen kitap arka planda indirilir
 * ve doğrudan kütüphaneye ("Okuma Kitapları" rafı) eklenir.
 *
 * KAYNAK: Open Library search.json API (ücretsiz, anahtar gerekmez).
 */
object PublicDomainDialog {

    fun show(ctx: Context, onImported: (bookId: String) -> Unit) {
        try {
            val b = DialogPublicDomainBinding.inflate(LayoutInflater.from(ctx))
            val dialog = AlertDialog.Builder(ctx)
                .setView(b.root)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            var results = emptyList<PublicDomainBooks.BookResult>()
            val labels = ArrayList<String>()

            b.pdSearch.setOnClickListener {
                val q = b.pdQuery.text.toString().trim()
                if (q.isEmpty()) {
                    Toast.makeText(ctx, "Kitap adı veya yazar yaz", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                b.pdProgress.visibility = View.VISIBLE
                b.pdResults.visibility = View.GONE

                PublicDomainBooks.search(ctx, q) { list ->
                    b.pdProgress.visibility = View.GONE
                    if (list.isEmpty()) {
                        Toast.makeText(ctx, "Sonuç bulunamadı", Toast.LENGTH_SHORT).show()
                        return@search
                    }
                    results = list
                    labels.clear()
                    list.forEach { r ->
                        labels.add("${r.title}\n${r.author} (${r.year}) — ${r.source}")
                    }
                    b.pdResults.adapter = ArrayAdapter(
                        ctx, android.R.layout.simple_list_item_1, labels
                    )
                    b.pdResults.visibility = View.VISIBLE
                }
            }

            b.pdResults.setOnItemClickListener { _, _, pos, _ ->
                val book = results.getOrNull(pos) ?: return@setOnItemClickListener
                if (book.downloadUrl == null) {
                    Toast.makeText(ctx, "Bu kitabın indirilebilir dosyası yok", Toast.LENGTH_SHORT).show()
                    return@setOnItemClickListener
                }
                Toast.makeText(ctx, "\"${book.title}\" indiriliyor…", Toast.LENGTH_SHORT).show()

                PublicDomainBooks.downloadAndImport(ctx, book) { id ->
                    if (id != null) {
                        Toast.makeText(
                            ctx,
                            "\"${book.title}\" kütüphaneye eklendi ✓",
                            Toast.LENGTH_LONG
                        ).show()
                        onImported(id)
                        dialog.dismiss()
                    } else {
                        Toast.makeText(
                            ctx,
                            "İndirme başarısız — tekrar dene",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            dialog.show()
        } catch (t: Throwable) {
            BookStore.logError(ctx, "PublicDomainDialog.show", t)
            Toast.makeText(ctx, "Kamu malı kitaplık açılamadı", Toast.LENGTH_SHORT).show()
        }
    }
}
