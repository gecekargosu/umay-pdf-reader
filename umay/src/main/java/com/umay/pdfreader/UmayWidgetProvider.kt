package com.umay.pdfreader

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Calendar

/**
 * v12 (YENİ ÖZELLİK) — ANA EKRAN WIDGET'ı.
 *
 * Ana ekranda sıradaki dersi ve geri sayımı gösterir:
 *   "Sıradaki: Veri Yapıları / Salı • 14:00 • 47 dk sonra"
 * Ders yoksa "Bugün ders yok 🎉" yazar.
 * Widget'a dokununca Umay açılır (son kitaba döner).
 *
 * GÜNCELLEME NOKTALARI:
 *  - Uygulama açılışında (MainActivity.onResume)
 *  - Ders programı değişince (ScheduleActivity.commitImport / CourseStore)
 *  - Günlük alarm saatinde (ReminderReceiver — aynı receiver çağırır)
 *  - Widget kendisi saat başı kendini yeniler (updatePeriodMillis=1800000).
 */
class UmayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        ctx: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id -> renderOne(ctx, manager, id) }
    }

    /** Tek bir widget hücresini çizer. */
    private fun renderOne(
        ctx: Context,
        manager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(ctx.packageName, R.layout.widget_next_class)
        try {
            val next = nextClassText(ctx)
            views.setTextViewText(R.id.widgetText, next.first)
            views.setTextColor(R.id.widgetText, ctx.getColor(next.second))
        } catch (t: Throwable) {
            BookStore.logError(ctx, "Widget.render", t)
            views.setTextViewText(R.id.widgetText, ctx.getString(R.string.widget_no_class))
        }
        // Dokunma → uygulamayı aç
        val intent = Intent(ctx, MainActivity::class.java)
        val pending = android.app.PendingIntent.getActivity(
            ctx, appWidgetId, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, pending)
        manager.updateAppWidget(appWidgetId, views)
    }

    /**
     * Sıradaki ders bilgisini + kalan dakikayı üretir.
     * @return (metin, vurgu rengi) — ders yakınsa turuncu, uzaksa turkuaz.
     */
    private fun nextClassText(ctx: Context): Pair<String, Int> {
        val slots = CourseStore.slotsOf(ctx)  // tüm program satırları
        if (slots.isEmpty()) return ctx.getString(R.string.widget_no_class) to
            ctx.getColor(R.color.umay_text_dim)

        val now = Calendar.getInstance()
        val nowDay = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7   // Pzt=0
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        // Bugünün kalan dersleri → en yakını; yoksa haftanın geri kalanı
        fun candidates(day: Int, afterMinute: Int) = slots
            .filter { it.day == day && it.minuteOfDay > afterMinute }
            .sortedBy { it.minuteOfDay }

        var best: ScheduleSlot? = null
        var minutesLeft = 0

        candidates(nowDay, nowMin).firstOrNull()?.let { s ->
            best = s; minutesLeft = s.minuteOfDay - nowMin
        }
        if (best == null) {
            // Önümüzdeki 7 gün içinde tara
            for (d in 1..7) {
                val day = (nowDay + d) % 7
                val c = candidates(day, -1).firstOrNull() ?: continue
                val minsTodayLeft = 24 * 60 - nowMin
                best = c
                minutesLeft = minsTodayLeft + d * 24 * 60 + c.minuteOfDay
                break
            }
        }
        val s = best ?: return ctx.getString(R.string.widget_no_class) to
            ctx.getColor(R.color.umay_text_dim)

        val hh = s.minuteOfDay / 60
        val mm = s.minuteOfDay % 60
        val timeStr = "%02d:%02d".format(hh, mm)
        val dayNames = arrayOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
        val text = ctx.getString(
            R.string.widget_next,
            s.courseName.ifBlank { s.room },
            "${dayNames[s.day]} $timeStr",
            minutesLeft
        )
        // 60 dk içindeyse turuncu (yaklaşıyor), değilse turkuaz
        val color = if (minutesLeft <= 60) R.color.umay_orange else R.color.umay_accent
        return text to color
    }

    companion object {
        /** Widget hücrelerini anında yenile (program değişince çağrılır). */
        fun refreshAll(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, UmayWidgetProvider::class.java))
                if (ids.isEmpty()) return
                val intent = Intent(ctx, UmayWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                ctx.sendBroadcast(intent)
            } catch (t: Throwable) {
                BookStore.logError(ctx, "Widget.refreshAll", t)
            }
        }
    }
}
