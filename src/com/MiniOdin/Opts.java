package com.MiniOdin;

import android.content.Context;

// Options de flash, mémorisées dans les préférences
public class Opts {
    static final String[] K = {"reboot", "slotb", "nomd5", "erase", "repart", "tflash"};
    static final String[][] L = {
        {"Auto Reboot", "Auto Reboot"},
        {"Flash vers Slot B", "Flash to Slot B"},
        {"Ignorer MD5", "Skip MD5"},
        {"Effacer la partition userdata", "Erase userdata partition"},
        {"Repartitionner", "Repartition"},
        {"TFlash", "TFlash"}
    };

    public static boolean on(Context c, String k) { return c.getSharedPreferences("p", 0).getBoolean("o_" + k, false); }

    public static void set(Context c, String k, boolean v) { c.getSharedPreferences("p", 0).edit().putBoolean("o_" + k, v).commit(); }

    public static String summary(Context c) {
        String s = "";
        for (String k : K) if (on(c, k)) s += (s.length() > 0 ? ", " : "") + k;
        return s.length() > 0 ? "  [" + s + "]" : "";
    }
}
