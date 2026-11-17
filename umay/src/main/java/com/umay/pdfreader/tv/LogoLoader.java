package com.umay.pdfreader.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Kanal logolarini arka planda indirip onbellege alan basit yukleyici. */
public class LogoLoader {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(120);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    public static void load(final ImageView view, final String url) {
        if (url == null || url.length() == 0) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        view.setTag(url);
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(6000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0");
                    InputStream in = c.getInputStream();
                    bmp = BitmapFactory.decodeStream(in);
                    in.close();
                } catch (Exception ignored) {
                }
                if (bmp != null) {
                    CACHE.put(url, bmp);
                    final Bitmap f = bmp;
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            if (url.equals(view.getTag())) view.setImageBitmap(f);
                        }
                    });
                }
            }
        });
    }
}
