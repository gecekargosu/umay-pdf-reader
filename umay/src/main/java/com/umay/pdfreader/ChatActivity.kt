package com.umay.pdfreader

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.umay.pdfreader.databinding.ActivityChatBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sohbet ekranı.
 * - API anahtarı YOKSA: tamamen offline, PDF metninde arama + özet.
 * - API anahtarı VARSA: ücretsiz Gemini / OpenAI-uyumlu servisle gerçek sohbet.
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        /** ADIM 3: okuma ekranında açık olan sayfa (0 tabanlı) — bağlam önceliği. */
        const val EXTRA_PAGE = "page"
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var bookId: String
    /** Okuma ekranından gelindiyse açık sayfa; -1 = belirsiz. */
    private var focusPage: Int = -1

    private val messages = mutableListOf<Msg>()
    private lateinit var adapter: MsgAdapter
    private var pages: List<String> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingJob: Job? = null

    data class Msg(val fromUser: Boolean, var text: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run { finish(); return }
        focusPage = intent.getIntExtra(EXTRA_PAGE, -1)   // ADIM 3

        adapter = MsgAdapter(messages)
        binding.messageList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.messageList.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSend.setOnClickListener { send() }

        updateModeChip()

        addMsg(false, getString(R.string.sohbet_hazir))
    }

    override fun onDestroy() {
        // Activity kapanınca bekleyen cevap döngüsü ve scope yaşamasın
        pendingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateModeChip()
        AppPrefs.warmApiCache(this)
    }

    private fun updateModeChip() {
        AppPrefs.warmApiCache(this)
        val online = !AppPrefs.lastApiKey.isNullOrBlank()
        binding.modeChip.text = getString(if (online) R.string.sohbet_online else R.string.sohbet_offline)
        val hint = getString(
            if (online) R.string.sohbet_online else R.string.sohbet_offline_ipucu
        )
        // ADIM 3: okuma ekranından geldiysek açık sayfayı bağlam olarak bildir
        binding.chatHint.text = if (focusPage >= 0) {
            hint + "\n" + getString(R.string.sohbet_sayfa_baglami, focusPage + 1)
        } else {
            hint
        }
    }

    private fun send() {
        val q = binding.messageInput.text.toString().trim()
        if (q.isEmpty()) return
        binding.messageInput.setText("")

        addMsg(true, q)
        val placeholder = addMsg(false, "…")

        pendingJob = scope.launch {
            // Sayfalar çıkarılıyor olabilir; tazele (IO thread'de)
            val answer = withContext(Dispatchers.IO) { answerFor(q) }
            placeholder.text = answer
            adapter.notifyItemChanged(messages.size - 1)
            scrollToBottom()
        }
    }

    private fun answerFor(question: String): String {
        // IO thread'inden çağrılır: tüm sayfa metinleri burada okunur
        pages = BookStore.allPages(this, bookId)

        // Taranmış kitapta metin henüz çıkmadıysa (OCR sürüyor) bunu açıkça söyle
        if (pages.none { it.isNotBlank() }) {
            return getString(R.string.sohbet_metin_hazir_degil)
        }

        val apiKey = AppPrefs.lastApiKey
        val online = !apiKey.isNullOrBlank()

        // Offline özel komutlar
        if (!online) {
            val q = question.lowercase()
            if (q.contains("özet") || q.contains("ozet")) {
                return "📖 ÖZET\n\n" + TextUtil.summarize(pages)
            }
            val hits = TextUtil.search(pages, question, 4)
            if (hits.isEmpty()) {
                // Cümle bazlı eşleşme yoksa sayfa bazlı (kısmi) eşleşmeyi dene
                val loose = TextUtil.contextPages(pages, question, 3, 1200)
                if (loose.isNotEmpty()) {
                    return "🔍 PDF'te şunları buldum:\n\n" +
                        loose.joinToString("\n\n") { it.take(400) }
                }
                return "Buna dair PDF'te bir şey bulamadım.\n\n" +
                    "İpucu: farklı kelimeler deneyebilir ya da \"özet yaz\" yazabilirsin.\n" +
                    "Gerçek sohbet için Ayarlar'dan ücretsiz bir API anahtarı girebilirsin."
            }
            val sb = StringBuilder("🔍 PDF'te buldum:\n\n")
            hits.forEachIndexed { i, h ->
                sb.append("${i + 1}) Sayfa ${h.page + 1}: ")
                sb.append(h.sentence.take(300))
                sb.append("\n\n")
            }
            return sb.toString().trim()
        }

        // Online sohbet — sayfa bazlı, kısmi eşleşmeli zengin bağlamla.
        // Eski kod: yalnız cümle bazlı eşleşen 6 parça + ilk 3 sayfa →
        // model "bu kitapta geçmiyor" diyordu.
        // ADIM 3: okuma ekranındaki açık sayfa bağlamın EN BAŞINA alınır
        // (aynı sayfa iki kez eklenmez).
        return try {
            val ctx = ArrayList<String>()
            val seenPages = HashSet<Int>()
            if (focusPage in pages.indices && pages[focusPage].isNotBlank()) {
                seenPages.add(focusPage)
                ctx.add(
                    "Sayfa ${focusPage + 1} (kullanıcının o an açık olduğu sayfa): " +
                        pages[focusPage].take(2500)
                )
            }
            TextUtil.contextPages(pages, question, 8, 6000).forEach { entry ->
                val n = entry.substringAfter("Sayfa ", "")
                    .substringBefore(':', "")
                    .toIntOrNull()
                if (n == null || seenPages.add(n - 1)) ctx.add(entry)
            }
            ApiClient.ask(question, ctx)
        } catch (e: Exception) {
            "Bağlantı hatası: ${e.message}\n\nOffline aramaya düşüyorum:\n\n" +
                runCatching {
                    TextUtil.search(pages, question, 3)
                        .joinToString("\n\n") { "Sayfa ${it.page + 1}: ${it.sentence.take(250)}" }
                }.getOrDefault("Çevrimdışı sonuç da yok.")
        }
    }

    private fun addMsg(fromUser: Boolean, text: String): Msg {
        val m = Msg(fromUser, text)
        messages.add(m)
        adapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()
        return m
    }

    private fun scrollToBottom() {
        binding.messageList.post {
            if (messages.isNotEmpty()) {
                binding.messageList.smoothScrollToPosition(messages.size - 1)
            }
        }
    }

    // ── Balon listesi ────────────────────────────────────────

    class MsgAdapter(private val items: List<Msg>) :
        RecyclerView.Adapter<MsgAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val bubble: TextView = v.findViewById(R.id.bubble)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_message, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val msg = items[position]
            val ctx = holder.itemView.context
            val lp = holder.bubble.layoutParams as LinearLayout.LayoutParams

            if (msg.fromUser) {
                lp.gravity = Gravity.END
                holder.bubble.setBackgroundResource(R.drawable.bg_button_secondary)
                holder.bubble.setTextColor(ctx.getColor(R.color.umay_accent))
            } else {
                lp.gravity = Gravity.START
                holder.bubble.setBackgroundResource(R.drawable.bg_panel)
                holder.bubble.setTextColor(ctx.getColor(R.color.umay_text))
            }
            holder.bubble.layoutParams = lp
            holder.bubble.text = msg.text
        }
    }
}
