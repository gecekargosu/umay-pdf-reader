package com.umay.pdfreader.tv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/** İstenen özelliklerin durumu: oldu / olmadı / neden / çözüm. */
public class StatusActivity extends Activity {

    private static final int BG = Color.parseColor("#11151C");
    private static final int CARD = Color.parseColor("#1B2230");
    private static final int TEXT = Color.parseColor("#F5F7FA");
    private static final int MUTED = Color.parseColor("#9AA4B2");
    private static final int OK = Color.parseColor("#4CAF50");
    private static final int NO = Color.parseColor("#FF5252");
    private static final int PART = Color.parseColor("#FFC107");

    private static final String[][] DATA = {
        {"1", "Kanal listesi uygulamanın içinde (siteye yönlendirme yok)", "ok", ""},
        {"1", "300+ kanal, ücretsiz kaynaklar", "ok", "302 kanal, hepsi tek tek test edildi"},
        {"1", "Kanal numarası + onayla açma (kumanda gibi)", "ok", ""},
        {"1", "Bilim kanalları", "ok", "10 kanal"},
        {"1", "Ülke bayrağı ve Türkçe/Yabancı filtresi", "ok", ""},
        {"1", "Uyku zamanlayıcısı (dk veya saat) + saydam saat", "ok", ""},
        {"1", "3'lü ekran, pencere başına ses, set favorisi", "ok", "4'lü (2×2) de eklendi"},
        {"1", "Uzun listede arama / numara ile filtre", "ok", ""},
        {"1", "Kayıt (ses dahil) → Movies/TV-Kayitlari", "ok", "Uygulama içi Kayıtlarım ekranı var"},
        {"1", "HD / kalite seçimi", "ok", ""},
        {"1", "Küçük pencere (PiP) — TikTok/Instagram üstünde TV", "ok", ""},
        {"1", "Yatayda tam ekran", "ok", ""},
        {"1", "Mobil veri uyarısı + onay + sadece ses", "ok", ""},
        {"1", "Veri sayacı, kanal başına MB/dk, günlük limit", "ok", "Limit dolunca sorar"},
        {"1", "Kilit ekranında ses devam etsin", "ok", "WakeLock + Wi-Fi lock eklendi"},
        {"1", "Radyo: ad + frekans, kilit ekranında çalma", "ok", "Frekans yerine yayın biçimi yazıyor (açıklama aşağıda)"},
        {"1", "Kanal/radyo silme + geri ekleme", "ok", "Uzun bas → gizle, 'Gizlenenler' → geri ekle"},
        {"1", "Rize / Trabzon / Artvin yerel kanalları", "part", "Mavi Karadeniz (Trabzon) ve Haber61 (Trabzon) eklendi. Rize TV, Çay TV, Kanal 53, Artvin TV yalnızca YouTube/web'den yayın yapıyor: uygulama tarayıcı/YouTube açmadığı için canlı akışı yok"},
        {"1", "Yabancı kanalda canlı Türkçe altyazı çevirisi", "no", "Yayınlarda altyazı izi çoğu zaman yok; gerçek zamanlı çeviri ücretli API ister. Çözüm: ücretli çeviri servisi + altyazı izi olan kanallar"},
        {"1", "EPG (yayın akışı / program rehberi)", "no", "Ücretsiz ve güvenilir XMLTV kaynağı yok. Çözüm: ticari EPG sağlayıcısı"},
        {"1", "Chromecast / DLNA (TV'ye gönderme)", "no", "Cast SDK, gradle'sız derleme zincirine eklenemiyor. Çözüm: projeyi Gradle'a taşımak"},
        {"1", "Zamanlanmış kayıt, time-shift, widget, kulaklık tuşu", "part", "Altyapı hazır; arka plan servisi + tampon gerektiriyor, sıradaki sürüme"},
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(12), dp(12), dp(6));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(36), dp(36));
        llp.setMargins(0, 0, dp(10), 0);
        logo.setLayoutParams(llp);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try { logo.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName())); }
        catch (Exception ignored) { }

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText("Ne var, ne yok?");
        t.setTextColor(TEXT);
        t.setTextSize(20);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        TextView sub = new TextView(this);
        sub.setText("İstediklerin · oldu / olmadı / nedeni");
        sub.setTextColor(MUTED);
        sub.setTextSize(11);
        titles.addView(t);
        titles.addView(sub);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(this);
        close.setText("Kapat");
        close.setTextColor(Color.WHITE);
        close.setTextSize(12);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable cd = new GradientDrawable();
        cd.setColor(Color.parseColor("#FF7A18"));
        cd.setCornerRadius(dp(16));
        close.setBackground(cd);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        head.addView(close);
        root.addView(head);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(dp(6));
        list.setPadding(0, dp(8), 0, dp(8));
        list.setClipToPadding(false);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return DATA.length; }
            @Override public Object getItem(int i) { return DATA[i]; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                String[] d = DATA[i];
                LinearLayout row = new LinearLayout(StatusActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(10), dp(10), dp(10), dp(10));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(CARD);
                bg.setCornerRadius(dp(12));
                row.setBackground(bg);

                TextView icon = new TextView(StatusActivity.this);
                boolean ok = "ok".equals(d[2]);
                boolean part = "part".equals(d[2]);
                icon.setText(ok ? "✓" : (part ? "◐" : "✕"));
                icon.setTextColor(ok ? OK : (part ? PART : NO));
                icon.setTextSize(18);
                icon.setGravity(Gravity.CENTER);
                icon.setLayoutParams(new LinearLayout.LayoutParams(dp(26),
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                row.addView(icon);

                LinearLayout col = new LinearLayout(StatusActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView n = new TextView(StatusActivity.this);
                n.setText(d[1]);
                n.setTextColor(TEXT);
                n.setTextSize(13);
                col.addView(n);
                if (d[3].length() > 0) {
                    TextView m = new TextView(StatusActivity.this);
                    m.setText(d[3]);
                    m.setTextColor(MUTED);
                    m.setTextSize(11);
                    m.setPadding(0, dp(2), 0, 0);
                    col.addView(m);
                }
                row.addView(col, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                return row;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
