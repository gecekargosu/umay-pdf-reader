package com.umay.pdfreader.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** İnternet radyosu: kilit ekranında da çalar, istenmeyen istasyonlar gizlenip geri eklenebilir. */
public class RadioActivity extends Activity {

    private static final int BG = Color.parseColor("#11151C");
    private static final int CARD = Color.parseColor("#1B2230");
    private static final int ACCENT = Color.parseColor("#FF7A18");
    private static final int TEXT = Color.parseColor("#F5F7FA");
    private static final int MUTED = Color.parseColor("#9AA4B2");

    private static final List<String> NAMES = new ArrayList<String>();
    private static final List<String> META = new ArrayList<String>();
    private static final List<String> URLS = new ArrayList<String>();
    private static boolean loaded = false;

    private final List<Integer> shown = new ArrayList<Integer>();
    private String query = "";
    private boolean showHidden = false;
    private RadioAdapter adapter;

    private static void load(Activity a) {
        if (loaded) return;
        loaded = true;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    a.getAssets().open("radios.m3u"), "UTF-8"));
            String line, info = null;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) continue;
                if (line.startsWith("#EXTINF")) {
                    info = line.substring(line.indexOf(',') + 1).trim();
                } else if (!line.startsWith("#") && info != null) {
                    String[] p = info.split("\\|");
                    NAMES.add(p.length > 0 ? p[0].trim() : "Radyo");
                    META.add(p.length > 1 ? (p[1].trim() + (p.length > 2 ? " · " + p[2].trim() : ""))
                            : "internet");
                    URLS.add(line);
                    info = null;
                }
            }
            r.close();
        } catch (Exception ignored) { }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        load(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(12), dp(12), dp(6));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(38), dp(38));
        llp.setMargins(0, 0, dp(10), 0);
        logo.setLayoutParams(llp);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try { logo.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName())); }
        catch (Exception ignored) { }
        head.addView(logo);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText("Radyo");
        t.setTextColor(TEXT);
        t.setTextSize(21);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        TextView sub = new TextView(this);
        sub.setText(NAMES.size() + " istasyon · kilit ekranında da çalar");
        sub.setTextColor(MUTED);
        sub.setTextSize(11);
        titles.addView(t);
        titles.addView(sub);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = pill("Kapat");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        head.addView(close);
        root.addView(head);

        EditText search = new EditText(this);
        search.setHint("Radyo ara...");
        search.setHintTextColor(MUTED);
        search.setTextColor(TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        GradientDrawable sbg = rounded(CARD, 14);
        search.setBackground(sbg);
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, dp(10), 0, dp(6));
        root.addView(search, slp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase();
                refresh();
            }
            public void afterTextChanged(Editable s) { }
        });

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        final TextView all = pill("Tümü");
        all.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHidden = false; refresh(); }
        });
        final TextView hidden = pill("🗑 Gizlenenler");
        hidden.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHidden = true; refresh(); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, dp(8), 0);
        hidden.setLayoutParams(clp);
        chips.addView(all);
        chips.addView(hidden);
        LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipsLp.setMargins(0, 0, 0, dp(6));
        root.addView(chips, chipsLp);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(dp(6));
        list.setPadding(0, dp(4), 0, dp(8));
        list.setClipToPadding(false);
        adapter = new RadioAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                play(shown.get(pos));
            }
        });
        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                menu(shown.get(pos));
                return true;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView foot = new TextView(this);
        foot.setText("İpucu: bir radyoya uzun bas → listeden gizle. Gizlediklerini "
                + "\"🗑 Gizlenenler\" sekmesinden geri ekleyebilirsin.");
        foot.setTextColor(MUTED);
        foot.setTextSize(10);
        foot.setPadding(0, dp(4), 0, 0);
        root.addView(foot);

        setContentView(root);
        refresh();
    }

    private void play(int i) {
        RadioPlayerActivity.open(this, i);
    }

    private void menu(final int i) {
        final boolean hidden = Prefs.hiddenRadios(this).contains(URLS.get(i));
        String[] items = hidden
                ? new String[]{"➕ Listeye geri ekle", "Şimdi çal"}
                : new String[]{"🗑 Listeden gizle", "Şimdi çal"};
        new AlertDialog.Builder(this).setTitle(NAMES.get(i)).setItems(items,
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 1) { play(i); return; }
                        if (hidden) Prefs.unhideRadio(RadioActivity.this, URLS.get(i));
                        else Prefs.hideRadio(RadioActivity.this, URLS.get(i));
                        refresh();
                    }
                }).show();
    }

    private void refresh() {
        shown.clear();
        java.util.Set<String> hid = Prefs.hiddenRadios(this);
        for (int i = 0; i < NAMES.size(); i++) {
            boolean isHidden = hid.contains(URLS.get(i));
            if (showHidden != isHidden) continue;
            if (query.length() > 0
                    && !NAMES.get(i).toLowerCase().contains(query)
                    && !META.get(i).toLowerCase().contains(query)) continue;
            shown.add(i);
        }
        adapter.notifyDataSetChanged();
    }

    private class RadioAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return NAMES.get(shown.get(i)); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View v, ViewGroup p) {
            int i = shown.get(pos);
            LinearLayout row = new LinearLayout(RadioActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(10), dp(10));
            GradientDrawable d = rounded(CARD, 14);
            row.setBackground(d);

            LinearLayout texts = new LinearLayout(RadioActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView n = new TextView(RadioActivity.this);
            n.setText("📻  " + NAMES.get(i));
            n.setTextColor(TEXT);
            n.setTextSize(14);
            n.setTypeface(n.getTypeface(), android.graphics.Typeface.BOLD);
            TextView m = new TextView(RadioActivity.this);
            m.setText(META.get(i));
            m.setTextColor(MUTED);
            m.setTextSize(11);
            texts.addView(n);
            texts.addView(m);
            row.addView(texts);

            TextView play = new TextView(RadioActivity.this);
            play.setText("▶");
            play.setTextColor(Color.WHITE);
            play.setTextSize(13);
            play.setGravity(Gravity.CENTER);
            play.setBackground(rounded(ACCENT, 18));
            play.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
            row.addView(play);
            return row;
        }
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView pill(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor("#2A3444"));
        d.setCornerRadius(dp(16));
        t.setBackground(d);
        return t;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
