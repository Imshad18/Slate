package com.slate.app;

import android.content.Context;
import android.content.SharedPreferences;

/** The handful of settings worth remembering between files. */
public final class Prefs {

    private Prefs() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("slate", Context.MODE_PRIVATE);
    }

    public static boolean dark(Context c) { return p(c).getBoolean("dark", true); }
    public static void setDark(Context c, boolean v) {
        p(c).edit().putBoolean("dark", v).apply();
    }

    public static float fontSize(Context c) { return p(c).getFloat("font", 13f); }
    public static void setFontSize(Context c, float v) {
        p(c).edit().putFloat("font", Math.max(7f, Math.min(28f, v))).apply();
    }

    public static boolean wrap(Context c) { return p(c).getBoolean("wrap", true); }
    public static void setWrap(Context c, boolean v) {
        p(c).edit().putBoolean("wrap", v).apply();
    }

    public static boolean lineNumbers(Context c) {
        return p(c).getBoolean("lines", true);
    }
    public static void setLineNumbers(Context c, boolean v) {
        p(c).edit().putBoolean("lines", v).apply();
    }
}
