package com.umay.pdfreader.tv;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Master m3u8 icindeki cozunurluk varyantlarini (360p/720p/1080p) listeler. */
public class Quality {

    public static class Variant {
        public final String label;
        public final String url;
        Variant(String label, String url) { this.label = label; this.url = url; }
        @Override public String toString() { return label; }
    }

    public interface Callback { void onResult(List<Variant> variants); }

    public static void load(final String playlistUrl, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Variant> out = new ArrayList<Variant>();
                try {
                    String txt = fetch(playlistUrl);
                    if (txt != null && txt.contains("#EXT-X-STREAM-INF")) {
                        String base = playlistUrl.substring(0, playlistUrl.lastIndexOf('/') + 1);
                        String[] lines = txt.split("\n");
                        for (int i = 0; i < lines.length; i++) {
                            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
                            String res = "";
                            java.util.regex.Matcher m = java.util.regex.Pattern
                                    .compile("RESOLUTION=(\\d+)x(\\d+)").matcher(lines[i]);
                            if (m.find()) res = m.group(2) + "p";
                            for (int j = i + 1; j < lines.length; j++) {
                                String c = lines[j].trim();
                                if (c.length() == 0 || c.startsWith("#")) continue;
                                out.add(new Variant(res.length() == 0 ? ("varyant " + (out.size() + 1)) : res,
                                        c.startsWith("http") ? c : base + c));
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) { }
                cb.onResult(out);
            }
        }, "quality").start();
    }

    /** En dusuk cozunurluklu varyant (veri tasarrufu icin). */
    public static String lowest(List<Variant> list) {
        if (list == null || list.isEmpty()) return null;
        Variant best = null;
        int bestH = Integer.MAX_VALUE;
        for (Variant v : list) {
            int h = Integer.MAX_VALUE;
            try { h = Integer.parseInt(v.label.replace("p", "").trim()); }
            catch (Exception ignored) { }
            if (h < bestH) { bestH = h; best = v; }
        }
        return best == null ? list.get(list.size() - 1).url : best.url;
    }

    private static String fetch(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(7000); c.setReadTimeout(7000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) { return null; }
    }
}
