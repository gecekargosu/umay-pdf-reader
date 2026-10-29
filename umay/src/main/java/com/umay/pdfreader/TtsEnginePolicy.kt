package com.umay.pdfreader

/**
 * v36 — TTS SAĞLAYICI KATMANI (karar çekirdeği).
 *
 * Neden: sesli okuma kararı `TtsManager` içine dağılmıştı (`edgeMode`,
 * `edgePreferred`, `builtinAllowed`, 403 geri çekilmesi, oturum kilidi…).
 * Bu yüzden "hangi motor, hangi sırayla" sorusu **test edilemiyordu**
 * (bkz. 31-ELESTIRI.md · C1 ve test eksikliği).
 *
 * Burada yalnız karar var; ses üretimi/oynatma yok. Böylece:
 *  • çevrimdışı → yerleşik motor,
 *  • Edge 403/401 → 24 saat Edge kapalı,
 *  • üst üste 2 başarısızlık → oturum boyunca Edge atlanır,
 *  • yerleşik motor kilitlenirse → oturumda Edge'e geri dönülür,
 *  • kullanıcı "mekanik sesi kapat" derse → yalnız Edge,
 * kuralları cihaz olmadan doğrulanabilir.
 *
 * İleride yeni bir motor (ör. yerel nöral TTS) eklenirse `plan()` içine
 * bir adım olarak girer; `ReadService`/`TtsManager` değişmez.
 */
object TtsEnginePolicy {

    enum class Engine { EDGE, BUILTIN }

    /**
     * Karar anındaki durum (hepsi saf veri — testte kolayca kurulur).
     *
     * @param mode üstteki ayar: "edge" (doğal ses) veya "builtin" (cihaz sesi)
     * @param edgeBlockedUntil Edge'in 403/401 sonrası kapatıldığı an (epoch ms)
     * @param now şimdiki zaman (epoch ms)
     * @param online internet var mı
     * @param edgeDisabledSession bu oturumda Edge 2 kez üst üste düştü mü
     * @param builtinStuck yerleşik motor kilitlendi mi (Edge'e geri dönme nedeni)
     * @param builtinMachineAllowed "mekanik sesi kullan" ayarı açık mı
     */
    data class State(
        val mode: String = "edge",
        val edgeBlockedUntil: Long = 0L,
        val now: Long = 0L,
        val online: Boolean = true,
        val edgeDisabledSession: Boolean = false,
        val builtinStuck: Boolean = false,
        val builtinMachineAllowed: Boolean = true
    )

    /** Edge şu an 403/401 nedeniyle kapalı mı? */
    fun edgeBlocked(s: State): Boolean = s.edgeBlockedUntil > s.now

    /** Kullanıcı Edge Neural'ı birincil motor olarak mı istiyor? */
    fun edgePreferred(s: State): Boolean = !edgeBlocked(s) && s.mode == "edge"

    /**
     * Edge bu parça için denenmeli mi?
     * Ayar "edge" ise VEYA yerleşik motor kilitlendiyse (kurtarma) VE
     * ağ var VE Edge ne kalıcı ne oturumluk kapatılmışsa.
     */
    fun edgeUsable(s: State): Boolean {
        if (edgeBlocked(s)) return false
        if (s.edgeDisabledSession) return false
        if (!s.online) return false
        return s.mode == "edge" || s.builtinStuck
    }

    /**
     * Edge başarısız olduğunda yerleşik motora düşülebilir mi?
     * Mekanik ses kapatılmışsa (ve ağ varken) yalnız Edge istenir → düşülmez.
     */
    fun builtinFallbackAllowed(s: State): Boolean = when {
        !edgePreferred(s) -> true      // zaten yerleşik motor seçili
        !s.online -> true              // çevrimdışı: tek yol yerleşik
        else -> s.builtinMachineAllowed
    }

    /**
     * Bir parça için denenecek motor sırası.
     * Boş liste = bu parça okunamaz (çağıran atlar / duraklatır).
     */
    fun plan(s: State): List<Engine> {
        val out = ArrayList<Engine>(2)
        val edge = edgeUsable(s)
        if (edge) out.add(Engine.EDGE)
        if (!edge) {
            if (builtinFallbackAllowed(s)) out.add(Engine.BUILTIN)
        } else if (s.builtinMachineAllowed) {
            // Edge tercihli ama yerleşik ses "mekanik" diye kapatılmamış → yedek
            out.add(Engine.BUILTIN)
        }
        return out
    }

    /**
     * Edge hatasından sonra hangi motor denenmeli?
     * 403/401 → Edge 24 saat kapanır (çağıran `blockedUntil`ı yazar).
     */
    fun afterEdgeFailure(s: State, errorMessage: String?, consecutiveFailures: Int): Engine? {
        val msg = errorMessage ?: ""
        val authBroken = msg.contains("403") || msg.contains("401") || msg.contains("101")
        if (authBroken) {
            // Kalıcı kapanış: çağıran 24 saat yazar; bu parça yerleşik motora gider
            return if (builtinFallbackAllowed(s)) Engine.BUILTIN else null
        }
        if (consecutiveFailures >= 2) {
            // Oturum boyunca Edge atlanır → yerleşik motor
            return if (builtinFallbackAllowed(s)) Engine.BUILTIN else null
        }
        return if (builtinFallbackAllowed(s)) Engine.BUILTIN else null
    }

    /** 403/401 sonrası Edge'in kapatılacağı an (24 saat). */
    fun blockDurationMs(): Long = 24L * 60L * 60L * 1000L
}
