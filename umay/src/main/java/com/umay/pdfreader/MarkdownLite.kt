package com.umay.pdfreader

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * v13 (AGENT 22 — A2) — MARKDOWN & LATEX HAFİF RENDERER.
 *
 * Yapay zekâ/makine öğrenmesi PDF'lerinde ve AI çıktılarında yoğun geçen
 * kod blokları (Python/C), kalın/italik vurgular ve matematiksel formüller
 * düz metin olarak değil; RENKLİ/SYNTAX-HIGHLIGHT EDİLMİŞ biçimde gösterilir.
 *
 * Destek:
 *  • ``` dil ... ``` kod blokları → monospace + koyu zemin + turuncu metin
 *  • `satır içi kod` → monospace + zemin
 *  • **kalın**, *italik*, # ## ### başlıklar, - madde işaretleri
 *  • $formül$ / \(formül\) → LaTeX sembolleri Unicode'a çevrilir
 *    (α β γ θ λ π σ ω Δ Σ Ω × ÷ ≤ ≥ ≠ ≈ ∞ ∑ ∫ √ · → …)
 *  • \frac{a}{b} → (a)/(b), \sqrt{x} → √(x)
 *
 * SAF fonksiyonlar (latexToPlain/mdToPlain) birim testlidir (MarkdownLiteTest).
 * render() Android Spannable kullanır (UI katmanı).
 */
object MarkdownLite {

    // ── LaTeX → Unicode sözlüğü (uzun olanlar önce!) ──────────────
    private val LATEX = listOf(
        "\\rightarrow" to "→", "\\leftarrow" to "←", "\\Rightarrow" to "⇒",
        "\\infty" to "∞", "\\approx" to "≈", "\\times" to "×", "\\div" to "÷",
        "\\pm" to "±", "\\cdot" to "·", "\\leq" to "≤", "\\geq" to "≥",
        "\\neq" to "≠", "\\equiv" to "≡", "\\propto" to "∝", "\\forall" to "∀",
        "\\exists" to "∃", "\\notin" to "∉", "\\subset" to "⊂",
        "\\cup" to "∪", "\\cap" to "∩", "\\partial" to "∂", "\\nabla" to "∇",
        "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫", "\\lim" to "lim",
        // ⚠ \\int \\in'ten ÖNCE gelmeli: \\int, \\in ile başlar (prefix tuzağı)
        "\\in" to "∈",
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\epsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ",
        "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ", "\\mu" to "μ",
        "\\nu" to "ν", "\\xi" to "ξ", "\\pi" to "π", "\\rho" to "ρ",
        "\\sigma" to "σ", "\\tau" to "τ", "\\upsilon" to "υ", "\\phi" to "φ",
        "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
        "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ",
        "\\Xi" to "Ξ", "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Phi" to "Φ",
        "\\Psi" to "Ψ", "\\Omega" to "Ω",
        "\\mathbb{R}" to "ℝ", "\\mathbb{N}" to "ℕ", "\\mathbb{Z}" to "ℤ",
        "\\mathbb{Q}" to "ℚ", "\\mathbb{C}" to "ℂ",
        "\\to" to "→", "\\ldots" to "…", "\\dots" to "…", "\\quad" to " ",
        "\\%" to "%", "\\&" to "&", "\\_" to "_"
    )

    /** $...$ veya \(...\) içindeki LaTeX parçasını Unicode'a çevirir. */
    fun latexToPlain(s: String): String {
        var out = s
        // \frac{a}{b} → (a)/(b)  — sade, iç içe olmayan biçim destekli
        out = out.replace(Regex("""\\frac\{([^{}]*)\}\{([^{}]*)\}""")) { m ->
            "(${m.groupValues[1].trim()})/(${m.groupValues[2].trim()})"
        }
        // \sqrt{x} → √(x)
        out = out.replace(Regex("""\\sqrt\{([^{}]*)\}""")) { m -> "√(${m.groupValues[1]})" }
        out = out.replace("\\sqrt", "√")
        // Semboller
        for ((k, v) in LATEX) out = out.replace(k, v)
        // Artık kalan { } ve ^ _ işaretleri (üstel/alt indeks yer tutucuları)
        out = out.replace(Regex("""\^\{?([^{}]*)\}?""")) { m -> sup(m.groupValues[1]) }
        out = out.replace("{", "").replace("}", "")
        return out
    }

    /** Basit üst indeks dönüşümü (x^{2} → x²). Desteklenenler dışında düz metin. */
    private fun sup(s: String): String = when (s.trim()) {
        "0" -> "⁰"; "1" -> "¹"; "2" -> "²"; "3" -> "³"; "4" -> "⁴"
        "5" -> "⁵"; "6" -> "⁶"; "7" -> "⁷"; "8" -> "⁸"; "9" -> "⁹"
        "n" -> "ⁿ"; "x" -> "ˣ"; "i" -> "ⁱ"
        else -> if (s.isBlank()) "" else "^$s"
    }

    /** TTS/dışa aktarma için: Markdown + LaTeX → temiz düz metin. */
    fun mdToPlain(s: String): String {
        var out = s.replace("\r\n", "\n")
        // Kod bloğu işaretçileri kaldır (içerik korunur)
        out = out.replace(Regex("(?m)^```[a-zA-Z0-9+#-]*\\s*$"), "")
        out = out.replace("`", "")
        out = out.replace("**", "").replace("__", "")
        out = out.replace(Regex("(?m)^#{1,6}\\s*"), "")
        out = out.replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
        // LaTeX: $...$ ve \(...\)
        out = out.replace(Regex("""\\\((.+?)\)""")) { m -> latexToPlain(m.groupValues[1]) }
        out = out.replace(Regex("""\$(.+?)\$""")) { m -> latexToPlain(m.groupValues[1]) }
        return out.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    // ── UI RENDER ──────────────────────────────────────────────────

    /**
     * Markdown/LaTeX metnini Spannable'a çevirir (kod blokları renkli,
     * başlıklar büyük+renkli, formüller vurgulu).
     *
     * @param codeColor kod metni rengi (örn. umay_orange)
     * @param codeBg    kod bloğu zemini (örn. umay_panel2)
     * @param headColor başlık/formül vurgu rengi (örn. umay_accent)
     */
    fun render(
        raw: String,
        codeColor: Int,
        codeBg: Int,
        headColor: Int
    ): CharSequence {
        val sb = SpannableStringBuilder()
        if (raw.isBlank()) return sb
        val lines = raw.replace("\r\n", "\n").split("\n")
        var inCode = false
        val mono = TypefaceSpan("monospace")
        for (line in lines) {
            val t = line.trimStart()
            if (t.startsWith("```")) {
                inCode = !inCode
                continue
            }
            val start = sb.length
            if (inCode) {
                sb.append(line).append('\n')
                val end = sb.length
                sb.setSpan(ForegroundColorSpan(codeColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(BackgroundColorSpan(codeBg), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(mono, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }
            // Başlık / madde
            val level = headingLevel(t)
            val body: String = when {
                level > 0 -> t.substring(level).trim()
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> "• " + t.substring(2)
                else -> t
            }
            sb.append(inline(body, headColor))
            sb.append('\n')
            if (level > 0) {
                sb.setSpan(
                    RelativeSizeSpan(if (level == 1) 1.3f else if (level == 2) 1.15f else 1.05f),
                    start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sb.setSpan(
                    StyleSpan(android.graphics.Typeface.BOLD),
                    start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        // Sondaki fazladan boş satırı kırp
        while (sb.isNotEmpty() && sb.last() == '\n') sb.delete(sb.length - 1, sb.length)
        return sb
    }

    private fun headingLevel(line: String): Int = when {
        line.startsWith("### ") -> 3
        line.startsWith("## ") -> 2
        line.startsWith("# ") -> 1
        else -> 0
    }

    /** Satır içi stiller: `kod`, **kalın**, *italik*, $formül$. */
    private fun inline(text: String, headColor: Int): CharSequence {
        val pattern = Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*|(?<!\\*)\\*(?!\\*)([^*]+)\\*(?!\\*)|\\$([^$]+)\\$")
        val sb = SpannableStringBuilder()
        var last = 0
        for (m in pattern.findAll(text)) {
            if (m.range.first > last) {
                val plain = text.substring(last, m.range.first)
                sb.append(latexSafe(plain))
            }
            val kind = m.groupValues
            val inner = when {
                kind[1].isNotEmpty() -> 1
                kind[2].isNotEmpty() -> 2
                kind[3].isNotEmpty() -> 3
                else -> 4
            }
            val content = kind[inner]
            val start = sb.length
            sb.append(
                when (inner) {
                    4 -> latexToPlain(content)
                    else -> content
                }
            )
            when (inner) {
                1 -> {   // `kod` → monospace
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                2 -> {   // **kalın**
                    sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (headColor != 0) sb.setSpan(
                        ForegroundColorSpan(headColor), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                3 -> {   // *italik*
                    sb.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                4 -> {   // $formül$ → vurgulu
                    if (headColor != 0) sb.setSpan(
                        ForegroundColorSpan(headColor), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            last = m.range.last + 1
        }
        if (last < text.length) sb.append(latexSafe(text.substring(last)))
        return sb
    }

    /** Düz metinde de geçerli olabilecek \(..\) kalıntılarını çevir. */
    private fun latexSafe(s: String): String =
        if (s.contains('\\')) latexToPlain(s) else s
}
