package com.umay.pdfreader.tv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChannelRepo {

    private static final List<Channel> CHANNELS = new ArrayList<Channel>();
    private static boolean loaded = false;

    public static List<Channel> all(Context ctx) {
        if (!loaded) load(ctx);
        return CHANNELS;
    }

    private static void load(Context ctx) {
        loaded = true;
        Set<String> seenUrl = new HashSet<String>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    ctx.getAssets().open("channels.m3u"), "UTF-8"));
            String line, name = null, group = "Genel", country = "", logo = "";
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) continue;
                if (line.startsWith("#EXTINF")) {
                    int comma = line.lastIndexOf(',');
                    name = comma >= 0 ? line.substring(comma + 1).trim() : "Kanal";
                    group = attr(line, "group-title", "Genel");
                    country = attr(line, "tvg-country", "");
                    logo = attr(line, "tvg-logo", "");
                } else if (!line.startsWith("#")) {
                    if (name != null && !seenUrl.contains(line)) {
                        seenUrl.add(line);
                        CHANNELS.add(new Channel(CHANNELS.size() + 1, name, group, country, logo, line));
                    }
                    name = null;
                }
            }
            r.close();
        } catch (Exception e) {
            // liste okunamazsa bos kalir
        }
    }

    private static String attr(String line, String key, String def) {
        String k = key + "=\"";
        int i = line.indexOf(k);
        if (i < 0) return def;
        int start = i + k.length();
        int end = line.indexOf('"', start);
        if (end < 0) return def;
        return line.substring(start, end);
    }

    public static List<String> groups(Context ctx) {
        List<String> out = new ArrayList<String>();
        for (Channel c : all(ctx)) if (!out.contains(c.group)) out.add(c.group);
        java.util.Collections.sort(out);
        return out;
    }

    public static Channel byNumber(Context ctx, int no) {
        for (Channel c : all(ctx)) if (c.no == no) return c;
        return null;
    }

    public static int indexOfUrl(String url) {
        for (int i = 0; i < CHANNELS.size(); i++) if (CHANNELS.get(i).url.equals(url)) return i;
        return 0;
    }
}
