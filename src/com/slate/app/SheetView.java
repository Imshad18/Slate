package com.slate.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * A spreadsheet grid, drawn rather than laid out.
 *
 * A real table of views is out of the question — a modest spreadsheet is
 * tens of thousands of cells, and inflating a view per cell would take
 * seconds and hundreds of megabytes. Only the cells inside the viewport are
 * ever touched, so a sheet with a million cells scrolls exactly as fast as
 * one with ten.
 */
public class SheetView extends View {

    public interface OnEdit { void onEdit(int row, int col, String current); }

    private List<List<String>> rows = new ArrayList<List<String>>();
    private float[] colW;
    private int cols;
    private boolean editable;
    private OnEdit editListener;

    private float scrollX, scrollY;
    private float lastX, lastY;
    private boolean dragging;
    private long downAt;
    private int selRow = -1, selCol = -1;

    private float rowH, headerH, gutterW;

    public SheetView(Context c) {
        super(c);
        setClickable(true);
        rowH = Ink.dp(30);
        headerH = Ink.dp(26);
        gutterW = Ink.dp(46);
    }

    public void setOnEdit(OnEdit l) { editListener = l; }
    public void setEditable(boolean e) { editable = e; }

    public void setRows(List<List<String>> r) {
        rows = r == null ? new ArrayList<List<String>>() : r;
        cols = 0;
        for (List<String> row : rows) cols = Math.max(cols, row.size());
        measureColumns();
        scrollX = scrollY = 0;
        invalidate();
    }

    public List<List<String>> rows() { return rows; }

    public void setCell(int r, int c, String v) {
        while (rows.size() <= r) rows.add(new ArrayList<String>());
        List<String> row = rows.get(r);
        while (row.size() <= c) row.add("");
        row.set(c, v);
        cols = Math.max(cols, c + 1);
        measureColumns();
        invalidate();
    }

    /**
     * Column widths from the content, sampled rather than measured in full.
     *
     * Measuring every cell in a large sheet costs more than drawing it. The
     * first few hundred rows decide the widths, which is what the eye judges
     * the layout by anyway.
     */
    private void measureColumns() {
        colW = new float[Math.max(1, cols)];
        Paint p = Ink.mono(11, Ink.TEXT, false);
        int sample = Math.min(rows.size(), 300);
        for (int c = 0; c < cols; c++) {
            float w = Ink.dp(54);
            for (int r = 0; r < sample; r++) {
                List<String> row = rows.get(r);
                if (c >= row.size()) continue;
                String v = row.get(c);
                if (v == null || v.isEmpty()) continue;
                w = Math.max(w, p.measureText(v) + Ink.dp(18));
            }
            colW[c] = Math.min(w, Ink.dp(240));
        }
    }

    private float colX(int c) {
        float x = gutterW;
        for (int i = 0; i < c && i < colW.length; i++) x += colW[i];
        return x;
    }

    private float totalW() {
        float w = gutterW;
        for (float v : colW) w += v;
        return w;
    }

    // =====================================================================

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Ink.BG);
        if (rows.isEmpty()) {
            Ink.textCenter(canvas, "empty sheet", getWidth() / 2f, getHeight() / 2f,
                    Ink.text(11, Ink.TEXT_DIM, false));
            return;
        }

        int firstRow = Math.max(0, (int) (scrollY / rowH));
        int lastRow = Math.min(rows.size() - 1,
                firstRow + (int) (getHeight() / rowH) + 2);

        Paint cellPaint = Ink.mono(11, Ink.TEXT, false);
        Paint headPaint = Ink.mono(9.5f, Ink.TEXT_DIM, true);
        Paint rule = Ink.stroke(0.8f, Ink.LINE);

        // ---- cells -------------------------------------------------------
        for (int r = firstRow; r <= lastRow; r++) {
            float y = headerH + r * rowH - scrollY;
            if (y > getHeight()) break;
            List<String> row = rows.get(r);

            if (r % 2 == 1) {
                canvas.drawRect(0, y, getWidth(), y + rowH, Ink.fill(Ink.BG_2));
            }

            for (int c = 0; c < cols; c++) {
                float x = colX(c) - scrollX;
                if (x > getWidth()) break;
                float w = colW[c];
                if (x + w < gutterW) continue;

                if (r == selRow && c == selCol) {
                    canvas.drawRect(x, y, x + w, y + rowH,
                            Ink.fill(Ink.alpha(Ink.ACCENT, 0.20f)));
                    canvas.drawRect(x, y, x + w, y + rowH, Ink.stroke(1.4f, Ink.ACCENT));
                }

                String v = c < row.size() ? row.get(c) : "";
                if (v != null && !v.isEmpty()) {
                    canvas.save();
                    canvas.clipRect(x, y, x + w - Ink.dp(3), y + rowH);
                    // numbers right-aligned, text left: the convention every
                    // spreadsheet uses, and it makes a column of figures
                    // readable at a glance
                    boolean numeric = isNumeric(v);
                    cellPaint.setColor(numeric ? Ink.NUMBER : Ink.TEXT);
                    if (numeric) {
                        Ink.textRight(canvas, v, x + w - Ink.dp(8),
                                y + rowH * 0.68f, cellPaint);
                    } else {
                        canvas.drawText(v, x + Ink.dp(8), y + rowH * 0.68f, cellPaint);
                    }
                    canvas.restore();
                }
                canvas.drawLine(x + w, y, x + w, y + rowH, rule);
            }
            canvas.drawLine(0, y + rowH, getWidth(), y + rowH, rule);

            // row number
            canvas.drawRect(0, y, gutterW, y + rowH, Ink.fill(Ink.PANEL));
            Ink.textRight(canvas, String.valueOf(r + 1), gutterW - Ink.dp(7),
                    y + rowH * 0.68f, headPaint);
            canvas.drawLine(gutterW, y, gutterW, y + rowH, rule);
        }

        // ---- column headers, pinned ---------------------------------------
        canvas.drawRect(0, 0, getWidth(), headerH, Ink.fill(Ink.PANEL));
        for (int c = 0; c < cols; c++) {
            float x = colX(c) - scrollX;
            if (x > getWidth()) break;
            if (x + colW[c] < gutterW) continue;
            Ink.textCenter(canvas, columnName(c), x + colW[c] / 2,
                    headerH * 0.70f, headPaint);
            canvas.drawLine(x + colW[c], 0, x + colW[c], headerH, rule);
        }
        canvas.drawRect(0, 0, gutterW, headerH, Ink.fill(Ink.PANEL));
        canvas.drawLine(0, headerH, getWidth(), headerH, Ink.stroke(1.2f, Ink.LINE));
        canvas.drawLine(gutterW, 0, gutterW, getHeight(), Ink.stroke(1.2f, Ink.LINE));
    }

    private static boolean isNumeric(String s) {
        boolean digit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) { digit = true; continue; }
            if (c == '.' || c == ',' || c == '-' || c == '+' || c == '%'
                    || c == ' ' || c == '$' || c == '£' || c == '€') continue;
            return false;
        }
        return digit;
    }

    /** A, B, ... Z, AA, AB ... */
    public static String columnName(int c) {
        StringBuilder sb = new StringBuilder();
        int n = c;
        do {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    // =====================================================================

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX(); lastY = e.getY();
                downAt = System.currentTimeMillis();
                dragging = false;
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - lastX, dy = e.getY() - lastY;
                if (Math.abs(dx) > Ink.dp(6) || Math.abs(dy) > Ink.dp(6)) dragging = true;
                if (dragging) {
                    scrollX = Math.max(0, Math.min(
                            Math.max(0, totalW() - getWidth() + Ink.dp(20)),
                            scrollX - dx));
                    scrollY = Math.max(0, Math.min(
                            Math.max(0, rows.size() * rowH - getHeight() + headerH
                                    + Ink.dp(20)),
                            scrollY - dy));
                    lastX = e.getX(); lastY = e.getY();
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (dragging) return true;
                int r = (int) ((e.getY() + scrollY - headerH) / rowH);
                if (r < 0 || r >= rows.size()) return true;
                int c = -1;
                float x = gutterW - scrollX;
                for (int i = 0; i < cols; i++) {
                    if (e.getX() >= x && e.getX() < x + colW[i]) { c = i; break; }
                    x += colW[i];
                }
                if (c < 0) return true;

                boolean again = (r == selRow && c == selCol);
                selRow = r; selCol = c;
                invalidate();

                if (again && editable && editListener != null) {
                    List<String> row = rows.get(r);
                    editListener.onEdit(r, c, c < row.size() ? row.get(c) : "");
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    /** The grid back as CSV, quoted where it has to be. */
    public String toCsv() {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : rows) {
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) sb.append(',');
                String v = row.get(c);
                if (v == null) v = "";
                if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) {
                    sb.append('"').append(v.replace("\"", "\"\"")).append('"');
                } else {
                    sb.append(v);
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Parses delimited text.
     *
     * Quoted fields, doubled quotes inside them, and newlines inside quotes
     * are all handled, because a CSV exported from any real tool will have
     * all three and splitting on commas silently corrupts the data.
     */
    public static List<List<String>> parseCsv(String text, char delim) {
        List<List<String>> out = new ArrayList<List<String>>();
        List<String> row = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"'); i++;
                    } else quoted = false;
                } else cell.append(c);
                continue;
            }
            if (c == '"' && cell.length() == 0) { quoted = true; continue; }
            if (c == delim) { row.add(cell.toString()); cell.setLength(0); continue; }
            if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                out.add(row);
                row = new ArrayList<String>();
                continue;
            }
            if (c == '\r') continue;
            cell.append(c);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); out.add(row); }
        return out;
    }

    /** Guesses the delimiter from the first few lines. */
    public static char sniffDelimiter(String text) {
        int end = Math.min(text.length(), 4000);
        int commas = 0, tabs = 0, semis = 0, pipes = 0;
        for (int i = 0; i < end; i++) {
            char c = text.charAt(i);
            if (c == ',') commas++;
            else if (c == '\t') tabs++;
            else if (c == ';') semis++;
            else if (c == '|') pipes++;
        }
        if (tabs > commas && tabs > semis) return '\t';
        if (semis > commas) return ';';
        if (pipes > commas && pipes > tabs) return '|';
        return ',';
    }
}
