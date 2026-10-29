package com.umay.pdfreader

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.umay.pdfreader.databinding.ActivityExamsBinding
import java.util.Calendar

/**
 * v16 (GÖREV 6) — İNTERAKTİF SINAV TAKVİMİ + NOT / YÜZDE / AGNO EKRANI.
 *
 *  • ÜSTTE: telefon takvimi gibi ileri/geri kaydırılabilen `MonthCalendarView`
 *    (v14'te yazılmış ama hiçbir ekrana BAĞLANMAMIŞTI — ilk kez burada kullanılıyor).
 *    Sınavı olan günler turuncu nokta ile işaretlenir.
 *  • Güne dokun → o günün sınav/etkinlikleri listelenir; "Seçili güne ekle" ile
 *    ders + ad + tarih + saat girilerek sınav eklenir (`CourseStore.addExam`).
 *  • ALTTA: her ders için Vize/Quiz/Final/Proje **puanı + yüzdesi** ve **AKTS**
 *    girilir (`GradeStore`); ders ortalaması + harf notu ve genel **AGNO**
 *    canlı hesaplanır (Σ puan×yüzde / Σ yüzde · AGNO = Σ harfPuanı×AKTS / Σ AKTS).
 *
 * Tüm DB erişimi CourseStore/GradeStore üzerinden (try/catch korumalı) —
 * akademik veri bozuksa ekran çöker değil, boş durum gösterir.
 */
class ExamsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExamsBinding

    /** Takvimde seçili gün (00:00 millis); varsayılan: bugün. */
    private var selectedDayMillis: Long = 0L

    /** Sınav ekleme diyaloğunun geçici tarih/saat değerleri. */
    private var draftAt: Calendar = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExamsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)

        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        selectedDayMillis = today.timeInMillis

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddExam.setOnClickListener { showAddExamDialog() }

        // Takvim: gün seçimi + ay değişimi
        binding.calView.onDayClick = { millis, _ ->
            selectedDayMillis = millis
            refreshDay()
        }
        binding.calView.onMonthChange = { refreshCalendar() }
    }

    override fun onResume() {
        super.onResume()
        try {
            refreshAll()
        } catch (t: Throwable) {
            BookStore.logError(this, "ExamsActivity.onResume", t)
        }
    }

    private val en: Boolean get() = LangHelper.isEnglish(this)

    private fun refreshAll() {
        refreshCalendar()
        refreshDay()
        refreshGrades()
        refreshAgno()
    }

    // ── TAKVİM ──────────────────────────────────────────────

    private fun refreshCalendar() {
        val exams = CourseStore.allExams(this)
        binding.calView.setExams(exams.map { it.exam.atMillis })

        // En yakın sınav geri sayımı (TR/EN)
        val now = System.currentTimeMillis()
        val next = exams.filter { it.exam.atMillis >= now }.minByOrNull { it.exam.atMillis }
        binding.nextExamText.text = if (next == null) {
            getString(R.string.sinavlar_bos)
        } else {
            val label = if (next.courseName.isBlank()) next.exam.title
            else "${next.courseName} – ${next.exam.title}"
            getString(
                R.string.en_yakin_sinav,
                Academy.countdownPhrase(Academy.daysLeft(next.exam.atMillis, now), en) +
                    " · " + Academy.formatDateTime(next.exam.atMillis, en) + " · " + label
            )
        }
    }

    // ── SEÇİLİ GÜNÜN SINAVLARI ──────────────────────────────

    private fun refreshDay() {
        binding.dayLabel.text = Academy.formatDate(selectedDayMillis, en)
        // Takvimde seçili gün halkası (v16: MonthCalendarView.selectedMillis)
        binding.calView.selectedMillis = selectedDayMillis

        val list = binding.examList
        list.removeAllViews()

        val dayExams = CourseStore.allExams(this).filter {
            sameDay(it.exam.atMillis, selectedDayMillis)
        }
        if (dayExams.isEmpty()) {
            list.addView(
                TextView(this).apply {
                    text = getString(R.string.sinav_yok_gun)
                    setTextColor(getColor(R.color.umay_text_dim))
                    textSize = 13f
                    setPadding(4, 18, 4, 6)
                }
            )
            return
        }

        dayExams.forEach { e ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_academy_row, list, false)
            val who = if (e.courseName.isBlank()) "" else " (${e.courseName})"
            row.findViewById<TextView>(R.id.rowTitle).text =
                "${Academy.formatClock(Academy.minutesOfDayOf(e.exam.atMillis))} — " +
                    "${e.exam.title}$who"
            row.findViewById<TextView>(R.id.rowSub).text =
                if (e.exam.note.isBlank()) Academy.countdownPhrase(
                    Academy.daysLeft(e.exam.atMillis, System.currentTimeMillis()), en
                ) else e.exam.note
            row.findViewById<TextView>(R.id.rowAction).apply {
                contentDescription = getString(R.string.slot_duzenle)
                setOnClickListener { editExam(e) }
            }
            // Satıra dokunmak da DÜZENLE (GÖREV 5 — silme ayrı adımda)
            row.findViewById<TextView>(R.id.rowTitle).setOnClickListener { editExam(e) }
            list.addView(row)
        }
    }

    /**
     * v16 (GÖREV 5) — Sınav kartı DÜZENLEME diyaloğu: başlık, tarih, saat,
     * not + silme eylemi. Kayıt `CourseStore.updateExam` ile yapılır.
     */
    private fun editExam(e: CourseStore.ExamWithCourse) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        val cal = Calendar.getInstance().apply { timeInMillis = e.exam.atMillis }

        box.addView(label(getString(R.string.sinav_basligi)))
        val titleInput = EditText(this).apply { setText(e.exam.title) }
        box.addView(titleInput)

        val dateBtn = TextView(this).apply {
            setTextColor(getColor(R.color.umay_accent))
            setPadding(0, dp(12), 0, dp(4))
            isClickable = true
            fun paint() {
                text = getString(R.string.tarih) + ": " +
                    Academy.formatDateTime(cal.timeInMillis, en)
            }
            paint()
            setOnClickListener {
                DatePickerDialog(
                    this@ExamsActivity,
                    { _, y, m, d ->
                        cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m)
                        cal.set(Calendar.DAY_OF_MONTH, d); paint()
                    },
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                ).show()
            }
        }
        box.addView(dateBtn)

        val timeBtn = TextView(this).apply {
            setTextColor(getColor(R.color.umay_accent))
            setPadding(0, dp(4), 0, dp(4))
            isClickable = true
            fun paint() {
                text = getString(R.string.saat) + ": " +
                    Academy.formatClock(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
            }
            paint()
            setOnClickListener {
                TimePickerDialog(
                    this@ExamsActivity,
                    { _, h, mm ->
                        cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, mm)
                        paint()
                    },
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true
                ).show()
            }
        }
        box.addView(timeBtn)

        box.addView(label(getString(R.string.sinav_notu)))
        val noteInput = EditText(this).apply {
            setText(e.exam.note)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        box.addView(noteInput)

        AlertDialog.Builder(this)
            .setTitle(e.exam.title)
            .setView(box)
            .setPositiveButton(R.string.kaydet) { _, _ ->
                CourseStore.updateExam(
                    this, e.exam.id,
                    titleInput.text.toString(), cal.timeInMillis,
                    noteInput.text.toString()
                )
                Toast.makeText(this, R.string.sinav_guncellendi, Toast.LENGTH_SHORT).show()
                refreshAll()
            }
            .setNeutralButton(R.string.sil) { _, _ -> confirmDeleteExam(e) }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    private fun confirmDeleteExam(e: CourseStore.ExamWithCourse) {
        AlertDialog.Builder(this)
            .setTitle(R.string.sinavlar_baslik)
            .setMessage(
                "${Academy.formatDateTime(e.exam.atMillis, en)} — ${e.exam.title}"
            )
            .setPositiveButton(R.string.sil) { _, _ ->
                CourseStore.deleteExam(this, e.exam.id)
                refreshAll()
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    /** Aynı takvim günü mü? (yıl + yılın günü; DST güvenli). */
    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    // ── SINAV EKLEME ────────────────────────────────────────

    private fun showAddExamDialog() {
        val courses = CourseStore.courses(this)
        if (courses.isEmpty()) {
            Toast.makeText(this, R.string.ders_yok_uyari, Toast.LENGTH_LONG).show()
            return
        }

        // Diyaloğun başlangıç tarihi: takvimde seçili gün
        draftAt = Calendar.getInstance().apply {
            timeInMillis = selectedDayMillis
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }

        box.addView(label(getString(R.string.ders_sec)))
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ExamsActivity, android.R.layout.simple_list_item_1,
                courses.map { it.name }
            )
        }
        box.addView(spinner)

        box.addView(label(getString(R.string.sinav_basligi)))
        val titleInput = EditText(this).apply {
            hint = getString(R.string.not_turu_vize)
            setText(getString(R.string.not_turu_vize))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        box.addView(titleInput)

        val dateBtn = TextView(this).apply {
            text = getString(R.string.tarih) + ": " +
                Academy.formatDateTime(draftAt.timeInMillis, en)
            setTextColor(getColor(R.color.umay_accent))
            setPadding(0, dp(12), 0, dp(6))
            isClickable = true
            setOnClickListener {
                DatePickerDialog(
                    this@ExamsActivity,
                    { _, y, m, d ->
                        draftAt.set(Calendar.YEAR, y)
                        draftAt.set(Calendar.MONTH, m)
                        draftAt.set(Calendar.DAY_OF_MONTH, d)
                        text = getString(R.string.tarih) + ": " +
                            Academy.formatDateTime(draftAt.timeInMillis, en)
                    },
                    draftAt.get(Calendar.YEAR),
                    draftAt.get(Calendar.MONTH),
                    draftAt.get(Calendar.DAY_OF_MONTH)
                ).show()
            }
        }
        box.addView(dateBtn)

        val timeBtn = TextView(this).apply {
            text = getString(R.string.saat) + ": " + Academy.formatClock(
                draftAt.get(Calendar.HOUR_OF_DAY) * 60 + draftAt.get(Calendar.MINUTE)
            )
            setTextColor(getColor(R.color.umay_accent))
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            setOnClickListener {
                TimePickerDialog(
                    this@ExamsActivity,
                    { _, h, mm ->
                        draftAt.set(Calendar.HOUR_OF_DAY, h)
                        draftAt.set(Calendar.MINUTE, mm)
                        text = getString(R.string.saat) + ": " +
                            Academy.formatClock(h * 60 + mm)
                    },
                    draftAt.get(Calendar.HOUR_OF_DAY),
                    draftAt.get(Calendar.MINUTE),
                    true
                ).show()
            }
        }
        box.addView(timeBtn)

        AlertDialog.Builder(this)
            .setTitle(R.string.sinav_ekle_gun)
            .setView(box)
            .setPositiveButton(R.string.ekle) { _, _ ->
                val idx = spinner.selectedItemPosition.coerceIn(0, courses.size - 1)
                val course = courses[idx]
                val title = titleInput.text.toString().ifBlank { course.name }
                CourseStore.addExam(this, course.id, title, draftAt.timeInMillis)
                Toast.makeText(this, R.string.sinav_eklendi, Toast.LENGTH_SHORT).show()
                refreshAll()
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    // ── NOTLAR / YÜZDE / AKTS ───────────────────────────────

    private fun refreshGrades() {
        val list = binding.gradeList
        list.removeAllViews()
        val overview = GradeStore.overview(this)

        if (overview.isEmpty()) {
            list.addView(
                TextView(this).apply {
                    text = getString(R.string.notlar_bos)
                    setTextColor(getColor(R.color.umay_text_dim))
                    textSize = 13f
                    setPadding(4, 18, 4, 6)
                }
            )
            return
        }

        overview.forEach { (course, avg, ects) ->
            val row = TextView(this).apply {
                val letter = GradeStore.letterOf(avg)
                text = "${course.name}\n" +
                    "Ort: ${GradeStore.format2(avg)} · Harf: $letter · " +
                    "${getString(R.string.akts_kisa)}: $ects"
                setTextColor(getColor(R.color.umay_text))
                textSize = 13.5f
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = getDrawable(R.drawable.bg_panel2)
                isClickable = true
                setOnClickListener { editGrades(course) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            list.addView(row, lp)
        }
    }

    private fun refreshAgno() {
        val overview = GradeStore.overview(this)
        val agno = GradeStore.agno(overview.map { it.second to it.third })
        val totalEcts = overview.sumOf { it.third }
        binding.agnoText.text = getString(R.string.agno_baslik) + ": " +
            if (agno == null) "—" else GradeStore.format2(agno)
        binding.aktsText.text = if (totalEcts <= 0) {
            getString(R.string.agno_hesaplanamadi)
        } else {
            getString(R.string.agno_toplam_akts, totalEcts)
        }
    }

    /** Puan + yüzde + AKTS düzenleme diyaloğu (ders başına). */
    private fun editGrades(course: Course) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        val fields = HashMap<String, Pair<EditText, EditText>>(4)
        val saved = GradeStore.gradesOf(this, course.id).associateBy { it.kind }

        GradeStore.KINDS.forEach { kind ->
            box.addView(label(kindLabel(kind)))
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

            val score = EditText(this).apply {
                hint = getString(R.string.not_puan)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                // NO_SCORE (-1) = puan girilmemiş → alan BOŞ gösterilir
                saved[kind]?.takeIf { it.score >= 0.0 }?.let { setText(trimNum(it.score)) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val weight = EditText(this).apply {
                hint = getString(R.string.not_yuzde)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(
                    trimNum(
                        saved[kind]?.weight
                            ?: (GradeStore.DEFAULT_WEIGHTS[kind] ?: 0.0)
                    )
                )
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            line.addView(score)
            line.addView(weight)
            box.addView(line)
            fields[kind] = score to weight
        }

        box.addView(label(getString(R.string.not_akts_girdi)))
        val ectsInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (course.ects > 0) course.ects.toString() else "")
            hint = getString(R.string.akts_kisa)
        }
        box.addView(ectsInput)

        val scroll = android.widget.ScrollView(this).apply { addView(box) }

        AlertDialog.Builder(this)
            .setTitle(course.name)
            .setView(scroll)
            .setPositiveButton(R.string.kaydet) { _, _ ->
                fields.forEach { (kind, pair) ->
                    val scoreText = pair.first.text.toString().replace(',', '.').trim()
                    val weightText = pair.second.text.toString().replace(',', '.').trim()
                    val weight = weightText.toDoubleOrNull() ?: 0.0
                    val score = scoreText.toDoubleOrNull()
                    // Puan boş → null (yüzde korunur, ortalama hesabına girmez)
                    GradeStore.setGrade(this, course.id, kind, score, weight)
                }
                ectsInput.text.toString().trim().toIntOrNull()?.let {
                    GradeStore.setEcts(this, course.id, it)
                }
                Toast.makeText(this, R.string.not_kaydedildi, Toast.LENGTH_SHORT).show()
                refreshGrades()
                refreshAgno()
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    /** 80.0 → "80" · 82.5 → "82.5" (diyalog alanlarını temiz doldurur). */
    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun kindLabel(kind: String): String = getString(
        when (kind) {
            "quiz" -> R.string.not_turu_quiz
            "final" -> R.string.not_turu_final
            "proje" -> R.string.not_turu_proje
            else -> R.string.not_turu_vize
        }
    )

    /** Diyalog içi küçük başlık (isim çakışmasına karşı parametre adı farklı). */
    private fun label(labelText: String): TextView = TextView(this).apply {
        text = labelText
        setTextColor(getColor(R.color.umay_text_dim))
        textSize = 12.5f
        gravity = Gravity.START
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
