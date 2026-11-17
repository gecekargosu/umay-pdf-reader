package com.umay.pdfreader.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Tum kucuk tercihleri tek yerde toplar: favoriler, son izlenenler, olu kanal sayaci, setler. */
public class Prefs {

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("canlitv", Context.MODE_PRIVATE);
    }

    // --- favoriler ---
    public static Set<String> favs(Context c) {
        return new HashSet<String>(sp(c).getStringSet("favs", new HashSet<String>()));
    }

    public static void toggleFav(Context c, String url) {
        Set<String> f = favs(c);
        if (f.contains(url)) f.remove(url); else f.add(url);
        sp(c).edit().putStringSet("favs", f).apply();
    }

    // --- son izlenenler ---
    public static List<String> recent(Context c) {
        String s = sp(c).getString("recent", "");
        List<String> out = new ArrayList<String>();
        for (String u : s.split("\n")) if (u.trim().length() > 0) out.add(u.trim());
        return out;
    }

    public static void addRecent(Context c, String url) {
        List<String> r = recent(c);
        r.remove(url);
        r.add(0, url);
        while (r.size() > 20) r.remove(r.size() - 1);
        StringBuilder b = new StringBuilder();
        for (String u : r) b.append(u).append("\n");
        sp(c).edit().putString("recent", b.toString()).apply();
    }

    // --- olu kanal sayaci ---
    public static int fails(Context c, String url) {
        return sp(c).getInt("fail:" + url.hashCode(), 0);
    }

    public static void addFail(Context c, String url) {
        sp(c).edit().putInt("fail:" + url.hashCode(), fails(c, url) + 1).apply();
    }

    public static void clearFails(Context c, String url) {
        sp(c).edit().remove("fail:" + url.hashCode()).apply();
    }

    public static boolean hideDead(Context c) {
        return sp(c).getBoolean("hideDead", false);
    }

    public static void setHideDead(Context c, boolean v) {
        sp(c).edit().putBoolean("hideDead", v).apply();
    }

    // --- kalite secimi (kanal url -> variant url) ---
    public static String quality(Context c, String url) {
        return sp(c).getString("q:" + url.hashCode(), null);
    }

    public static void setQuality(Context c, String url, String variant) {
        if (variant == null) sp(c).edit().remove("q:" + url.hashCode()).apply();
        else sp(c).edit().putString("q:" + url.hashCode(), variant).apply();
    }

    // --- coklu ekran ---
    public static String multiLast(Context c) {
        return sp(c).getString("multiLast", "");
    }

    public static void setMultiLast(Context c, String v) {
        sp(c).edit().putString("multiLast", v).apply();
    }

    public static Set<String> multiSets(Context c) {
        return new LinkedHashSet<String>(sp(c).getStringSet("multiSets", new HashSet<String>()));
    }

    public static void saveMultiSet(Context c, String encoded) {
        Set<String> s = multiSets(c);
        s.add(encoded);
        sp(c).edit().putStringSet("multiSets", s).apply();
    }

    public static void removeMultiSet(Context c, String encoded) {
        Set<String> s = multiSets(c);
        s.remove(encoded);
        sp(c).edit().putStringSet("multiSets", s).apply();
    }

    public static void setLastIndex(Context c, int i) {
        sp(c).edit().putInt("lastIndex", i).apply();
    }

    public static int lastIndex(Context c) {
        return sp(c).getInt("lastIndex", 0);
    }

    // --- listeden gizlenen kanallar / radyolar ---
    public static Set<String> hiddenChannels(Context c) {
        return new HashSet<String>(sp(c).getStringSet("hiddenCh", new HashSet<String>()));
    }

    public static void hideChannel(Context c, String url) {
        Set<String> s = hiddenChannels(c);
        s.add(url);
        sp(c).edit().putStringSet("hiddenCh", s).apply();
    }

    public static void unhideChannel(Context c, String url) {
        Set<String> s = hiddenChannels(c);
        s.remove(url);
        sp(c).edit().putStringSet("hiddenCh", s).apply();
    }

    public static Set<String> hiddenRadios(Context c) {
        return new HashSet<String>(sp(c).getStringSet("hiddenRd", new HashSet<String>()));
    }

    public static void hideRadio(Context c, String url) {
        Set<String> s = hiddenRadios(c);
        s.add(url);
        sp(c).edit().putStringSet("hiddenRd", s).apply();
    }

    public static void unhideRadio(Context c, String url) {
        Set<String> s = hiddenRadios(c);
        s.remove(url);
        sp(c).edit().putStringSet("hiddenRd", s).apply();
    }

    // --- internet kullanimi ---
    private static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
    }

    /** Kanal basina toplam kullanim (bayt). */
    public static long usage(Context c, String url) {
        return sp(c).getLong("use:" + url.hashCode(), 0);
    }

    public static int secs(Context c, String url) {
        return sp(c).getInt("useS:" + url.hashCode(), 0);
    }

    /** Gunluk toplam (bayt); gun degisince sifirlanir. */
    public static long todayBytes(Context c) {
        if (!today().equals(sp(c).getString("day", ""))) return 0;
        return sp(c).getLong("dayBytes", 0);
    }

    public static void addUsage(Context c, String url, long bytes, int seconds) {
        SharedPreferences.Editor e = sp(c).edit();
        e.putLong("use:" + url.hashCode(), usage(c, url) + bytes);
        e.putInt("useS:" + url.hashCode(), secs(c, url) + seconds);
        long t = today().equals(sp(c).getString("day", "")) ? sp(c).getLong("dayBytes", 0) : 0;
        e.putString("day", today());
        e.putLong("dayBytes", t + bytes);
        e.apply();
    }

    public static int limitMb(Context c) {
        return sp(c).getInt("limitMb", 0);
    }

    public static void setLimitMb(Context c, int mb) {
        sp(c).edit().putInt("limitMb", mb).apply();
    }

    public static void resetToday(Context c) {
        sp(c).edit().putString("day", today()).putLong("dayBytes", 0).apply();
    }

    public static void resetAllUsage(Context c) {
        SharedPreferences.Editor e = sp(c).edit();
        for (String k : sp(c).getAll().keySet()) {
            if (k.startsWith("use:") || k.startsWith("useS:")) e.remove(k);
        }
        e.putString("day", today()).putLong("dayBytes", 0).apply();
    }

    /** Mobil veride onay: son onay zamani (1 saat gecerli). */
    public static boolean mobileOk(Context c) {
        return System.currentTimeMillis() - sp(c).getLong("mobileOk", 0) < 3600000L;
    }

    public static void setMobileOk(Context c, boolean v) {
        sp(c).edit().putLong("mobileOk", v ? System.currentTimeMillis() : 0).apply();
    }

    /** Veri tasarrufu: yayinlarin en dusuk cozunurlugunu kullan. */
    public static boolean dataSaver(Context c) {
        return sp(c).getBoolean("dataSaver", false);
    }

    public static void setDataSaver(Context c, boolean v) {
        sp(c).edit().putBoolean("dataSaver", v).apply();
    }

    /** Veri tasarrufu: mobil veride sadece ses cal. */
    public static boolean audioOnlyMobile(Context c) {
        return sp(c).getBoolean("audioOnlyMobile", false);
    }

    public static void setAudioOnlyMobile(Context c, boolean v) {
        sp(c).edit().putBoolean("audioOnlyMobile", v).apply();
    }
}
