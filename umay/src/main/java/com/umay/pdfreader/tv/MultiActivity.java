package com.umay.pdfreader.tv;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 1-3 pencere canli TV. Her pencerenin sesi ayri acilip kapanabilir, istenen pencere kaydedilebilir. */
public class MultiActivity extends android.app.Activity {

    private static final int ACCENT = Color.parseColor("#FF7A18");
    private static final int TEXT = Color.parseColor("#FFFFFF");
    private static final int PANEL = Color.parseColor("#CC101319");

    private List<Channel> list;
    private final Slot[] slots = new Slot[4];
    private final boolean[] audible = {true, false, false, false};
    private int paneCount = 3;
    private boolean tracking = false;
    private long openedAt = 0;
    private LinearLayout panelRef;
    private boolean screenOffAudio = false;
    private boolean controlsVisible = true;
    private TextView dataRef;
    private LinearLayout col;
    private TextView clock;
    private HlsRecorder recorder = new HlsRecorder();
    private int recSlot = 1;
    private final Handler ui = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        list = ChannelRepo.all(this);

        int start = getIntent().getIntExtra("start", -1);
        String saved = getIntent().getStringExtra("set");
        if (saved == null || saved.length() == 0) saved = Prefs.multiLast(this);

        int[] idx = new int[]{start >= 0 ? start : 0, 1, 2, 3};
        boolean[] aud = new boolean[]{true, false, false, false};
        int cnt = 3;
        if (saved != null && saved.contains("|") && !saved.startsWith("ad:")) {
            try {
                String[] p = saved.replace("ad:", "").split("\\|");
                String[] parts = p[1].split(",");
                for (int i = 0; i < 4 && i < parts.length; i++) idx[i] = Integer.parseInt(parts[i].trim());
                String mask = p[2];
                for (int i = 0; i < 4 && i < mask.length(); i++) aud[i] = mask.charAt(i) == '1';
                cnt = Integer.parseInt(p[3].trim());
            } catch (Exception ignored) { }
        }
        for (int i = 0; i < 4; i++) audible[i] = aud[i];
        paneCount = Math.max(1, Math.min(4, cnt));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 4; i++) slots[i] = new Slot(i);
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- kontrol paneli ---
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(8), dp(6), dp(8), dp(6));
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(PANEL);
        pb.setCornerRadius(dp(14));
        panel.setBackground(pb);
        panelRef = panel;
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.BOTTOM;
        panelLp.setMargins(dp(8), 0, dp(8), dp(10));
        root.addView(panel, panelLp);

        TextView home = chip("🏠");
        home.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        panel.addView(home);

        for (int i = 1; i <= 4; i++) {
            final int n = i;
            TextView b = chip(i == 4 ? "⊞4" : String.valueOf(i) + "□");
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setPaneCount(n); }
            });
            panel.addView(b);
        }

        final EditText recNo = new EditText(this);
        recNo.setHint("no");
        recNo.setText(String.valueOf(recSlot));
        recNo.setInputType(InputType.TYPE_CLASS_NUMBER);
        recNo.setTextColor(TEXT);
        recNo.setHintTextColor(Color.parseColor("#8890A0"));
        recNo.setTextSize(12);
        recNo.setWidth(dp(46));
        recNo.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                try { recSlot = Math.max(1, Math.min(4, Integer.parseInt(s.toString().trim()))); }
                catch (Exception ignored) { }
            }
            public void afterTextChanged(Editable s) { }
        });
        panel.addView(recNo);

        final TextView rec = chip("⏺ kayıt");
        rec.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleRecord((TextView) v, recSlot); }
        });
        panel.addView(rec);

        TextView saveSet = chip("★ set");
        saveSet.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveSetDialog(); }
        });
        panel.addView(saveSet);

        dataRef = new TextView(this);
        dataRef.setTextColor(Color.parseColor("#CCFFFFFF"));
        dataRef.setTextSize(10);
        dataRef.setPadding(dp(4), 0, dp(4), 0);
        panel.addView(dataRef);

        clock = new TextView(this);
        clock.setTextColor(Color.parseColor("#CCFFFFFF"));
        clock.setTextSize(12);
        clock.setPadding(dp(6), 0, dp(4), 0);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        panel.addView(clock, clp);

        setContentView(root);

        for (int i = 0; i < 4; i++) slots[i].setChannel(idx[i] % list.size());
        applyAudio();
        setPaneCount(paneCount);
        tracking = true;
        openedAt = System.currentTimeMillis();
        Net.begin();
        ui.post(tick);
        hideControlsSoon();
        if (Net.mobile(this) && !Prefs.mobileOk(this)) {
            new AlertDialog.Builder(this).setTitle("Mobil veri uyarısı")
                    .setMessage("Wi-Fi yok. Çoklu ekran mobil veride hızlı tüketir.\n\nBugün: "
                            + Net.mb(Prefs.todayBytes(this)) + "\n\nDevam edilsin mi?")
                    .setPositiveButton("Devam", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            Prefs.setMobileOk(MultiActivity.this, true);
                        }
                    })
                    .setNegativeButton("1. ekran sadece ses", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            Prefs.setMobileOk(MultiActivity.this, true);
                            for (int i = 0; i < 4; i++) audible[i] = (i == 0);
                            applyAudio();
                        }
                    })
                    .show();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clock.setText(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(new java.util.Date()));
            if (dataRef != null) dataRef.setText("📶 " + Net.mb(Prefs.todayBytes(MultiActivity.this)
                    + Net.sessionSoFar()));
            ui.postDelayed(this, 1000);
        }
    };

    /** Ekrana dokununca kontrol paneli alttan gelir, 4 sniye sonra kendini gizler. */
    private void toggleControls() {
        controlsVisible = !controlsVisible;
        applyControls();
        if (controlsVisible) hideControlsSoon();
    }

    private void applyControls() {
        if (panelRef != null)
            panelRef.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
        for (Slot s : slots) if (s != null)
            s.speaker.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
    }

    private final Runnable hideControls = new Runnable() {
        @Override public void run() {
            controlsVisible = false;
            applyControls();
        }
    };

    private void hideControlsSoon() {
        ui.removeCallbacks(hideControls);
        ui.postDelayed(hideControls, 4500);
    }

    private void setPaneCount(int n) {
        paneCount = n;
        for (int i = 0; i < 4; i++) {
            ViewGroup par = (ViewGroup) slots[i].getParent();
            if (par != null) par.removeView(slots[i]);
        }
        col.removeAllViews();
        if (n == 4) {
            LinearLayout r1 = new LinearLayout(this);
            LinearLayout r2 = new LinearLayout(this);
            r1.setOrientation(LinearLayout.HORIZONTAL);
            r2.setOrientation(LinearLayout.HORIZONTAL);
            r1.addView(slots[0], new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            r1.addView(slots[1], new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            r2.addView(slots[2], new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            r2.addView(slots[3], new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            col.addView(r1, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            col.addView(r2, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            for (int i = 0; i < n; i++) {
                col.addView(slots[i], new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            }
        }
        saveState();
    }

    private void saveState() {
        StringBuilder b = new StringBuilder("__last__|");
        for (int i = 0; i < 4; i++) b.append(slots[i].chan).append(i < 3 ? "," : "");
        b.append("|");
        for (int i = 0; i < 4; i++) b.append(audible[i] ? "1" : "0");
        b.append("|").append(paneCount);
        Prefs.setMultiLast(this, b.toString());
    }

    private void applyAudio() {
        for (int i = 0; i < 4; i++) slots[i].setAudible(audible[i]);
    }

    private void toggleRecord(final TextView btn, int slotNo) {
        if (recorder.isRunning()) {
            recorder.stop();
            btn.setText("⏺ kayıt");
            return;
        }
        int s = Math.max(0, Math.min(3, slotNo - 1));
        Channel c = list.get(slots[s].chan);
        btn.setText("⏹ durdur");
        recorder.start(this, c.url, "ekran" + (s + 1) + "_" + c.name, new HlsRecorder.Listener() {
            @Override public void onInfo(String msg) { }
            @Override public void onError(final String msg) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        btn.setText("⏺ kayıt");
                        Toast.makeText(MultiActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
            @Override public void onStopped(final String path, final long bytes, final boolean sesli) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        btn.setText("⏺ kayıt");
                        Toast.makeText(MultiActivity.this, "Kayıt bitti: " + (bytes / 1048576)
                                + " MB (ses dahil) → Movies/TV-Kayitlari", Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
        Toast.makeText(this, (s + 1) + ". ekran kaydediliyor (ses dahil)", Toast.LENGTH_SHORT).show();
    }

    private void saveSetDialog() {
        final EditText et = new EditText(this);
        et.setHint("Set adı (örn: Spor + Haber)");
        new AlertDialog.Builder(this).setTitle("Bu seti favori yap").setView(et)
                .setPositiveButton("Kaydet", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (name.length() == 0) name = "Set";
                        StringBuilder b = new StringBuilder("ad:" + name + "|");
                        for (int i = 0; i < 4; i++) b.append(slots[i].chan).append(i < 3 ? "," : "");
                        b.append("|");
                        for (int i = 0; i < 4; i++) b.append(audible[i] ? "1" : "0");
                        b.append("|").append(paneCount);
                        Prefs.saveMultiSet(MultiActivity.this, b.toString());
                        Toast.makeText(MultiActivity.this, "Kaydedildi: " + name, Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton("Vazgeç", null).show();
    }

    private class Slot extends FrameLayout implements SurfaceHolder.Callback {

        final SurfaceView surface;
        final TextView label, speaker;
        MediaPlayer mp;
        int chan = 0;
        boolean surfaceReady = false, on = false;
        final int slotIndex;

        Slot(int slotIndex) {
            super(MultiActivity.this);
            this.slotIndex = slotIndex;

            surface = new SurfaceView(MultiActivity.this);
            addView(surface, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            surface.getHolder().addCallback(this);

            label = new TextView(MultiActivity.this);
            label.setTextColor(TEXT);
            label.setTextSize(12);
            label.setPadding(dp(42), dp(5), dp(8), dp(5));
            label.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Color.parseColor("#AA000000"), Color.parseColor("#00000000")}));
            label.setVisibility(View.GONE);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP;
            addView(label, lp);

            speaker = new TextView(MultiActivity.this);
            speaker.setText("🔊");
            speaker.setTextSize(15);
            speaker.setGravity(Gravity.CENTER);
            GradientDrawable sb = new GradientDrawable();
            sb.setColor(Color.parseColor("#99000000"));
            sb.setCornerRadius(dp(16));
            speaker.setBackground(sb);
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(dp(34), dp(34));
            slp.gravity = Gravity.TOP | Gravity.START;
            slp.setMargins(dp(4), dp(4), 0, 0);
            addView(speaker, slp);
            speaker.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    audible[slotIndex] = !audible[slotIndex];
                    applyAudio();
                    saveState();
                }
            });

            speaker.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    for (int i = 0; i < 4; i++) audible[i] = (i == slotIndex);
                    applyAudio();
                    saveState();
                    Toast.makeText(MultiActivity.this,
                            (slotIndex + 1) + ". ekranın sesi öne alındı", Toast.LENGTH_SHORT).show();
                    return true;
                }
            });
            setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { toggleControls(); }
            });
            setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    pickChannel(Slot.this);
                    return true;
                }
            });
        }

        void setChannel(int i) {
            chan = Math.max(0, Math.min(list.size() - 1, i));
            reload();
        }

        void setAudible(boolean b) {
            on = b;
            try { if (mp != null) mp.setVolume(b ? 1f : 0f, b ? 1f : 0f); } catch (Exception ignored) { }
            speaker.setText(b ? "🔊" : "🔇");
        }

        void reload() {
            release();
            Channel c = list.get(chan);
            label.setVisibility(View.GONE);
            if (!surfaceReady) return;
            try {
                mp = new MediaPlayer();
                mp.setDisplay(surface.getHolder());
                String q = Prefs.quality(MultiActivity.this, c.url);
                mp.setDataSource(q != null ? q : c.url);
                mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override public void onPrepared(MediaPlayer m) {
                        AudioService.stop(MultiActivity.this);
                        screenOffAudio = false;
                        m.setVolume(on ? 1f : 0f, on ? 1f : 0f);
                        m.start();
                    }
                });
                mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override public boolean onError(MediaPlayer m, int w, int e) {
                        label.setText("açılamadı · uzun bas → kanal seç");
                        label.setVisibility(View.VISIBLE);
                        return true;
                    }
                });
                mp.prepareAsync();
            } catch (Exception e) {
                label.setText("açılamadı · №" + c.no + " " + c.name);
            }
        }

        void release() {
            try { if (mp != null) { mp.reset(); mp.release(); mp = null; } } catch (Exception ignored) { }
        }

        @Override public void surfaceCreated(SurfaceHolder h) { surfaceReady = true; reload(); }
        @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
        @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; release(); }
    }

    /** Aranabilir kanal secimi: numara yaz veya isim yaz. */
    private void pickChannel(final Slot slot) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), 0);

        final EditText filter = new EditText(this);
        filter.setHint("Kanal no veya ad yaz...");
        filter.setSingleLine(true);
        box.addView(filter);

        final List<String> rows = new ArrayList<String>();
        final List<Integer> idxx = new ArrayList<Integer>();
        final ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, rows);
        final ListView lv = new ListView(this);
        lv.setAdapter(ad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380));
        box.addView(lv, lp);

        final Runnable fill = new Runnable() {
            @Override public void run() {
                String q = filter.getText().toString().trim().toLowerCase();
                rows.clear(); idxx.clear();
                for (int i = 0; i < list.size(); i++) {
                    Channel c = list.get(i);
                    if (q.length() > 0
                            && !c.name.toLowerCase().contains(q)
                            && !c.group.toLowerCase().contains(q)
                            && !String.valueOf(c.no).equals(q)) continue;
                    rows.add("№" + c.no + "  " + c.flag() + "  " + c.name + "   ·  " + c.group);
                    idxx.add(i);
                }
                ad.notifyDataSetChanged();
            }
        };
        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { fill.run(); }
            public void afterTextChanged(Editable s) { }
        });
        fill.run();

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Kanal seç")
                .setView(box)
                .setNegativeButton("Kapat", null)
                .create();

        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                slot.setChannel(idxx.get(pos));
                saveState();
                dlg.dismiss();
            }
        });

        dlg.show();
    }

    private TextView chip(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(TEXT);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor("#33FFFFFF"));
        d.setCornerRadius(dp(14));
        t.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onPause() {
        super.onPause();
        if (tracking) {
            tracking = false;
            long bytes = Net.end();
            int sec = (int) ((System.currentTimeMillis() - openedAt) / 1000);
            if (sec > 2) {
                long per = bytes / Math.max(1, paneCount);
                for (int i = 0; i < paneCount; i++)
                    Prefs.addUsage(this, list.get(slots[i].chan).url, per, sec);
            }
        }
        // ekran kapaninca / uygulamadan cikinca sesli devam: sesi acik pencerenin akisi servise devredilir
        boolean wasPlaying = false;
        for (Slot s : slots) {
            try { if (s.mp != null && s.mp.isPlaying()) wasPlaying = true; } catch (Exception ignored) { }
        }
        if (wasPlaying) {
            int pick = 0;
            for (int i = 0; i < 4; i++) if (audible[i]) { pick = i; break; }
            Channel c = list.get(slots[pick].chan);
            String q = Prefs.quality(this, c.url);
            AudioService.start(this, q != null ? q : c.url, c.name);
            screenOffAudio = true;
        }
        for (Slot s : slots) {
            try { if (s.mp != null && s.mp.isPlaying()) s.mp.pause(); } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (Slot s : slots) {
            try { if (s.mp != null && !s.mp.isPlaying()) s.mp.start(); } catch (Exception ignored) { }
        }
        if (screenOffAudio) {
            screenOffAudio = false;
            AudioService.stop(this);
        }
        if (!tracking) { tracking = true; openedAt = System.currentTimeMillis(); Net.begin(); }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (recorder.isRunning()) recorder.stop();
        for (Slot s : slots) if (s != null) s.release();
        super.onDestroy();
    }
}
