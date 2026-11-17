package com.umay.pdfreader.tv;

public class Channel {
    public final int no;
    public final String name;
    public final String group;
    public final String country;
    public final String logo;
    public final String url;

    public Channel(int no, String name, String group, String country, String logo, String url) {
        this.no = no;
        this.name = name;
        this.group = group;
        this.country = country == null ? "" : country.toLowerCase();
        this.logo = logo;
        this.url = url;
    }

    /** Ulke kodundan bayrak emojisi uretir (tr -> 🇹🇷). */
    public String flag() {
        if (country.length() != 2) return "🌐";
        int a = Character.toUpperCase(country.charAt(0)) - 'A' + 0x1F1E6;
        int b = Character.toUpperCase(country.charAt(1)) - 'A' + 0x1F1E6;
        if (a < 0x1F1E6 || b < 0x1F1E6) return "🌐";
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }

    public boolean turkish() { return "tr".equals(country); }

    public String label() { return flag() + "  " + name; }
}
