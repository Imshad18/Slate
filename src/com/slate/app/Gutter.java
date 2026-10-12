package com.slate.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/**
 * Line numbers, as a column beside the editor rather than inside it.
 *
 * Drawing them within the EditText seemed simpler and was wrong: with
 * horizontal scrolling the numbers are part of the scrolled content, so they
 * slide away under the text and end up floating in the middle of the line
 * they are supposed to label. A sibling view shares the vertical scroll and
 * cannot move sideways at all.
 */
public class Gutter extends View {

    private CodeView code;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public Gutter(Context c) {
        super(c);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextAlign(Paint.Align.RIGHT);
    }

    public void attach(CodeView c) { code = c; invalidate(); }

    /** Wide enough for the largest line number the file can reach. */
    public int widthFor() {
        if (code == null) return (int) Ink.dp(34);
        paint.setTextSize(code.getTextSize() * 0.84f);
        int lines = Math.max(10, code.logicalLineCount());
        int digits = String.valueOf(lines).length();
        return (int) (paint.measureText("0") * digits + Ink.dp(16));
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int h = code == null ? 0 : code.getHeight();
        setMeasuredDimension(widthFor(), Math.max(h, MeasureSpec.getSize(hSpec)));
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(Ink.BG_2);
        if (code == null) return;
        android.text.Layout layout = code.getLayout();
        if (layout == null) return;

        float w = getWidth();
        c.drawLine(w - 1, 0, w - 1, getHeight(), Ink.stroke(1f, Ink.LINE));

        paint.setTextSize(code.getTextSize() * 0.84f);
        int cursorLine = -1;
        try { cursorLine = layout.getLineForOffset(code.getSelectionStart()); }
        catch (Throwable ignored) {}

        CharSequence t = code.getText();
        int top = code.getPaddingTop();

        // Only logical lines are numbered. Numbering each wrapped row would
        // make the gutter disagree with every stack trace and error message
        // the file will ever appear in.
        for (int line = 0; line < layout.getLineCount(); line++) {
            int start = layout.getLineStart(line);
            if (start != 0 && (start - 1 >= t.length() || t.charAt(start - 1) != '\n')) {
                continue;
            }
            float y = top + layout.getLineBaseline(line);
            if (y < -Ink.dp(20) || y > getHeight() + Ink.dp(20)) continue;
            paint.setColor(line == cursorLine ? Ink.ACCENT : Ink.GUTTER);
            c.drawText(String.valueOf(code.logicalLineAt(start)),
                    w - Ink.dp(8), y, paint);
        }
    }
}
