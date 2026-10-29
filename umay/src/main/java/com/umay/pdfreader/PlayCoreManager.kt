package com.umay.pdfreader

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * v6 — AŞAMA 6.1: Google Play Core API'leri.
 *
 *  - **In-App Review:** 3. oturumda VEYA biten kitap sonrası, sürüm başına
 *    yalnız bir kez 5 yıldız isteme akışı (Play inceleme dialogu).
 *  - **In-App Updates:** esnek (flexible) güncelleme kontrolü — açılışta
 *    bir kez; güncelleme varsa indirme Play tarafından arka planda yapılır.
 *
 * Tüm çağrılar dinleyici tabanlıdır ve hataları yutar: Play Core
 * bulunamazsa/çökersе uygulama normal çalışmaya devam eder.
 */
object PlayCoreManager {

    private const val TAG = "PlayCore"
    const val REQUEST_UPDATE = 7301

    /** Uygulamayı değerlendirmeye yönlendir (sürüm başına bir kez). */
    fun maybeAskReview(activity: Activity, trigger: String) {
        try {
            if (AppPrefs.reviewShownFor(activity, BuildInfo.versionName)) return
            val manager = ReviewManagerFactory.create(activity)
            manager.requestReviewFlow().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    try {
                        manager.launchReviewFlow(activity, task.result)
                        AppPrefs.markReviewShown(activity, BuildInfo.versionName)
                        CrashLog.log(activity, "review_flow shown ($trigger)")
                    } catch (t: Throwable) {
                        CrashLog.record(activity, "launchReviewFlow", t)
                    }
                } else {
                    CrashLog.log(activity, "review_flow failed: ${task.exception?.message}")
                }
            }
        } catch (t: Throwable) {
            // Play Core cihazda yoksa (sideload) sessizce geç
            CrashLog.record(activity, "maybeAskReview", t)
        }
    }

    /**
     * Esnek güncelleme kontrolü. `UPDATE_AVAILABLE` ve izin verilmişse
     * Play güncelleme akışını başlatır; kullanıcı isterse daha sonra
     * günceller (akıcı/ezilmeyen davranış).
     */
    fun checkFlexibleUpdate(activity: Activity) {
        try {
            val manager = AppUpdateManagerFactory.create(activity)
            manager.appUpdateInfo
                .addOnSuccessListener { info ->
                    try {
                        val available = info.updateAvailability() ==
                            UpdateAvailability.UPDATE_AVAILABLE ||
                            info.updateAvailability() ==
                            UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                        if (available && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                            manager.startUpdateFlowForResult(
                                info,
                                activity,
                                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                                REQUEST_UPDATE
                            )
                            CrashLog.log(activity, "update_flow started")
                        }
                    } catch (t: Throwable) {
                        CrashLog.record(activity, "startUpdateFlow", t)
                    }
                }
                .addOnFailureListener { e ->
                    CrashLog.log(activity, "update_check failed: ${e.message}")
                }
        } catch (t: Throwable) {
            CrashLog.record(activity, "checkFlexibleUpdate", t)
        }
    }

    /** Sonraki açılışta Play'den gelen güncelleme sonucu (kullanılmayan ama hazır). */
    fun onUpdateResult(requestCode: Int, resultCode: Int) {
        if (requestCode != REQUEST_UPDATE) return
        Log.d(TAG, "update result code=$resultCode")
    }
}
