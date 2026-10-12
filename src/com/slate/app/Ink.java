package com.slate.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * Colours and drawing helpers.
 *
 * Two themes, switched by a single flag, with every colour read through a
 * static rather than baked into a resource. A theme change then costs one
 * assignment and a redraw instead of an activity restart, which matters
 * because recreating the activity would throw away the loaded document.
 */
public final class Ink {

    private Ink() {}

    public static float density = 3f;
    public static float dp(float v) { return v * density; }

    public static boolean dark = true;

    // ---- surfaces ----------------------------------------------------------
    public static int BG, BG_2, PANEL, LINE, TEXT, TEXT_DIM, ACCENT, GUTTER, SEL;
    // ---- syntax ------------------------------------------------------------
    public static int KEYWORD, STRING, COMMENT, NUMBER, TYPE, FUNC, ANNOT, ATTR, LINK;

    static {
        applyTheme(true);
    }

    public static void applyTheme(boolean darkMode) {
        dark = darkMode;
        if (darkMode) {
            BG       = 0xFF14161A;
            BG_2     = 0xFF1B1E24;
            PANEL    = 0xFF21252C;
            LINE     = 0xFF2E333C;
            TEXT     = 0xFFE4E8EF;
            TEXT_DIM = 0xFF8C94A3;
            ACCENT   = 0xFF4FC3F7;
            GUTTER   = 0xFF5A6373;
            SEL      = 0x404FC3F7;

            KEYWORD  = 0xFFC792EA;
            STRING   = 0xFF9CCC65;
            COMMENT  = 0xFF6B7585;
            NUMBER   = 0xFFF78C6C;
            TYPE     = 0xFF82AAFF;
            FUNC     = 0xFF7FDBCA;
            ANNOT    = 0xFFFFCB6B;
            ATTR     = 0xFFFFCB6B;
            LINK     = 0xFF4FC3F7;
        } else {
            BG       = 0xFFFBFBFD;
            BG_2     = 0xFFF2F3F7;
            PANEL    = 0xFFE9EBF0;
            LINE     = 0xFFD4D8E0;
            TEXT     = 0xFF1C2026;
            TEXT_DIM = 0xFF6B7280;
            ACCENT   = 0xFF0277BD;
            GUTTER   = 0xFF9AA2B1;
            SEL      = 0x400277BD;

            KEYWORD  = 0xFF8E24AA;
            STRING   = 0xFF2E7D32;
            COMMENT  = 0xFF90A4AE;
            NUMBER   = 0xFFD84315;
            TYPE     = 0xFF1565C0;
            FUNC     = 0xFF00838F;
            ANNOT    = 0xFFB8860B;
            ATTR     = 0xFFB8860B;
            LINK     = 0xFF0277BD;
        }
    }

    public static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
    }

    // ---- pooled paints -----------------------------------------------------
    // Allocating a Paint per draw call hands the collector thousands of
    // short-lived objects a second; the pauses land as scroll stutter.

    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint TXT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint MONO = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        STROKE.setStyle(Paint.Style.STROKE);
        MONO.setTypeface(Typeface.MONOSPACE);
    }

    public static Paint fill(int c) {
        FILL.setColor(c); FILL.setStyle(Paint.Style.FILL); FILL.setShader(null);
        return FILL;
    }

    public static Paint stroke(float wDp, int c) {
        STROKE.setColor(c); STROKE.setStrokeWidth(dp(wDp)); STROKE.setShader(null);
        return STROKE;
    }

    public static Paint text(float spSize, int c, boolean bold) {
        TXT.setColor(c);
        TXT.setTextSize(dp(spSize));
        TXT.setFakeBoldText(bold);
        TXT.setTextAlign(Paint.Align.LEFT);
        TXT.setTypeface(Typeface.DEFAULT);
        return TXT;
    }

    public static Paint mono(float spSize, int c, boolean bold) {
        MONO.setColor(c);
        MONO.setTextSize(dp(spSize));
        MONO.setFakeBoldText(bold);
        MONO.setTextAlign(Paint.Align.LEFT);
        return MONO;
    }

    public static int alpha(int c, float a) {
        int base = (int) (((c >>> 24) & 255) * Math.max(0, Math.min(1, a)));
        return (base << 24) | (c & 0x00FFFFFF);
    }

    public static void textCenter(Canvas c, String s, float x, float y, Paint p) {
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, x, y, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    public static void textRight(Canvas c, String s, float x, float y, Paint p) {
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(s, x, y, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    public static void round(Canvas c, RectF r, float radDp, int fillCol, int edgeCol) {
        float rad = dp(radDp);
        if (fillCol != 0) c.drawRoundRect(r, rad, rad, fill(fillCol));
        if (edgeCol != 0) c.drawRoundRect(r, rad, rad, stroke(1f, edgeCol));
    }
}
