package com.umay.pdfreader

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.umay.pdfreader.databinding.ActivityCompetitionsBinding

/**
 * v5.15 — PROJE YARIŞMALARI SAYFASI.
 *
 * TÜBİTAK / TEKNOFEST / ASELSAN / TUSAŞ / operatörler ve benzeri kurumların
 * programları kapaklı liste halinde gösterilir. Arama kutusu başlık, kurum,
 * kategori ve özette arar; kategori çipleri listeyi süzer.
 *
 * Bir karta dokununca detay penceresi açılır → "Siteyi aç" resmî sayfaya
 * gider (tarayıcıda detaylı inceleme). Liste tamamen çevrimdışı çalışır;
 * ağ yalnız kullanıcı siteyi açtığında kullanılır.
 */
class CompetitionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompetitionsBinding
    private var adapter: CompetitionAdapter? = null
    private var query: String = ""
    private var category: String = CompetitionStore.CATEGORIES.first()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompetitionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)

        binding.btnCompBack.setOnClickListener { finish() }

        adapter = CompetitionAdapter(
            onClick = { showDetail(it) },
            onOpen = { openSite(it.url) }
        )
        binding.compList.layoutManager = LinearLayoutManager(this)
        binding.compList.adapter = adapter
        binding.compList.isNestedScrollingEnabled = false

        binding.compSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                refresh()
            }
        })

        binding.btnCompWeb.setOnClickListener {
            val q = if (query.isBlank()) "" else query
            openSite(CompetitionStore.webSearchUrl(q))
        }

        buildChips()
        refresh()
    }

    /** Kategori çipleri (programatik — tek tek id tanımlamaya gerek yok). */
    private fun buildChips() {
        binding.compChips.removeAllViews()
        CompetitionStore.CATEGORIES.forEach { cat ->
            val chip = TextView(this).apply {
                text = cat
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(30, 14, 30, 14)
                setOnClickListener {
                    category = cat
                    buildChips()
                    refresh()
                }
            }
            val on = cat == category
            chip.setBackgroundResource(
                if (on) R.drawable.bg_button_primary else R.drawable.bg_input
            )
            chip.setTextColor(getColor(if (on) R.color.umay_on_accent else R.color.umay_text))
            chip.typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else null
            binding.compChips.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 12 }
            )
        }
    }

    private fun refresh() {
        // v5.16 — liste son başvuru tarihine göre sıralı gelir
        val items = CompetitionStore.sortedByDeadline(
            CompetitionStore.search(query, category)
        )
        adapter?.submit(items)
        binding.compEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.compList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        binding.compCount.text = getString(R.string.yarisma_sayi, items.size) +
            " · " + getString(R.string.yarisma_siralama)
    }

    /** Detay penceresi: kapsam + tarih + destek, altında "Siteyi aç". */
    private fun showDetail(c: CompetitionStore.Competition) {
        val body = buildString {
            append(getString(R.string.yarisma_kurum)).append(": ").append(c.org).append('\n')
            append(getString(R.string.yarisma_kategori)).append(": ").append(c.category).append('\n')
            append(getString(R.string.yarisma_son_tarih)).append(": ").append(c.deadline).append('\n')
            if (c.support.isNotBlank()) {
                append(getString(R.string.yarisma_destek)).append(": ").append(c.support).append('\n')
            }
            append('\n').append(getString(R.string.yarisma_kapsam)).append(":\n")
            append(c.details).append("\n\n")
            append(getString(R.string.yarisma_kaynak_not))
        }
        AlertDialog.Builder(this)
            .setTitle(c.title)
            .setMessage(body)
            .setPositiveButton(R.string.yarisma_detay_site) { _, _ -> openSite(c.url) }
            .setNegativeButton(R.string.kapat, null)
            .show()
    }

    private fun openSite(url: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (t: Throwable) {
            BookStore.logError(this, "competitionOpen", t)
            Toast.makeText(this, R.string.yarisma_site_yok, Toast.LENGTH_LONG).show()
        }
    }

    // ── Liste bağdaştırıcısı ────────────────────────────────

    private class CompetitionAdapter(
        val onClick: (CompetitionStore.Competition) -> Unit,
        val onOpen: (CompetitionStore.Competition) -> Unit
    ) : RecyclerView.Adapter<CompetitionAdapter.Holder>() {

        private val items = ArrayList<CompetitionStore.Competition>()

        fun submit(list: List<CompetitionStore.Competition>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val cover: View = v.findViewById(R.id.compCover)
            val badge: TextView = v.findViewById(R.id.compBadge)
            val title: TextView = v.findViewById(R.id.compTitle)
            val org: TextView = v.findViewById(R.id.compOrg)
            val deadline: TextView = v.findViewById(R.id.compDeadline)
            val summary: TextView = v.findViewById(R.id.compSummary)
            val open: TextView = v.findViewById(R.id.compOpen)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_competition, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val c = items[position]
            holder.title.text = c.title
            holder.org.text = c.org
            holder.deadline.text = "${holder.itemView.context.getString(R.string.yarisma_son_tarih)}: ${c.deadline}"
            holder.summary.text = c.summary
            holder.badge.text = c.badge

            // Kapak: kuruma özel gradyan + yuvarlatılmış köşe
            val bg = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(c.colorFrom, c.colorTo)
            ).apply {
                cornerRadius = 20f
                setStroke(2, Color.argb(90, 255, 255, 255))
            }
            holder.cover.background = bg

            holder.itemView.setOnClickListener { onClick(c) }
            holder.open.setOnClickListener { onOpen(c) }
        }
    }
}
