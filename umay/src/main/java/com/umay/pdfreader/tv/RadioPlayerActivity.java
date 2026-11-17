package com.umay.pdfreader.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Radyo calmа ekrani: analog radyo goruntusu, istasyon adi, animasyonlu ekolayzir ve bant ayarlari. */
public class RadioPlayerActivity extends Activity {

    private static final int BG = Color.parseColor("#11151C");
    private static final int CARD = Color.parseColor("#1B2230");
    private static final int ORANGE = Color.parseColor("#FF7A18");
    private static final int NAVY = Color.parseColor("#1F3A93");
    private static final int TEXT = Color.parseColor("#F5F7FA");
    private static final int MUTED = Color.parseColor("#9AA4B2");

    private static List<String> names, metas, urls;
    private static boolean loaded = false;

    private int index = 0;
    private TextView nameView, metaView, statusView;
    private EqView eqView;
    private final Handler ui = new Handler();
    private boolean playing = true;

    public static void open(Context ctx, int i) {
        android.content.Intent it = new android.content.Intent(ctx, RadioPlayerActivity.class);
        it.putExtra("index", i);
        ctx.startActivity(it);
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        names = new java.util.ArrayList<String>();
        metas = new java.util.ArrayList<String>();
        urls = new java.util.ArrayList<String>();
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    getAssets().open("radios.m3u"), "UTF-8"));
            String line, info = null;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) continue;
                if (line.startsWith("#EXTINF")) {
                    info = line.substring(line.indexOf(',') + 1).trim();
                } else if (!line.startsWith("#") && info != null) {
                    String[] p = info.split("\\|");
                    names.add(p.length > 0 ? p[0].trim() : "Radyo");
                    metas.add(p.length > 1 ? p[1].trim() + (p.length > 2 ? " · " + p[2].trim() : "") : "internet");
                    urls.add(line);
                    info = null;
                }
            }
            r.close();
        } catch (Exception ignored) { }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        load();
        index = Math.max(0, Math.min(names.size() - 1, getIntent().getIntExtra("index", 0)));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(16));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView close = chip("‹ Liste");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        head.addView(close);
        TextView home = chip("🏠");
        home.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        head.addView(home);
        TextView title = new TextView(this);
        title.setText("UMAY RADYO");
        title.setTextColor(ORANGE);
        title.setTextSize(13);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView stopBtn = chip("⏹");
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                AudioService.stop(RadioPlayerActivity.this);
                statusView.setText("durduruldu");
                playing = false;
            }
        });
        head.addView(stopBtn);
        root.addView(head);

        RadioArt art = new RadioArt(this);
        root.addView(art, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(210)));

        nameView = new TextView(this);
        nameView.setTextColor(TEXT);
        nameView.setTextSize(22);
        nameView.setGravity(Gravity.CENTER);
        nameView.setTypeface(nameView.getTypeface(), android.graphics.Typeface.BOLD);
        nameView.setPadding(0, dp(8), 0, 0);
        root.addView(nameView);

        metaView = new TextView(this);
        metaView.setTextColor(MUTED);
        metaView.setTextSize(12);
        metaView.setGravity(Gravity.CENTER);
        root.addView(metaView);

        statusView = new TextView(this);
        statusView.setTextColor(ORANGE);
        statusView.setTextSize(11);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(2), 0, dp(6));
        root.addView(statusView);

        eqView = new EqView(this);
        root.addView(eqView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        // --- bant ayarlari (gercek Equalizer) ---
        TextView eqTitle = new TextView(this);
        eqTitle.setText("Ekolayzır ayarları");
        eqTitle.setTextColor(TEXT);
        eqTitle.setTextSize(13);
        eqTitle.setPadding(0, dp(10), 0, dp(4));
        root.addView(eqTitle);

        LinearLayout bands = new LinearLayout(this);
        bands.setOrientation(LinearLayout.HORIZONTAL);
        bands.setGravity(Gravity.CENTER);
        root.addView(bands, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));

        final int bandCount = 5;
        final SeekBar[] bars = new SeekBar[bandCount];
        final TextView[] labels = new TextView[bandCount];
        int[] range = AudioService.eqRange();
        short[] freqs = AudioService.eqFreqs();
        short[] cur = AudioService.eqLevels();
        for (int i = 0; i < bandCount; i++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView f = new TextView(this);
            int hz = (freqs != null && i < freqs.length) ? freqs[i] : (60 + i * 500);
            f.setText(hz >= 1000 ? (hz / 1000) + "k" : String.valueOf(hz));
            f.setTextColor(MUTED);
            f.setTextSize(10);
            f.setGravity(Gravity.CENTER);
            labels[i] = f;
            col.addView(f);

            SeekBar sb = new SeekBar(this);
            sb.setMax(range[1] - range[0]);
            int level = (cur != null && i < cur.length) ? cur[i] : 0;
            sb.setProgress(level - range[0]);
            sb.setRotation(270);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    if (!fromUser) return;
                    int band = indexOf(s, bars);
                    if (band >= 0) AudioService.eqSet(band, (short) (p + AudioService.eqRange()[0]));
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(46), dp(120));
            col.addView(sb, slp);
            bars[i] = sb;
            bands.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        TextView presets = new TextView(this);
        presets.setText("Hazır ayarlar:  Konuşma · Müzik · Bas · Düz");
        presets.setTextColor(MUTED);
        presets.setTextSize(10);
        presets.setGravity(Gravity.CENTER);
        presets.setPadding(0, dp(4), 0, dp(10));
        root.addView(presets);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        TextView speech = chip("🎙 Konuşma");
        speech.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyPreset(bars, "speech"); }
        });
        TextView music = chip("🎵 Müzik");
        music.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyPreset(bars, "music"); }
        });
        TextView bass = chip("🔊 Bas");
        bass.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyPreset(bars, "bass"); }
        });
        TextView flat = chip("➖ Düz");
        flat.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyPreset(bars, "flat"); }
        });
        row.addView(speech); row.addView(music); row.addView(bass); row.addView(flat);
        root.addView(row);

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(0, dp(14), 0, 0);
        TextView prev = bigButton("⏮");
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { changeStation(-1); }
        });
        TextView play = bigButton("⏸");
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                AudioService.toggleStatic();
                playing = !playing;
                ((TextView) v).setText(playing ? "⏸" : "▶");
            }
        });
        TextView next = bigButton("⏭");
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { changeStation(1); }
        });
        nav.addView(prev); nav.addView(play); nav.addView(next);
        root.addView(nav);

        scroll.addView(root);
        setContentView(scroll);

        show();
        ui.post(anim);
    }

    private int indexOf(SeekBar s, SeekBar[] arr) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == s) return i;
        return -1;
    }

    private void applyPreset(SeekBar[] bars, String kind) {
        int[] range = AudioService.eqRange();
        short[] target;
        if ("speech".equals(kind)) target = new short[]{-300, 300, 500, 300, -200};
        else if ("music".equals(kind)) target = new short[]{200, 100, 0, 150, 250};
        else if ("bass".equals(kind)) target = new short[]{600, 400, 100, 0, -100};
        else target = new short[]{0, 0, 0, 0, 0};
        for (int i = 0; i < bars.length && i < target.length; i++) {
            short lv = target[i];
            if (lv < range[0]) lv = (short) range[0];
            if (lv > range[1]) lv = (short) range[1];
            bars[i].setProgress(lv - range[0]);
            AudioService.eqSet(i, lv);
        }
        Toast.makeText(this, "Ekolayzır ayarı uygulandı", Toast.LENGTH_SHORT).show();
    }

    private void changeStation(int d) {
        if (names.isEmpty()) return;
        index = (index + d + names.size()) % names.size();
        show();
    }

    private void show() {
        nameView.setText(names.get(index));
        metaView.setText(metas.get(index));
        statusView.setText("bağlanıyor…");
        AudioService.start(this, urls.get(index), names.get(index));
        playing = true;
    }

    private final Runnable anim = new Runnable() {
        @Override public void run() {
            if (eqView != null) eqView.invalidate();
            if (index >= 0 && index < names.size() && !AudioService.isPlayingStatic()) {
                statusView.setText("yayın bekleniyor… (internet zayıf olabilir)");
            } else {
                statusView.setText("çalıyor · ekolayzır aktif");
            }
            ui.postDelayed(this, 120);
        }
    };

    // ---------- animasyonlu ekolayzir ----------
    private static class EqView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] h = new float[22];
        private final float[] v = new float[22];
        EqView(Context c) {
            super(c);
            for (int i = 0; i < h.length; i++) { h[i] = (float) Math.random(); v[i] = 0.02f; }
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), ht = getHeight();
            int n = h.length;
            float bw = w / (n * 1.8f);
            for (int i = 0; i < n; i++) {
                float target = 0.15f + (float) Math.abs(Math.sin(System.currentTimeMillis() / 380.0 + i * 0.7)) * 0.8f;
                v[i] += (target - v[i]) * 0.15f;
                float bh = ht * 0.9f * v[i];
                float x = i * (w / n) + bw / 2;
                p.setColor(i % 3 == 0 ? Color.parseColor("#FF7A18")
                        : (i % 3 == 1 ? Color.parseColor("#1F3A93") : Color.parseColor("#F5F7FA")));
                p.setAlpha(230);
                RectF r = new RectF(x, ht - bh, x + bw, ht);
                canvas.drawRoundRect(r, bw / 2, bw / 2, p);
            }
        }
    }

    // ---------- analog radyo cizimi ----------
    private static class RadioArt extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        RadioArt(Context c) { super(c); }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float m = w * 0.04f;
            p.setColor(BG);
            canvas.drawRect(0, 0, w, h, p);
            RectF body = new RectF(m, h * 0.06f, w - m, h * 0.96f);
            p.setColor(Color.parseColor("#1B2230"));
            canvas.drawRoundRect(body, 26, 26, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3);
            p.setColor(NAVY);
            canvas.drawRoundRect(body, 26, 26, p);
            p.setStyle(Paint.Style.FILL);

            // hoparlor izgarasi
            float gx = body.left + body.width() * 0.07f, gy = body.top + body.height() * 0.18f;
            p.setColor(Color.parseColor("#2A3444"));
            for (int r = 0; r < 6; r++)
                for (int c = 0; c < 5; c++)
                    canvas.drawCircle(gx + c * 13, gy + r * 13, 3.4f, p);

            // kadran
            RectF dial = new RectF(body.left + body.width() * 0.34f, body.top + body.height() * 0.16f,
                    body.right - body.width() * 0.08f, body.top + body.height() * 0.46f);
            p.setColor(Color.parseColor("#E8EDF5"));
            canvas.drawRoundRect(dial, 10, 10, p);
            p.setColor(Color.parseColor("#7A879B"));
            for (int i = 0; i <= 20; i++) {
                float x = dial.left + dial.width() * i / 20f;
                float len = (i % 5 == 0) ? 13 : 7;
                canvas.drawLine(x, dial.top + 8, x, dial.top + 8 + len, p);
            }
            p.setColor(Color.parseColor("#1F3A93"));
            p.setStrokeWidth(3);
            canvas.drawLine(dial.left + dial.width() * 0.62f, dial.top + 4,
                    dial.left + dial.width() * 0.62f, dial.bottom - 6, p);
            p.setStrokeWidth(1);

            // iki dugme
            p.setColor(ORANGE);
            canvas.drawCircle(body.left + body.width() * 0.18f, body.bottom - body.height() * 0.22f, 20, p);
            p.setColor(NAVY);
            canvas.drawCircle(body.left + body.width() * 0.40f, body.bottom - body.height() * 0.22f, 20, p);

            // marka
            p.setColor(Color.parseColor("#F5F7FA"));
            p.setTextSize(20);
            p.setFakeBoldText(true);
            canvas.drawText("UMAY RADYO", body.left + body.width() * 0.34f,
                    body.bottom - body.height() * 0.10f, p);
        }
    }

    private TextView chip(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(TEXT);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor("#2A3444"));
        d.setCornerRadius(dp(16));
        t.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView bigButton(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setColor(ORANGE);
        d.setCornerRadius(dp(26));
        t.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(70), dp(52));
        lp.setMargins(dp(6), 0, dp(6), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
