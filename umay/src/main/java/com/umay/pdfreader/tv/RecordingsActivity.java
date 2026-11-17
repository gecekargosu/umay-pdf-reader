package com.umay.pdfreader.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Kaydedilen yayinlar: Movies/TV-Kayitlari klasorunu listeler, dokununca oynatir. */
public class RecordingsActivity extends Activity {

    private static final int BG = Color.parseColor("#11151C");
    private static final int CARD = Color.parseColor("#1B2230");
    private static final int TEXT = Color.parseColor("#F5F7FA");
    private static final int MUTED = Color.parseColor("#9AA4B2");

    private final List<String> names = new ArrayList<String>();
    private final List<String> metas = new ArrayList<String>();
    private final List<Uri> uris = new ArrayList<Uri>();
    private ListView list;
    private TextView empty;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(14), dp(14), dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText("Kayıtlarım");
        t.setTextColor(TEXT);
        t.setTextSize(20);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(this);
        close.setText("Kapat");
        close.setTextColor(Color.WHITE);
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

        empty = new TextView(this);
        empty.setText("Henüz kayıt yok.\nOynatıcıda ⏺ düğmesine bas; kayıt\nMovies / TV-Kayitlari klasörüne gider.");
        empty.setTextColor(MUTED);
        empty.setTextSize(13);
        empty.setPadding(0, dp(24), 0, 0);
        root.addView(empty);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(dp(6));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        askPermission();
        load();
    }

    private void askPermission() {
        String p = Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_VIDEO"
                : "android.permission.READ_EXTERNAL_STORAGE";
        if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{p}, 21);
        }
    }

    private void load() {
        names.clear(); metas.clear(); uris.clear();
        try {
            Uri q = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            String[] cols = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION};
            String where = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
            Cursor c = getContentResolver().query(q, cols, where,
                    new String[]{"%TV-Kayitlari%"}, MediaStore.Video.Media.DATE_ADDED + " DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String name = c.getString(1);
                    long size = c.getLong(2);
                    long dur = c.getLong(3);
                    names.add(name);
                    metas.add((dur / 1000 / 60) + " dk · " + Net.mb(size));
                    uris.add(Uri.withAppendedPath(q, String.valueOf(id)));
                }
                c.close();
            }
        } catch (Exception ignored) { }

        if (names.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
            return;
        }
        empty.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return names.size(); }
            @Override public Object getItem(int i) { return names.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                LinearLayout row = new LinearLayout(RecordingsActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                GradientDrawable d = new GradientDrawable();
                d.setColor(CARD);
                d.setCornerRadius(dp(14));
                row.setBackground(d);
                TextView n = new TextView(RecordingsActivity.this);
                n.setText(names.get(i));
                n.setTextColor(TEXT);
                n.setTextSize(14);
                TextView m = new TextView(RecordingsActivity.this);
                m.setText(metas.get(i));
                m.setTextColor(MUTED);
                m.setTextSize(11);
                row.addView(n);
                row.addView(m);
                return row;
            }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uris.get(pos), "video/*");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(RecordingsActivity.this, "Oynatıcı bulunamadı", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        load();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
