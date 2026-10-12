package com.slate.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.widget.EditText;

/**
 * The text and code editor.
 *
 * An EditText, not a custom canvas view, and that is a deliberate trade. A
 * canvas view scrolls a ten-megabyte file more smoothly, but it also means
 * reimplementing the cursor, selection handles, the clipboard, autocorrect,
 * IME composition and accessibility — and getting any of those subtly wrong
 * is far more damaging to an editor than a dropped frame.
 *
 * What makes it fast enough is that highlighting is never applied to the
 * whole document. Only the visible window plus a margin is coloured, on a
 * debounce, which keeps the span count in the low hundreds no matter how
 * large the file is. The usual reason a syntax-highlighted EditText grinds to
 * a halt is a Spannable carrying tens of thousands of spans.
 */
public class CodeView extends EditText {

    private int lang = Syntax.NONE;
    private boolean showLineNumbers = true;
    private boolean wrap;

    private final Rect visible = new Rect();

    private int lastFrom = -1, lastTo = -1;
    private boolean highlighting;
    private boolean dirty;

    public interface OnDirty { void onDirty(boolean d); }
    private OnDirty dirtyListener;

    public CodeView(Context c) {
        super(c);
        setBackgroundColor(0);
        setTypeface(Typeface.MONOSPACE);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setHorizontallyScrolling(true);
        setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        applyColours();


        addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                if (highlighting) return;
                if (!dirty) { dirty = true; if (dirtyListener != null) dirtyListener.onDirty(true); }
                scheduleHighlight();
            }
        });
    }

    public void applyColours() {
        setTextColor(Ink.TEXT);
        setHintTextColor(Ink.TEXT_DIM);
        try { setHighlightColor(Ink.SEL); } catch (Throwable ignored) {}
        invalidateHighlight();
    }

    public void setOnDirty(OnDirty l) { dirtyListener = l; }
    public boolean isDirty() { return dirty; }
    public void markClean() {
        dirty = false;
        if (dirtyListener != null) dirtyListener.onDirty(false);
    }

    public void setLanguage(int l) { lang = l; invalidateHighlight(); }
    public int language() { return lang; }

    public void setLineNumbers(boolean on) {
        showLineNumbers = on;
        updatePadding();
        invalidate();
    }
    public boolean lineNumbers() { return showLineNumbers; }

    public void setWrap(boolean on) {
        wrap = on;
        setHorizontallyScrolling(!on);
        requestLayout();
    }
    public boolean wrapping() { return wrap; }

    public void setFontSize(float sp) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(7f, Math.min(28f, sp)));
        updatePadding();
    }

    private void updatePadding() {
        int left = (int) Ink.dp(12);
        setPadding(left, (int) Ink.dp(8), (int) Ink.dp(14), (int) Ink.dp(160));
    }


    // =====================================================================

    @Override protected void onDraw(Canvas canvas) {
        // The gutter is a sibling view now; see Gutter.
        super.onDraw(canvas);
    }

    /** Logical line count, for sizing the gutter. */
    public int logicalLineCount() {
        CharSequence t = getText();
        if (lineStartsFor != t.length() || lineStarts == null) rebuildLineIndex(t);
        return lineStarts.length;
    }

    /** Which logical line a character offset falls on, 1-based. */
    public int logicalLineAt(int offset) { return countLines(offset); }

    /** Which logical line an offset is on. Cached, because it is O(n). */
    private int[] lineStarts;
    private int lineStartsFor = -1;

    private int countLines(int offset) {
        CharSequence t = getText();
        if (lineStartsFor != t.length() || lineStarts == null) rebuildLineIndex(t);
        int lo = 0, hi = lineStarts.length - 1, ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineStarts[mid] <= offset) { ans = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return ans + 1;
    }

    /**
     * Index of every logical line start.
     *
     * Rebuilt only when the length changes. Scanning for newlines on every
     * gutter row would be O(n) per row and O(n²) per frame, which is the
     * difference between a gutter that is free and one that makes a large
     * file unusable.
     */
    private void rebuildLineIndex(CharSequence t) {
        int count = 1;
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) == '\n') count++;
        lineStarts = new int[count];
        int n = 1;
        lineStarts[0] = 0;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '\n' && n < count) lineStarts[n++] = i + 1;
        }
        lineStartsFor = t.length();
    }

    // =====================================================================
    // HIGHLIGHTING
    // =====================================================================

    private final Runnable highlightTask = new Runnable() {
        @Override public void run() { highlightVisible(true); }
    };

    private void scheduleHighlight() {
        removeCallbacks(highlightTask);
        // Long enough that a run of keystrokes rehighlights once, short
        // enough that it feels immediate when you stop.
        postDelayed(highlightTask, 110);
    }

    public void invalidateHighlight() {
        lastFrom = lastTo = -1;
        scheduleHighlight();
    }

    @Override protected void onScrollChanged(int l, int t, int ol, int ot) {
        super.onScrollChanged(l, t, ol, ot);
        highlightVisible(false);
    }

    /** Colours the visible window, with a margin so scrolling stays ahead. */
    void highlightVisible(boolean force) {
        if (lang == Syntax.NONE) return;
        android.text.Layout layout = getLayout();
        if (layout == null || !(getText() instanceof android.text.Spannable)) return;

        getLocalVisibleRect(visible);
        int h = Math.max(1, visible.height());
        int firstLine = layout.getLineForVertical(Math.max(0, visible.top - h));
        int lastLine = layout.getLineForVertical(visible.bottom + h);
        int from = layout.getLineStart(firstLine);
        int to = layout.getLineEnd(Math.min(lastLine, layout.getLineCount() - 1));

        if (!force && from == lastFrom && to == lastTo) return;
        lastFrom = from; lastTo = to;

        // Start from the beginning of the line so a token is never cut in
        // half, and extend to a line end for the same reason.
        highlighting = true;
        try {
            Syntax.apply((android.text.Spannable) getText(), from, to, lang);
        } catch (Throwable ignored) {
        } finally {
            highlighting = false;
        }
    }
}
