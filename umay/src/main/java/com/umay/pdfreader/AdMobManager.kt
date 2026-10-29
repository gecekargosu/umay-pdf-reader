package com.umay.pdfreader

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * v6 — AŞAMA 4: AdMob güvenli monetizasyon (strict safe).
 *
 *  - İçerik derecelendirmesi `MAX_AD_CONTENT_RATING_G` olarak **UmayApp**
 *    içinde sabitlenir (yetişkin/kumar/alkol/yanıltıcı reklamlar engellenir).
 *  - Alt sabit **Banner** → ana ekran (attachBanner).
 *  - **Ödüllü Video** → AI günlük kota dolduğunda (AdMobManager.showRewarded);
 *    izleyene `AppPrefs.AI_REWARDED_BONUS` ekstra AI hakkı yazılır.
 *
 * ⚠ TEST ID'LERİ: Google'ın resmi test reklam kimlikleri kullanılıyor.
 *   Yayına geçerken aşağıdaki iki sabit kendi AdMob panelindeki
 *   reklam birimi ID'lerinle değiştirilmelidir (AdMob App ID manifest'te).
 */
object AdMobManager {

    private const val TAG = "AdMobManager"

    // TODO(YAYIN): kendi banner birim kimliğin
    const val BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
    // TODO(YAYIN): kendi ödüllü reklam birim kimliğin
    const val REWARDED_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

    /**
     * v5.2 (ADIM C / B.6 GUARD) — Koruma katmanı:
     *  1) `isTestUnit`: verilen ID Google'ın resmi TEST kimliği mi?
     *     → gerçek ID'ler bağlanınca panel/manifest uyumsuzluğu log'dan anlaşılır.
     *  2) `adsEnabled`: yayın/kota koruma anahtarı (KEY_ADS_ENABLED).
     *     Varsayılan **true** = mevcut davranış birebir korunur; kapatılırsa
     *     banner görünmez, ödüllü gate/ödül ÖDÜLSÜZ ve REKLAMSIZ devam eder
     *     (akış asla kilitlenmez — Google Play politikasıyla uyumlu).
     */
    fun isTestUnit(unitId: String): Boolean =
        unitId.startsWith("ca-app-pub-3940256099942544")

    /**
     * v21.3 — Test birim kimlikleri varken reklamlar TAMAMEN kapalı sayılır:
     * kullanıcı ekranında "Test Reklamı" görünmez, ödüllü kapı kullanıcıyı
     * cezalandırmaz. Gerçek AdMob kimlikleri girilince otomatik açılır.
     */
    fun adsEnabled(ctx: Context): Boolean =
        AppPrefs.getBool(ctx, AppPrefs.KEY_ADS_ENABLED, true) &&
            !isTestUnit(BANNER_UNIT_ID) && !isTestUnit(REWARDED_UNIT_ID)

    /** Kota aşımında kullanıcıya gösterilen standart mesaj (Aşama 2.4). */
    const val LIMIT_MSG =
        "Günlük AI kullanım limitinize ulaşıldı. " +
            "Ödüllü reklam izleyerek veya Puan harcayarak hak kazanabilirsiniz!"

    private var rewarded: RewardedAd? = null
    private var loadingReward = false

    /**
     * Aşama 4.1 — İçerik filtreleme: yalnız G sınıfı (herkes için uygun)
     * reklamlar istenir; çocuk/alt yaş etiketleri de açıkça kapatılır.
     * UmayApp.onCreate'den bir kez çağrılır.
     */
    fun configureRequestConfiguration() {
        try {
            MobileAds.setRequestConfiguration(
                RequestConfiguration.Builder()
                    .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                    .setTagForChildDirectedTreatment(
                        RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE
                    )
                    .setTagForUnderAgeOfConsent(
                        RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_FALSE
                    )
                    .build()
            )
        } catch (t: Throwable) {
            Log.e(TAG, "RequestConfiguration ayarlanamadı", t)
        }
    }

    /** İdempotent başlatma + ilk ödüllü reklam ön yüklemesi. */
    fun initialize(ctx: Context) {
        try {
            MobileAds.initialize(ctx.applicationContext) {
                preloadRewarded(ctx.applicationContext)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "MobileAds.initialize başarısız", t)
        }
    }

    /** Alt sabit banner yerleştirir ve yükler (hata asla çökertmez). */
    fun attachBanner(activity: Activity, container: FrameLayout) {
        // v5.2 GUARD: reklamlar kapalıysa banner hiç eklenmez (alan daralır)
        if (!adsEnabled(activity)) {
            container.removeAllViews()
            container.visibility = android.view.View.GONE
            return
        }
        // v21.3 — Test kimliği varken banner hiç yüklenmez ("Test Reklamı"
        // görünmez). Gerçek birim kimlikleri girilince kendiliğinden açılır.
        if (isTestUnit(BANNER_UNIT_ID)) {
            container.removeAllViews()
            container.visibility = android.view.View.GONE
            return
        }
        try {
            container.removeAllViews()
            val adView = AdView(activity)
            adView.adUnitId = BANNER_UNIT_ID
            adView.setAdSize(AdSize.BANNER)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            container.addView(adView, lp)
            adView.loadAd(AdRequest.Builder().build())
        } catch (t: Throwable) {
            Log.e(TAG, "Banner yüklenemedi", t)
            container.visibility = android.view.View.GONE
        }
    }

    /** Ödülü reklamı ön yükler (kota dolmadan önce hazır dursun). */
    fun preloadRewarded(ctx: Context) {
        if (rewarded != null || loadingReward) return
        // v5.2 GUARD: reklamlar kapalıysa ağ isteği/atık yok
        if (!adsEnabled(ctx)) return
        loadingReward = true
        try {
            RewardedAd.load(
                ctx.applicationContext,
                REWARDED_UNIT_ID,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        loadingReward = false
                        rewarded = ad
                        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                            override fun onAdDismissedFullScreenContent() {
                                rewarded = null
                                preloadRewarded(ctx.applicationContext)
                            }
                        }
                    }

                    override fun onAdFailedToLoad(err: LoadAdError) {
                        loadingReward = false
                        rewarded = null
                        Log.w(TAG, "Ödüllü reklam yüklenemedi: ${err.message}")
                    }
                }
            )
        } catch (t: Throwable) {
            loadingReward = false
            Log.e(TAG, "preloadRewarded hatası", t)
        }
    }

    /**
     * Ödülü reklamı gösterir.
     *  - Hazırsa gösterilir; `onEarned` ödül verildiğinde çağrılır.
     *  - Hazır DEĞİLSE `onUnavailable(LIMIT_MSG)` ile kullanıcıya kota
     *    mesajı gösterilir (uygulama asla kapanmaz — Aşama 2.4).
     */
    fun showRewarded(
        ctx: Context,
        onEarned: () -> Unit,
        onUnavailable: (String) -> Unit
    ) {
        // v5.2 GUARD: reklam kapalıysa kullanıcı cezalandırılmaz → ödül verilir
        if (!adsEnabled(ctx)) { onEarned(); return }
        val activity = ctx as? Activity
        val ad = rewarded
        if (activity == null || ad == null) {
            preloadRewarded(ctx.applicationContext)
            onUnavailable(LIMIT_MSG)
            return
        }
        rewarded = null
        try {
            ad.show(activity) {
                onEarned()
                preloadRewarded(activity.applicationContext)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "showRewarded hatası", t)
            onUnavailable(LIMIT_MSG)
            preloadRewarded(activity.applicationContext)
        }
    }

    /**
     * v9 (M8) — ÖDÜLLÜ VİDEO GATE (3. AI özeti / OCR belge dönüşümü).
     *
     * Akış: hazır rewarded reklam varsa gösterilir; kullanıcı izleyip ödül
     * alsa da almasa da `onFinished` çağrılır (AKIŞ ASLA KİLİTLENMEZ —
     * Google Play politikası dayatmayı hoş karşılamaz). Reklam HAZIR DEĞİLSE
     * yeniden ön yüklenir + bilgilendirme yapılır ve yine `onFinished`
     * çağrılır — kullanıcıyı bekletmek yok.
     *
     * @param reason kullanıcıya gösterilen bağlam metni
     */
    fun showRewardedGate(
        activity: Activity,
        reason: String,
        onFinished: () -> Unit
    ) {
        // v5.2 GUARD: reklamlar kapalıysa gate beklemeden geçilir
        if (!adsEnabled(activity)) { onFinished(); return }
        val ad = rewarded
        if (ad == null) {
            preloadRewarded(activity.applicationContext)
            try {
                android.widget.Toast.makeText(
                    activity,
                    "$reason — reklam şu an hazır değil, devam ediyorsun.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (_: Throwable) {
            }
            onFinished()
            return
        }
        rewarded = null
        try {
            android.app.AlertDialog.Builder(activity)
                .setTitle("Destek ol")
                .setMessage("$reason\n\nKısa bir reklam izle; desteğin için teşekkürler 💛")
                .setPositiveButton("İzle") { _, _ ->
                    try {
                        ad.show(activity) {
                            preloadRewarded(activity.applicationContext)
                            onFinished()
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "showRewardedGate hatası", t)
                        preloadRewarded(activity.applicationContext)
                        onFinished()
                    }
                }
                .setNegativeButton("Şimdilik geç") { _, _ ->
                    preloadRewarded(activity.applicationContext)
                    onFinished()
                }
                .setCancelable(false)
                .show()
        } catch (t: Throwable) {
            // Dialog bile açılamıyorsa akışı ASLA tıkama
            Log.e(TAG, "showRewardedGate dialog hatası", t)
            onFinished()
        }
    }

    /**
     * AI kotası dolduğunda çağrır: ödüllü reklamı gösterir, ödül gelirse
     * kullanıcıya ekstra AI hakkı yazar; reklam yoksa standart kota mesajı
     * gösterilir (Aşama 2.4 — asla çökme).
     */
    fun offerAiBonus(activity: Activity) {
        showRewarded(
            activity,
            onEarned = {
                AppPrefs.aiQuotaAddBonus(activity, AppPrefs.AI_REWARDED_BONUS)
                try {
                    android.widget.Toast.makeText(
                        activity,
                        "İzlediğin için +${AppPrefs.AI_REWARDED_BONUS} AI hakkı kazandın!",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Throwable) {
                }
            },
            onUnavailable = { msg ->
                try {
                    android.widget.Toast.makeText(
                        activity, msg, android.widget.Toast.LENGTH_LONG
                    ).show()
                } catch (_: Throwable) {
                }
            }
        )
    }

}
