package com.umay.pdfreader

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.umay.pdfreader.databinding.ActivityFlashcardBinding

/**
 * v12 (YENİ ÖZELLİK) — AI FLASHCARD / ÇALIŞMA KARTI EKRANI.
 *
 * Akış:
 *  1. ReaderActivity'den gelen sayfa metni ile kartlar üretilir (yükleme döner).
 *  2. Kartlar sırayla gösterilir: soru üstte, 4 şık altta.
 *  3. Kullanıcı şık seçer → doğru/yanlış renklenir + açıklama görünür.
 *  4. "Sonraki" ile devam; bitince sonuç ekranı (N/10 + tekrar dene).
 *
 * GÜVENLİK: üretemezse kullanıcıyı ReaderActivity'ye geri gönderir;
 * asla ekran boş kilitlenmez.
 */
class FlashcardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFlashcardBinding
    private var cards: List<FlashcardManager.Card> = emptyList()
    private var index = 0
    private var correct = 0
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFlashcardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { finish() }
        // v13 (A6): Hata ekranındaki kapat butonu XML android:onClick="finish"
        // ile bağlıydı; framework refleksiyonla finish(View) ARAR ve
        // NoSuchMethodException → IllegalStateException (XML crash) fırlatabilir.
        // Artık açıkça koda bağlandı (refleksiyon yok, crash yok).
        binding.btnErrorClose.setOnClickListener { finish() }

        val pageText = intent.getStringExtra(EXTRA_PAGE_TEXT) ?: ""
        val pageNo = intent.getIntExtra(EXTRA_PAGE_NO, 1)
        if (pageText.isBlank()) {
            Toast.makeText(this, R.string.flashcard_no_text, Toast.LENGTH_SHORT).show()
            finish(); return
        }

        showLoading()
        FlashcardManager.generate(this, pageText, pageNo) { result ->
            result.fold(
                onSuccess = { list ->
                    if (list.isEmpty()) {
                        showError(getString(R.string.flashcard_none)); return@fold
                    }
                    cards = list; index = 0; correct = 0; showCard()
                },
                onFailure = { e ->
                    showError(e.message ?: getString(R.string.flashcard_error))
                }
            )
        }
    }

    private fun showLoading() {
        binding.progress.visibility = View.VISIBLE
        binding.cardGroup.visibility = View.GONE
        binding.resultGroup.visibility = View.GONE
        binding.errorGroup.visibility = View.GONE
    }

    private fun showError(msg: String) {
        binding.progress.visibility = View.GONE
        binding.cardGroup.visibility = View.GONE
        binding.resultGroup.visibility = View.GONE
        binding.errorGroup.visibility = View.VISIBLE
        binding.errorText.text = msg
    }

    /** Bir sonraki kartı gösterir veya sonuç ekranına geçer. */
    private fun showCard() {
        if (index >= cards.size) { showResult(); return }
        answered = false
        val c = cards[index]
        binding.progress.visibility = View.GONE
        binding.resultGroup.visibility = View.GONE
        binding.errorGroup.visibility = View.GONE
        binding.cardGroup.visibility = View.VISIBLE

        binding.cardCounter.text = "${index + 1} / ${cards.size}"
        binding.questionText.text = c.question
        binding.explanationText.visibility = View.GONE
        binding.btnNext.visibility = View.GONE

        // v13 (AGENT 22 — A2): Kartlar kullanıcı tarafından DÜZENLENEBİLİR
        binding.btnEditCard.setOnClickListener { editCard() }
        binding.btnEditCard.visibility = View.VISIBLE

        val options = listOf(binding.optionA, binding.optionB, binding.optionC, binding.optionD)
        c.options.take(4).forEachIndexed { i, opt ->
            options[i].text = "${'A' + i}) $opt"
            options[i].visibility = View.VISIBLE
            options[i].isEnabled = true
            options[i].setBackgroundResource(R.drawable.bg_input)
            options[i].setOnClickListener { pickOption(i) }
        }
        // 4'ten az şık varsa gizle
        for (i in c.options.size until 4) options[i].visibility = View.GONE
    }

    /** Şık seçimi: doğru → yeşil, yanlış → kırmızı + doğruyu göster. */
    private fun pickOption(i: Int) {
        if (answered) return
        answered = true
        val c = cards[index]
        val options = listOf(binding.optionA, binding.optionB, binding.optionC, binding.optionD)
        options.forEach { it.isEnabled = false }
        if (i == c.answerIndex) {
            correct++
            options[i].setBackgroundResource(R.drawable.bg_button_primary)
        } else {
            options[i].setBackgroundResource(R.drawable.bg_input_danger)
            if (c.answerIndex in 0..3) {
                options[c.answerIndex].setBackgroundResource(R.drawable.bg_button_primary)
            }
        }
        if (c.explanation.isNotBlank()) {
            binding.explanationText.text = c.explanation
            binding.explanationText.visibility = View.VISIBLE
        }
        binding.btnNext.visibility = View.VISIBLE
        binding.btnNext.setOnClickListener { index++; showCard() }
    }

    private fun showResult() {
        binding.progress.visibility = View.GONE
        binding.cardGroup.visibility = View.GONE
        binding.errorGroup.visibility = View.GONE
        binding.resultGroup.visibility = View.VISIBLE
        binding.resultText.text = getString(R.string.flashcard_result, correct, cards.size)
    }

    /**
     * v13 (AGENT 22 — A2): Mevcut kartı düzenle — AI çıktısı (soru/şık/
     * doğru cevap/açıklama) kullanıcı tarafından düzeltilebilir; değişiklik
     * bu oturumda geçerli olur ve kart yeniden çizilir.
     */
    private fun editCard() {
        if (index !in cards.indices) return
        val c = cards[index]
        val view = layoutInflater.inflate(R.layout.dialog_card_edit, null)
        val q = view.findViewById<EditText>(R.id.editQuestion)
        val o = listOf(
            view.findViewById<EditText>(R.id.editOptionA),
            view.findViewById<EditText>(R.id.editOptionB),
            view.findViewById<EditText>(R.id.editOptionC),
            view.findViewById<EditText>(R.id.editOptionD)
        )
        val answerSpin = view.findViewById<Spinner>(R.id.editAnswer)
        val exp = view.findViewById<EditText>(R.id.editExplanation)
        q.setText(c.question)
        c.options.take(4).forEachIndexed { i, text -> o[i].setText(text) }
        answerSpin.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, arrayOf("A", "B", "C", "D")
        )
        answerSpin.setSelection(c.answerIndex.coerceIn(0, 3))
        exp.setText(c.explanation)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.flashcard_edit_title)
            .setView(view)
            .setPositiveButton(R.string.kaydet) { _, _ ->
                val newOptions = o.mapIndexed { i, et ->
                    et.text.toString().trim().ifBlank { "${'A' + i}) —" }
                }
                cards = cards.toMutableList().apply {
                    this[index] = FlashcardManager.Card(
                        question = q.text.toString().trim().ifBlank { c.question },
                        options = newOptions,
                        answerIndex = answerSpin.selectedItemPosition.coerceIn(0, 3),
                        explanation = exp.text.toString().trim()
                    )
                }
                showCard()
                Toast.makeText(this, R.string.flashcard_edited, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.kapat, null)
            .show()
    }

    companion object {
        const val EXTRA_PAGE_TEXT = "page_text"
        const val EXTRA_PAGE_NO = "page_no"
    }
}
