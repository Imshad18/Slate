package com.slate.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.ParcelFileDescriptor;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * A continuously scrolling document: all pages in one column, and images.
 *
 * Pages are not a thing you step through with buttons. Every reader anyone
 * has ever used scrolls, and a hundred-and-ninety-four page book behind a
 * NEXT button is unusable — which is exactly what the previous version was.
 * The whole document is laid out as one tall strip and the viewport moves
 * over it.
 *
 * Only the pages actually on screen are rasterised, and they are released as
 * they leave, so a long document costs the same memory as a short one.
 */
public class PageView extends View {

    public interface OnPage { void onPage(int index, int count); }

    private android.graphics.pdf.PdfRenderer pdf;
    private ParcelFileDescriptor pfd;
    private Bitmap single;                 // for an image rather than a PDF
    private int pageCount;
    private OnPage pageListener;

    /** Aspect ratio (height / width) of each page. */
    private float[] aspect;

    /** Rendered pages, keyed by index. */
    private final Map<Integer, Bitmap> cache = new HashMap<Integer, Bitmap>();
    private final Map<Integer, Float> cacheScale = new HashMap<Integer, Float>();
    private final java.util.Set<Integer> rendering =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());

    private final Matrix matrix = new Matrix();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG
            | Paint.ANTI_ALIAS_FLAG);

    /** Zoom, as a multiple of fit-to-width. */
    private float zoom = 1f;
    /** Scroll position down the whole document, and sideways when zoomed in. */
    private float scrollY, scrollX;

    private float lastX, lastY, pinchBase, pinchZoomBase, pinchCx, pinchCy;
    private boolean pinching, dragging;
    private long lastTapAt;
    private VelocityTracker velocity;
    private float flingV;
    private int touchSlop;

    private static final float GAP_DP = 10;

    public PageView(Context c) {
        super(c);
        setClickable(true);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    public void setOnPage(OnPage l) { pageListener = l; }

    // =====================================================================
    // SOURCES
    // =====================================================================

    public void setBitmap(Bitmap bm) {
        clearCache();
        single = bm;
        pageCount = 1;
        aspect = new float[]{bm == null ? 1 : bm.getHeight() / (float) bm.getWidth()};
        zoom = 1f;
        scrollY = scrollX = 0;
        invalidate();
    }

    /**
     * Opens a PDF.
     *
     * PdfRenderer needs a seekable descriptor, which a plain InputStream from
     * a content provider cannot give — hence the ParcelFileDescriptor.
     */
    public boolean openPdf(ParcelFileDescriptor fd) {
        closePdf();
        try {
            pfd = fd;
            pdf = new android.graphics.pdf.PdfRenderer(fd);
            pageCount = pdf.getPageCount();
            single = null;
            zoom = 1f;
            scrollY = scrollX = 0;
            measurePages();
            invalidate();
            return true;
        } catch (Throwable t) {
            closePdf();
            return false;
        }
    }

    /**
     * Records every page's shape.
     *
     * Needed before anything can be laid out, because a scroll position means
     * nothing without knowing how tall the document is. Assuming every page
     * matches the first is the usual shortcut and it breaks on any book with
     * a fold-out, a landscape plate, or a differently sized cover — the
     * scroll then drifts further out of true with every page.
     */
    private void measurePages() {
        aspect = new float[pageCount];
        for (int i = 0; i < pageCount; i++) {
            android.graphics.pdf.PdfRenderer.Page p = null;
            try {
                p = pdf.openPage(i);
                aspect[i] = p.getHeight() / (float) Math.max(1, p.getWidth());
            } catch (Throwable t) {
                aspect[i] = 1.414f;
            } finally {
                if (p != null) try { p.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public void closePdf() {
        clearCache();
        try { if (pdf != null) pdf.close(); } catch (Throwable ignored) {}
        try { if (pfd != null) pfd.close(); } catch (Throwable ignored) {}
        pdf = null; pfd = null;
    }

    private void clearCache() {
        for (Bitmap b : cache.values()) if (b != null && !b.isRecycled()) b.recycle();
        cache.clear();
        cacheScale.clear();
    }

    public boolean isPdf() { return pdf != null; }
    public int pageCount() { return pageCount; }

    /**
     * The page the reader is on: the topmost one still meaningfully visible.
     *
     * Two obvious rules both fail. Probing a point partway down the viewport
     * misses any page shorter than the screen — a landscape plate, a
     * half-height cover — and reports the one after it, so jumping to that
     * page announces the next one. Taking whichever page covers the most
     * screen has the same blind spot for the same reason.
     *
     * Topmost-still-visible matches what a reader means by "which page am I
     * on", and the sliver threshold is what stops the previous page from
     * holding the title while two lines of it trail off the top.
     */
    public int pageIndex() {
        if (aspect == null || aspect.length == 0) return 0;
        float sliver = getHeight() * 0.08f;
        float y = -scrollY;
        int fallback = 0;
        for (int i = 0; i < pageCount; i++) {
            float h = pageHeight(i);
            float visible = Math.min(y + h, getHeight()) - Math.max(y, 0);
            if (visible > 0) {
                fallback = i;
                if (visible >= sliver) return i;
            }
            y += h + gap();
            if (y > getHeight()) break;
        }
        return fallback;
    }

    public Bitmap currentBitmap() {
        if (single != null) return single;
        Bitmap b = cache.get(pageIndex());
        return b;
    }

    // =====================================================================
    // LAYOUT
    // =====================================================================

    private float gap() { return Ink.dp(GAP_DP); }

    /** Rendered width of a page at the current zoom. */
    private float pageWidth() { return getWidth() * zoom; }

    private float pageHeight(int i) {
        if (aspect == null || i < 0 || i >= aspect.length) return getHeight();
        return pageWidth() * aspect[i];
    }

    private float documentHeight() {
        if (aspect == null) return getHeight();
        float h = 0;
        for (int i = 0; i < pageCount; i++) h += pageHeight(i) + gap();
        return Math.max(h - gap(), 1);
    }

    /** Document-space top of a page. */
    private float pageTop(int i) {
        float y = 0;
        for (int k = 0; k < i && k < pageCount; k++) y += pageHeight(k) + gap();
        return y;
    }

    private void clampScroll() {
        float maxY = Math.max(0, documentHeight() - getHeight());
        scrollY = Math.max(0, Math.min(maxY, scrollY));
        float over = Math.max(0, pageWidth() - getWidth());
        scrollX = Math.max(-over / 2, Math.min(over / 2, scrollX));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Width drives everything, so every cached raster is now the wrong
        // size. Dropping them is cheaper than scaling them badly.
        clearCache();
        clampScroll();
        invalidate();
    }

    // =====================================================================
    // RENDERING
    // =====================================================================

    /**
     * Rasterises one page at the current zoom, on a worker.
     *
     * Rendering on the main thread stalls the scroll for as long as the page
     * takes, which on a dense scan is long enough to feel broken. Off-thread
     * means the strip keeps moving and pages fill in behind it.
     */
    private void requestRender(final int index) {
        if (pdf == null || index < 0 || index >= pageCount) return;
        Float at = cacheScale.get(index);
        if (at != null && Math.abs(at - zoom) < 0.12f) return;
        if (!rendering.add(index)) return;

        final int w = (int) pageWidth();
        final float z = zoom;
        if (w < 4) { rendering.remove(index); return; }

        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap bm = null;
                android.graphics.pdf.PdfRenderer.Page p = null;
                try {
                    // PdfRenderer allows only one open page at a time across
                    // the whole renderer, so every use of it is serialised.
                    synchronized (PageView.this) {
                        if (pdf == null) return;
                        p = pdf.openPage(index);
                        int h = (int) (w * (p.getHeight() / (float) p.getWidth()));
                        long px = (long) w * h;
                        int rw = w, rh = h;
                        if (px > 12_000_000L) {
                            double k = Math.sqrt(12_000_000.0 / px);
                            rw = Math.max(1, (int) (w * k));
                            rh = Math.max(1, (int) (h * k));
                        }
                        bm = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
                        bm.eraseColor(0xFFFFFFFF);
                        p.render(bm, null, null, android.graphics.pdf.PdfRenderer
                                .Page.RENDER_MODE_FOR_DISPLAY);
                    }
                } catch (Throwable t) {
                    bm = null;
                } finally {
                    if (p != null) try { p.close(); } catch (Throwable ignored) {}
                }
                final Bitmap done = bm;
                post(new Runnable() {
                    @Override public void run() {
                        rendering.remove(index);
                        if (done == null) return;
                        Bitmap old = cache.put(index, done);
                        cacheScale.put(index, z);
                        if (old != null && !old.isRecycled()) old.recycle();
                        invalidate();
                    }
                });
            }
        }, "slate-page-" + index).start();
    }

    /** Releases pages that are far from the viewport. */
    private void trimCache(int from, int to) {
        java.util.List<Integer> drop = new java.util.ArrayList<Integer>();
        for (Integer k : cache.keySet()) {
            if (k < from - 1 || k > to + 1) drop.add(k);
        }
        for (Integer k : drop) {
            Bitmap b = cache.remove(k);
            cacheScale.remove(k);
            if (b != null && !b.isRecycled()) b.recycle();
        }
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(Ink.BG);
        if (getWidth() == 0) return;

        if (single != null) { drawSingle(c); return; }
        if (pdf == null || aspect == null) return;

        advanceFling();

        float left = (getWidth() - pageWidth()) / 2f - scrollX;
        int first = -1, last = -1;
        float y = -scrollY;

        for (int i = 0; i < pageCount; i++) {
            float h = pageHeight(i);
            if (y + h >= -gap() && y <= getHeight() + gap()) {
                if (first < 0) first = i;
                last = i;

                RectF dst = new RectF(left, y, left + pageWidth(), y + h);
                c.drawRect(dst, Ink.fill(0xFFFFFFFF));

                Bitmap bm = cache.get(i);
                if (bm != null && !bm.isRecycled()) {
                    c.drawBitmap(bm, null, dst, paint);
                } else {
                    Paint p = Ink.text(9, Ink.TEXT_DIM, false);
                    Ink.textCenter(c, "page " + (i + 1), dst.centerX(),
                            dst.centerY(), p);
                    requestRender(i);
                }
                c.drawRect(dst, Ink.stroke(0.8f, Ink.LINE));
            }
            y += h + gap();
            if (y > getHeight() + gap() * 2 && first >= 0) break;
        }

        if (first >= 0) {
            // One page ahead and behind, so a scroll does not show blanks.
            requestRender(Math.max(0, first - 1));
            requestRender(Math.min(pageCount - 1, last + 1));
            trimCache(first, last);
        }

        drawEditing(c);
        drawPageBadge(c);
    }

    private void drawSingle(Canvas c) {
        if (single == null) return;
        advanceFling();
        float w = pageWidth();
        float h = w * aspect[0];
        float left = (getWidth() - w) / 2f - scrollX;
        float top = h <= getHeight() ? (getHeight() - h) / 2f : -scrollY;
        RectF dst = new RectF(left, top, left + w, top + h);
        matrix.reset();
        matrix.setRectToRect(new RectF(0, 0, single.getWidth(), single.getHeight()),
                dst, Matrix.ScaleToFit.FILL);
        c.drawBitmap(single, matrix, paint);
        drawEditing(c);
    }

    private void drawPageBadge(Canvas c) {
        if (pageCount <= 1) return;
        String label = (pageIndex() + 1) + " / " + pageCount;
        Paint p = Ink.text(9, Ink.TEXT, true);
        float w = p.measureText(label) + Ink.dp(20);
        RectF r = new RectF(getWidth() - w - Ink.dp(12), getHeight() - Ink.dp(42),
                getWidth() - Ink.dp(12), getHeight() - Ink.dp(14));
        Ink.round(c, r, 14, Ink.alpha(Ink.PANEL, 0.92f), Ink.LINE);
        Ink.textCenter(c, label, r.centerX(), r.centerY() + Ink.dp(3.5f), p);
        // Only on an actual change. This runs inside onDraw, so notifying
        // every frame would push a setText sixty times a second and make the
        // bar re-lay-out continuously while the document scrolls.
        int now = pageIndex();
        if (now != lastNotifiedPage) {
            lastNotifiedPage = now;
            if (pageListener != null) pageListener.onPage(now, pageCount);
        }
    }

    // =====================================================================
    // SCROLLING
    // =====================================================================

    /** Carries a flick forward and decays it, frame by frame. */
    private void advanceFling() {
        if (Math.abs(flingV) < 8) { flingV = 0; return; }
        scrollY -= flingV / 60f;
        flingV *= 0.93f;
        clampScroll();
        postInvalidateOnAnimation();
    }

    public void goToPage(int i) {
        if (aspect == null) return;
        i = Math.max(0, Math.min(pageCount - 1, i));
        scrollY = pageTop(i);
        flingV = 0;
        clampScroll();
        invalidate();
    }

    /** Fit the whole of the current page on screen. */
    public void fit() {
        zoom = 1f;
        scrollX = 0;
        clearCache();
        clampScroll();
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (tool == TOOL_COVER && e.getPointerCount() < 2 && handleEditDrag(e)) {
            return true;
        }

        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(e);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX(); lastY = e.getY();
                pinching = false; dragging = false;
                flingV = 0;
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() >= 2) {
                    pinching = true;
                    pinchBase = spacing(e);
                    pinchZoomBase = zoom;
                    pinchCx = (e.getX(0) + e.getX(1)) / 2;
                    pinchCy = (e.getY(0) + e.getY(1)) / 2;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (pinching && e.getPointerCount() >= 2) {
                    float d = spacing(e);
                    if (pinchBase > 10) zoomAbout(pinchZoomBase * (d / pinchBase),
                            pinchCx, pinchCy);
                } else if (!pinching) {
                    float dx = e.getX() - lastX, dy = e.getY() - lastY;
                    if (!dragging && (Math.abs(dx) > touchSlop
                            || Math.abs(dy) > touchSlop)) dragging = true;
                    if (dragging) {
                        scrollY -= dy;
                        scrollX -= dx;
                        clampScroll();
                        lastX = e.getX(); lastY = e.getY();
                        invalidate();
                    }
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                pinching = false;
                // Re-rasterise at the new zoom once the gesture ends; doing
                // it per frame would stall the pinch.
                if (pdf != null) invalidate();
                return true;

            case MotionEvent.ACTION_UP: {
                if (!dragging && !pinching) {
                    if (tool != TOOL_NONE && handleEditTap(e.getX(), e.getY())) {
                        return true;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastTapAt < 280) {
                        if (zoom > 1.3f) fit();
                        else zoomAbout(2.5f, e.getX(), e.getY());
                    }
                    lastTapAt = now;
                } else if (dragging) {
                    velocity.computeCurrentVelocity(1000);
                    flingV = velocity.getYVelocity();
                    postInvalidateOnAnimation();
                }
                dragging = false; pinching = false;
                if (velocity != null) { velocity.recycle(); velocity = null; }
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                dragging = false; pinching = false;
                if (velocity != null) { velocity.recycle(); velocity = null; }
                return true;
        }
        return super.onTouchEvent(e);
    }

    /**
     * Zooms about a point, keeping that point still.
     *
     * The scroll position is in document space, which stretches with the
     * zoom, so it has to be rescaled too. Without that the view jumps to a
     * different part of the document on every pinch.
     */
    private void zoomAbout(float want, float cx, float cy) {
        float old = zoom;
        zoom = Math.max(1f, Math.min(6f, want));
        if (Math.abs(zoom - old) < 0.001f) return;

        float docY = (scrollY + cy) / old;
        scrollY = docY * zoom - cy;
        scrollX = (scrollX + cx - getWidth() / 2f) * (zoom / old)
                - cx + getWidth() / 2f;

        // Cached pages are at the old scale; let them be redrawn.
        cacheScale.clear();
        clampScroll();
        invalidate();
    }

    private float spacing(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    // =====================================================================
    // EDIT MODE
    // =====================================================================

    public interface OnEditRun { void onEditRun(Pdf.Run run); }
    public interface OnTapPoint { void onTapPoint(int page, float pdfX, float pdfY); }
    public interface OnRegion {
        void onRegion(int page, float x, float y, float w, float h);
    }

    private java.util.List<Pdf.Run> runs;
    private int runsPage = -1;
    private float pageW = 612, pageH = 792;
    private OnEditRun editListener;
    private OnTapPoint pointListener;
    private OnRegion regionListener;

    public static final int TOOL_NONE = 0, TOOL_TEXT = 1, TOOL_ADD = 2, TOOL_COVER = 3;
    private int tool = TOOL_NONE;

    private int lastNotifiedPage = -1;

    private float regionX0, regionY0, regionX1, regionY1;
    private boolean draggingRegion;

    public void setRuns(java.util.List<Pdf.Run> r, int forPage, float w, float h) {
        runs = r;
        runsPage = forPage;
        if (w > 0) pageW = w;
        if (h > 0) pageH = h;
        invalidate();
    }

    public void setTool(int t) { tool = t; draggingRegion = false; invalidate(); }
    public int tool() { return tool; }
    public void setOnEditRun(OnEditRun l) { editListener = l; }
    public void setOnTapPoint(OnTapPoint l) { pointListener = l; }
    public void setOnRegion(OnRegion l) { regionListener = l; }

    /** Screen rectangle currently occupied by a page. */
    private RectF rectOf(int index) {
        float left = (getWidth() - pageWidth()) / 2f - scrollX;
        float top = pageTop(index) - scrollY;
        return new RectF(left, top, left + pageWidth(), top + pageHeight(index));
    }

    /**
     * PDF user space to screen, for a given page.
     *
     * The two systems disagree about which way is up — PDF counts from the
     * bottom-left, a canvas from the top-left — so y is flipped here. Getting
     * that wrong puts every edit box on the mirror image of its text.
     */
    private float toScreenX(int page, float x) {
        RectF r = rectOf(page);
        return r.left + (x / pageW) * r.width();
    }

    private float toScreenY(int page, float y) {
        RectF r = rectOf(page);
        return r.top + ((pageH - y) / pageH) * r.height();
    }

    private float toPdfX(int page, float sx) {
        RectF r = rectOf(page);
        return r.width() == 0 ? 0 : (sx - r.left) / r.width() * pageW;
    }

    private float toPdfY(int page, float sy) {
        RectF r = rectOf(page);
        return r.height() == 0 ? 0 : pageH - (sy - r.top) / r.height() * pageH;
    }

    void drawEditing(Canvas c) {
        if (tool == TOOL_NONE) return;

        if (tool == TOOL_TEXT && runs != null && runsPage >= 0) {
            Paint box = Ink.stroke(1f, Ink.alpha(Ink.ACCENT, 0.6f));
            Paint fillP = Ink.fill(Ink.alpha(Ink.ACCENT, 0.12f));
            for (Pdf.Run r : runs) {
                float left = toScreenX(runsPage, r.x);
                float top = toScreenY(runsPage, r.y + r.height);
                float right = toScreenX(runsPage, r.x + r.width);
                float bottom = toScreenY(runsPage, r.y - r.height * 0.22f);
                if (right < 0 || left > getWidth() || bottom < 0
                        || top > getHeight()) continue;
                RectF rect = new RectF(left, top, right, bottom);
                c.drawRect(rect, fillP);
                c.drawRect(rect, box);
            }
        }

        if (tool == TOOL_COVER && draggingRegion) {
            RectF r = new RectF(Math.min(regionX0, regionX1),
                    Math.min(regionY0, regionY1),
                    Math.max(regionX0, regionX1), Math.max(regionY0, regionY1));
            c.drawRect(r, Ink.fill(Ink.alpha(0xFFFFFFFF, 0.65f)));
            c.drawRect(r, Ink.stroke(1.6f, Ink.ACCENT));
        }

        String hint = tool == TOOL_TEXT ? "tap a highlighted line to edit it"
                : tool == TOOL_ADD ? "tap where the new text should go"
                : "drag over what you want covered";
        Paint p = Ink.text(9, Ink.TEXT, true);
        float w = p.measureText(hint) + Ink.dp(22);
        RectF bar = new RectF(getWidth() / 2f - w / 2, Ink.dp(10),
                getWidth() / 2f + w / 2, Ink.dp(40));
        Ink.round(c, bar, 14, Ink.alpha(Ink.PANEL, 0.96f), Ink.ACCENT);
        Ink.textCenter(c, hint, bar.centerX(), bar.centerY() + Ink.dp(3.5f), p);
    }

    boolean handleEditTap(float x, float y) {
        if (tool == TOOL_NONE) return false;

        if (tool == TOOL_TEXT) {
            if (runs == null || runsPage < 0) return true;
            Pdf.Run best = null;
            float bestArea = Float.MAX_VALUE;
            for (Pdf.Run r : runs) {
                float left = toScreenX(runsPage, r.x);
                float top = toScreenY(runsPage, r.y + r.height);
                float right = toScreenX(runsPage, r.x + r.width);
                float bottom = toScreenY(runsPage, r.y - r.height * 0.22f);
                // Generous: these boxes are estimated from character counts,
                // so an exact test would reject taps that visually landed on
                // the words.
                float pad = Ink.dp(6);
                if (x < left - pad || x > right + pad
                        || y < top - pad || y > bottom + pad) continue;
                float area = (right - left) * (bottom - top);
                if (area < bestArea) { bestArea = area; best = r; }
            }
            if (best != null && editListener != null) editListener.onEditRun(best);
            return true;
        }

        if (tool == TOOL_ADD && pointListener != null) {
            int p = pageIndex();
            pointListener.onTapPoint(p, toPdfX(p, x), toPdfY(p, y));
            return true;
        }
        return true;
    }

    boolean handleEditDrag(MotionEvent e) {
        if (tool != TOOL_COVER) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                regionX0 = regionX1 = e.getX();
                regionY0 = regionY1 = e.getY();
                draggingRegion = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (draggingRegion) {
                    regionX1 = e.getX(); regionY1 = e.getY();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (draggingRegion) {
                    draggingRegion = false;
                    int p = pageIndex();
                    float x0 = toPdfX(p, Math.min(regionX0, regionX1));
                    float x1 = toPdfX(p, Math.max(regionX0, regionX1));
                    float y0 = toPdfY(p, Math.max(regionY0, regionY1));
                    float y1 = toPdfY(p, Math.min(regionY0, regionY1));
                    invalidate();
                    if (Math.abs(x1 - x0) > 2 && Math.abs(y1 - y0) > 2
                            && regionListener != null) {
                        regionListener.onRegion(p, x0, y0, x1 - x0, y1 - y0);
                    }
                }
                return true;
        }
        return false;
    }
}
