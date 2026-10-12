package com.slate.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * A hex dump, for files nothing else can read.
 *
 * Drawn a row at a time from the byte array rather than formatted into a
 * string first: a hex dump is about four times the size of the file as text,
 * so a ten-megabyte binary would become forty megabytes of String before a
 * single line appeared.
 */
public class HexView extends View {

    private byte[] data = new byte[0];
    private long fileLength;
    private boolean truncated;

    private float scrollY;
    private float lastY;
    private boolean dragging;

    private static final int PER_ROW = 16;
    private float rowH, charW;

    public HexView(Context c) { super(c); setClickable(true); }

    public void setData(byte[] d, long fullLength) {
        data = d == null ? new byte[0] : d;
        fileLength = fullLength;
        truncated = fullLength > data.length;
        scrollY = 0;
        invalidate();
    }

    private void metrics() {
        Paint p = Ink.mono(10, Ink.TEXT, false);
        charW = p.measureText("0");
        rowH = Ink.dp(17);
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(Ink.BG);
        if (data.length == 0) {
            Ink.textCenter(c, "empty", getWidth() / 2f, getHeight() / 2f,
                    Ink.text(11, Ink.TEXT_DIM, false));
            return;
        }
        metrics();

        int rows = (data.length + PER_ROW - 1) / PER_ROW;
        int first = Math.max(0, (int) (scrollY / rowH));
        int last = Math.min(rows - 1, first + (int) (getHeight() / rowH) + 1);

        Paint off = Ink.mono(9.5f, Ink.GUTTER, false);
        Paint hex = Ink.mono(10, Ink.TEXT, false);
        Paint asc = Ink.mono(10, Ink.ACCENT, false);

        float offW = charW * 9;
        float hexX = offW + Ink.dp(8);
        float ascX = hexX + charW * (PER_ROW * 3) + Ink.dp(10);

        StringBuilder hexRow = new StringBuilder(PER_ROW * 3);
        StringBuilder ascRow = new StringBuilder(PER_ROW);

        for (int r = first; r <= last; r++) {
            float y = r * rowH - scrollY + rowH;
            int base = r * PER_ROW;

            hexRow.setLength(0);
            ascRow.setLength(0);
            for (int i = 0; i < PER_ROW; i++) {
                int idx = base + i;
                if (idx >= data.length) { hexRow.append("   "); continue; }
                int v = data[idx] & 255;
                hexRow.append(HEX[v >> 4]).append(HEX[v & 15]);
                hexRow.append(i == 7 ? "  " : " ");
                ascRow.append(v >= 32 && v < 127 ? (char) v : '.');
            }

            c.drawText(String.format(java.util.Locale.US, "%08X", base), 0, y, off);
            c.drawText(hexRow.toString(), hexX, y, hex);
            c.drawText(ascRow.toString(), ascX, y, asc);
        }

        if (truncated) {
            String msg = "showing first " + Doc.size(data.length)
                    + " of " + Doc.size(fileLength);
            Paint p = Ink.text(8.5f, Ink.TEXT_DIM, false);
            float w = p.measureText(msg) + Ink.dp(20);
            android.graphics.RectF box = new android.graphics.RectF(
                    getWidth() / 2f - w / 2, getHeight() - Ink.dp(36),
                    getWidth() / 2f + w / 2, getHeight() - Ink.dp(10));
            Ink.round(c, box, 13, Ink.alpha(Ink.PANEL, 0.94f), Ink.LINE);
            Ink.textCenter(c, msg, box.centerX(), box.centerY() + Ink.dp(3.5f), p);
        }
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastY = e.getY(); dragging = false; return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = e.getY() - lastY;
                if (Math.abs(dy) > Ink.dp(4)) dragging = true;
                if (dragging) {
                    int rows = (data.length + PER_ROW - 1) / PER_ROW;
                    float max = Math.max(0, rows * rowH - getHeight() + Ink.dp(40));
                    scrollY = Math.max(0, Math.min(max, scrollY - dy));
                    lastY = e.getY();
                    invalidate();
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }
}
