package com.slate.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;

/**
 * OCR templates rendered from the device's own fonts.
 *
 * Shapes come from the same font the phone draws everything else with, which
 * is usually the font in whatever was screenshotted. Templates are built once
 * and cached; building all of them takes a few milliseconds and happens off
 * the main thread the first time OCR is asked for.
 */
public class AndroidGlyphs implements Ocr.Glyphs {

    /** Everything worth recognising in ordinary documents and code. */
    public static final String ALPHABET =
            "abcdefghijklmnopqrstuvwxyz"
          + "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
          + "0123456789"
          + ".,:;!?'\"()[]{}<>-_=+*/\\|&%$#@^~`";

    private static final int RENDER = 96;

    private final Typeface face;
    private final char[] alpha = ALPHABET.toCharArray();
    private final Map<Character, float[]> grids = new HashMap<Character, float[]>();
    private final Map<Character, float[]> metrics = new HashMap<Character, float[]>();
    private float xHeight = 1;

    public AndroidGlyphs(Typeface f) {
        face = f == null ? Typeface.DEFAULT : f;
        float[] x = measure('x');
        xHeight = (x == null || x[0] <= 0) ? RENDER * 0.5f : x[0];
    }

    @Override public char[] alphabet() { return alpha; }

    /** {aboveBaseline, height, belowBaseline} in render pixels. */
    private float[] measure(char c) {
        float[] m = metrics.get(c);
        if (m != null) return m;
        boolean[] ink = render(c);
        int w = RENDER * 2, h = RENDER * 2, baseline = RENDER;
        int minY = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (ink[y * w + x]) {
                    if (minY < 0) minY = y;
                    maxY = y;
                    break;
                }
            }
        }
        m = minY < 0 ? new float[]{0, 0, 0}
                     : new float[]{baseline - minY, maxY - minY + 1, maxY - baseline};
        metrics.put(c, m);
        return m;
    }

    /**
     * Rasterises one character to a boolean ink mask.
     *
     * ARGB_8888 rather than the smaller ALPHA_8, because ALPHA_8's buffer
     * layout is a trap: copyPixelsToBuffer into an int buffer packs four
     * alpha bytes per int, and reading those back as pixels produces
     * plausible-looking nonsense rather than an obvious failure.
     */
    private boolean[] render(char c) {
        int w = RENDER * 2, h = RENDER * 2;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        cv.drawColor(Color.BLACK);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(face);
        p.setTextSize(RENDER);
        p.setColor(Color.WHITE);
        cv.drawText(String.valueOf(c), RENDER / 2f, RENDER, p);

        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        bm.recycle();

        boolean[] ink = new boolean[w * h];
        for (int i = 0; i < px.length; i++) ink[i] = (px[i] & 255) > 110;
        return ink;
    }

    @Override public float[] template(char c) {
        float[] cached = grids.get(c);
        if (cached != null) return cached;

        boolean[] ink = render(c);
        int w = RENDER * 2, h = RENDER * 2;
        int minX = w, maxX = -1, minY = h, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!ink[y * w + x]) continue;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        float[] g = new float[Ocr.GRID * Ocr.GRID];
        if (maxX < 0) { grids.put(c, g); return g; }

        int bw = maxX - minX + 1, bh = maxY - minY + 1;
        for (int gy = 0; gy < Ocr.GRID; gy++) {
            int sy0 = minY + gy * bh / Ocr.GRID, sy1 = minY + (gy + 1) * bh / Ocr.GRID;
            if (sy1 <= sy0) sy1 = sy0 + 1;
            for (int gx = 0; gx < Ocr.GRID; gx++) {
                int sx0 = minX + gx * bw / Ocr.GRID, sx1 = minX + (gx + 1) * bw / Ocr.GRID;
                if (sx1 <= sx0) sx1 = sx0 + 1;
                int n = 0, tot = 0;
                for (int y = sy0; y < sy1 && y < h; y++) {
                    for (int x = sx0; x < sx1 && x < w; x++) {
                        tot++;
                        if (ink[y * w + x]) n++;
                    }
                }
                g[gy * Ocr.GRID + gx] = tot == 0 ? 0 : n / (float) tot;
            }
        }
        grids.put(c, g);
        return g;
    }

    @Override public float capTop(char c) { return measure(c)[0] / xHeight; }

    @Override public float descend(char c) {
        return Math.max(0, measure(c)[2]) / xHeight;
    }

    /** Builds every template up front, so the first scan is not the slow one. */
    public void warm() { for (char c : alpha) template(c); }
}
