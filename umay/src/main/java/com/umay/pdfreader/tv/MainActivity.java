package com.umay.pdfreader.tv;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends android.app.Activity {

    private static final int BG = Color.parseColor("#11151C");
    private static final int CARD = Color.parseColor("#1B2230");
    private static final int ACCENT = Color.parseColor("#FF7A18");
    private static final int TEXT = Color.parseColor("#F5F7FA");
    private static final int MUTED = Color.parseColor("#9AA4B2");

    private final List<Channel> shown = new ArrayList<Channel>();
    private List<Channel> all;
    private ChannelAdapter adapter;
    private String group = "Tümü";
    private String query = "";
    private LinearLayout chipsRow;
    private TextView dataBar;
    private TextView emptyView;
    private ListView listView;
    private boolean onlyHidden = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        all = ChannelRepo.all(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        // --- baslik ---
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(16), dp(16), dp(16), dp(4));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView brand = new ImageView(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(42), dp(42));
        blp.setMargins(0, 0, dp(10), 0);
        brand.setLayoutParams(blp);
        brand.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try { brand.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName())); }
        catch (Exception ignored) { }
        topRow.addView(brand);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("UMAY Multi TV");
        title.setTextColor(TEXT);
        title.setTextSize(23);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        TextView sub = new TextView(this);
        sub.setText(all.size() + " kanal · no ile aç, 3'lü izle, kayıt");
        sub.setTextColor(MUTED);
        sub.setTextSize(11);
        titleCol.addView(title);
        titleCol.addView(sub);
        topRow.addView(titleCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView multi = pill("▦ 3'lü", ACCENT);
        multi.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, MultiActivity.class));
            }
        });
        multi.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                savedSets();
                return true;
            }
        });
        topRow.addView(multi);

        TextView radio = pill("📻", Color.parseColor("#2A3444"));
        radio.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, RadioActivity.class));
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(dp(6), 0, 0, 0);
        radio.setLayoutParams(rlp);
        topRow.addView(radio);

        TextView settings = pill("⚙", Color.parseColor("#2A3444"));
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { settingsDialog(); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(dp(6), 0, 0, 0);
        settings.setLayoutParams(slp);
        topRow.addView(settings);

        head.addView(topRow);

        dataBar = new TextView(this);
        dataBar.setTextSize(11);
        dataBar.setTextColor(MUTED);
        dataBar.setPadding(0, dp(6), 0, 0);
        head.addView(dataBar);

        root.addView(head);

        // --- arama + kanal no ---
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srlp.setMargins(dp(14), dp(8), dp(14), dp(4));
        searchRow.setLayoutParams(srlp);

        EditText search = new EditText(this);
        search.setHint("Kanal ara...");
        search.setHintTextColor(MUTED);
        search.setTextColor(TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        GradientDrawable sbg = rounded(CARD, 14);
        search.setBackground(sbg);
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchRow.addView(search, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase();
                refresh();
            }
            public void afterTextChanged(Editable s) { }
        });

        final EditText noInput = new EditText(this);
        noInput.setHint("No");
        noInput.setHintTextColor(MUTED);
        noInput.setTextColor(TEXT);
        noInput.setTextSize(14);
        noInput.setSingleLine(true);
        noInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        noInput.setGravity(Gravity.CENTER);
        noInput.setBackground(rounded(CARD, 14));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(58),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMargins(dp(6), 0, 6, 0);
        noInput.setLayoutParams(nlp);
        searchRow.addView(noInput);

        TextView go = pill("Git", ACCENT);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    int no = Integer.parseInt(noInput.getText().toString().trim());
                    gotoNumber(no);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Kanal numarası yaz", Toast.LENGTH_SHORT).show();
                }
            }
        });
        searchRow.addView(go);
        root.addView(searchRow);

        // --- cipler ---
        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setPadding(dp(12), dp(4), dp(12), dp(4));
        chipsScroll.addView(chipsRow);
        root.addView(chipsScroll);
        buildChips();

        // --- liste ---
        ListView list = new ListView(this);
        listView = list;
        list.setDivider(null);
        list.setDividerHeight(dp(6));
        list.setPadding(dp(12), dp(4), dp(12), dp(12));
        list.setClipToPadding(false);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        adapter = new ChannelAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                final Channel c = shown.get(pos);
                if (onlyHidden) { channelMenu(c); return; }
                open(c);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                channelMenu(shown.get(pos));
                return true;
            }
        });

        emptyView = new TextView(this);
        emptyView.setTextColor(MUTED);
        emptyView.setTextSize(13);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(24), dp(40), dp(24), dp(24));
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refresh();
    }

    private void buildChips() {
        chipsRow.removeAllViews();
        List<String> cats = new ArrayList<String>();
        cats.add("Tümü");
        cats.add("★ Favoriler");
        cats.add("🇹🇷 Türkçe");
        cats.add("🌐 Yabancı");
        cats.add("🕘 Son");
        cats.add("🗑 Gizlenenler");
        cats.addAll(ChannelRepo.groups(this));
        for (final String c : cats) {
            TextView chip = new TextView(this);
            chip.setText(c);
            chip.setTextSize(12);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            chip.setTag(c);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    group = c;
                    onlyHidden = "🗑 Gizlenenler".equals(c);
                    styleChips();
                    refresh();
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(0, 0, dp(6), 0);
            chip.setLayoutParams(clp);
            chipsRow.addView(chip);
        }
        styleChips();
    }

    private void styleChips() {
        for (int i = 0; i < chipsRow.getChildCount(); i++) {
            TextView chip = (TextView) chipsRow.getChildAt(i);
            boolean sel = chip.getTag().toString().equals(group);
            chip.setBackground(rounded(sel ? ACCENT : CARD, 18));
            chip.setTextColor(sel ? Color.WHITE : MUTED);
        }
    }

    private void gotoNumber(int no) {
        final Channel c = ChannelRepo.byNumber(this, no);
        if (c == null) {
            Toast.makeText(this, "№" + no + " diye kanal yok (1-" + all.size() + ")", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Kanal " + no)
                .setMessage(c.flag() + "  " + c.name + "\n" + c.group + "\n\nAçılsın mı?")
                .setPositiveButton("Aç", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        open(c);
                    }
                })
                .setNegativeButton("Vazgeç", null)
                .show();
    }

    private void savedSets() {
        final List<String> sets = new ArrayList<String>(Prefs.multiSets(this));
        sets.add(0, "Son kullandığım set");
        new AlertDialog.Builder(this)
                .setTitle("3'lü izleme setleri")
                .setItems(sets.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        Intent i = new Intent(MainActivity.this, MultiActivity.class);
                        if (which > 0) i.putExtra("set", sets.get(which));
                        startActivity(i);
                    }
                })
                .setNeutralButton("Set sil", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { deleteSetDialog(); }
                })
                .show();
    }

    private void deleteSetDialog() {
        final List<String> sets = new ArrayList<String>(Prefs.multiSets(this));
        if (sets.isEmpty()) {
            Toast.makeText(this, "Kayıtlı set yok", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[sets.size()];
        for (int i = 0; i < sets.size(); i++) {
            String s = sets.get(i);
            labels[i] = s.startsWith("ad:") ? s.substring(3, Math.max(3, s.indexOf('|'))) : s;
        }
        new AlertDialog.Builder(this).setTitle("Hangi set silinsin?").setItems(labels,
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        Prefs.removeMultiSet(MainActivity.this, sets.get(which));
                        Toast.makeText(MainActivity.this, "Silindi", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void settingsDialog() {
        final boolean hide = Prefs.hideDead(this);
        final boolean audioOnly = Prefs.audioOnlyMobile(this);
        int lim = Prefs.limitMb(this);
        String[] items = {
                "📁 Kayıtlarım",
                "📶 Veri limiti: " + (lim == 0 ? "yok (sınırsız)" : lim + " MB/gün"),
                audioOnly ? "🎧 Mobil veride sadece ses: AÇIK" : "🎧 Mobil veride sadece ses: KAPALI",
                hide ? "Ölü kanalları gizle: AÇIK" : "Ölü kanalları gizle: KAPALI",
                "🩺 Tüm kanalları test et (sağlık taraması)",
                "Bugünün verisini sıfırla",
                "Tüm veri sayaçlarını sıfırla",
                "Kayıtlar nereye gidiyor?",
                "📻 Radyo",
                "ℹ️ Ne var ne yok? (yapılanlar listesi)",
                "🗑 Gizlenenleri göster",
                (Prefs.dataSaver(this) ? "💾 Veri tasarrufu (düşük kalite): AÇIK"
                        : "💾 Veri tasarrufu (düşük kalite): KAPALI")
        };
        new AlertDialog.Builder(this).setTitle("Ayarlar").setItems(items,
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                startActivity(new Intent(MainActivity.this, RecordingsActivity.class));
                                break;
                            case 1:
                                askLimit();
                                break;
                            case 2:
                                Prefs.setAudioOnlyMobile(MainActivity.this, !audioOnly);
                                Toast.makeText(MainActivity.this,
                                        !audioOnly ? "Mobil veride artık sadece ses çalacak"
                                                : "Mobil veride görüntü de açılacak (onay ister)",
                                        Toast.LENGTH_SHORT).show();
                                break;
                            case 3:
                                Prefs.setHideDead(MainActivity.this, !hide);
                                break;
                            case 4:
                                healthScan();
                                break;
                            case 5:
                                Prefs.resetToday(MainActivity.this);
                                break;
                            case 6:
                                Prefs.resetAllUsage(MainActivity.this);
                                break;
                            case 7:
                                new AlertDialog.Builder(MainActivity.this).setTitle("Kayıtlar")
                                        .setMessage("Kayıtlar galeride:\n\nMovies / TV-Kayitlari\n\n"
                                                + "Dosya biçimi .ts (ses + görüntü birlikte).\n"
                                                + "Uygulama içinden: Ayarlar → Kayıtlarım")
                                        .setPositiveButton("Tamam", null).show();
                                break;
                            case 8:
                                startActivity(new Intent(MainActivity.this, RadioActivity.class));
                                break;
                            case 9:
                                startActivity(new Intent(MainActivity.this, StatusActivity.class));
                                break;
                            case 10:
                                Prefs.setDataSaver(MainActivity.this, !Prefs.dataSaver(MainActivity.this));
                                Toast.makeText(MainActivity.this,
                                        !Prefs.dataSaver(MainActivity.this)
                                                ? "Veri tasarrufu açık: yayınlar en düşük kalitede açılacak"
                                                : "Veri tasarrufu kapalı: en iyi kalite",
                                        Toast.LENGTH_LONG).show();
                                break;
                            default:
                                group = "🗑 Gizlenenler";
                                onlyHidden = true;
                                styleChips();
                                break;
                        }
                        refresh();
                    }
                }).show();
    }

    private void askLimit() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("örn: 2000 (0 = sınırsız)");
        et.setText(String.valueOf(Prefs.limitMb(this)));
        new AlertDialog.Builder(this)
                .setTitle("Günlük mobil veri limiti (MB)")
                .setMessage("Limit dolunca kanal açmadan önce uyarı verir.")
                .setView(et)
                .setPositiveButton("Kaydet", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            Prefs.setLimitMb(MainActivity.this, Integer.parseInt(et.getText().toString().trim()));
                        } catch (Exception e) {
                            Prefs.setLimitMb(MainActivity.this, 0);
                        }
                        refresh();
                    }
                }).setNegativeButton("Vazgeç", null).show();
    }

    private void healthScan() {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("Kanallar test ediliyor...");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            @Override public void run() {
                int ok = 0, bad = 0;
                for (Channel c : all) {
                    boolean good = probe(c.url);
                    if (good) { Prefs.clearFails(MainActivity.this, c.url); ok++; }
                    else { Prefs.addFail(MainActivity.this, c.url); Prefs.addFail(MainActivity.this, c.url);
                        Prefs.addFail(MainActivity.this, c.url); bad++; }
                }
                final int fok = ok, fbad = bad;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        refresh();
                        Toast.makeText(MainActivity.this, "Tarama bitti: " + fok + " çalışıyor, "
                                + fbad + " ölü (gizlemek için ⚙ → ölü gizle)", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private boolean probe(String url) {
        java.net.HttpURLConnection con = null;
        try {
            con = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(6000);
            con.setRequestProperty("User-Agent", "VLC/3.0");
            int code = con.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) try { con.disconnect(); } catch (Exception ignored) { }
        }
    }

    private void channelMenu(final Channel c) {
        final boolean hidden = Prefs.hiddenChannels(this).contains(c.url);
        final boolean fav = Prefs.favs(this).contains(c.url);
        String[] items = hidden
                ? new String[]{"\u2795 Listeye geri ekle", "Aç"}
                : new String[]{"Aç", fav ? "★ Favoriden çıkar" : "★ Favorilere ekle", "\ud83d\uddd1 Listeden gizle"};
        new AlertDialog.Builder(this)
                .setTitle("№" + c.no + "  " + c.flag() + "  " + c.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (hidden) {
                            if (which == 0) { Prefs.unhideChannel(MainActivity.this, c.url); refresh(); }
                            else open(c);
                            return;
                        }
                        if (which == 0) { open(c); return; }
                        if (which == 1) { Prefs.toggleFav(MainActivity.this, c.url); refresh(); return; }
                        Prefs.hideChannel(MainActivity.this, c.url);
                        refresh();
                        Toast.makeText(MainActivity.this,
                                c.name + " listeden gizlendi.\nGeri eklemek: \ud83d\uddd1 Gizlenenler",
                                Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void open(Channel c) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("index", ChannelRepo.indexOfUrl(c.url));
        startActivity(i);
    }

    private void refresh() {
        shown.clear();
        Set<String> favs = Prefs.favs(this);
        List<String> recent = Prefs.recent(this);
        java.util.Set<String> hidden = Prefs.hiddenChannels(this);
        for (Channel c : all) {
            if (onlyHidden != hidden.contains(c.url)) continue;
            if (!onlyHidden && Prefs.hideDead(this) && Prefs.fails(this, c.url) >= 3) continue;
            if (!"Tümü".equals(group)) {
                if ("★ Favoriler".equals(group)) {
                    if (!favs.contains(c.url)) continue;
                } else if ("🇹🇷 Türkçe".equals(group)) {
                    if (!c.turkish()) continue;
                } else if ("🌐 Yabancı".equals(group)) {
                    if (c.turkish()) continue;
                } else if ("🕘 Son".equals(group)) {
                    if (!recent.contains(c.url)) continue;
                } else if (!c.group.equals(group)) {
                    continue;
                }
            }
            if (query.length() > 0
                    && !c.name.toLowerCase().contains(query)
                    && !c.group.toLowerCase().contains(query)
                    && !String.valueOf(c.no).equals(query)) continue;
            shown.add(c);
        }
        if ("🕘 Son".equals(group)) {
            final List<Channel> ordered = new ArrayList<Channel>();
            for (String u : recent) for (Channel c : shown) if (c.url.equals(u)) ordered.add(c);
            for (Channel c : new ArrayList<Channel>(shown)) if (!ordered.contains(c)) ordered.add(c);
            shown.clear();
            shown.addAll(ordered);
        }
        adapter.notifyDataSetChanged();
        if (shown.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText(onlyHidden
                    ? "Gizlenen kanal yok.\n\nBir kanala uzun basıp \"Listeden gizle\" dersen burada birikir."
                    : "Bu filtrede kanal yok.\n\nAramayı temizle veya başka bir sekme dene.");
            if (listView != null) listView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            if (listView != null) listView.setVisibility(View.VISIBLE);
        }
        updateDataBar();
    }

    private void updateDataBar() {
        long used = Prefs.todayBytes(this);
        int lim = Prefs.limitMb(this);
        String s = "📶 Bugün: " + Net.mb(used);
        if (lim > 0) {
            s += " / " + lim + " MB";
            if (used > lim * 1048576L) s += "  ⚠ LİMİT AŞILDI";
        } else {
            s += " · limit yok";
        }
        s += Prefs.audioOnlyMobile(this) ? "  · 🎧 mobilde sadece ses" : "";
        dataBar.setText(s);
        dataBar.setTextColor(lim > 0 && used > lim * 1048576L ? Color.parseColor("#FF5252") : MUTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView pill(String s, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(9), dp(12), dp(9));
        t.setBackground(rounded(color, 18));
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private class ChannelAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(10), dp(8));
            row.setBackground(rounded(CARD, 14));

            Channel c = shown.get(i);

            TextView no = new TextView(MainActivity.this);
            no.setText(String.valueOf(c.no));
            no.setTextColor(Color.WHITE);
            no.setTextSize(11);
            no.setGravity(Gravity.CENTER);
            no.setBackground(rounded(Color.parseColor("#2A3444"), 10));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(34), dp(28));
            nlp.setMargins(0, 0, dp(8), 0);
            no.setLayoutParams(nlp);
            row.addView(no);

            ImageView logo = new ImageView(MainActivity.this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
            lp.setMargins(0, 0, dp(10), 0);
            logo.setLayoutParams(lp);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LogoLoader.load(logo, c.logo);
            row.addView(logo);

            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView name = new TextView(MainActivity.this);
            name.setText(c.flag() + "  " + c.name);
            name.setTextColor(TEXT);
            name.setTextSize(14);
            name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
            texts.addView(name);

            TextView meta = new TextView(MainActivity.this);
            boolean fav = Prefs.favs(MainActivity.this).contains(c.url);
            String use = "";
            long ub = Prefs.usage(MainActivity.this, c.url);
            if (ub > 0) {
                int us = Prefs.secs(MainActivity.this, c.url);
                use = "  ·  " + Net.mb(ub) + (us > 0 ? " (" + us / 60 + " dk)" : "");
            }
            meta.setText(c.group + (fav ? "  ★" : "")
                    + (Prefs.fails(MainActivity.this, c.url) > 0 ? "  ⚠" : "") + use);
            meta.setTextColor(MUTED);
            meta.setTextSize(11);
            texts.addView(meta);
            row.addView(texts);

            TextView play = new TextView(MainActivity.this);
            play.setText("▶");
            play.setTextColor(Color.WHITE);
            play.setTextSize(13);
            play.setGravity(Gravity.CENTER);
            play.setBackground(rounded(ACCENT, 18));
            play.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
            row.addView(play);

            return row;
        }
    }
}
