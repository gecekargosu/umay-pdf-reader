package com.umay.pdfreader

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.umay.pdfreader.databinding.ActivityThemeStudioBinding

/**
 * v14 (V5.0 §1) — ÖZEL TEMA TASARLAYICI EKRANI.
 *
 *  • 4 bileşen (arka plan / panel / aksan / raf çerçevesi) renk satırı:
 *    tıklayınca mat renk paleti açılır, seçim CANLI ÖNİZLEME'ye anında
 *    yansır (kaydet butonu gerekmez — her değişim kalıcıdır).
 *  • Hazır mat paletler tek dokunuşla uygulanır.
 *  • "Varsayılana dön" siber-akademik paleti geri getirir.
 */
class ThemeStudioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThemeStudioBinding

    /** Mat renk paleti (canlı önizleme seçicisi). */
    private val palette = listOf(
        "#0B0E14" to "Siber Gece", "#161B22" to "Panel", "#1E293B" to "Çerçeve",
        "#FF6B00" to "Turuncu", "#00E5FF" to "Turkuaz", "#7BC96F" to "Orman",
        "#A78BFA" to "Lavanta", "#E0B034" to "Altın", "#0E7490" to "Okyanus",
        "#F4F4F2" to "Açık Mat", "#FFFFFF" to "Beyaz", "#12100E" to "Sıcak Siyah"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThemeStudioBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // Renk satırları: tıkla → palet aç → canlı uygula (v16: metinler
        // string kaynağından → İngilizce modda da doğru görünür)
        bindRow(binding.rowBg, getString(R.string.tema_row_bg)) { c -> applyChange { ThemeStudio.bg = c } }
        bindRow(binding.rowPanel, getString(R.string.tema_row_panel)) { c -> applyChange { ThemeStudio.panel = c } }
        bindRow(binding.rowAccent, getString(R.string.tema_row_accent)) { c -> applyChange { ThemeStudio.accent = c } }
        bindRow(binding.rowShelf, getString(R.string.tema_row_shelf)) { c -> applyChange { ThemeStudio.shelfFrame = c } }

        buildPresetList()

        // v16 (GÖREV 2) — OTOMATİK MOD: özel tema kapatılır, seçim görünür olur
        binding.btnAutoTheme.setOnClickListener {
            ThemeStudio.clearCustomization(this)
            android.widget.Toast.makeText(
                this, R.string.tema_auto_done, android.widget.Toast.LENGTH_SHORT
            ).show()
            recreate()
        }

        binding.btnResetTheme.setOnClickListener {
            ThemeStudio.reset(this)
            refreshAll()
            android.widget.Toast.makeText(this, R.string.tema_reset_done, android.widget.Toast.LENGTH_SHORT).show()
        }

        refreshAll()
    }

    /** Değişikliği uygula + önizle + kaydet (kalıcı). */
    private fun applyChange(block: () -> Unit) {
        block()
        ThemeStudio.saveAndApply(
            this, ThemeStudio.Preset(
                "özel", ThemeStudio.bg, ThemeStudio.panel, ThemeStudio.accent, ThemeStudio.shelfFrame
            )
        )
        ThemeStudio.apply(this)
        refreshAll()
    }

    /** Satır bağlayıcı: etiket + mevcut renk örneği + palet diyaloğu. */
    private fun bindRow(row: TextView, label: String, onPick: (String) -> Unit) {
        row.text = "$label\n—"
        row.gravity = Gravity.CENTER_VERTICAL
        row.setOnClickListener {
            val labels = palette.map { it.second }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(label)
                .setItems(labels) { _, which -> onPick(palette[which].first) }
                .setNegativeButton(R.string.kapat, null)
                .show()
        }
    }

    /** Hazır palet listesini koda üret. */
    private fun buildPresetList() {
        val list = binding.presetList
        list.removeAllViews()
        // v16 (GÖREV 3) — palet adı aktif dile göre (TR/EN)
        val english = (LangHelper.currentLang(this) == "en")
        ThemeStudio.PRESETS.forEach { p ->
            val v = TextView(this).apply {
                text = p.label(english)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(28, 30, 28, 30)
                textSize = 14f
                setBackgroundColor(ThemeStudio.color(p.panel))
                // Sol kenarda arka plan + aksan örnek şeritleri
                compoundDrawablePadding = 18
                setCompoundDrawablesWithIntrinsicBounds(swatch(p.bg), null, swatch(p.accent), null)
                setOnClickListener {
                    ThemeStudio.saveAndApply(this@ThemeStudioActivity, p)
                    ThemeStudio.apply(this@ThemeStudioActivity)
                    refreshAll()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10 }
        list.addView(v, lp)
        }
    }

    /** 24dp renk örneği (drawable). */
    private fun swatch(hex: String): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 12f * resources.displayMetrics.density
            setColor(ThemeStudio.color(hex))
            val s = (24 * resources.displayMetrics.density).toInt()
            setBounds(0, 0, s, s)
        }

    /** Tüm ekranı (satırlar + canlı önizleme) tazele. */
    private fun refreshAll() {
        fun colorRow(row: TextView, label: String, hex: String) {
            row.text = "$label  ·  $hex"
            row.setCompoundDrawablesWithIntrinsicBounds(swatch(hex), null, null, null)
            row.compoundDrawablePadding = 18
            row.setBackgroundColor(ThemeStudio.color(ThemeStudio.panel, Color.TRANSPARENT))
        }
        colorRow(binding.rowBg, getString(R.string.tema_row_bg_kisa), ThemeStudio.bg)
        colorRow(binding.rowPanel, getString(R.string.tema_row_panel_kisa), ThemeStudio.panel)
        colorRow(binding.rowAccent, getString(R.string.tema_row_accent_kisa), ThemeStudio.accent)
        colorRow(binding.rowShelf, getString(R.string.tema_row_shelf_kisa), ThemeStudio.shelfFrame)

        // Canlı önizleme kutusu
        binding.previewBox.setBackgroundColor(ThemeStudio.color(ThemeStudio.bg))
        binding.previewTitle.setTextColor(ThemeStudio.color(ThemeStudio.accent))
        binding.previewPanel.setBackgroundColor(ThemeStudio.color(ThemeStudio.panel))
        binding.previewShelf.apply {
            setBackgroundColor(ThemeStudio.color(ThemeStudio.shelfFrame))
            setTextColor(Color.WHITE)
        }
        binding.previewAccentBtn.apply {
            setBackgroundColor(ThemeStudio.color(ThemeStudio.accent))
            setTextColor(
                if (ThemeStudio.isLight(ThemeStudio.color(ThemeStudio.accent))) Color.BLACK else Color.WHITE
            )
        }
    }
}
