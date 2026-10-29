package com.umay.pdfreader

import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import android.widget.LinearLayout
import com.umay.pdfreader.databinding.ActivityScheduleBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * Haftalık ders programı ekranı (ADIM 3):
 *  - Gün gün program listesi (saat — ders — derslik — hatırlatma)
 *  - Ders ekleme (ders yoksa yerinde oluşturulur)
 *  - Ders öncesi alarm: `Reminder` üzerinden AlarmManager (20/30/60 dk önce)
 *  - Günlük sınav/ödev kontrol saati + test bildirimi
 *  - v5: PDF/GÖRSEL (OCR) ile otomatik ders programı içe aktarma
 *  - v5: SCHEDULE_EXACT_ALARM (SDK 36) izin denetimi + yönlendirme
 */
class ScheduleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScheduleBinding
    private var courses: List<Course> = emptyList()

    // v16 (YENİ GÖREV 3) — Dikey liste görünümü TAMAMEN KALDIRILDI;
    // ekran artık yalnız Excel tarzı matris tablodur (gridMode yok).

    companion object {
        /** Matris tablo ölçüleri (dp). */
        private const val HOUR_W = 62
        private const val DAY_W = 104
        private const val HEAD_H = 40
        private const val ROW_H = 58

        /** Y ekseni saat aralığı (YENİ GÖREV 3 şartı: 09:00 … 19:00). */
        private const val GRID_START_HOUR = 9
        private const val GRID_END_HOUR = 19

        /**
         * v16 (GÖREV 6) — Ders süresi (dakika). Rumeli Ünv. programı 2 saatlik
         * bloklar hâlinde (10:00-12:00 vb.) → matris hücreleri İKİ satır kaplar.
         */
        private const val CLASS_MINUTES = 120

        /** Renkli ders hücreleri (tema token'ları — gece/gündüz otomatik). */
        private val COURSE_FILL = intArrayOf(
            R.color.umay_course_0, R.color.umay_course_1, R.color.umay_course_2,
            R.color.umay_course_3, R.color.umay_course_4
        )
        private val COURSE_STROKE = intArrayOf(
            R.color.umay_course_stroke_0, R.color.umay_course_stroke_1,
            R.color.umay_course_stroke_2, R.color.umay_course_stroke_3,
            R.color.umay_course_stroke_4
        )
    }

    /** Diyaloğa giren değerler (kayıt anında okunur). */
    private var pickedMinute = 9 * 60

    /** v5 — Program içe aktarma: PDF veya görsel seçici (ADIM A). */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importSchedule(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScheduleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)  // SDK 36 edge-to-edge

        Reminder.ensureChannel(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddSlot.setOnClickListener { showSlotDialog() }
        // v5: PDF / görselden otomatik ders programı içe aktarma (ADIM A)
        binding.btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/pdf", "image/*"))
        }
        binding.btnTestNotify.setOnClickListener {
            Reminder.notify(
                this, 9901,
                getString(R.string.ders_hatirlatma_baslik),
                getString(R.string.test_bildirim_metin)
            )
        }
        binding.btnDailyHour.setOnClickListener { pickDailyHour() }
        // v16 (YENİ GÖREV 3) — görünüm seçici KALDIRILDI: ekran yalnız matris tablo.
    }

    override fun onResume() {
        super.onResume()
        checkExactAlarmPermission()
        refresh()
    }

    private fun updateDailyLabel() {
        val (h, m) = Reminder.dailyTime(this)
        binding.btnDailyHour.text =
            getString(R.string.gunluk_saat, Academy.formatClock(h * 60 + m))
    }

    /**
     * v16 (YENİ GÖREV 3) — Ekran YALNIZ Excel tarzı matris tablodur
     * (X: Pazartesi…Cuma · Y: 09:00…19:00). Liste görünümü kaldırıldı.
     */
    private fun refresh() {
        updateDailyLabel()
        binding.scheduleGridScroll.visibility = View.VISIBLE
        binding.scheduleWrap.removeAllViews()
        binding.scheduleWrap.visibility = View.VISIBLE
        binding.scheduleGrid.removeAllViews()

        val slots = CourseStore.slotsOf(this)
        if (slots.isEmpty()) {
            binding.scheduleWrap.addView(
                TextView(this).apply {
                    text = getString(R.string.program_bos)
                    setTextColor(getColor(R.color.umay_text_dim))
                    textSize = 14f
                    setPadding(8, 48, 8, 8)
                }
            )
            return
        }

        buildGrid(slots)
        scrollToNow()
    }

    /**
     * v5.8 — Programa girerken saat ekseni ŞİMDİKİ saate kaydırılır.
     * 09:00–19:00 arası 11 satırlık tabloda kullanıcı sürekli yukarı
     * kaydırmak zorunda kalıyordu ("dağınık" hissinin bir parçası).
     */
    private fun scrollToNow() { 
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            cal.get(java.util.Calendar.MINUTE)
        val hour = (nowMin / 60).coerceIn(GRID_START_HOUR, GRID_END_HOUR)
        val offset = dp(HEAD_H) + (hour - GRID_START_HOUR) * dp(ROW_H) - dp(ROW_H)
        binding.scheduleGridScroll.post {
            binding.scheduleGridScroll.scrollTo(0, offset.coerceAtLeast(0))
        }
    }

    // ══════ v16 (YENİ GÖREV 3) — EXCEL MATRİS TABLO (tek görünüm) ══════

    /** X ekseni: ders günleri (Pzt…Cuma) · Y ekseni: saat dilimleri (09:00…19:00). */
    private fun buildGrid(slots: List<ScheduleSlot>) {
        val grid = binding.scheduleGrid
        grid.removeAllViews()
        val en = LangHelper.isEnglish(this)

        val headBg = getColor(R.color.umay_panel2)
        val hourBg = getColor(R.color.umay_panel2)
        val emptyBg = getColor(R.color.umay_panel)
        val line = getColor(R.color.umay_border)
        val accent = getColor(R.color.umay_accent)
        val textMain = getColor(R.color.umay_text)
        val textDim = getColor(R.color.umay_text_dim)
        // v5.8 — GÜN SÜTUNLARI EKRANA SIĞAR. Eskiden sabit 104dp × 5 gün
        // ekran genişliğini aşıyor, tablo yatay kaydırma istiyor ve ders
        // adları sağdan kesiliyordu ("dağınık" görünümün ana sebebi).
        val screenDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val dayW = (((screenDp - 32f - HOUR_W) / 5f) - 3f).toInt().coerceIn(48, 96)

        // ── Başlık satırı: sol üst köşe + HAFTA İÇİ 5 gün (YENİ GÖREV 3) ──
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(
            gridCell(
                getString(R.string.saat_kolonu), HOUR_W, HEAD_H,
                headBg, line, textDim, 11f, true, null
            )
        )
        // v5.8 — bugünün sütunu başlıkta vurgulanır (hangi gündeyim?)
        val today = ((java.util.Calendar.getInstance()
            .get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7)
        for (day in 0..4) {
            val isToday = day == today
            head.addView(
                gridCell(
                    Academy.dayShort(day, en), dayW, HEAD_H,
                    if (isToday) getColor(R.color.umay_course_0) else headBg,
                    if (isToday) accent else line,
                    if (isToday) textMain else accent, 12f, true, null
                )
            )
        }
        grid.addView(head)

        // ── Saat satırları ──
        for (hour in GRID_START_HOUR..GRID_END_HOUR) {
            val minute = hour * 60
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            // v5.8 — Saat sütunu sadeleştirildi: her satır aynı ince çizgi.
            // (Eskiden çift saatler mavi çerçeveli kutu oluyordu → ekran
            // gereksiz renkli bloklarla doluyordu.)
            row.addView(
                gridCell(
                    Academy.formatClock(minute), HOUR_W, ROW_H,
                    hourBg, line, textDim, 11f, false, null
                )
            )
            for (day in 0..4) {
                // v16 (GÖREV 6): ders BLOCK olarak bulunur (2 saatlik ders,
                // o satır ve SONRAKİ satırı kapsar → birleşik Excel hücresi gibi)
                // v5.8 — blok başlangıcı saat satırına yuvarlanır; bitiş
                // dersin GERÇEK süresinden (slot.durationMin) hesaplanır.
                val slot = slots.firstOrNull {
                    it.day == day &&
                        minute >= (it.minuteOfDay / 60) * 60 &&
                        minute < it.minuteOfDay + it.durationMin
                }
                if (slot == null) {
                    // Boş hücre → o gün/saat için hızlı ders ekleme
                    row.addView(
                        gridCell("", dayW, ROW_H, emptyBg, line, textDim, 11f, false) {
                            pickedMinute = minute
                            showSlotDialog()
                        }
                    )
                    continue
                }
                val idx = ((slot.courseName.hashCode() % COURSE_FILL.size) +
                    COURSE_FILL.size) % COURSE_FILL.size
                val fill = getColor(COURSE_FILL[idx])
                val stroke = getColor(COURSE_STROKE[idx])
                val startsHere = minute == (slot.minuteOfDay / 60) * 60
                // v5.7 — Ders bloğu TEK PARÇA görünsün: saat aralığı boyunca
                // renk kesintisiz akar; araya ızgara çizgisi/etiket tekrarı
                // girmez (kullanıcı: "o zaman aralığı o renkte boyalı olmalı").
                val endsHere = minute + 60 >= slot.minuteOfDay + slot.durationMin
                val range = Academy.formatClockRange(slot.minuteOfDay, slot.durationMin)
                val label = when {
                    startsHere && slot.room.isBlank() -> "${slot.courseName}\n$range"
                    startsHere -> "${slot.courseName}\n${slot.room} · $range"
                    else -> ""
                }
                row.addView(
                    slotCell(
                        label, fill, stroke, textMain, startsHere, endsHere, dayW
                    ) {
                        // Tık → DÜZENLE (GÖREV 5); uzun bas → sil yolu ayrı
                        editSlot(slot)
                    }
                )
            }
            grid.addView(row)
        }
    }

    /**
     * v5.7 — DERS BLOĞU HÜCRESİ.
     * Aynı dersin saat dilimleri arasında boşluk/çizgi olmaz; yalnız bloğun
     * ilk hücresi üstten, son hücresi alttan yuvarlatılır → 09:00–11:00
     * aralığı tek renkli şerit olarak görünür.
     */
    private fun slotCell(
        label: String,
        fill: Int,
        stroke: Int,
        textColor: Int,
        top: Boolean,
        bottom: Boolean,
        wDp: Int,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        gravity = android.view.Gravity.CENTER
        setTextColor(textColor)
        textSize = if (top) 10.5f else 10f
        maxLines = 4
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(4), dp(2), dp(4), dp(2))
        if (top) setTypeface(typeface, android.graphics.Typeface.BOLD)
        val r = dp(7).toFloat()
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadii = floatArrayOf(
                if (top) r else 0f, if (top) r else 0f,
                if (top) r else 0f, if (top) r else 0f,
                if (bottom) r else 0f, if (bottom) r else 0f,
                if (bottom) r else 0f, if (bottom) r else 0f
            )
            // Orta hücrelerde kenarlık rengi = zemin → blok kesintisiz görünür
            setStroke(dp(1), if (top || bottom) stroke else fill)
        }
        layoutParams = LinearLayout.LayoutParams(dp(wDp), dp(ROW_H)).apply {
            setMargins(dp(1), if (top) dp(1) else 0, dp(1), if (bottom) dp(1) else 0)
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    /**
     * Tek ızgara hücresi: tema renkleri `ContextCompat#getColor` ile çözülür →
     * Gece/Gündüz paletlerinde otomatik doğru (sabit hex YOK).
     */
    private fun gridCell(
        label: String, wDp: Int, hDp: Int, fill: Int, stroke: Int,
        textColor: Int, textSizeSp: Float, bold: Boolean, onClick: (() -> Unit)?
    ): TextView = TextView(this).apply {
        text = label
        gravity = android.view.Gravity.CENTER
        setTextColor(textColor)
        textSize = textSizeSp
        maxLines = 3
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(3), dp(2), dp(3), dp(2))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(6).toFloat()
            setStroke(dp(1), stroke)
        }
        layoutParams = LinearLayout.LayoutParams(dp(wDp), dp(hDp)).apply {
            setMargins(dp(1), dp(1), dp(1), dp(1))
        }
        if (onClick != null) {
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** dp → px. */
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // v16 (YENİ GÖREV 3) — buildList() (dikey liste görünümü) KALDIRILDI:
    // program yalnız Excel matris tablo olarak gösteriliyor.

    /**
     * v16 (GÖREV 5) — Matris hücresine dokunma = **DÜZENLE** diyaloğu.
     * (Eski davranışta yalnız silme vardı; saat/gün/derslik düzeltilemiyordu.)
     */
    private fun editSlot(slot: ScheduleSlot) {
        val en = LangHelper.isEnglish(this)
        val course = CourseStore.course(this, slot.courseId)
        val info = buildString {
            append(slot.courseName)
            append("\n")
            append(Academy.dayName(slot.day, en))
            append(" · ")
            append(
                Academy.formatClockRange(slot.minuteOfDay, CLASS_MINUTES)
            )
            if (slot.room.isNotBlank()) {
                append("\n")
                append(slot.room)
            }
            if (!course?.instructor.isNullOrBlank()) {
                append(" · ")
                append(course?.instructor)
            }
        }
        val items = arrayOf(
            getString(R.string.slot_duzenle),
            getString(R.string.sil),
            getString(R.string.kapat)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.program_baslik)
            .setMessage(info)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showSlotEditDialog(slot)
                    1 -> confirmDeleteSlot(slot)
                }
            }
            .show()
    }

    /**
     * v16 (GÖREV 5) — Mevcut satırı düzenleme diyaloğu (aynı layout, DÜZENLE
     * modu): gün / saat / derslik / hatırlatma anında güncellenir + alarm yeniden
     * kurulur (`updateSlot` + `Reminder.reschedule`).
     */
    private fun showSlotEditDialog(slot: ScheduleSlot) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_schedule_edit, null)
        val spinCourse = v.findViewById<Spinner>(R.id.spinCourse)
        val spinDay = v.findViewById<Spinner>(R.id.spinDay)
        val spinRemind = v.findViewById<Spinner>(R.id.spinRemind)
        val spinDuration = v.findViewById<Spinner>(R.id.spinDuration)
        val timeBtn = v.findViewById<TextView>(R.id.btnPickTime)
        val roomInput = v.findViewById<EditText>(R.id.inputRoom)

        bindDurationSpinner(spinDuration, slot.durationMin)
        courses = CourseStore.courses(this)
        spinCourse.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, courses.map { it.name }
        )
        spinCourse.setSelection(
            courses.indexOfFirst { it.id == slot.courseId }.coerceAtLeast(0)
        )
        spinDay.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, Academy.dayNames(LangHelper.isEnglish(this))
        )
        spinDay.setSelection(slot.day.coerceIn(0, 6))
        spinRemind.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            listOf(
                getString(R.string.remind_20), getString(R.string.remind_30),
                getString(R.string.remind_60), getString(R.string.remind_off)
            )
        )
        spinRemind.setSelection(
            when (slot.remindMin) { 30 -> 1; 60 -> 2; 0 -> 3; else -> 0 }
        )

        pickedMinute = slot.minuteOfDay
        timeBtn.text = Academy.formatClock(pickedMinute)
        timeBtn.setOnClickListener {
            TimePickerDialog(
                this,
                { _, h, m ->
                    pickedMinute = h * 60 + m
                    timeBtn.text = Academy.formatClock(pickedMinute)
                },
                pickedMinute / 60, pickedMinute % 60, true
            ).show()
        }
        roomInput.setText(slot.room)

        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.slot_duzenle)
            .setView(v)
            .create()
        v.findViewById<TextView>(R.id.btnSlotSave).setOnClickListener {
            val day = spinDay.selectedItemPosition.coerceIn(0, 6)
            val remind = intArrayOf(20, 30, 60, 0)[spinRemind.selectedItemPosition.coerceIn(0, 3)]
            val room = roomInput.text.toString().trim()
            val idx = spinCourse.selectedItemPosition.coerceIn(0, courses.size - 1)
            val newCourseId = courses.getOrNull(idx)?.id ?: slot.courseId
            dlg.dismiss()
            // Yerine UPDATE: ders adı / gün / saat / derslik / hatırlatma
            val duration = durationOf(spinDuration)
            CourseStore.updateSlot(this, slot.id, day, pickedMinute, room, remind, duration)
            if (newCourseId != slot.courseId) {
                // Ders değiştirildiyse satırı yeniden kur (eski alarmı kapat)
                Reminder.cancelSlot(this, slot.id)
                CourseStore.deleteSlot(this, slot.id)
                saveSlot(newCourseId, day, room, remind, duration)
            } else {
                // Alarm: yeni saat/güne göre yeniden kur
                Reminder.cancelSlot(this, slot.id)
                CourseStore.slot(this, slot.id)?.let { Reminder.scheduleClassSlot(this, it) }
                Toast.makeText(this, R.string.slot_guncellendi, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
        dlg.show()
    }

    private fun confirmDeleteSlot(slot: ScheduleSlot) {
        AlertDialog.Builder(this)
            .setTitle(R.string.program_baslik)
            .setMessage(
                "${Academy.dayName(slot.day)} ${Academy.formatClock(slot.minuteOfDay)} — " +
                    slot.courseName
            )
            .setPositiveButton(R.string.sil) { _, _ ->
                CourseStore.deleteSlot(this, slot.id)
                Reminder.cancelSlot(this, slot.id)
                Toast.makeText(this, R.string.slot_silindi, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    // ── Programa ders ekleme ────────────────────────────────

    private fun showSlotDialog() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_schedule_edit, null)
        val spinCourse = v.findViewById<Spinner>(R.id.spinCourse)
        val spinDay = v.findViewById<Spinner>(R.id.spinDay)
        val spinRemind = v.findViewById<Spinner>(R.id.spinRemind)
        val spinDuration = v.findViewById<Spinner>(R.id.spinDuration)
        val timeBtn = v.findViewById<TextView>(R.id.btnPickTime)
        val roomInput = v.findViewById<EditText>(R.id.inputRoom)

        bindDurationSpinner(spinDuration, 120)
        courses = CourseStore.courses(this)
        val labels = courses.map { it.name }.toMutableList()
        labels.add(getString(R.string.yeni_ders_secenege))
        spinCourse.adapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        spinDay.adapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_1, Academy.DAY_NAMES)
        spinRemind.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            listOf(
                getString(R.string.remind_20),
                getString(R.string.remind_30),
                getString(R.string.remind_60),
                getString(R.string.remind_off)
            )
        )
        spinRemind.setSelection(0)

        pickedMinute = 9 * 60
        timeBtn.text = Academy.formatClock(pickedMinute)
        timeBtn.setOnClickListener {
            TimePickerDialog(
                this,
                { _, h, m ->
                    pickedMinute = h * 60 + m
                    timeBtn.text = Academy.formatClock(pickedMinute)
                },
                pickedMinute / 60, pickedMinute % 60, true
            ).show()
        }

        val dlg = AlertDialog.Builder(this).setView(v).create()
        v.findViewById<TextView>(R.id.btnSlotSave).setOnClickListener {
            val day = spinDay.selectedItemPosition
            // v5: varsayılan/ilk seçenek 20 dakika önce (melodili alarm)
            val remind = intArrayOf(20, 30, 60, 0)[spinRemind.selectedItemPosition.coerceIn(0, 3)]
            val room = roomInput.text.toString().trim()
            val idx = spinCourse.selectedItemPosition

            val duration = durationOf(spinDuration)
            if (courses.isEmpty() || idx >= courses.size) {
                // "＋ Yeni ders…" seçildi → önce dersi oluştur, sonra programı kaydet
                dlg.dismiss()
                askNewCourse { course ->
                    if (course != null) saveSlot(course.id, day, room, remind, duration)
                }
            } else {
                val course = courses[idx]
                dlg.dismiss()
                saveSlot(course.id, day, room, remind, duration)
            }
        }
        dlg.show()
    }

    private fun askNewCourse(onDone: (Course?) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.ders_adi)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.yeni_ders_ekle)
            .setView(input)
            .setPositiveButton(R.string.kaydet) { _, _ ->
                val name = input.text.toString().trim()
                val c = if (name.isEmpty()) null else CourseStore.addCourse(this, name)
                if (c == null) {
                    Toast.makeText(this, R.string.ders_bos_olamaz, Toast.LENGTH_SHORT).show()
                }
                onDone(c)
            }
            .setNegativeButton(R.string.iptal) { _, _ -> onDone(null) }
            .show()
    }

    private fun saveSlot(courseId: String, day: Int, room: String, remind: Int, duration: Int = 120) {
        val id = CourseStore.addSlot(this, courseId, day, pickedMinute, room, remind, duration)
        if (id == null) {
            Toast.makeText(this, "Programa eklenemedi", Toast.LENGTH_SHORT).show()
            return
        }
        // Alarmı hemen kur + tüm programu tazele
        CourseStore.slot(this, id)?.let { Reminder.scheduleClassSlot(this, it) }
        Toast.makeText(this, R.string.slot_kaydedildi, Toast.LENGTH_SHORT).show()
        refresh()
    }

    /** v5.8 — Süre seçenekleri: 30…180 dk, 120 varsayılan. */
    private val durationOptions = intArrayOf(30, 60, 90, 120, 150, 180)

    private fun bindDurationSpinner(spin: Spinner, current: Int) {
        spin.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            durationOptions.map { getString(R.string.sure_dk, it) }
        )
        spin.setSelection(durationOptions.indexOfFirst { it == current }.coerceAtLeast(0))
    }

    private fun durationOf(spin: Spinner): Int =
        durationOptions.getOrElse(spin.selectedItemPosition) { 120 }

    // ── Günlük hatırlatma saati ─────────────────────────────

    private fun pickDailyHour() {
        val (h, m) = Reminder.dailyTime(this)
        TimePickerDialog(
            this,
            { _, nh, nm ->
                Reminder.setDailyTime(this, nh, nm)
                updateDailyLabel()
            },
            h, m, true
        ).show()
    }

    // ── v5: SCHEDULE_EXACT_ALARM denetimi (SDK 36) ─────────────

    /**
     * Android 12+ : tam alarm izni kullanıcı tarafından iptal edilebilir.
     * Kapalıysa turuncu uyarı satırı görünür; tıklayınca sistem ayarına
     * yönlendirilir (aksi halde hatırlatmalar gecikir — bilinçsiz alarma düşer).
     */
    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(AlarmManager::class.java)
            if (am?.canScheduleExactAlarms() == false) {
                binding.exactAlarmWarn.visibility = View.VISIBLE
                binding.exactAlarmWarn.setOnClickListener {
                    // Doğrudan bu uygulamanın "Alarmlar ve hatırlatıcılar" sayfası
                    val pkg = Uri.parse("package:$packageName")
                    try {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(pkg)
                        )
                    } catch (_: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                        } catch (_: Exception) {
                        }
                    }
                }
                return
            }
        }
        binding.exactAlarmWarn.visibility = View.GONE
    }

    // ── v5/v7: PDF / Görselden ders programı içe aktarma (ADIM A) ────

    /**
     * v7 — İÇE AKTARMA AKIŞI (kritik onarım):
     *   1) PDF metni (PDFBox) veya görsel OCR (ML Kit) → TextUtil.parseSchedule
     *   2) Tablo fotoğrafı OCR'da genelde BOŞ çıkar ("metni çıkarılamadı") →
     *      görselin/pdfsayfasının ilk görüntüsü **Gemini Vision**'a gider,
     *      yapılandırılmış JSON döner ve satırlara çevrilir.
     *   3) Sonuç NE OLURSA OLSUN `ScheduleEditDialog` açılır: çıkarılan
     *      satırlar önizlenir, eksik gün/saat elle tamamlanır ve kaydedilir.
     * Hiçbir yol uygulamayı çökertmez; en kötü durumda BOŞ diyalog açılır.
     */
    private fun importSchedule(uri: Uri) {
        Toast.makeText(this, R.string.program_okunuyor, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val (rows, source) = withContext(Dispatchers.IO) { analyzeSchedule(uri) }
                openImportPreview(rows, source)
            } catch (t: Throwable) {
                // lifecycleScope'ta yakalanmayan istisna tüm uygulamayı çökertir
                BookStore.logError(this@ScheduleActivity, "importSchedule", t)
                openImportPreview(emptyList(), "error")
            }
        }
    }

    /** Önizleme + manuel düzenleme ekranı (hiç satır yoksa da açılır). */
    private fun openImportPreview(rows: List<TextUtil.ParsedClass>, source: String) {
        try {
            when {
                rows.isNotEmpty() && source == "ai" -> Toast.makeText(
                    this, getString(R.string.program_ai_analiz, rows.size), Toast.LENGTH_LONG
                ).show()

                rows.isEmpty() -> Toast.makeText(
                    this, R.string.program_bos_elle, Toast.LENGTH_LONG
                ).show()
            }
        } catch (_: Throwable) {
        }
        ScheduleEditDialog.show(this, rows) { saved -> commitImport(saved) }
    }

    /** IO thread: çıkarma yollarını dener, asla fırlatmaz (boş → manuel ekran). */
    private fun analyzeSchedule(uri: Uri): Pair<List<TextUtil.ParsedClass>, String> {
        return try {
            val isImage = (contentResolver.getType(uri) ?: "").startsWith("image/")

            // 1) Metin yolu (PDFBox / ML Kit OCR)
            val ocr = TextUtil.parseSchedule(extractScheduleLines(uri))
            if (ocr.isNotEmpty()) return ocr to "ocr"

            // 2) Metin yoksa → görseli doğrudan Gemini Vision'a gönder
            val payload = (
                if (isImage) imageVisionPayload(uri) else pdfPageVisionPayload(uri)
                ) ?: return emptyList<TextUtil.ParsedClass>() to "empty"
            val ai = visionRows(payload.first, payload.second)
            if (ai.isNotEmpty()) ai to "ai" else emptyList<TextUtil.ParsedClass>() to "empty"
        } catch (t: Throwable) {
            BookStore.logError(this, "analyzeSchedule", t)
            emptyList<TextUtil.ParsedClass>() to "error"
        }
    }

    /**
     * v7 — Gemini Vision analizi. Anahtar yoksa / AI hakkı yoksa / hata olursa
     * sessizce boş liste döner ve kullanıcı manuel düzenleme ekranına düşer.
     */
    private fun visionRows(bytes: ByteArray, mime: String): List<TextUtil.ParsedClass> {
        return try {
            AppPrefs.warmApiCache(this)
            if (AppPrefs.lastApiKey.isNullOrBlank()) return emptyList()
            if (AppPrefs.aiQuotaLeft(this) <= 0) return emptyList()  // hak yok → elle
            runOnUiThread {
                Toast.makeText(this, R.string.program_ai_okunuyor, Toast.LENGTH_SHORT).show()
            }
            val json = ApiClient.scheduleFromImage(bytes, mime)
            // v10 (M2 — ESNEK OKUMA): önce sert JSON; boş dönerse model düz
            // metin/serbest satır döndürmüş olabilir → regex geriye düşme.
            var rows = parseVisionJson(json)
            if (rows.isEmpty()) rows = parseLooseScheduleText(json)
            // Yalnızca BAŞARILI analizde AI hakkı düşülür
            if (rows.isNotEmpty()) AppPrefs.aiQuotaConsume(this)
            rows
        } catch (t: Throwable) {
            BookStore.logError(this, "visionSchedule", t)
            emptyList()
        }
    }

    /** Görseli 1600px'e indirger (büyük fotoğraf payload'u şişirmesin). */
    private fun imageVisionPayload(uri: Uri): Pair<ByteArray, String>? {
        return try {
            val mime = contentResolver.getType(uri) ?: "image/jpeg"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null

            // v8 (KATEGORİ 1 — resim ölçekleme): önce BOYUT oku, örneklemeli
            // çöz. Eski kod tam çözünürlükte decode ediyordu → büyük telefon
            // fotoğraflarında (48 MP) OutOfMemoryError ile ÇÖKÜYORDU.
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return bytes to mime

            var sample = 1
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxSide / sample > 1600) sample *= 2

            var decoded: android.graphics.Bitmap? = null
            for (attempt in 0 until 3) {   // OOM'da örneklemeyi artırıp tekrar dene
                try {
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sample
                    }
                    decoded = android.graphics.BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size, opts
                    )
                } catch (oom: OutOfMemoryError) {
                    decoded = null
                }
                if (decoded != null) break
                sample *= 2
            }
            val bmp = decoded ?: return bytes to mime   // çözülemediyse ham gönder
            jpegOf(bmp, 1600)
        } catch (t: Throwable) {
            BookStore.logError(this, "imageVisionPayload", t)
            null
        }
    }

    /** PDF'in ilk sayfasını render edip JPEG'e çevirir (tablo metni OCR'da
     *  kaybolduğu için tabloyu görsel olarak AI'a göndeririz). */
    private fun pdfPageVisionPayload(uri: Uri): Pair<ByteArray, String>? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val tmp = File(cacheDir, "schedule_vision.pdf").apply { writeBytes(bytes) }
            try {
                UmayApp.initPdfBox(this)
                pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
                val rend = PdfRenderer(pfd!!)
                renderer = rend
                if (rend.pageCount == 0) return null
                val page = rend.openPage(0)
                try {
                    val targetW = 1400
                    val scale = targetW.toFloat() / page.width.coerceAtLeast(1)
                    val bmp = android.graphics.Bitmap.createBitmap(
                        targetW,
                        (page.height * scale).toInt().coerceIn(1, 2400),
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    bmp.setHasAlpha(false)   // şeffaflık → koyu/bozuk render hataları
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    jpegOf(bmp, 1600)
                } finally {
                    try { page.close() } catch (_: Throwable) {}
                }
            } finally {
                tmp.delete()
            }
        } catch (t: Throwable) {
            BookStore.logError(this, "pdfPageVisionPayload", t)
            null
        } finally {
            try { renderer?.close() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
        }
    }

    /** Bitmap → (en fazla maxW px, JPEG 88). */
    private fun jpegOf(src: android.graphics.Bitmap, maxW: Int): Pair<ByteArray, String> {
        var bmp = src
        try {
            if (bmp.width > maxW) {
                val s = maxW.toFloat() / bmp.width
                val scaled = android.graphics.Bitmap.createScaledBitmap(
                    bmp, maxW, (bmp.height * s).toInt().coerceAtLeast(1), true
                )
                if (scaled != bmp) bmp.recycle()
                bmp = scaled
            }
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
            return out.toByteArray() to "image/jpeg"
        } finally {
            bmp.recycle()
        }
    }

    // ── v7: Gemini Vision JSON → ParsedClass ─────────────────────

    /** Bir anahtardan ilk boş olmayan string değeri alır. */
    private fun firstString(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.opt(k) ?: continue
            val s = v.toString().trim()
            if (s.isNotEmpty() && s != "null") return s
        }
        return null
    }

    /** Türkçe/İngilizce gün adı → 0..6 (Pazartesi=0). */
    private fun parseDay(raw: String): Int? {
        val n = raw.trim().toIntOrNull()
        if (n != null) {
            // ISO: 1=Pazartesi … 7=Pazar (0 ve 7 tek istisna)
            return when {
                n == 0 -> 0
                n in 1..7 -> n - 1
                else -> null
            }
        }
        val t = buildString(raw.length) {
            for (ch in raw) {
                when (ch) {
                    'İ', 'I', 'ı' -> append('i')
                    'Ğ', 'ğ' -> append('g')
                    'Ş', 'ş' -> append('s')
                    'Ç', 'ç' -> append('c')
                    'Ö', 'ö' -> append('o')
                    'Ü', 'ü' -> append('u')
                    else -> append(Character.toLowerCase(ch))
                }
            }
        }
        val table = listOf(
            listOf("pazartesi", "pzt", "monday", "mon") to 0,
            listOf("salı", "sali", "sal", "tuesday", "tue") to 1,
            listOf("çarşamba", "carsamba", "çar", "car", "wednesday", "wed") to 2,
            listOf("perşembe", "persembe", "per", "thursday", "thu") to 3,
            listOf("cuma", "cum", "friday", "fri") to 4,
            listOf("cumartesi", "cmt", "sat", "saturday") to 5,
            listOf("pazar", "pazr", "sunday", "sun") to 6
        )
        // Önce tam eşleşme ("cuma" vs "cumartesi" karışmasın), sonra içerme
        for ((keys, idx) in table) if (keys.any { t == it }) return idx
        for ((keys, idx) in table) if (keys.any { t.contains(it) }) return idx
        return null
    }

    /** "09:00" / "9.00" / "09:00 - 11:00" / "9" → dakika (0..1439). */
    private fun parseMinute(raw: String): Int? {
        val s = raw.trim()
        Regex("""(\d{1,2})[:.](\d{2})""").find(s)?.let { m ->
            val h = m.groupValues[1].toIntOrNull()
            val min = m.groupValues[2].toIntOrNull()
            if (h != null && min != null && h in 0..23 && min in 0..59) return h * 60 + min
        }
        Regex("""\b(\d{1,2})\s*(?:h|saat)?\b""").find(s)?.let { m ->
            val h = m.groupValues[1].toIntOrNull()
            if (h != null && h in 0..23) return h * 60
        }
        return null
    }

    /** Modelin döndürdüğü JSON metnini satırlara çevirir (asla fırlatmaz). */
    private fun parseVisionJson(raw: String): List<TextUtil.ParsedClass> {
        return try {
            var text = raw.trim()
            // ```json ... ``` blokları ve başına/sonuna gelen anlamsız metin
            text = text.replace(Regex("(?is)```(?:json)?"), "").trim()
            val start = text.indexOfFirst { it == '[' || it == '{' }
            if (start < 0) return emptyList()
            text = text.substring(start)

            val arr: JSONArray = when (val root = JSONTokener(text).nextValue()) {
                is JSONArray -> root
                is JSONObject -> {
                    val preferred = listOf(
                        "classes", "dersler", "schedule", "items", "rows",
                        "lessons", "data", "entries"
                    )
                    var found: JSONArray? = null
                    for (k in preferred) {
                        val a = root.optJSONArray(k)
                        if (a != null) {
                            found = a
                            break
                        }
                    }
                    if (found == null) {
                        for (k in root.keys()) {
                            val a = root.optJSONArray(k)
                            if (a != null) {
                                found = a
                                break
                            }
                        }
                    }
                    found ?: return emptyList()
                }

                else -> return emptyList()
            }

            val out = ArrayList<TextUtil.ParsedClass>(arr.length())
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = firstString(
                    o, "name", "course", "ders", "dersAdi", "ders_adi",
                    "courseName", "title"
                )?.trim() ?: continue
                if (name.length < 2) continue
                val day = firstString(o, "day", "gun", "weekday", "gün")
                    ?.let { parseDay(it) } ?: continue
                val startRaw = firstString(
                    o, "start", "baslangic", "baslangicSaati", "baslangic_saat",
                    "startTime", "time", "saat"
                ) ?: continue
                val minute = parseMinute(startRaw) ?: continue
                val room = firstString(o, "room", "derslik", "classroom", "place", "location") ?: ""
                val key = "$day|$minute|${name.lowercase(java.util.Locale.US)}"
                if (!seen.add(key)) continue
                out.add(TextUtil.ParsedClass(day, minute, name, room.trim()))
            }
            out
        } catch (t: Throwable) {
            BookStore.logError(this, "parseVisionJson", t)
            emptyList()
        }
    }

    /**
     * v10 (M2 — ESNEK GERİYE DÜŞME): Model geçerli JSON yerine düz metin
     * döndürürse (markdown bloğu, açıklama satırları, "Pzt 09:00 Matematik"
     * gibi serbest satırlar) dersleri YİNE de çıkarırız. İki katman:
     *  1) TextUtil.parseSchedule — testli OCR satı ayrıştırıcısı
     *  2) Serbest regex — her satırda gün + saat aranır, kalan metin ders adı olur
     * Hiçbir şekilde fırlatmaz; boş liste dönerse çağıran manuel ekrana düşer.
     */
    private fun parseLooseScheduleText(raw: String): List<TextUtil.ParsedClass> {
        return try {
            val cleaned = raw.replace(Regex("(?is)```(?:json)?"), "").trim()
            if (cleaned.isEmpty()) return emptyList()

            val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }

            // 1. katman: OCR satı ayrıştırıcısı
            val viaParser = try {
                TextUtil.parseSchedule(lines)
            } catch (t: Throwable) {
                BookStore.logError(this, "looseSchedule.parser", t)
                emptyList()
            }
            if (viaParser.isNotEmpty()) return dedupRows(viaParser)

            // 2. katman: serbest satır regex (JSON metinleri dahil her şeyde çalışır)
            // NOT: Android Pattern'da \b Türkçe karakterlerle ÇALIŞMAZ (bkz.
            // TextUtil.DAY_PATTERN) → sınır açık karakter sınıflı lookaround ile
            // kuruldu; "salı" / "pazar" gibi kelimeler de böylece eşleşiyor.
            val dayRegex = Regex(
                "(?i)(?<![A-Za-z0-9_çğıöşüÇĞİÖŞÜ])" +
                    "(pazartesi|pzt|salı|sali|sal|çarşamba|carsamba|çar|car|" +
                    "perşembe|persembe|per|cuma|cumartesi|cmt|cum|pazar|pazr)" +
                    "(?![A-Za-z0-9_çğıöşüÇĞİÖŞÜ])"
            )
            val timeRegex = Regex("(\\d{1,2})[:.](\\d{2})")
            val out = ArrayList<TextUtil.ParsedClass>()
            for (line in lines) {
                val day = dayRegex.find(line)?.let { parseDay(it.value) } ?: continue
                val minute = timeRegex.find(line)?.let { parseMinute(it.value) } ?: continue
                var rest = dayRegex.replace(line, " ")
                rest = timeRegex.replace(rest, " ")
                // JSON anahtarları / ayraçlar / saat aralığı artıkları temizlenir
                rest = rest.replace(
                    Regex(
                        "(?i)\\\"(day|start|end|name|room|classes|ders|gun|saat|derslik)\\\"\\s*:"
                    ), " "
                )
                rest = rest.replace(Regex("[|;·•,{}\\[\\]\\-–—]+"), " ")
                rest = rest.replace(Regex("\\s{2,}"), " ").trim()
                    .trim('"', '\'')
                if (rest.length < 2) continue
                out.add(TextUtil.ParsedClass(day, minute, rest, ""))
            }
            dedupRows(out)
        } catch (t: Throwable) {
            BookStore.logError(this, "parseLooseScheduleText", t)
            emptyList()
        }
    }

    /** Aynı (gün, saat, ad) üçlüsünden tek satır bırakır. */
    private fun dedupRows(rows: List<TextUtil.ParsedClass>): List<TextUtil.ParsedClass> {
        val out = ArrayList<TextUtil.ParsedClass>(rows.size)
        for (r in rows) {
            if (out.any {
                    it.day == r.day && it.minuteOfDay == r.minuteOfDay &&
                        it.name.equals(r.name, true)
                }
            ) continue
            out.add(r)
        }
        return out
    }

    /**
     * Dosyadan metin satırları çıkarır (IO thread):
     *  - PDF → PDFBox `PDFTextStripper` (kitap çıkarmayla aynı motor)
     *  - Görsel (JPG/PNG) → ML Kit offline OCR (satır satır)
     */
    private fun extractScheduleLines(uri: Uri): List<String> {
        return try {
            extractScheduleLinesInner(uri)
        } catch (t: Throwable) {
            BookStore.logError(this, "importSchedule", t)
            emptyList()
        }
    }

    private fun extractScheduleLinesInner(uri: Uri): List<String> {
        return try {
            val type = contentResolver.getType(uri) ?: ""
        if (type.startsWith("image/")) {
            val input = InputImage.fromFilePath(this, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val text = Tasks.await(recognizer.process(input))
                text.textBlocks.flatMap { block -> block.lines.map { it.text } }
            } finally {
                recognizer.close()
            }
        } else {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return emptyList()
            val tmp = File(cacheDir, "schedule_import.pdf").apply { writeBytes(bytes) }
            try {
                UmayApp.initPdfBox(this)
                PDDocument.load(tmp).use { doc ->
                    val stripper = PDFTextStripper().apply { sortByPosition = true }
                    (stripper.getText(doc) ?: "").lines()
                }
            } finally {
                tmp.delete()
            }
        }
        } catch (t: Throwable) {
            BookStore.logError(this, "importSchedule", t)
            emptyList()
        }
    }

    /** Bulunan satırları onaydan sonra program + alarmlara yazar. */
    private fun commitImport(rows: List<TextUtil.ParsedClass>) {
        var added = 0
        val existing = CourseStore.courses(this).toMutableList()
        rows.forEach { row ->
            var course = existing.firstOrNull { it.name.equals(row.name, true) }
            if (course == null) {
                course = CourseStore.addCourse(this, row.name) ?: return@forEach
                existing.add(course)
            }
            val id = CourseStore.addSlot(
                this, course.id, row.day, row.minuteOfDay, row.room, Academy.REMIND_DEFAULT
            )
            if (id != null) {
                added++
                CourseStore.slot(this, id)?.let { Reminder.scheduleClassSlot(this, it) }
            }
        }
        Toast.makeText(this, getString(R.string.program_ice_aktarildi, added), Toast.LENGTH_LONG).show()
        refresh()
    }
}
