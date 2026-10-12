package com.slate.app;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

/**
 * Markdown to styled text.
 *
 * A renderer rather than a parser: it walks the source once and emits spans,
 * with no intermediate document tree. Markdown's block structure is shallow
 * enough that a tree buys nothing here, and a phone opening a long README
 * should not pay for one.
 */
public final class Markdown {

    private Markdown() {}

    public static CharSequence render(String src) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = src.split("\n", -1);

        boolean inFence = false;
        int fenceStart = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // ---- fenced code ------------------------------------------
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                if (!inFence) { inFence = true; fenceStart = out.length(); }
                else {
                    inFence = false;
                    if (fenceStart >= 0 && out.length() > fenceStart) {
                        out.setSpan(new TypefaceSpan("monospace"), fenceStart,
                                out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new BackgroundColorSpan(Ink.BG_2), fenceStart,
                                out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new ForegroundColorSpan(Ink.STRING), fenceStart,
                                out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new LeadingMarginSpan.Standard((int) Ink.dp(12)),
                                fenceStart, out.length(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                continue;
            }
            if (inFence) { out.append(line).append('\n'); continue; }

            // ---- horizontal rule ---------------------------------------
            if (trimmed.matches("^([-*_])\\1{2,}$")) {
                int s = out.length();
                out.append("────────────────────\n");
                out.setSpan(new ForegroundColorSpan(Ink.LINE), s, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            // ---- heading -------------------------------------------------
            int hashes = 0;
            while (hashes < trimmed.length() && trimmed.charAt(hashes) == '#') hashes++;
            if (hashes > 0 && hashes <= 6
                    && hashes < trimmed.length() && trimmed.charAt(hashes) == ' ') {
                int s = out.length();
                appendInline(out, trimmed.substring(hashes + 1).trim());
                out.append('\n');
                float size = new float[]{1.7f, 1.45f, 1.25f, 1.12f, 1.05f, 1f}[hashes - 1];
                out.setSpan(new RelativeSizeSpan(size), s, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(
                        hashes <= 2 ? Ink.ACCENT : Ink.TEXT), s, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }

            // ---- quote ---------------------------------------------------
            if (trimmed.startsWith(">")) {
                int s = out.length();
                appendInline(out, trimmed.substring(1).trim());
                out.append('\n');
                out.setSpan(new ForegroundColorSpan(Ink.TEXT_DIM), s, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new LeadingMarginSpan.Standard((int) Ink.dp(16)), s,
                        out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new StyleSpan(Typeface.ITALIC), s, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            // ---- list ----------------------------------------------------
            String bulletBody = null;
            boolean checkbox = false, checked = false;
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")
                    || trimmed.startsWith("+ ")) {
                bulletBody = trimmed.substring(2);
                if (bulletBody.startsWith("[ ] ")) {
                    checkbox = true; bulletBody = bulletBody.substring(4);
                } else if (bulletBody.toLowerCase(java.util.Locale.US).startsWith("[x] ")) {
                    checkbox = true; checked = true; bulletBody = bulletBody.substring(4);
                }
            } else if (trimmed.matches("^\\d+\\. .*")) {
                int dot = trimmed.indexOf('.');
                int s = out.length();
                out.append(trimmed.substring(0, dot + 1)).append(' ');
                out.setSpan(new ForegroundColorSpan(Ink.ACCENT), s, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                appendInline(out, trimmed.substring(dot + 2));
                out.append('\n');
                continue;
            }

            if (bulletBody != null) {
                int s = out.length();
                out.append(checkbox ? (checked ? "  ☑  " : "  ☐  ") : "  •  ");
                out.setSpan(new ForegroundColorSpan(checked ? Ink.STRING : Ink.ACCENT),
                        s, out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                int b = out.length();
                appendInline(out, bulletBody);
                if (checked) {
                    out.setSpan(new StrikethroughSpan(), b, out.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(Ink.TEXT_DIM), b, out.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                out.append('\n');
                continue;
            }

            // ---- table row ------------------------------------------------
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                // separator rows carry no content
                if (trimmed.replaceAll("[|\\-: ]", "").isEmpty()) continue;
                int s = out.length();
                out.append(trimmed.replace("|", "  ").trim()).append('\n');
                out.setSpan(new TypefaceSpan("monospace"), s, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            appendInline(out, line);
            out.append('\n');
        }
        return out;
    }

    /**
     * Inline emphasis, code and links.
     *
     * Scanned character by character, left to right, so an unclosed marker
     * cannot swallow the rest of the document — which is what a regex-based
     * pass does the first time it meets an apostrophe in prose.
     */
    private static void appendInline(SpannableStringBuilder out, String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);

            if (c == '`') {
                int e = s.indexOf('`', i + 1);
                if (e > 0) {
                    int start = out.length();
                    out.append(s, i + 1, e);
                    out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(Ink.STRING), start,
                            out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(Ink.BG_2), start,
                            out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = e + 1;
                    continue;
                }
            }

            if ((c == '*' || c == '_') && i + 1 < s.length()
                    && s.charAt(i + 1) == c) {
                String marker = s.substring(i, i + 2);
                int e = s.indexOf(marker, i + 2);
                if (e > 0) {
                    int start = out.length();
                    appendInline(out, s.substring(i + 2, e));
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = e + 2;
                    continue;
                }
            }

            if (c == '*' || c == '_') {
                int e = s.indexOf(c, i + 1);
                if (e > i + 1) {
                    int start = out.length();
                    out.append(s, i + 1, e);
                    out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = e + 1;
                    continue;
                }
            }

            if (c == '[') {
                int close = s.indexOf(']', i);
                if (close > 0 && close + 1 < s.length() && s.charAt(close + 1) == '(') {
                    int paren = s.indexOf(')', close);
                    if (paren > 0) {
                        int start = out.length();
                        out.append(s, i + 1, close);
                        out.setSpan(new ForegroundColorSpan(Ink.LINK), start,
                                out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new UnderlineSpan(), start, out.length(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = paren + 1;
                        continue;
                    }
                }
            }

            out.append(c);
            i++;
        }
    }
}
