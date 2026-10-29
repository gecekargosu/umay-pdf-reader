package com.umay.pdfreader

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
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

    // ── v5.10 (F2) — konu bazlı kalıcı geçmiş ────────────────
    /** Açık konuşmanın kimliği; null ise ilk mesajda tespit edilir. */
    private var topicId: String? = null
    private var topicTitle: String = ""
    /** Kitabın adı — konu tespitinde ve başlıkta kullanılır. */
    private var bookTitle: String = ""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingJob: Job? = null

    data class Msg(val fromUser: Boolean, var text: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)  // SDK 36 edge-to-edge

        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run { finish(); return }
        focusPage = intent.getIntExtra(EXTRA_PAGE, -1)   // ADIM 3
        bookTitle = runCatching {
            BookStore.loadBooks(this).firstOrNull { it.id == bookId }?.title ?: ""
        }.getOrDefault("")

        adapter = MsgAdapter(messages) { linkifyPages(it) }
        binding.messageList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.messageList.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSend.setOnClickListener { send() }
        // v5.10 (F2): geçmiş — konu listesi, konuya dönüş, silme
        binding.btnHistory.setOnClickListener { showHistory() }

        updateModeChip()

        // Bu kitapta daha önce konuşulmuşsa kaldığı yerden devam et.
        val previous = runCatching { ChatHistoryStore.latestForBook(this, bookId) }.getOrNull()
        if (previous != null && previous.messages.isNotEmpty()) {
            openTopic(previous)
        } else {
            addMsg(false, getString(R.string.sohbet_hazir))
        }
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
        // v5.11: üstteki rozet zaten "AI bağlı" diyor → çevrimiçi iken aynı
        // satırı ipucunda TEKRARLAMIYORUZ (ekran kalabalıklaşmasın).
        val base = if (online) "" else getString(R.string.sohbet_offline_ipucu)
        // ADIM 3: okuma ekranından geldiysek açık sayfayı bağlam olarak bildir
        val pageLine = if (focusPage >= 0) {
            getString(R.string.sohbet_sayfa_baglami, focusPage + 1)
        } else ""
        // v5.10 (F2): açık konu ve kayıtlı soru sayısı görünür olsun.
        val topicLine = if (topicId != null && topicTitle.isNotBlank()) {
            getString(R.string.sohbet_konu, topicTitle, messages.count { it.fromUser })
        } else ""
        val hint = listOf(base, pageLine, topicLine)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        binding.chatHint.text = hint
        binding.chatHint.visibility = if (hint.isBlank()) View.GONE else View.VISIBLE
    }

    // ── v5.10 (F2): geçmiş yönetimi ──────────────────────────

    private fun openTopic(t: ChatHistoryStore.Topic) {
        topicId = t.id
        topicTitle = t.title
        messages.clear()
        t.messages.forEach { messages.add(Msg(it.fromUser, it.text)) }
        adapter.notifyDataSetChanged()
        updateModeChip()
        scrollToBottom()
    }

    private fun startNewChat() {
        topicId = null
        topicTitle = ""
        messages.clear()
        adapter.notifyDataSetChanged()
        addMsg(false, getString(R.string.sohbet_hazir))
        updateModeChip()
    }

    /** Konu listesi: dokun → aç, uzun bas → sil. */
    private fun showHistory() {
        val topics = runCatching { ChatHistoryStore.topics(this) }.getOrDefault(emptyList())
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        lateinit var dlg: AlertDialog

        container.addView(historyRow(
            getString(R.string.sohbet_yeni),
            getString(R.string.sohbet_yeni_aciklama),
            highlight = true
        ) {
            dlg.dismiss()
            startNewChat()
        })

        if (topics.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.sohbet_bos_gecmis)
                setTextColor(getColor(R.color.umay_text_dim))
                textSize = 13f
                setPadding(dp(12), dp(16), dp(12), dp(16))
            })
        } else {
            topics.forEach { t ->
                val row = historyRow(
                    t.title,
                    "${t.messageCount} mesaj · ${t.dateLabel()}" +
                        if (t.preview.isNotBlank()) "\n${t.preview}" else "",
                    highlight = false
                ) {
                    dlg.dismiss()
                    openTopic(t)
                }
                row.setOnLongClickListener {
                    confirmDelete(t, dlg)
                    true
                }
                container.addView(row)
            }
        }

        val scroll = ScrollView(this).apply { addView(container) }
        dlg = AlertDialog.Builder(this)
            .setTitle(R.string.sohbet_gecmis)
            .setView(scroll)
            .setNegativeButton(R.string.kapat, null)
            .create()
        dlg.show()
    }

    private fun historyRow(title: String, subtitle: String, highlight: Boolean, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
            addView(TextView(context).apply {
                text = title
                setTextColor(context.getColor(if (highlight) R.color.umay_orange else R.color.umay_accent))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            if (subtitle.isNotBlank()) {
                addView(TextView(context).apply {
                    text = subtitle
                    setTextColor(context.getColor(R.color.umay_text_dim))
                    textSize = 12f
                    setPadding(0, dp(3), 0, 0)
                })
            }
        }
    }

    private fun confirmDelete(t: ChatHistoryStore.Topic, parent: AlertDialog) {
        AlertDialog.Builder(this)
            .setTitle(R.string.sohbet_sil)
            .setMessage(getString(R.string.sohbet_sil_onay, t.title))
            .setPositiveButton(R.string.sil) { _, _ ->
                runCatching { ChatHistoryStore.delete(this, t.id) }
                if (t.id == topicId) startNewChat()
                parent.dismiss()
                Toast.makeText(this, R.string.sohbet_silindi, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun send() {
        val q = binding.messageInput.text.toString().trim()
        if (q.isEmpty()) return
        binding.messageInput.setText("")

        // v5.10 (F2): konu yoksa ilk sorudan tespit et ve geçmişi geri yükle.
        if (topicId == null) {
            val title = runCatching {
                ChatHistoryStore.detectTitle(q, bookTitle, courseNames())
            }.getOrDefault(ChatHistoryStore.GENERIC)
            val t = runCatching { ChatHistoryStore.startTopic(this, title, bookId) }.getOrNull()
            if (t != null) {
                topicId = t.id
                topicTitle = t.title
                if (t.messages.isNotEmpty()) {
                    // Aynı konu daha önce konuşulmuş: eski balonlar geri gelsin.
                    messages.clear()
                    t.messages.forEach { messages.add(Msg(it.fromUser, it.text)) }
                    adapter.notifyDataSetChanged()
                    addMsg(false, getString(R.string.sohbet_devam, t.title))
                }
                updateModeChip()
            }
        } else {
            // v5.11 — Açık konu varken soru AÇIKÇA BAŞKA BİR DERS hakkındaysa
            // o dersin konuşmasına geç ("ikili arama ağacı" → Veri Yapıları).
            // Genel/serbest sorularda ("peki bu neden böyle?") konu DEĞİŞMEZ.
            val names = courseNames()
            val detected = runCatching {
                ChatHistoryStore.detectTitle(q, bookTitle, names)
            }.getOrNull()
            val courseTitle = detected?.let { ChatHistoryStore.matchCourse(it, names) } ?: ""
            val isCourse = courseTitle.isNotBlank()
            if (isCourse && !courseTitle.equals(topicTitle, true)) {
                val t = runCatching { ChatHistoryStore.startTopic(this, courseTitle, bookId) }
                    .getOrNull()
                if (t != null) {
                    topicId = t.id
                    topicTitle = t.title
                    messages.clear()
                    t.messages.forEach { messages.add(Msg(it.fromUser, it.text)) }
                    adapter.notifyDataSetChanged()
                    addMsg(false, getString(R.string.sohbet_konu_degisti, t.title))
                    updateModeChip()
                }
            }
        }

        addMsg(true, q)
        topicId?.let { id ->
            runCatching { ChatHistoryStore.append(this, id, true, q) }
        }
        val placeholder = addMsg(false, "…")

        pendingJob = scope.launch {
            // Sayfalar çıkarılıyor olabilir; tazele (IO thread'de).
            // v5.1: runCatching — korumasız istisna scope'u patlatıp
            // uygulamayı çökertirdi (boş sayfa / disk okuma hatası vb.)
            val answer = withContext(Dispatchers.IO) {
                runCatching { answerFor(q) }
                    .getOrElse { e ->
                        BookStore.logError(this@ChatActivity, "answerFor", e)
                        "Bir hata oluştu, tekrar dene. (${e.message ?: "bilinmiyor"})"
                    }
            }
            placeholder.text = answer
            adapter.notifyItemChanged(messages.size - 1)
            // v5.10 (F2): cevabı da geçmişe yaz (uygulama kapansa da kalsın).
            topicId?.let { id ->
                runCatching { ChatHistoryStore.append(this@ChatActivity, id, false, answer) }
            }
            updateModeChip()
            scrollToBottom()
        }
    }

    /** Kullanıcının kendi ders adları — konu tespitinde öncelikli. */
    private fun courseNames(): List<String> = runCatching {
        CourseStore.courses(this).map { it.name }
    }.getOrDefault(emptyList())

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
            // v5.11 — çevrimdışı da akıllı arama motorunu kullanır
            // (kelime sayımı değil; nadir kelime + cümle uyumu + sayfa sırası)
            val hits = BookRetriever.passages(pages, question, 4500)
            if (hits.isEmpty()) {
                return "Buna dair kitapta bir şey bulamadım.\n\n" +
                    "İpucu: farklı kelimeler dene ya da \"özet yaz\" yaz.\n" +
                    "Daha akıllı, açıklamalı cevaplar için Ayarlar'dan ücretsiz bir " +
                    "API anahtarı girebilirsin."
            }
            val sb = StringBuilder("🔍 Kitapta buldum (çevrimdışı):\n")
            hits.take(4).forEach { h ->
                sb.append("\n📄 Sayfa ${h.page + 1}: ")
                sb.append(h.text.replace("\n", " ").take(600))
                sb.append("\n")
            }
            return sb.toString().trim()
        }

        // Online sohbet — sayfa bazlı, kısmi eşleşmeli zengin bağlamla.
        // Eski kod: yalnız cümle bazlı eşleşen 6 parça + ilk 3 sayfa →
        // model "bu kitapta geçmiyor" diyordu.
        // ADIM 3: okuma ekranındaki açık sayfa bağlamın EN BAŞINA alınır
        // (aynı sayfa iki kez eklenmez).
        return try {
            // v6 (Aşama 4) — AI günlük kota: doluysa ödüllü reklam + çevrimdışı
            if (!AppPrefs.aiQuotaConsume(this)) {
                runOnUiThread { AdMobManager.offerAiBonus(this) }
                return getString(R.string.ai_limit) + "\n\n" + runCatching {
                    BookRetriever.passages(pages, question, 1800)
                        .joinToString("\n\n") { "Sayfa ${it.page + 1}: ${it.text.take(300)}" }
                }.getOrDefault("Çevrimdışı sonuç da yok.")
            }
            // v5.11 — AKILLI BAĞLAM: cümle düzeyinde seçilmiş, sayfa numaralı
            // parçalar (9.000 karakter). Eski kod tüm sayfaları gönderiyordu.
            val contextText = BookRetriever.withOpenPage(pages, question, focusPage, 9000)
            val ctx = if (contextText.isBlank()) emptyList() else listOf(contextText)
            // v5.11 — KONUŞMA HAFIZASI: "peki bu neden böyle?" devam sorusu
            // çalışsın diye aynı konunun son turları modele gönderilir.
            ApiClient.ask(question, ctx, historyTurns(question))
        } catch (e: Exception) {
            "Bağlantı hatası: ${e.message}\n\nÇevrimdışı aramaya düşüyorum:\n\n" +
                runCatching {
                    BookRetriever.passages(pages, question, 1800)
                        .joinToString("\n\n") { "Sayfa ${it.page + 1}: ${it.text.take(300)}" }
                }.getOrDefault("Çevrimdışı sonuç da yok.")
        }
    }

    /**
     * v5.11 — Bu konunun son turları (modele bağlam).
     * Kaydedilmiş konudan okunur; sohbet ekranındaki karşılama balonu veya
     * "…" yer tutucusu asla modele gitmez. Şu anki soru, geçmişte varsa
     * tekrarlanmasın diye son kullanıcı mesajı çıkarılır.
     */
    private fun historyTurns(question: String): List<ApiClient.Turn> {
        val id = topicId ?: return emptyList()
        val stored = runCatching { ChatHistoryStore.load(this, id) }.getOrNull()
            ?: return emptyList()
        val msgs = stored.messages.toMutableList()
        while (msgs.isNotEmpty() && msgs.last().fromUser &&
            msgs.last().text.trim() == question.trim()
        ) {
            msgs.removeAt(msgs.size - 1)
        }
        return msgs.filter { it.text.isNotBlank() && it.text.trim() != "…" }
            .takeLast(6)
            .map { ApiClient.Turn(it.fromUser, it.text) }
    }

    private fun addMsg(fromUser: Boolean, text: String): Msg {
        val m = Msg(fromUser, text)
        messages.add(m)
        adapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()
        return m
    }

    // ── v35 (A5/F3) — yanıttaki sayfa bağlantısı ────────────
    /**
     * AI yanıtındaki "(s. 42)" / "p. 42" ifadelerindeki sayfa numarasını
     * tıklanabilir yapar; dokununca okuyucu o sayfada açılır.
     * Yalnızca gerçekten var olan sayfalara bağlantı kurar (uydurma numara tıklanmaz).
     */
    private fun linkifyPages(src: CharSequence): CharSequence {
        val sp = if (src is android.text.Spannable) src
        else android.text.SpannableString(src)
        try {
            // v36.1 — eşleme saf `PageRefParser`de (birim testli)
            for (ref in PageRefParser.find(sp, pages.size)) {
                val no = ref.page
                val a = ref.start
                val b = ref.end
                sp.setSpan(
                    object : android.text.style.ClickableSpan() {
                        override fun onClick(w: View) { openPage(no) }
                        override fun updateDrawState(ds: android.text.TextPaint) {
                            ds.color = getColor(R.color.umay_accent)
                            ds.isUnderlineText = true
                        }
                    },
                    a, b, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } catch (t: Throwable) {
            BookStore.logError(this, "linkifyPages", t)
        }
        return sp
    }

    /** Sayfa numarasına (1 tabanlı) giderek okuyucuyu açar. */
    private fun openPage(pageNo: Int) {
        try {
            val total = pages.size
            if (total > 0 && pageNo !in 1..total) {
                Toast.makeText(
                    this,
                    getString(R.string.sohbet_sayfa_yok, pageNo, total),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            startActivity(
                android.content.Intent(this, ReaderActivity::class.java).apply {
                    putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
                    putExtra(ReaderActivity.EXTRA_PAGE, (pageNo - 1).coerceAtLeast(0))
                }
            )
        } catch (t: Throwable) {
            BookStore.logError(this, "openPage", t)
            Toast.makeText(this, getString(R.string.sohbet_sayfa_acilmadi), Toast.LENGTH_SHORT).show()
        }
    }

    private fun scrollToBottom() {
        binding.messageList.post {
            if (messages.isNotEmpty()) {
                binding.messageList.smoothScrollToPosition(messages.size - 1)
            }
        }
    }

    // ── Balon listesi ────────────────────────────────────────

    class MsgAdapter(
        private val items: List<Msg>,
        /** v35: AI balonundaki sayfa bağlantılarını tıklanabilir yapar. */
        private val pageLinks: ((CharSequence) -> CharSequence)? = null
    ) : RecyclerView.Adapter<MsgAdapter.VH>() {

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
                holder.bubble.text = msg.text
            } else {
                lp.gravity = Gravity.START
                holder.bubble.setBackgroundResource(R.drawable.bg_panel)
                holder.bubble.setTextColor(ctx.getColor(R.color.umay_text))
                // v13 (AGENT 22 — A2): AI yanıtları Markdown/LaTeX olarak render
                // (kod blokları renkli/monospace, formüller Unicode sembollerle)
                val rendered: CharSequence = try {
                    MarkdownLite.render(
                        msg.text,
                        ctx.getColor(R.color.umay_orange),
                        ctx.getColor(R.color.umay_panel2),
                        ctx.getColor(R.color.umay_accent)
                    )
                } catch (_: Throwable) {
                    msg.text
                }
                // v35 (A5): "(s. 42)" → dokunulunca o sayfayı aç
                holder.bubble.text = try {
                    val withLinks = pageLinks?.invoke(rendered) ?: rendered
                    holder.bubble.movementMethod =
                        android.text.method.LinkMovementMethod.getInstance()
                    withLinks
                } catch (t: Throwable) {
                    BookStore.logError(ctx, "chat/pageLinks", t)
                    rendered
                }
            }
            holder.bubble.layoutParams = lp
        }
    }
}
