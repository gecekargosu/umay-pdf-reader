package com.umay.pdfreader

import android.app.Activity
import android.app.TimePickerDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * v7 — Ders programı **ÖNİZLEME + DÜZENLEME** diyaloğu.
 *
 * Neden var: tablo fotoğrafı/WhatsApp görselleri metin OCR'ıyla düzgün
 * okunamıyor ("Ders programı metni çıkarılamadı"). Artık akış şu:
 *   1) ML Kit OCR  →  2) Gemini Vision (yapılandırılmış JSON)  →  3) BURASI.
 *
 * Çıkarılan satırlar burada gösterilir; kullanıcı eksik/garip gün-saat
 * bilgilerini düzeltir, yeni satır ekler, satır siler ve tek tuşla programa
 * yazar. Hiçbir satır çıkarılamadıysa da açılır (boş satırla) — yani
 * uygulama asla "çıkarılamadı" deyip kullanıcıyı bırakmaz.
 *
 * Kaydetme işlemi veritabanına BURADA değil, çağıran tarafın verdiği
 * `onSaved` geri çağrısında yapılır (ScheduleActivity.commitImport).
 */
object ScheduleEditDialog {

    /** Diyalogdaki düzenlenebilir tek satır (kaydet anında ParsedClass'a döner). */
    private class Row(
        var day: Int,
        var minute: Int,
        var name: String,
        var room: String
    )

    /**
     * @param initial OCR/Vision sonucu satırlar (boşsa tek boş satırla açılır)
     * @param onSaved geçerli (adı dolu) satırlar — çağiran taraf programa yazar
     */
    fun show(
        activity: Activity,
        initial: List<TextUtil.ParsedClass>,
        onSaved: (List<TextUtil.ParsedClass>) -> Unit
    ) {
        val rows = initial
            .map { Row(it.day, it.minuteOfDay.coerceIn(0, 1439), it.name, it.room) }
            .toMutableList()
        if (rows.isEmpty()) rows.add(Row(0, 9 * 60, "", ""))

        val view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_schedule_import, null, false)
        val container = view.findViewById<LinearLayout>(R.id.rowsContainer)

        /** Tek satırı XML'den kurar; ad/saat/gün alanları Row nesnesine işlenir. */
        fun buildRow(row: Row): View {
            val v = LayoutInflater.from(activity)
                .inflate(R.layout.item_schedule_import_row, container, false)

            val nameInput = v.findViewById<EditText>(R.id.rowName)
            val roomInput = v.findViewById<EditText>(R.id.rowRoom)
            val daySpin = v.findViewById<Spinner>(R.id.rowDay)
            val timeBtn = v.findViewById<TextView>(R.id.rowTime)
            val delBtn = v.findViewById<ImageButton>(R.id.rowDelete)

            nameInput.setText(row.name)
            roomInput.setText(row.room)
            timeBtn.text = Academy.formatClock(row.minute)

            fun bindText(target: EditText, apply: (String) -> Unit) {
                target.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) = apply(s?.toString() ?: "")
                })
            }
            bindText(nameInput) { row.name = it }
            bindText(roomInput) { row.room = it }

            // v16 (GÖREV 3) — gün adları aktif dile göre (TR/EN)
            daySpin.adapter = ArrayAdapter(
                activity, android.R.layout.simple_list_item_1,
                Academy.dayNames(LangHelper.isEnglish(activity))
            )
            daySpin.setSelection(row.day.coerceIn(0, 6))
            daySpin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    row.day = pos.coerceIn(0, 6)
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

            timeBtn.setOnClickListener {
                TimePickerDialog(
                    activity,
                    { _, h, m ->
                        row.minute = h * 60 + m
                        timeBtn.text = Academy.formatClock(row.minute)
                    },
                    row.minute / 60, row.minute % 60, true
                ).show()
            }

            delBtn.setOnClickListener {
                val idx = container.indexOfChild(v)
                if (idx >= 0 && idx < rows.size) {
                    rows.removeAt(idx)
                    container.removeViewAt(idx)
                }
            }
            return v
        }

        fun addRow(row: Row) {
            rows.add(row)
            container.addView(buildRow(row))
        }

        // Mevcut satırları çiz + "＋ Ders Ekle"
        rows.forEach { container.addView(buildRow(it)) }
        view.findViewById<TextView>(R.id.btnAddRow).setOnClickListener {
            addRow(Row(0, 9 * 60, "", ""))
        }

        val dlg = AlertDialog.Builder(activity)
            .setTitle(R.string.program_onizleme_baslik)
            .setView(view)
            .setPositiveButton(R.string.kaydet, null)   // null → butonu sonra ele alıyoruz
            .setNegativeButton(R.string.iptal, null)
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val valid = rows.filter { it.name.isNotBlank() }
                if (valid.isEmpty()) {
                    Toast.makeText(activity, R.string.ders_adi_zorunlu, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener   // diyalog KAPANMAZ → veri kaybolmaz
                }
                onSaved(
                    valid.map {
                        TextUtil.ParsedClass(
                            it.day,
                            it.minute.coerceIn(0, 1439),
                            it.name.trim(),
                            it.room.trim()
                        )
                    }
                )
                dlg.dismiss()
            }
        }
        dlg.show()
        // Uzun listelerde taşma olmasın diye genişlik tam ekrana yakın
        dlg.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}
