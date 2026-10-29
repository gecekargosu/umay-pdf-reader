package com.umay.pdfreader

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.umay.pdfreader.databinding.ActivityTranscriptBinding
import org.json.JSONArray
import org.json.JSONObject

/**
 * v14 (V5.0 §6) — AKADEMİK TRANSKRİPT & AGNO HESAPLAYICI.
 *
 *  • Kullanıcı dönem dönem ders ekler: ders adı + AKTS kredisi + harf notu.
 *  • Harf notu → 4'lük katsayı (AA 4.00 … FF 0.00).
 *  • AGNO = Σ(not × AKTS) / Σ(AKTS) — dönem ortalaması ve GENEL AGNO
 *    anında hesaplanır.
 *  • Veri: AppPrefs KEY_TRANSCRIPT_JSON — {"1":[{name,akts,grade},…], …}
 *  • Dışa aktarma: dönem + AGNO içeren .md dosyası (paylaşım menüsü).
 */
class TranscriptActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTranscriptBinding
    private var semester = 1

    // Harf notu → 4'lük katsayı (Türkiye üniversiteleri standardı)
    private val gradePoints = linkedMapOf(
        "AA" to 4.0, "BA" to 3.5, "BB" to 3.0, "CB" to 2.5,
        "CC" to 2.0, "DD" to 1.5, "FD" to 1.0, "FF" to 0.0
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTranscriptBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddCourse.setOnClickListener { addCourseDialog() }
        binding.btnExport.setOnClickListener { exportTranscript() }

        // Dönem seçici (1–8)
        binding.semesterSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            (1..8).map { "$it. Dönem" }
        )
        binding.semesterSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long
                ) {
                    semester = pos + 1
                    refresh()
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }

        refresh()
    }

    // ── Veri (AppPrefs JSON) ────────────────────────────────

    private fun loadData(): JSONObject = try {
        val raw = AppPrefs.getString(this, AppPrefs.KEY_TRANSCRIPT_JSON)
        if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
    } catch (_: Throwable) {
        JSONObject()
    }

    private fun saveData(o: JSONObject) {
        AppPrefs.putString(this, AppPrefs.KEY_TRANSCRIPT_JSON, o.toString())
    }

    private fun coursesOf(sem: Int): List<Triple<String, Double, String>> {
        val arr = loadData().optJSONArray("$sem") ?: return emptyList()
        val out = ArrayList<Triple<String, Double, String>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Triple(
                    o.optString("name"),
                    o.optDouble("akts", 0.0),
                    o.optString("grade", "FF")
                )
            )
        }
        return out
    }

    // ── Ekleme / silme ──────────────────────────────────────

    /** Ders ekleme diyaloğu: ad + AKTS + harf notu. Çıkarılamayan alanlar elle. */
    private fun addCourseDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_transcript_add, null)
        val nameEt = view.findViewById<EditText>(R.id.inputCourseName)
        val aktsEt = view.findViewById<EditText>(R.id.inputAkts)
        val gradeSpin = view.findViewById<Spinner>(R.id.gradeSpinner)
        gradeSpin.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, gradePoints.keys.toList()
        )
        gradeSpin.setSelection(2) // varsayılan BB

        AlertDialog.Builder(this)
            .setTitle(R.string.transcript_add)
            .setView(view)
            .setPositiveButton(R.string.kaydet) { _, _ ->
                val name = nameEt.text.toString().trim()
                val akts = aktsEt.text.toString().trim().toDoubleOrNull() ?: 0.0
                if (name.isBlank()) return@setPositiveButton
                var grade = gradeSpin.selectedItem?.toString() ?: "FF"
                if (gradeSpin.selectedItem == null) gradeSpin.setSelection(0)
                val data = loadData()
                val arr = data.optJSONArray("$semester") ?: JSONArray()
                arr.put(JSONObject().apply {
                    put("name", name); put("akts", akts); put("grade", grade)
                })
                data.put("$semester", arr)
                saveData(data)
                refresh()
            }
            .setNegativeButton(R.string.kapat, null)
            .show()
    }

    /** Satıra dokun → silmek isteye misin. */
    private fun removeCourse(idx: Int) {
        val data = loadData()
        val arr = data.optJSONArray("$semester") ?: return
        if (idx < 0 || idx >= arr.length()) return
        val name = arr.optJSONObject(idx)?.optString("name") ?: "?"
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage("Bu dersi transkriptten sil?")
            .setPositiveButton(R.string.sil) { _, _ ->
                val d2 = loadData()
                val a2 = d2.optJSONArray("$semester") ?: return@setPositiveButton
                val newList = JSONArray()
                for (i in 0 until a2.length()) if (i != idx) newList.put(a2.optJSONObject(i))
                d2.put("$semester", newList)
                saveData(d2)
                refresh()
            }
            .setNegativeButton(R.string.kapat, null)
            .show()
    }

    // ── Çizim + AGNO ────────────────────────────────────────

    private fun refresh() {
        val list = binding.courseList
        list.removeAllViews()
        val courses = coursesOf(semester)
        if (courses.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.transcript_empty)
                setTextColor(getColor(R.color.umay_text_dim))
                setPadding(0, 24, 0, 24)
            }
            list.addView(empty)
        }
        courses.forEachIndexed { i, (name, akts, grade) ->
            val row = layoutInflater.inflate(R.layout.item_academy_row, list, false)
            row.findViewById<TextView>(R.id.rowTitle).text = name
            row.findViewById<TextView>(R.id.rowSub).text =
                "AKTS $akts · $grade (${gradePoints[grade] ?: 0.0})"
            row.findViewById<TextView>(R.id.rowAction).text = "✕"
            row.findViewById<TextView>(R.id.rowAction).setOnClickListener { removeCourse(i) }
            list.addView(row)
        }

        // Dönem ortalaması + genel AGNO
        val semAvg = gpa(courses)
        binding.semesterGpa.text = getString(R.string.transcript_sem_agno, fmt(semAvg))
        val all = (1..8).flatMap { coursesOf(it) }
        binding.totalGpa.text = getString(R.string.transcript_agno, fmt(gpa(all)))
    }

    /** AGNO = Σ(nokta×AKTS)/Σ(AKTS); ders yoksa 0.0 */
    private fun gpa(courses: List<Triple<String, Double, String>>): Double {
        val totalAkts = courses.sumOf { it.second }
        if (totalAkts <= 0.0) return 0.0
        val weighted = courses.sumOf { (gradePoints[it.third] ?: 0.0) * it.second }
        return weighted / totalAkts
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    // ── Dışa aktarma (.md) ──────────────────────────────────

    private fun exportTranscript() {
        val sb = StringBuilder()
        sb.appendLine("# UMAY Akademik Transkript")
        sb.appendLine()
        val all = (1..8).flatMap { coursesOf(it) }
        sb.appendLine("**Genel AGNO: ${fmt(gpa(all))}**  ·  Toplam AKTS: ${all.sumOf { it.second }}")
        sb.appendLine()
        for (sem in 1..8) {
            val courses = coursesOf(sem)
            if (courses.isEmpty()) continue
            sb.appendLine("## $sem. Dönem — ortalama ${fmt(gpa(courses))}")
            courses.forEach { (n, a, g) ->
                sb.appendLine("- $n — AKTS $a — $g (${gradePoints[g] ?: 0.0})")
            }
            sb.appendLine()
        }
        val f = try {
            ExportManager.exportSummary(this, "transkript", sb.toString())
        } catch (t: Throwable) {
            BookStore.logError(this, "exportTranscript", t)
            null
        }
        if (f != null) {
            Toast.makeText(this, R.string.disa_aktarildi, Toast.LENGTH_SHORT).show()
            ExportManager.shareFile(this, f)
        } else {
            Toast.makeText(this, R.string.disa_aktarilamadi, Toast.LENGTH_SHORT).show()
        }
    }
}
