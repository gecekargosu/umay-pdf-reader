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
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var bookId: String

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

        adapter = MsgAdapter(messages)
        binding.messageList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.messageList.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSend.setOnClickListener { send() }

        updateModeChip()

        // Sohbet edilecek PDF'in metni (kısmen çıkmış olabilir)
        pages = BookStore.allPages(this, bookId)

        addMsg(false, getString(R.string.sohbet_hazir))
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
        binding.chatHint.text = getString(
            if (online) R.string.sohbet_online else R.string.sohbet_offline_ipucu
        )
    }

    private fun send() {
        val q = binding.messageInput.text.toString().trim()
        if (q.isEmpty()) return
        binding.messageInput.setText("")

        addMsg(true, q)
        val placeholder = addMsg(false, "…")

        // Sayfalar çıkarılıyor olabilir; tazele
        pages = BookStore.allPages(this, bookId)

        pendingJob = scope.launch {
            val answer = withContext(Dispatchers.IO) { answerFor(q) }
            placeholder.text = answer
            adapter.notifyItemChanged(messages.size - 1)
            scrollToBottom()
        }
    }

    private fun answerFor(question: String): String {
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

        // Online sohbet — PDF bağlamıyla
        return try {
            val contextHits = TextUtil.search(pages, question, 6)
                .map { "Sayfa ${it.page + 1}: ${it.sentence.take(400)}" }
            if (contextHits.isEmpty()) {
                // Bağlam boşsa ilk sayfalardan birkaç parça ekle
                val fallback = pages.take(3)
                    .mapIndexed { i, t -> "Sayfa ${i + 1}: ${t.take(400)}" }
                ApiClient.ask(question, fallback)
            } else {
                ApiClient.ask(question, contextHits)
            }
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
