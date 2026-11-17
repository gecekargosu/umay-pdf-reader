package com.umay.pdfreader.tv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.Process;

/** Mobil veri tespiti ve yayin basina internet kullanimi sayaci (uid bazli). */
public class Net {

    private static long sessionStart = 0;

    public static boolean mobile(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities cap = cm.getNetworkCapabilities(n);
            if (cap == null) return false;
            return cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities cap = cm.getNetworkCapabilities(n);
            return cap != null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return false;
        }
    }

    private static long rx() {
        long v = TrafficStats.getUidRxBytes(Process.myUid());
        return v < 0 ? 0 : v;
    }

    /** Izleme basinda cagir. */
    public static void begin() {
        sessionStart = rx();
        if (sessionStart == 0) sessionStart = -1;
    }

    /** Devam eden oturumda simdiye kadar harcanan bayt. */
    public static long sessionSoFar() {
        if (sessionStart <= 0) return 0;
        long used = rx() - sessionStart;
        return used > 0 ? used : 0;
    }

    /** Izleme bitince: bu oturumda harcanan bayt. */
    public static long end() {
        if (sessionStart == -1) return 0;
        long now = rx();
        long used = now - sessionStart;
        sessionStart = 0;
        return used > 0 ? used : 0;
    }

    public static String mb(long bytes) {
        if (bytes < 1048576L) return (bytes / 1024) + " KB";
        if (bytes < 1073741824L) return String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1048576.0);
        return String.format(java.util.Locale.getDefault(), "%.2f GB", bytes / 1073741824.0);
    }
}
