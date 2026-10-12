package com.slate.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Text recognition for machine-printed documents.
 *
 * There is no OCR in the Android framework and no usable one that can be
 * linked here — ML Kit needs native libraries and this project has no NDK —
 * so this is the real thing, written out: adaptive binarisation, connected
 * component labelling, line and word grouping, and glyph classification
 * against templates rendered from the device's own fonts.
 *
 * Be clear about what that buys. On screenshots, PDFs and clean scans of
 * ordinary fonts it reads well. On a photograph of a page at an angle, on
 * handwriting, or on heavy serif display faces it will not. Template matching
 * recognises shapes it has seen the shape of; it does not generalise the way
 * a trained model does. The honest framing is "pull the text out of a
 * screenshot", not "read anything".
 *
 * No Android types appear in this file, which is deliberate: the whole
 * pipeline can then be run and measured on a desktop JVM against images with
 * known content, instead of being judged by squinting at a phone.
 */
public final class Ocr {

    /** Supplies the shapes to match against. */
    public interface Glyphs {
        /** Characters this source can draw, in matching order. */
        char[] alphabet();
        /**
         * Ink coverage of one character on a GRID x GRID lattice, values 0..1,
         * row-major, trimmed to the glyph's own bounding box before sampling.
         */
        float[] template(char c);
        /** How far the character rises above the baseline, in x-heights. */
        float capTop(char c);
        /** How far it falls below the baseline, in x-heights. */
        float descend(char c);
    }

    public static final int GRID = 16;

    /** Maximum pixels fed to the recogniser; larger images are downsampled. */
    public static final int MAX_PIXELS = 4_000_000;

    public static class Result {
        public String text = "";
        public int lines, words, glyphs;
        public double confidence;      // 0..1, mean match quality
        public long millis;
    }

    /** One ink blob. */
    static class Blob {
        int x0, y0, x1, y1;            // inclusive bounds
        int pixels;
        float[] grid;                  // GRID*GRID coverage
        char best;
        float score;

        int w() { return x1 - x0 + 1; }
        int h() { return y1 - y0 + 1; }
        float aspect() { return w() / (float) Math.max(1, h()); }
        int cx() { return (x0 + x1) / 2; }
    }

    // =====================================================================

    /**
     * Reads an image.
     *
     * @param argb   pixels, row-major
     * @param w,h    dimensions
     * @param glyphs template source
     */
    public static Result read(int[] argb, int w, int h, Glyphs glyphs) {
        long t0 = System.currentTimeMillis();
        Result r = new Result();
        if (argb == null || w < 8 || h < 8) return r;

        // ---- 1. luminance, with a histogram ------------------------------
        int[] lum = new int[w * h];
        int[] hist = new int[256];
        for (int i = 0; i < lum.length; i++) {
            int p = argb[i];
            int v = (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587
                    + (p & 255) * 114) / 1000;
            lum[i] = v;
            hist[v]++;
        }

        // ---- 2. polarity, decided BEFORE thresholding --------------------
        //
        // This has to happen first, and the reason is not obvious. Adaptive
        // thresholding marks pixels darker than their neighbourhood, so on a
        // dark background with light text it marks the BACKGROUND around each
        // glyph — a halo, which is a minority of the image, so counting the
        // marked pixels afterwards and inverting never triggers. Every blob
        // downstream is then the hole where a letter was. Deciding from the
        // median luminance of the original image costs a histogram and is
        // simply correct: a light page has a light median, a dark one does not.
        int median = 0, seen = 0, half = lum.length / 2;
        for (int v = 0; v < 256; v++) {
            seen += hist[v];
            if (seen >= half) { median = v; break; }
        }
        if (median < 112) {
            for (int i = 0; i < lum.length; i++) lum[i] = 255 - lum[i];
        }

        // ---- 3. adaptive threshold ---------------------------------------
        boolean[] ink = binarise(lum, w, h);

        // ---- 4. components -------------------------------------------------
        List<Blob> blobs = components(ink, w, h);
        if (blobs.isEmpty()) { r.millis = System.currentTimeMillis() - t0; return r; }

        // ---- 5. discard noise and frames -----------------------------------
        int medianH = medianHeight(blobs);
        List<Blob> kept = new ArrayList<Blob>();
        for (Blob b : blobs) {
            if (b.pixels < 4) continue;                      // speckle
            if (b.h() > medianH * 6 && b.w() > medianH * 6) continue;  // a picture
            if (b.h() > h * 0.6 || b.w() > w * 0.92) continue;         // a rule or border
            kept.add(b);
        }
        if (kept.isEmpty()) { r.millis = System.currentTimeMillis() - t0; return r; }

        // ---- 6. rejoin split glyphs ---------------------------------------
        kept = mergeMarks(kept, medianH);

        // ---- 7. lines --------------------------------------------------------
        List<List<Blob>> lines = groupLines(kept, medianH);

        // ---- 7. classify and assemble ----------------------------------------
        StringBuilder out = new StringBuilder();
        double scoreSum = 0;
        int scored = 0;

        for (List<Blob> line : lines) {
            Collections.sort(line, new Comparator<Blob>() {
                @Override public int compare(Blob a, Blob b) { return a.x0 - b.x0; }
            });
            int lineTop = Integer.MAX_VALUE, lineBot = 0;
            for (Blob b : line) {
                lineTop = Math.min(lineTop, b.y0);
                lineBot = Math.max(lineBot, b.y1);
            }
            int lineH = Math.max(1, lineBot - lineTop + 1);

            // A word gap is measured against the line's OWN letter spacing.
            //
            // A fixed fraction of the line height splits proportional text to
            // pieces: the natural gap either side of a full stop in
            // "System.out.println" is wider than that, so it came back as
            // three words. The median gap between adjacent glyphs on this
            // line is what a letter gap actually is here, and a word break is
            // a large multiple of it.
            float baseline = baselineOf(line);
            float[] xh = xHeightOf(line, baseline, lineH);
            float xHeight = xh[0], geomTrust = xh[1];
            float wordGap = wordGapFor(line, lineH, xHeight);

            int prevRight = -1;
            for (Blob b : line) {
                if (prevRight >= 0 && b.x0 - prevRight > wordGap) out.append(' ');
                sample(ink, w, b);
                classify(b, glyphs, baseline, xHeight, geomTrust);
                out.append(b.best);
                scoreSum += b.score;
                scored++;
                prevRight = b.x1;
                r.glyphs++;
            }
            out.append('\n');
            r.lines++;
        }

        r.text = out.toString();
        r.words = r.text.trim().isEmpty() ? 0 : r.text.trim().split("\\s+").length;
        r.confidence = scored == 0 ? 0 : Math.max(0, Math.min(1, scoreSum / scored));
        r.millis = System.currentTimeMillis() - t0;
        return r;
    }

    // =====================================================================
    // BINARISATION
    // =====================================================================

    /**
     * Local-mean thresholding over an integral image.
     *
     * A single global threshold fails on anything with uneven lighting — a
     * photographed page, a screenshot with a gradient header — because the
     * darkest paper in one corner is lighter than the lightest ink in
     * another. Comparing each pixel against the mean of its own neighbourhood
     * costs two passes and is immune to that. The integral image is what
     * makes the window mean O(1) per pixel instead of O(window²).
     */
    static boolean[] binarise(int[] lum, int w, int h) {
        long[] integral = new long[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            long rowSum = 0;
            for (int x = 0; x < w; x++) {
                rowSum += lum[y * w + x];
                integral[(y + 1) * (w + 1) + (x + 1)] =
                        integral[y * (w + 1) + (x + 1)] + rowSum;
            }
        }

        int win = Math.max(8, Math.min(w, h) / 24);
        boolean[] ink = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - win), y1 = Math.min(h - 1, y + win);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - win), x1 = Math.min(w - 1, x + win);
                long area = (long) (x1 - x0 + 1) * (y1 - y0 + 1);
                long s = integral[(y1 + 1) * (w + 1) + (x1 + 1)]
                       - integral[y0 * (w + 1) + (x1 + 1)]
                       - integral[(y1 + 1) * (w + 1) + x0]
                       + integral[y0 * (w + 1) + x0];
                long mean = s / area;
                // 12 percent below the local mean counts as ink. Tighter and
                // thin strokes break up; looser and paper texture comes through.
                ink[y * w + x] = lum[y * w + x] < mean * 0.88;
            }
        }
        return ink;
    }

    // =====================================================================
    // COMPONENTS
    // =====================================================================

    /**
     * Eight-connected labelling with an explicit stack.
     *
     * Recursive flood fill is the obvious way and overflows the stack on the
     * first large connected region it meets — a table rule, an underline, a
     * dark photograph. An explicit int stack has no such limit.
     */
    static List<Blob> components(boolean[] ink, int w, int h) {
        List<Blob> out = new ArrayList<Blob>();
        boolean[] seen = new boolean[ink.length];
        int[] stack = new int[Math.max(1024, ink.length / 4)];

        for (int start = 0; start < ink.length; start++) {
            if (!ink[start] || seen[start]) continue;

            int sp = 0;
            stack[sp++] = start;
            seen[start] = true;

            Blob b = new Blob();
            b.x0 = b.x1 = start % w;
            b.y0 = b.y1 = start / w;

            while (sp > 0) {
                int p = stack[--sp];
                int px = p % w, py = p / w;
                b.pixels++;
                if (px < b.x0) b.x0 = px;
                if (px > b.x1) b.x1 = px;
                if (py < b.y0) b.y0 = py;
                if (py > b.y1) b.y1 = py;

                for (int dy = -1; dy <= 1; dy++) {
                    int ny = py + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = px + dx;
                        if (nx < 0 || nx >= w) continue;
                        int q = ny * w + nx;
                        if (!ink[q] || seen[q]) continue;
                        seen[q] = true;
                        if (sp == stack.length) {
                            int[] bigger = new int[stack.length * 2];
                            System.arraycopy(stack, 0, bigger, 0, stack.length);
                            stack = bigger;
                        }
                        stack[sp++] = q;
                    }
                }
            }
            out.add(b);
        }
        return out;
    }

    static int medianHeight(List<Blob> blobs) {
        int[] hs = new int[blobs.size()];
        for (int i = 0; i < hs.length; i++) hs[i] = blobs.get(i).h();
        java.util.Arrays.sort(hs);
        return Math.max(1, hs[hs.length / 2]);
    }

    // =====================================================================
    // LINES
    // =====================================================================

    /**
     * Groups blobs into text lines by vertical overlap.
     *
     * Not by y-centre, which splits a line the moment a capital or a
     * descender shifts one glyph's centre; and not by a fixed band, which
     * merges two lines of a tight paragraph. Overlap of the vertical extents
     * is the property that actually defines "on the same line".
     */
    static List<List<Blob>> groupLines(List<Blob> blobs, int medianH) {
        List<Blob> sorted = new ArrayList<Blob>(blobs);
        Collections.sort(sorted, new Comparator<Blob>() {
            @Override public int compare(Blob a, Blob b) {
                int d = a.y0 - b.y0;
                return d != 0 ? d : a.x0 - b.x0;
            }
        });

        List<List<Blob>> lines = new ArrayList<List<Blob>>();
        List<int[]> extents = new ArrayList<int[]>();   // {top, bottom}

        for (Blob b : sorted) {
            int bestLine = -1;
            float bestOverlap = 0;
            for (int i = 0; i < lines.size(); i++) {
                int[] e = extents.get(i);
                boolean small = b.h() < medianH * 0.55f;
                int slack = small ? (int) (medianH * 0.40f) : 0;
                int top = Math.max(e[0] - slack, b.y0);
                int bot = Math.min(e[1] + slack, b.y1);
                int overlap = bot - top;
                if (overlap <= 0) continue;
                float frac = overlap / (float) Math.min(e[1] - e[0], b.h());
                if (frac > bestOverlap) { bestOverlap = frac; bestLine = i; }
            }
            // Half of the shorter extent is the point where "same line" stops
            // being ambiguous; below it, a tall glyph on the line above would
            // start capturing the line below.
            //
            // Punctuation is the exception. A comma or a full stop sits
            // entirely at or below the baseline, so when the line it belongs
            // to happens to contain no descender it overlaps that line by
            // nothing at all and starts a line of its own — which is exactly
            // what put a stray row containing one mark in the middle of an
            // invoice. Small marks are therefore allowed to join on much less.
            float need = b.h() < medianH * 0.55f ? 0.02f : 0.45f;
            if (bestLine >= 0 && bestOverlap > need) {
                lines.get(bestLine).add(b);
                int[] e = extents.get(bestLine);
                e[0] = Math.min(e[0], b.y0);
                e[1] = Math.max(e[1], b.y1);
            } else {
                List<Blob> l = new ArrayList<Blob>();
                l.add(b);
                lines.add(l);
                extents.add(new int[]{b.y0, b.y1});
            }
        }

        // top to bottom
        final List<int[]> ex = extents;
        Integer[] order = new Integer[lines.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                return ex.get(a)[0] - ex.get(b)[0];
            }
        });
        List<List<Blob>> result = new ArrayList<List<Blob>>();
        for (Integer i : order) result.add(lines.get(i));
        return result;
    }

    // =====================================================================
    // CLASSIFICATION
    // =====================================================================

    /** Resamples a blob's ink onto the GRID lattice by area coverage. */
    static void sample(boolean[] ink, int w, Blob b) {
        float[] g = new float[GRID * GRID];
        int bw = b.w(), bh = b.h();
        for (int gy = 0; gy < GRID; gy++) {
            int sy0 = b.y0 + gy * bh / GRID;
            int sy1 = b.y0 + (gy + 1) * bh / GRID;
            if (sy1 <= sy0) sy1 = sy0 + 1;
            for (int gx = 0; gx < GRID; gx++) {
                int sx0 = b.x0 + gx * bw / GRID;
                int sx1 = b.x0 + (gx + 1) * bw / GRID;
                if (sx1 <= sx0) sx1 = sx0 + 1;
                int count = 0, total = 0;
                for (int y = sy0; y < sy1; y++) {
                    for (int x = sx0; x < sx1; x++) {
                        total++;
                        if (ink[y * w + x]) count++;
                    }
                }
                g[gy * GRID + gx] = total == 0 ? 0 : count / (float) total;
            }
        }
        b.grid = g;
    }

    /**
     * Picks the character whose template this blob looks most like.
     *
     * Geometry is expressed against the line's BASELINE and X-HEIGHT, not
     * against its overall extent. That distinction is the whole difficulty.
     * A line's extent depends on which characters happen to be in it — a line
     * with no descender and no capital is much shorter than one with both —
     * so a glyph's position within that extent means something different on
     * every line, and weighting it heavily made descenders read as their
     * ascender-less twins: p as n, y as v, j as a stray mark.
     *
     * Baseline and x-height are properties of the typeface at that size, not
     * of the sentence, so they are stable. Measured from the line itself:
     * most characters sit on the baseline, and most lowercase reach x-height,
     * so the medians find both without knowing what the text says.
     */
    static void classify(Blob b, Glyphs glyphs, float baseline, float xHeight,
                         float trust) {
        char[] alpha = glyphs.alphabet();
        float xh = Math.max(1f, xHeight);

        float above = (baseline - b.y0) / xh;      // 1.0 for x, ~1.4 for A
        float below = (b.y1 - baseline) / xh;      // 0 for x, ~0.4 for p
        float aspect = b.aspect();

        float bestD = Float.MAX_VALUE;
        char best = '?';

        for (char c : alpha) {
            float[] t = glyphs.template(c);
            if (t == null) continue;

            float d = 0;
            for (int i = 0; i < GRID * GRID; i++) {
                float diff = b.grid[i] - t[i];
                d += diff * diff;
            }
            d /= (GRID * GRID);

            // S/s, O/o, C/c, Z/z, 0/o, P/p are the same shape at different
            // sizes. The grid cannot tell them apart at all; these two terms
            // are the only thing that can.
            d += 1.05f * trust * Math.abs(above - glyphs.capTop(c));
            d += 1.05f * trust * Math.abs(below - glyphs.descend(c));

            float tAspect = templateAspect(t);
            d += 0.20f * Math.abs(aspect - tAspect) / Math.max(1f, aspect + tAspect);

            if (d < bestD) { bestD = d; best = c; }
        }
        b.best = best;
        b.score = (float) Math.max(0, Math.min(1, 1.0 - bestD * 2.2));
    }

    /** The baseline of a line: where most glyph bottoms sit. */
    static float baselineOf(List<Blob> line) {
        int[] bottoms = new int[line.size()];
        for (int i = 0; i < bottoms.length; i++) bottoms[i] = line.get(i).y1;
        java.util.Arrays.sort(bottoms);
        return bottoms[bottoms.length / 2];
    }

    /**
     * The x-height of a line, and how much to believe it.
     *
     * Returns {xHeight, confidence}. The confidence matters more than the
     * value, and here is why: a line reading "1234567890" contains no
     * lowercase at all, so the median height of its baseline-sitting glyphs
     * is the CAP height, and calling that the x-height makes every digit look
     * like a short character. Weighted heavily, that is worse than having no
     * geometry at all — it actively argued that 5 was s and 0 was o, which is
     * exactly what came back.
     *
     * Two distinct heights among baseline glyphs is the evidence that a line
     * has both cases. Without it, the feature is guessed and trusted little.
     */
    static float[] xHeightOf(List<Blob> line, float baseline, int lineH) {
        List<Integer> hs = new ArrayList<Integer>();
        for (Blob b : line) {
            if (Math.abs(b.y1 - baseline) > lineH * 0.14f) continue;
            // Full stops, commas and underscores sit on the baseline and are
            // a few pixels tall. Left in, they form their own cluster at the
            // bottom and the split below decides the x-height is the height
            // of a full stop — which turned a whole line of code into noise.
            if (b.h() < lineH * 0.25f) continue;
            {
                int hh = (int) (baseline - b.y0);
                if (hh > 0) hs.add(hh);
            }
        }
        if (hs.size() < 2) return new float[]{Math.max(1, lineH * 0.62f), 0.12f};

        Collections.sort(hs);
        // Split the heights into two clusters at their largest proportional
        // jump, exactly as the word gaps are split. A percentile does not
        // work here: on a line that is mostly digits with a couple of
        // lowercase letters, the 25th percentile still lands on a capital,
        // and the x-height comes out far too tall — which made every digit
        // look short and read 2026 as :o:e.
        Collections.sort(hs);
        int n = hs.size();
        float bestRatio = 0;
        int cut = -1;
        for (int i = 1; i < n; i++) {
            float ratio = (hs.get(i) + 1f) / (hs.get(i - 1) + 1f);
            if (ratio > bestRatio) { bestRatio = ratio; cut = i; }
        }

        // A cap is around 1.4 x-heights, so a jump of 1.18 is enough to
        // believe there are two sizes present. Less than that is one size.
        if (cut > 0 && bestRatio > 1.18f) {
            long sum = 0;
            for (int i = 0; i < cut; i++) sum += hs.get(i);
            return new float[]{Math.max(1, sum / (float) cut), 0.95f};
        }

        // One height only. Guess from the typical ratio and barely trust it,
        // so shape decides instead of a number that was invented.
        int med = hs.get(n / 2);
        return new float[]{Math.max(1, med / 1.38f), 0.12f};
    }

    /** Width-to-height of the inked part of a template. */
    static float templateAspect(float[] t) {
        int minX = GRID, maxX = -1, minY = GRID, maxY = -1;
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                if (t[y * GRID + x] > 0.12f) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return 1f;
        return (maxX - minX + 1) / (float) Math.max(1, maxY - minY + 1);
    }

    /**
     * Rejoins glyphs that are made of more than one piece.
     *
     * The dot of an i, the dot of a j, both dots of a colon, the dot under a
     * question mark, any accent — each is its own connected component, and
     * left alone each becomes its own character. That is why "Invoice" came
     * back as "lnvolce": every dotted i had lost its dot and matched 'l'
     * instead, and the orphaned dots were scattered through the line as
     * full stops and occasionally spawned a whole phantom line of their own.
     *
     * The test is horizontal overlap plus a small vertical gap, applied to
     * small blobs only, with a ceiling on the merged height so two stacked
     * letters on adjacent lines can never be fused into one.
     */
    static List<Blob> mergeMarks(List<Blob> blobs, int medianH) {
        List<Blob> out = new ArrayList<Blob>(blobs);
        boolean merged = true;
        int guard = 0;

        while (merged && guard++ < 6) {
            merged = false;
            for (int i = 0; i < out.size(); i++) {
                Blob a = out.get(i);
                // only small pieces are candidates to be absorbed
                if (a.h() > medianH * 0.62f) continue;

                int bestJ = -1;
                float bestGap = Float.MAX_VALUE;
                for (int j = 0; j < out.size(); j++) {
                    if (i == j) continue;
                    Blob b = out.get(j);

                    int ox0 = Math.max(a.x0, b.x0), ox1 = Math.min(a.x1, b.x1);
                    int overlap = ox1 - ox0 + 1;
                    if (overlap <= 0) continue;
                    // the mark must sit over (or under) the body, not beside it
                    if (overlap < Math.min(a.w(), b.w()) * 0.55f) continue;

                    int gap = a.y1 < b.y0 ? b.y0 - a.y1 : a.y0 - b.y1;
                    if (gap < 0) gap = 0;                 // already touching
                    if (gap > medianH * 0.55f) continue;

                    int mergedH = Math.max(a.y1, b.y1) - Math.min(a.y0, b.y0) + 1;
                    if (mergedH > medianH * 1.70f) continue;

                    if (gap < bestGap) { bestGap = gap; bestJ = j; }
                }

                if (bestJ >= 0) {
                    Blob b = out.get(bestJ);
                    b.x0 = Math.min(b.x0, a.x0);
                    b.x1 = Math.max(b.x1, a.x1);
                    b.y0 = Math.min(b.y0, a.y0);
                    b.y1 = Math.max(b.y1, a.y1);
                    b.pixels += a.pixels;
                    out.remove(i);
                    i--;
                    merged = true;
                }
            }
        }
        return out;
    }

    /**
     * Where the gaps on this line stop being letter spacing and start being
     * word spacing.
     *
     * Neither a fraction of the line height nor a multiple of the median gap
     * survives contact with a proportional font: letter gaps there vary
     * enormously — "rn" is tight, "vo" is loose — so a fixed multiple either
     * splits words apart or runs a whole sentence together, and this did both
     * on different fonts.
     *
     * The gaps are genuinely bimodal though, so the threshold is found rather
     * than assumed: sort them, look for the largest proportional jump in the
     * upper part of the sorted list, and cut there. That is a one-dimensional
     * two-cluster split, and it adapts to any face at any size.
     */
    static float wordGapFor(List<Blob> line, int lineH, float xHeight) {
        if (line.size() < 4) return Math.max(lineH * 0.30f, xHeight * 0.45f);

        int n = line.size() - 1;
        int[] gaps = new int[n];
        for (int i = 1; i < line.size(); i++) {
            gaps[i - 1] = Math.max(0, line.get(i).x0 - line.get(i - 1).x1);
        }
        int[] sorted = gaps.clone();
        java.util.Arrays.sort(sorted);

        // Look only in the top half: a word gap is never a below-median gap,
        // and searching the whole range finds a meaningless jump from 0 to 1.
        int from = Math.max(1, n / 2);
        float bestRatio = 0;
        int cut = -1;
        for (int i = from; i < n; i++) {
            float lo = sorted[i - 1] + 1f, hi = sorted[i] + 1f;
            float ratio = hi / lo;
            if (ratio > bestRatio) { bestRatio = ratio; cut = i; }
        }

        // A jump of less than about two to one is not a real break — that is
        // a line which is one long word, and anything would be a false split.
        if (cut < 0 || bestRatio < 1.55f) {
            // No clean break found. Fall back to a high percentile rather
            // than returning "never", which ran whole sentences together
            // into a single unbroken word.
            return Math.max(sorted[(int) (n * 0.80f)] + 1f, xHeight * 0.42f);
        }

        float threshold = (sorted[cut - 1] + sorted[cut]) * 0.5f;
        // Never below a plausible floor; a one-pixel "word gap" is noise.
        return Math.max(threshold, xHeight * 0.18f);
    }
}
