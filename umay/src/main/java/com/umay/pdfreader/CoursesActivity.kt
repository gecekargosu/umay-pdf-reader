package com.umay.pdfreader

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.umay.pdfreader.databinding.ActivityCoursesBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v40 — DERSLER SAYFASI (kullanıcı isteği).
 *
 * "Derslerim çerçevesinin bir yerine bastığımda yeni sayfa açılmalı ve o
 * kısımda bulunan dersler listelenmeli. Derslerin yerleri değişebilmeli.
 * Derslerdeki kaynak sayısı gösterilmeli. Yaklaşan sınavı/projesi varsa
 * metin yanıp sönmeli."
 *
 * • Kaynak sayısı: [CourseStore.statsOf] (docs)
 * • Yaklaşan sınav: [CourseStore.allExams] → en yakın gelecek sınav
 * • 7 günden az kaldıysa uyarı satırı NABIZ gibi yanıp söner
 * • Sıra ▲▼ ile değişir, kalıcıdır ([CourseOrder])
 */
class CoursesActivity : AppCompatActivity() {

    companion object {
        fun newIntent(ctx: Context): Intent = Intent(ctx, CoursesActivity::class.java)
    }

    private lateinit var binding: ActivityCoursesBinding
    private val rows = mutableListOf<Row>()
    private val adapter = RowAdapter()
    private val dayFmt = SimpleDateFormat("d MMM", Locale("tr", "TR"))
    private val pulses = mutableListOf<ObjectAnimator>()

    private data class Row(
        val course: Course,
        val docs: Int,
        val exams: Int,
        val alertText: String,
        val alertDays: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCoursesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)

        binding.courseRows.layoutManager = LinearLayoutManager(this)
        binding.courseRows.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddCourse.setOnClickListener { addCourse() }
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        pulses.forEach { try { it.cancel() } catch (_: Throwable) {} }
        pulses.clear()
        super.onDestroy()
    }

    private fun reload() {
        stopPulses()
        val courses = try {
            CourseOrder.apply(this, CourseStore.courses(this))
        } catch (t: Throwable) {
            BookStore.logError(this, "CoursesActivity.courses", t)
            emptyList()
        }
        val nearest = nearestExamByCourse()
        val now = System.currentTimeMillis()

        rows.clear()
        var withAlert = 0
        courses.forEach { c ->
            val stats = try {
                CourseStore.statsOf(this, c.id)
            } catch (_: Throwable) {
                CourseStore.CourseStats(0, 0, 0)
            }
            val ex = nearest[c.id]
            var alert = ""
            var days = Int.MAX_VALUE
            if (ex != null) {
                val diff = ex.atMillis - now
                days = (diff / 86_400_000L).toInt()
                val hours = (diff / 3_600_000L).toInt()
                val whenText = when {
                    diff < 0 -> ""
                    hours < 24 -> "bugün"
                    days <= 1 -> "yarın"
                    else -> "$days gün kaldı"
                }
                if (whenText.isNotEmpty()) {
                    alert = "⏰ ${ex.type} · ${ex.title} — $whenText (${dayFmt.format(Date(ex.atMillis))})"
                }
            }
            if (days in 0..7) withAlert++
            rows.add(Row(c, stats.docs, stats.exams, alert, days))
        }
        adapter.notifyDataSetChanged()

        val empty = rows.isEmpty()
        binding.coursesEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.courseRows.visibility = if (empty) View.GONE else View.VISIBLE
        binding.coursesSummary.text = if (empty) "" else
            "${rows.size} ders" + if (withAlert > 0) " · $withAlert yaklaşan sınav" else ""
    }

    /** Ders başına EN YAKIN gelecek sınav. */
    private fun nearestExamByCourse(): Map<String, Exam> = try {
        val now = System.currentTimeMillis()
        val map = HashMap<String, Exam>()
        CourseStore.allExams(this).forEach { e ->
            if (e.exam.atMillis < now) return@forEach
            val cur = map[e.exam.courseId]
            if (cur == null || e.exam.atMillis < cur.atMillis) map[e.exam.courseId] = e.exam
        }
        map
    } catch (t: Throwable) {
        BookStore.logError(this, "CoursesActivity.exams", t)
        emptyMap()
    }

    private fun addCourse() {
        val input = EditText(this).apply {
            hint = getString(R.string.ders_adi)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.yeni_ders_ekle)
            .setView(input)
            .setPositiveButton(R.string.kaydet) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.ders_bos_olamaz, Toast.LENGTH_SHORT).show()
                } else {
                    CourseStore.addCourse(this, name)
                    reload()
                }
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    private fun move(index: Int, delta: Int) {
        val to = index + delta
        if (index !in rows.indices || to !in rows.indices) return
        val moved = rows.removeAt(index)
        rows.add(to, moved)
        adapter.notifyItemMoved(index, to)
        CourseOrder.save(this, rows.map { it.course.id })
        adapter.notifyDataSetChanged()
    }

    private fun open(course: Course) {
        startActivity(
            Intent(this, CourseDetailActivity::class.java).apply {
                putExtra(CourseDetailActivity.EXTRA_COURSE_ID, course.id)
            }
        )
    }

    private fun stopPulses() {
        pulses.forEach { try { it.cancel() } catch (_: Throwable) {} }
        pulses.clear()
    }

    private fun pulse(v: View) {
        try {
            val a = ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.25f)
            a.duration = 700
            a.repeatMode = ObjectAnimator.REVERSE
            a.repeatCount = ObjectAnimator.INFINITE
            a.interpolator = LinearInterpolator()
            a.start()
            pulses.add(a)
        } catch (_: Throwable) {}
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {

        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.crName)
            val meta: TextView = v.findViewById(R.id.crMeta)
            val alert: TextView = v.findViewById(R.id.crAlert)
            val up: TextView = v.findViewById(R.id.crUp)
            val down: TextView = v.findViewById(R.id.crDown)
            val accent: View = v.findViewById(R.id.crAccent)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_course_row, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.name.text = row.course.name
            holder.meta.text = getString(R.string.ders_satir_ozet, row.docs, row.exams)
            if (row.alertText.isBlank()) {
                holder.alert.visibility = View.GONE
                holder.alert.alpha = 1f
            } else {
                holder.alert.visibility = View.VISIBLE
                holder.alert.text = row.alertText
                // 1 haftadan az kaldıysa yanıp söner (kullanıcı isteği)
                if (row.alertDays in 0..7) pulse(holder.alert) else holder.alert.alpha = 1f
            }
            try {
                holder.accent.setBackgroundColor(getColor(R.color.umay_course_stroke_0))
            } catch (_: Throwable) {}
            holder.v.setOnClickListener { open(row.course) }
            holder.up.setOnClickListener { move(holder.bindingAdapterPosition, -1) }
            holder.down.setOnClickListener { move(holder.bindingAdapterPosition, +1) }
            holder.up.alpha = if (position == 0) 0.25f else 1f
            holder.down.alpha = if (position == rows.size - 1) 0.25f else 1f
        }
    }
}
