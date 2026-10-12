package com.slate.app;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;

/**
 * The real text inside a PDF.
 *
 * A PDF that was produced by a word processor already contains its text, as
 * characters, in the page content streams. Running OCR over a picture of that
 * text is throwing away a perfect copy and guessing at a blurry one — so this
 * reads the document instead, and OCR is kept for the case it is actually for:
 * a scan, where there is no text layer at all.
 *
 * Written against the format rather than a library, because there is no
 * library here. It handles the parts real documents use — object streams,
 * Flate compression, ToUnicode maps, encoding differences — and gives up
 * cleanly on the parts they do not, so the caller can fall back to OCR.
 */
public final class Pdf {

    private Pdf() {}

    public static class Text {
        public String text = "";
        public int pages;
        public boolean hasTextLayer;
        public String note = "";
    }

    /** One indirect object: its dictionary source and its stream, if any. */
    private static class Obj {
        String dict = "";
        byte[] stream;
    }

    // =====================================================================

    public static Text extract(byte[] data) {
        Text out = new Text();
        try {
            Map<Integer, Obj> objs = scanObjects(data);
            expandObjectStreams(objs);

            List<Integer> pages = pageOrder(objs);
            out.pages = pages.size();
            if (pages.isEmpty()) { out.note = "no pages found"; return out; }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pages.size(); i++) {
                Obj page = objs.get(pages.get(i));
                if (page == null) continue;
                Map<String, Map<Integer, String>> fonts = fontsFor(page, objs);
                byte[] content = contentsOf(page, objs);
                if (content == null) continue;
                if (pages.size() > 1) {
                    sb.append(i > 0 ? "\n\n" : "")
                      .append("── page ").append(i + 1).append(" ──\n");
                }
                sb.append(renderContent(content, fonts));
            }

            out.text = tidy(sb.toString());
            // A scan has pages and no words. Saying so is what tells the
            // caller to offer OCR instead of showing an empty editor.
            out.hasTextLayer = out.text.replaceAll("\\s+", "").length() > 20;
            if (!out.hasTextLayer) out.note = "no text layer — this looks like a scan";
        } catch (Throwable t) {
            out.note = "could not read the text layer";
        }
        return out;
    }

    // =====================================================================
    // STRUCTURE
    // =====================================================================

    /**
     * Finds every indirect object by scanning for the "N G obj" header.
     *
     * The cross-reference table would be the proper route and is also the
     * least reliable thing in a PDF: it is routinely stale in files that have
     * been appended to, and wrong in files written by the many tools that get
     * it wrong. Scanning finds objects that a broken xref would lose.
     */
    private static Map<Integer, Obj> scanObjects(byte[] d) {
        Map<Integer, Obj> out = new HashMap<Integer, Obj>();
        for (int i = 0; i + 3 < d.length; i++) {
            if (d[i] != 'o' || d[i + 1] != 'b' || d[i + 2] != 'j') continue;
            if (i + 3 < d.length && isRegular(d[i + 3])) continue;

            // walk back over "N G "
            int j = i - 1;
            while (j >= 0 && isSpace(d[j])) j--;
            int genEnd = j + 1;
            while (j >= 0 && isDigit(d[j])) j--;
            int genStart = j + 1;
            if (genStart >= genEnd) continue;
            while (j >= 0 && isSpace(d[j])) j--;
            int numEnd = j + 1;
            while (j >= 0 && isDigit(d[j])) j--;
            int numStart = j + 1;
            if (numStart >= numEnd) continue;
            if (j >= 0 && isRegular(d[j])) continue;

            int num;
            try { num = Integer.parseInt(new String(d, numStart, numEnd - numStart,
                    java.nio.charset.StandardCharsets.ISO_8859_1).trim()); }
            catch (Throwable t) { continue; }

            int bodyStart = i + 3;
            int end = indexOf(d, "endobj", bodyStart);
            if (end < 0) end = d.length;

            Obj o = new Obj();
            int streamAt = indexOf(d, "stream", bodyStart, end);
            int dictEnd = streamAt > 0 ? streamAt : end;
            o.dict = new String(d, bodyStart, Math.max(0, dictEnd - bodyStart),
                    java.nio.charset.StandardCharsets.ISO_8859_1);

            if (streamAt > 0) {
                int s = streamAt + 6;
                if (s < d.length && d[s] == '\r') s++;
                if (s < d.length && d[s] == '\n') s++;
                int e = indexOf(d, "endstream", s, end + 9);
                if (e < 0) e = end;
                byte[] raw = new byte[Math.max(0, e - s)];
                System.arraycopy(d, s, raw, 0, raw.length);
                o.stream = decode(raw, o.dict);
            }
            out.put(num, o);
            i = Math.max(i, end);
        }
        return out;
    }

    /**
     * Unpacks compressed object streams.
     *
     * Everything written in the last fifteen years puts most of its objects
     * inside /ObjStm containers, so a reader that only scans the file body
     * finds the pages of an old PDF and almost nothing in a new one.
     */
    private static void expandObjectStreams(Map<Integer, Obj> objs) {
        List<Obj> streams = new ArrayList<Obj>();
        for (Obj o : objs.values()) {
            if (o.stream != null && o.dict.contains("/ObjStm")) streams.add(o);
        }
        for (Obj o : streams) {
            try {
                int n = intValue(o.dict, "/N");
                int first = intValue(o.dict, "/First");
                if (n <= 0 || first <= 0 || first > o.stream.length) continue;

                String header = new String(o.stream, 0, first,
                        java.nio.charset.StandardCharsets.ISO_8859_1);
                String[] nums = header.trim().split("\\s+");
                for (int k = 0; k + 1 < nums.length && k / 2 < n; k += 2) {
                    int objNum = Integer.parseInt(nums[k]);
                    int off = Integer.parseInt(nums[k + 1]);
                    int start = first + off;
                    int end = (k + 3 < nums.length)
                            ? first + Integer.parseInt(nums[k + 3])
                            : o.stream.length;
                    if (start < 0 || end > o.stream.length || end <= start) continue;
                    if (objs.containsKey(objNum)) continue;
                    Obj inner = new Obj();
                    inner.dict = new String(o.stream, start, end - start,
                            java.nio.charset.StandardCharsets.ISO_8859_1);
                    objs.put(objNum, inner);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** Page objects, in document order where the tree can be walked. */
    private static List<Integer> pageOrder(Map<Integer, Obj> objs) {
        List<Integer> out = new ArrayList<Integer>();

        // Walk /Pages /Kids when there is a catalog, so the order is right.
        Integer root = null;
        for (Map.Entry<Integer, Obj> e : objs.entrySet()) {
            if (e.getValue().dict.contains("/Type") && e.getValue().dict.contains("/Pages")
                    && e.getValue().dict.contains("/Kids")
                    && !e.getValue().dict.contains("/Parent")) {
                root = e.getKey();
                break;
            }
        }
        if (root != null) {
            collectKids(root, objs, out, 0);
            if (!out.isEmpty()) return out;
        }

        // Otherwise take every /Type /Page in object order, which is usually
        // the same thing and is never catastrophically wrong.
        List<Integer> keys = new ArrayList<Integer>(objs.keySet());
        java.util.Collections.sort(keys);
        for (Integer k : keys) {
            String d = objs.get(k).dict;
            if (isPage(d)) out.add(k);
        }
        return out;
    }

    private static boolean isPage(String dict) {
        int i = dict.indexOf("/Type");
        if (i < 0) return false;
        String after = dict.substring(i + 5, Math.min(dict.length(), i + 24));
        return after.trim().startsWith("/Page") && !after.trim().startsWith("/Pages");
    }

    private static void collectKids(int node, Map<Integer, Obj> objs,
                                    List<Integer> out, int depth) {
        if (depth > 32 || out.size() > 5000) return;
        Obj o = objs.get(node);
        if (o == null) return;
        if (isPage(o.dict)) { out.add(node); return; }

        int k = o.dict.indexOf("/Kids");
        if (k < 0) return;
        int open = o.dict.indexOf('[', k);
        int close = o.dict.indexOf(']', open);
        if (open < 0 || close < 0) return;
        for (int ref : refsIn(o.dict.substring(open, close))) {
            collectKids(ref, objs, out, depth + 1);
        }
    }

    // =====================================================================
    // CONTENT
    // =====================================================================

    private static byte[] contentsOf(Obj page, Map<Integer, Obj> objs) {
        int c = page.dict.indexOf("/Contents");
        if (c < 0) return null;
        String tail = page.dict.substring(c + 9,
                Math.min(page.dict.length(), c + 400));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (int ref : refsIn(tail)) {
            Obj o = objs.get(ref);
            if (o != null && o.stream != null) {
                bo.write(o.stream, 0, o.stream.length);
                bo.write('\n');
            }
            // A page's content is either one stream or an array of them; the
            // first reference outside an array is all there is.
            if (tail.trim().startsWith("[")) continue; else break;
        }
        return bo.size() == 0 ? null : bo.toByteArray();
    }

    /**
     * Renders a content stream to text.
     *
     * Only the text operators matter: BT/ET delimit a block, Tf selects a
     * font, Td/TD/Tm/T* move the cursor, and Tj/TJ/'/" draw. Vertical
     * movement between blocks is what produces line breaks, and the kerning
     * numbers inside a TJ array are what produce the spaces — a PDF rarely
     * stores a space character, it just moves the cursor a bit further along.
     */
    private static String renderContent(byte[] content,
                                        Map<String, Map<Integer, String>> fonts) {
        String s = new String(content, java.nio.charset.StandardCharsets.ISO_8859_1);
        StringBuilder out = new StringBuilder();
        StringBuilder line = new StringBuilder();

        Map<Integer, String> cmap = null;
        double lastY = Double.NaN, lastX = Double.NaN;
        List<String> stack = new ArrayList<String>();

        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);

            if (c == '(') {
                int[] end = new int[1];
                String lit = readLiteral(s, i, end);
                stack.add("(" + lit);
                i = end[0];
                continue;
            }
            if (c == '<' && i + 1 < s.length() && s.charAt(i + 1) != '<') {
                int e = s.indexOf('>', i);
                if (e < 0) break;
                stack.add("<" + s.substring(i + 1, e));
                i = e + 1;
                continue;
            }
            if (c == '<' || c == '>' || c == '[' || c == ']') {
                stack.add(String.valueOf(c));
                i++;
                continue;
            }
            if (isSpaceChar(c)) { i++; continue; }

            // a token: number, name or operator
            int j = i;
            while (j < s.length() && !isSpaceChar(s.charAt(j))
                    && "()<>[]{}/".indexOf(s.charAt(j)) < 0) j++;
            if (j == i) {
                if (s.charAt(i) == '/') {
                    j = i + 1;
                    while (j < s.length() && isRegularChar(s.charAt(j))) j++;
                    stack.add(s.substring(i, j));
                    i = j;
                    continue;
                }
                i++;
                continue;
            }
            String tok = s.substring(i, j);
            i = j;

            if (tok.equals("Tf")) {
                String name = null;
                for (int k = stack.size() - 1; k >= 0; k--) {
                    if (stack.get(k).startsWith("/")) { name = stack.get(k); break; }
                }
                cmap = name == null ? null : fonts.get(name.substring(1));
                stack.clear();
                continue;
            }

            if (tok.equals("Td") || tok.equals("TD") || tok.equals("Tm")
                    || tok.equals("T*") || tok.equals("ET") || tok.equals("BT")) {
                double y = Double.NaN, x = Double.NaN;
                if (tok.equals("Td") || tok.equals("TD")) {
                    if (stack.size() >= 2) {
                        x = num(stack.get(stack.size() - 2));
                        y = num(stack.get(stack.size() - 1));
                        // Td is RELATIVE, so a non-zero y is itself a line move
                        if (!Double.isNaN(y) && Math.abs(y) > 0.5 && line.length() > 0) {
                            out.append(line).append('\n');
                            line.setLength(0);
                        }
                        stack.clear();
                        lastY = Double.NaN;
                        continue;
                    }
                } else if (tok.equals("Tm")) {
                    if (stack.size() >= 6) {
                        x = num(stack.get(stack.size() - 2));
                        y = num(stack.get(stack.size() - 1));
                    }
                } else if (tok.equals("T*")) {
                    if (line.length() > 0) { out.append(line).append('\n'); line.setLength(0); }
                    stack.clear();
                    continue;
                }

                if (!Double.isNaN(y)) {
                    if (!Double.isNaN(lastY) && Math.abs(y - lastY) > 1.2
                            && line.length() > 0) {
                        out.append(line).append('\n');
                        line.setLength(0);
                    } else if (!Double.isNaN(lastX) && !Double.isNaN(x)
                            && Math.abs(y - lastY) <= 1.2 && x - lastX > 6
                            && line.length() > 0
                            && line.charAt(line.length() - 1) != ' ') {
                        line.append(' ');
                    }
                    lastY = y;
                    lastX = x;
                }
                stack.clear();
                continue;
            }

            if (tok.equals("Tj") || tok.equals("'") || tok.equals("\"")) {
                if (tok.equals("'") || tok.equals("\"")) {
                    if (line.length() > 0) { out.append(line).append('\n'); line.setLength(0); }
                }
                for (String it : stack) {
                    if (it.startsWith("(") || it.startsWith("<")) {
                        line.append(decodeString(it, cmap));
                    }
                }
                stack.clear();
                continue;
            }

            if (tok.equals("TJ")) {
                for (String it : stack) {
                    if (it.startsWith("(") || it.startsWith("<")) {
                        line.append(decodeString(it, cmap));
                    } else {
                        double v = num(it);
                        // A large negative kern is how a PDF writes a space.
                        if (!Double.isNaN(v) && v < -120 && line.length() > 0
                                && line.charAt(line.length() - 1) != ' ') {
                            line.append(' ');
                        }
                    }
                }
                stack.clear();
                continue;
            }

            if (tok.equals("Tc") || tok.equals("Tw") || tok.equals("Tz")
                    || tok.equals("TL") || tok.equals("Ts") || tok.equals("Tr")
                    || tok.equals("cm") || tok.equals("q") || tok.equals("Q")
                    || tok.equals("gs") || tok.equals("re") || tok.equals("f")
                    || tok.equals("W") || tok.equals("n") || tok.equals("S")) {
                stack.clear();
                continue;
            }

            stack.add(tok);
            if (stack.size() > 64) stack.remove(0);
        }
        if (line.length() > 0) out.append(line).append('\n');
        return out.toString();
    }

    // =====================================================================
    // FONTS
    // =====================================================================

    /** Code-to-character maps for every font the page uses. */
    private static Map<String, Map<Integer, String>> fontsFor(
            Obj page, Map<Integer, Obj> objs) {
        Map<String, Map<Integer, String>> out =
                new HashMap<String, Map<Integer, String>>();
        try {
            String res = page.dict;
            int r = res.indexOf("/Resources");
            if (r < 0) return out;

            // /Resources may be inline or a reference
            String resDict = res.substring(r);
            int[] refs = refsIn(resDict.substring(0,
                    Math.min(resDict.length(), 60)));
            if (refs.length > 0 && objs.containsKey(refs[0])
                    && !resDict.substring(0, Math.min(resDict.length(), 40)).contains("<<")) {
                resDict = objs.get(refs[0]).dict;
            }

            int f = resDict.indexOf("/Font");
            if (f < 0) return out;
            String fontDict = resDict.substring(f + 5);
            int[] fr = refsIn(fontDict.substring(0, Math.min(fontDict.length(), 40)));
            if (fr.length > 0 && objs.containsKey(fr[0])
                    && !fontDict.trim().startsWith("<<")) {
                fontDict = objs.get(fr[0]).dict;
            }

            // each entry is /Name N 0 R
            int i = 0;
            while (true) {
                int slash = fontDict.indexOf('/', i);
                if (slash < 0) break;
                int e = slash + 1;
                while (e < fontDict.length() && isRegularChar(fontDict.charAt(e))) e++;
                String name = fontDict.substring(slash + 1, e);
                int[] ref = refsIn(fontDict.substring(e,
                        Math.min(fontDict.length(), e + 24)));
                if (ref.length > 0) {
                    Obj fo = objs.get(ref[0]);
                    if (fo != null) out.put(name, buildCMap(fo, objs));
                }
                i = e;
                if (i >= fontDict.length()) break;
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** A font's code-to-text map, from /ToUnicode or /Differences. */
    private static Map<Integer, String> buildCMap(Obj font, Map<Integer, Obj> objs) {
        Map<Integer, String> map = new HashMap<Integer, String>();
        try {
            int t = font.dict.indexOf("/ToUnicode");
            if (t >= 0) {
                int[] ref = refsIn(font.dict.substring(t + 10,
                        Math.min(font.dict.length(), t + 34)));
                if (ref.length > 0) {
                    Obj cm = objs.get(ref[0]);
                    if (cm != null && cm.stream != null) {
                        parseToUnicode(new String(cm.stream,
                                java.nio.charset.StandardCharsets.ISO_8859_1), map);
                    }
                }
            }
            int e = font.dict.indexOf("/Differences");
            if (e >= 0) parseDifferences(font.dict.substring(e), map);
        } catch (Throwable ignored) {}
        return map.isEmpty() ? null : map;
    }

    /** bfchar and bfrange entries of a ToUnicode CMap. */
    private static void parseToUnicode(String cmap, Map<Integer, String> map) {
        int i = 0;
        while (true) {
            int b = cmap.indexOf("beginbfchar", i);
            if (b < 0) break;
            int e = cmap.indexOf("endbfchar", b);
            if (e < 0) break;
            String body = cmap.substring(b + 11, e);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").matcher(body);
            while (m.find()) {
                try {
                    int code = Integer.parseInt(m.group(1), 16);
                    map.put(code, hexToString(m.group(2)));
                } catch (Throwable ignored) {}
            }
            i = e + 9;
        }
        i = 0;
        while (true) {
            int b = cmap.indexOf("beginbfrange", i);
            if (b < 0) break;
            int e = cmap.indexOf("endbfrange", b);
            if (e < 0) break;
            String body = cmap.substring(b + 12, e);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")
                    .matcher(body);
            while (m.find()) {
                try {
                    int lo = Integer.parseInt(m.group(1), 16);
                    int hi = Integer.parseInt(m.group(2), 16);
                    int dst = Integer.parseInt(m.group(3), 16);
                    for (int c = lo; c <= hi && c - lo < 65536; c++) {
                        map.put(c, String.valueOf((char) (dst + (c - lo))));
                    }
                } catch (Throwable ignored) {}
            }
            i = e + 10;
        }
    }

    private static void parseDifferences(String s, Map<Integer, String> map) {
        int open = s.indexOf('[');
        int close = s.indexOf(']', open);
        if (open < 0 || close < 0) return;
        String[] parts = s.substring(open + 1, close).trim().split("\\s+");
        int code = 0;
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (p.charAt(0) == '/') {
                String g = glyphToChar(p.substring(1));
                if (g != null) map.put(code, g);
                code++;
            } else {
                try { code = Integer.parseInt(p); } catch (Throwable ignored) {}
            }
        }
    }

    /** The handful of glyph names that are not already their own character. */
    private static String glyphToChar(String name) {
        if (name.length() == 1) return name;
        if (name.startsWith("uni") && name.length() >= 7) {
            try { return String.valueOf((char) Integer.parseInt(
                    name.substring(3, 7), 16)); } catch (Throwable ignored) {}
        }
        if (name.equals("space")) return " ";
        if (name.equals("hyphen")) return "-";
        if (name.equals("period")) return ".";
        if (name.equals("comma")) return ",";
        if (name.equals("colon")) return ":";
        if (name.equals("semicolon")) return ";";
        if (name.equals("quotesingle")) return "'";
        if (name.equals("quotedbl")) return "\"";
        if (name.equals("quoteright")) return "’";
        if (name.equals("quoteleft")) return "‘";
        if (name.equals("quotedblleft")) return "“";
        if (name.equals("quotedblright")) return "”";
        if (name.equals("endash")) return "–";
        if (name.equals("emdash")) return "—";
        if (name.equals("bullet")) return "•";
        if (name.equals("parenleft")) return "(";
        if (name.equals("parenright")) return ")";
        if (name.equals("slash")) return "/";
        if (name.equals("ampersand")) return "&";
        if (name.equals("percent")) return "%";
        if (name.equals("at")) return "@";
        if (name.equals("plus")) return "+";
        if (name.equals("equal")) return "=";
        if (name.equals("numbersign")) return "#";
        if (name.equals("dollar")) return "$";
        if (name.equals("asterisk")) return "*";
        if (name.equals("question")) return "?";
        if (name.equals("exclam")) return "!";
        if (name.startsWith("g") || name.startsWith("cid")) return null;
        return null;
    }

    // =====================================================================
    // STRINGS
    // =====================================================================

    private static String decodeString(String token, Map<Integer, String> cmap) {
        StringBuilder sb = new StringBuilder();
        if (token.startsWith("<")) {
            String hex = token.substring(1).replaceAll("[^0-9A-Fa-f]", "");
            // Hex strings in a subset font are two bytes per glyph.
            int step = cmap != null ? 4 : 2;
            if (hex.length() % 4 != 0 && cmap != null) step = 2;
            for (int i = 0; i + step <= hex.length(); i += step) {
                try {
                    int code = Integer.parseInt(hex.substring(i, i + step), 16);
                    sb.append(lookup(code, cmap));
                } catch (Throwable ignored) {}
            }
        } else {
            String body = token.substring(1);
            for (int i = 0; i < body.length(); i++) {
                sb.append(lookup(body.charAt(i) & 255, cmap));
            }
        }
        return sb.toString();
    }

    private static String lookup(int code, Map<Integer, String> cmap) {
        if (cmap != null) {
            String s = cmap.get(code);
            if (s != null) return s;
        }
        if (code == 0) return "";
        return String.valueOf((char) code);
    }

    /** Reads a (...) literal, honouring escapes and nested parentheses. */
    private static String readLiteral(String s, int start, int[] endOut) {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (i + 1 >= s.length()) break;
                char n = s.charAt(i + 1);
                switch (n) {
                    case 'n': sb.append('\n'); i += 2; continue;
                    case 'r': sb.append('\r'); i += 2; continue;
                    case 't': sb.append('\t'); i += 2; continue;
                    case 'b': case 'f': i += 2; continue;
                    case '(': sb.append('('); i += 2; continue;
                    case ')': sb.append(')'); i += 2; continue;
                    case '\\': sb.append('\\'); i += 2; continue;
                    case '\n': i += 2; continue;
                    default:
                        if (n >= '0' && n <= '7') {
                            int v = 0, k = 0;
                            while (k < 3 && i + 1 + k < s.length()) {
                                char d = s.charAt(i + 1 + k);
                                if (d < '0' || d > '7') break;
                                v = v * 8 + (d - '0');
                                k++;
                            }
                            sb.append((char) v);
                            i += 1 + k;
                            continue;
                        }
                        sb.append(n); i += 2; continue;
                }
            }
            if (c == '(') { depth++; sb.append(c); i++; continue; }
            if (c == ')') {
                depth--;
                if (depth == 0) { i++; break; }
                sb.append(c); i++; continue;
            }
            sb.append(c);
            i++;
        }
        endOut[0] = i;
        return sb.toString();
    }

    private static String hexToString(String hex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 4 <= hex.length(); i += 4) {
            try { sb.append((char) Integer.parseInt(hex.substring(i, i + 4), 16)); }
            catch (Throwable ignored) {}
        }
        if (sb.length() == 0 && hex.length() >= 2) {
            try { sb.append((char) Integer.parseInt(hex.substring(0, 2), 16)); }
            catch (Throwable ignored) {}
        }
        return sb.toString();
    }

    // =====================================================================
    // STREAM DECODING
    // =====================================================================

    /** Inflates a stream if it says it is compressed. */
    private static byte[] decode(byte[] raw, String dict) {
        if (dict.contains("/FlateDecode")) {
            byte[] out = inflate(raw);
            if (out != null) return out;
        }
        return raw;
    }

    private static byte[] inflate(byte[] raw) {
        // Some writers leave stray whitespace before the zlib header, which
        // makes the first byte invalid; skipping leading whitespace rescues
        // those files instead of dropping the page.
        int start = 0;
        while (start < raw.length && isSpace(raw[start])) start++;
        for (int attempt = 0; attempt < 2; attempt++) {
            Inflater inf = new Inflater(attempt == 1);
            try {
                inf.setInput(raw, start, raw.length - start);
                ByteArrayOutputStream bo = new ByteArrayOutputStream(
                        Math.max(1024, raw.length * 3));
                byte[] buf = new byte[32768];
                while (!inf.finished()) {
                    int n = inf.inflate(buf);
                    if (n == 0) {
                        if (inf.needsInput() || inf.needsDictionary()) break;
                    } else bo.write(buf, 0, n);
                    if (bo.size() > 80_000_000) break;
                }
                if (bo.size() > 0) return bo.toByteArray();
            } catch (Throwable ignored) {
            } finally {
                inf.end();
            }
        }
        return null;
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private static boolean isDigit(byte b) { return b >= '0' && b <= '9'; }
    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t' || b == 0 || b == 12;
    }
    private static boolean isRegular(byte b) {
        return !isSpace(b) && "()<>[]{}/%".indexOf((char) b) < 0;
    }
    private static boolean isSpaceChar(char c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == 0 || c == 12;
    }
    private static boolean isRegularChar(char c) {
        return !isSpaceChar(c) && "()<>[]{}/%".indexOf(c) < 0;
    }

    private static double num(String s) {
        try { return Double.parseDouble(s); } catch (Throwable t) { return Double.NaN; }
    }

    private static int intValue(String dict, String key) {
        int i = dict.indexOf(key);
        if (i < 0) return -1;
        int j = i + key.length();
        while (j < dict.length() && isSpaceChar(dict.charAt(j))) j++;
        int k = j;
        while (k < dict.length() && Character.isDigit(dict.charAt(k))) k++;
        if (k == j) return -1;
        try { return Integer.parseInt(dict.substring(j, k)); }
        catch (Throwable t) { return -1; }
    }

    /** Object numbers of every "N G R" reference in a fragment. */
    private static int[] refsIn(String s) {
        List<Integer> out = new ArrayList<Integer>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\s+(\\d+)\\s+R").matcher(s);
        while (m.find()) {
            try { out.add(Integer.parseInt(m.group(1))); } catch (Throwable ignored) {}
            if (out.size() > 2000) break;
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    private static int indexOf(byte[] d, String what, int from) {
        return indexOf(d, what, from, d.length);
    }

    private static int indexOf(byte[] d, String what, int from, int to) {
        byte[] w = what.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        int limit = Math.min(to, d.length) - w.length;
        outer:
        for (int i = Math.max(0, from); i <= limit; i++) {
            for (int k = 0; k < w.length; k++) if (d[i + k] != w[k]) continue outer;
            return i;
        }
        return -1;
    }

    private static String tidy(String s) {
        String[] lines = s.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int blanks = 0;
        for (String l : lines) {
            String t = l.replaceAll("[ \\t]+", " ").trim();
            if (t.isEmpty()) { if (++blanks > 1) continue; } else blanks = 0;
            sb.append(t).append('\n');
        }
        return sb.toString().trim();
    }

    // =====================================================================
    // EDITING
    // =====================================================================

    /**
     * One editable piece of text on a page.
     *
     * Carries where it sits so it can be tapped, and where it came from in
     * the file so it can be written back: which object, and which byte range
     * of that object's decompressed content stream holds the string literal.
     */
    public static class Run {
        public String text = "";
        public float x, y, width, height;     // PDF user space, origin bottom-left
        public int objNum;                    // content stream object
        public int from, to;                  // byte range of the token in that stream
        public boolean hex;
        public Map<String, Integer> reverse;  // unicode -> font code, for writing back
        public float size = 12;
    }

    /** The editable text of one page, with positions. */
    public static List<Run> runs(byte[] data, int pageIndex) {
        List<Run> out = new ArrayList<Run>();
        try {
            Map<Integer, Obj> objs = scanObjects(data);
            expandObjectStreams(objs);
            List<Integer> pages = pageOrder(objs);
            if (pageIndex < 0 || pageIndex >= pages.size()) return out;

            Obj page = objs.get(pages.get(pageIndex));
            if (page == null) return out;
            Map<String, Map<Integer, String>> fonts = fontsFor(page, objs);

            int contentObj = contentObjNum(page, objs);
            if (contentObj < 0) return out;
            Obj co = objs.get(contentObj);
            if (co == null || co.stream == null) return out;

            collectRuns(co.stream, fonts, contentObj, out);
        } catch (Throwable ignored) {}
        return out;
    }

    private static int contentObjNum(Obj page, Map<Integer, Obj> objs) {
        int c = page.dict.indexOf("/Contents");
        if (c < 0) return -1;
        int[] refs = refsIn(page.dict.substring(c + 9,
                Math.min(page.dict.length(), c + 200)));
        return refs.length > 0 ? refs[0] : -1;
    }

    /**
     * Walks a content stream recording every drawn string and where it is.
     *
     * The same scan as rendering, but keeping the byte offsets of each
     * literal. Those offsets are what make editing possible: a replacement is
     * a splice into the original stream rather than a regeneration of it, so
     * everything the document does that this parser does not understand —
     * graphics, forms, annotations — survives untouched.
     */
    private static void collectRuns(byte[] content,
                                    Map<String, Map<Integer, String>> fonts,
                                    int objNum, List<Run> out) {
        String s = new String(content, java.nio.charset.StandardCharsets.ISO_8859_1);

        Map<Integer, String> cmap = null;
        Map<String, Integer> reverse = null;
        double tx = 0, ty = 0, size = 12;
        List<String> stack = new ArrayList<String>();
        List<int[]> spans = new ArrayList<int[]>();   // token byte ranges

        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);

            if (c == '(') {
                int[] end = new int[1];
                String lit = readLiteral(s, i, end);
                stack.add("(" + lit);
                spans.add(new int[]{i, end[0], 0});
                i = end[0];
                continue;
            }
            if (c == '<' && i + 1 < s.length() && s.charAt(i + 1) != '<') {
                int e = s.indexOf('>', i);
                if (e < 0) break;
                stack.add("<" + s.substring(i + 1, e));
                spans.add(new int[]{i, e + 1, 1});
                i = e + 1;
                continue;
            }
            if (c == '<' || c == '>' || c == '[' || c == ']') {
                stack.add(String.valueOf(c));
                spans.add(null);
                i++;
                continue;
            }
            if (isSpaceChar(c)) { i++; continue; }

            int j = i;
            while (j < s.length() && !isSpaceChar(s.charAt(j))
                    && "()<>[]{}/".indexOf(s.charAt(j)) < 0) j++;
            if (j == i) {
                if (s.charAt(i) == '/') {
                    j = i + 1;
                    while (j < s.length() && isRegularChar(s.charAt(j))) j++;
                    stack.add(s.substring(i, j));
                    spans.add(null);
                    i = j;
                    continue;
                }
                i++;
                continue;
            }
            String tok = s.substring(i, j);
            i = j;

            if (tok.equals("Tf")) {
                String name = null;
                for (int k = stack.size() - 1; k >= 0; k--) {
                    if (stack.get(k).startsWith("/")) { name = stack.get(k); break; }
                }
                if (!stack.isEmpty()) {
                    double v = num(stack.get(stack.size() - 1));
                    if (!Double.isNaN(v) && v > 0) size = v;
                }
                cmap = name == null ? null : fonts.get(name.substring(1));
                reverse = invert(cmap);
                stack.clear(); spans.clear();
                continue;
            }

            if (tok.equals("Td") || tok.equals("TD")) {
                if (stack.size() >= 2) {
                    double dx = num(stack.get(stack.size() - 2));
                    double dy = num(stack.get(stack.size() - 1));
                    if (!Double.isNaN(dx)) tx += dx;
                    if (!Double.isNaN(dy)) ty += dy;
                }
                stack.clear(); spans.clear();
                continue;
            }
            if (tok.equals("Tm")) {
                if (stack.size() >= 6) {
                    double a = num(stack.get(stack.size() - 6));
                    double x = num(stack.get(stack.size() - 2));
                    double y = num(stack.get(stack.size() - 1));
                    if (!Double.isNaN(x)) tx = x;
                    if (!Double.isNaN(y)) ty = y;
                    // the matrix carries its own scale; a 12pt font set with
                    // Tm 1 0 0 1 is 12pt, but with Tm 12 0 0 12 it is not
                    if (!Double.isNaN(a) && a > 0.01 && Math.abs(a - 1) > 0.01) {
                        size = size * a;
                    }
                }
                stack.clear(); spans.clear();
                continue;
            }
            if (tok.equals("BT")) { tx = 0; ty = 0; stack.clear(); spans.clear(); continue; }
            if (tok.equals("T*")) { ty -= size * 1.2; stack.clear(); spans.clear(); continue; }

            if (tok.equals("Tj") || tok.equals("TJ") || tok.equals("'")
                    || tok.equals("\"")) {
                if (tok.equals("'") || tok.equals("\"")) ty -= size * 1.2;

                StringBuilder txt = new StringBuilder();
                int from = -1, to = -1;
                boolean hex = false;
                for (int k = 0; k < stack.size(); k++) {
                    String it = stack.get(k);
                    if (!(it.startsWith("(") || it.startsWith("<"))) continue;
                    txt.append(decodeString(it, cmap));
                    int[] sp = k < spans.size() ? spans.get(k) : null;
                    if (sp != null) {
                        if (from < 0) from = sp[0];
                        to = sp[1];
                        hex = sp[2] == 1;
                    }
                }
                String t = txt.toString();
                if (t.trim().length() > 0 && from >= 0) {
                    Run r = new Run();
                    r.text = t;
                    r.x = (float) tx;
                    r.y = (float) ty;
                    r.size = (float) size;
                    // A rough advance: PDFs do not store widths here, and
                    // half the point size per character is close enough for
                    // a tap target.
                    r.width = (float) (t.length() * size * 0.5);
                    r.height = (float) size;
                    r.objNum = objNum;
                    r.from = from;
                    r.to = to;
                    r.hex = hex;
                    r.reverse = reverse;
                    out.add(r);
                    tx += r.width;
                }
                stack.clear(); spans.clear();
                continue;
            }

            if (tok.equals("Tc") || tok.equals("Tw") || tok.equals("Tz")
                    || tok.equals("TL") || tok.equals("Ts") || tok.equals("Tr")
                    || tok.equals("cm") || tok.equals("q") || tok.equals("Q")
                    || tok.equals("gs") || tok.equals("re") || tok.equals("f")
                    || tok.equals("W") || tok.equals("n") || tok.equals("S")
                    || tok.equals("ET")) {
                stack.clear(); spans.clear();
                continue;
            }

            stack.add(tok);
            spans.add(null);
            if (stack.size() > 64) { stack.remove(0); spans.remove(0); }
        }
    }

    private static Map<String, Integer> invert(Map<Integer, String> cmap) {
        if (cmap == null) return null;
        Map<String, Integer> out = new HashMap<String, Integer>();
        for (Map.Entry<Integer, String> e : cmap.entrySet()) {
            if (!out.containsKey(e.getValue())) out.put(e.getValue(), e.getKey());
        }
        return out;
    }

    /** Characters the run's font cannot draw, so the caller can warn. */
    public static String missingGlyphs(Run run, String text) {
        if (run == null || run.reverse == null) return "";
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            if (ch.equals(" ")) continue;
            if (!run.reverse.containsKey(ch) && bad.indexOf(ch) < 0) bad.append(ch);
        }
        return bad.toString();
    }

    // =====================================================================
    // WRITING
    // =====================================================================

    /**
     * Replaces the text of one run and returns a new PDF.
     *
     * Written as an INCREMENTAL UPDATE: the original bytes are copied
     * untouched and the changed object plus a fresh cross-reference section
     * are appended. Rewriting the whole file would mean re-serialising every
     * object this parser only half understands — fonts, images, forms,
     * signatures — and any one of them coming out slightly wrong corrupts the
     * document. Appending cannot damage what is already there, and a reader
     * that cannot follow the update still sees the original.
     */
    public static byte[] replaceText(byte[] data, Run run, String newText) {
        try {
            Map<Integer, Obj> objs = scanObjects(data);
            expandObjectStreams(objs);
            Obj co = objs.get(run.objNum);
            if (co == null || co.stream == null) return null;
            if (run.from < 0 || run.to > co.stream.length || run.to <= run.from) return null;

            byte[] token = encodeString(newText, run);
            if (token == null) return null;

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(co.stream, 0, run.from);
            body.write(token, 0, token.length);
            body.write(co.stream, run.to, co.stream.length - run.to);
            byte[] newStream = body.toByteArray();

            String dict = stripFilter(co.dict, newStream.length);
            return appendObject(data, run.objNum, dict, newStream);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Encodes replacement text in the run's own font encoding. */
    private static byte[] encodeString(String text, Run run) {
        try {
            if (run.hex && run.reverse != null) {
                StringBuilder sb = new StringBuilder("<");
                for (int i = 0; i < text.length(); i++) {
                    Integer code = run.reverse.get(String.valueOf(text.charAt(i)));
                    if (code == null) {
                        if (text.charAt(i) == ' ') code = run.reverse.get(" ");
                        if (code == null) continue;
                    }
                    sb.append(String.format("%04X", code));
                }
                sb.append('>');
                return sb.toString().getBytes(
                        java.nio.charset.StandardCharsets.ISO_8859_1);
            }

            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                int code = ch;
                if (run.reverse != null) {
                    Integer mapped = run.reverse.get(String.valueOf(ch));
                    if (mapped != null) code = mapped;
                }
                if (code == '(' || code == ')' || code == '\\') sb.append('\\');
                if (code < 32 || code > 126) {
                    sb.append('\\').append(String.format("%03o", code & 255));
                } else {
                    sb.append((char) code);
                }
            }
            sb.append(')');
            return sb.toString().getBytes(
                    java.nio.charset.StandardCharsets.ISO_8859_1);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Adds text on top of a page.
     *
     * A second content stream appended to the page's /Contents array, using a
     * base-14 font added to the page resources. This always works, whatever
     * the document is made of, and is how new text gets onto a scanned page
     * that has no font to borrow.
     */
    public static byte[] addText(byte[] data, int pageIndex, float x, float y,
                                 float size, String text, int rgb) {
        try {
            Map<Integer, Obj> objs = scanObjects(data);
            expandObjectStreams(objs);
            List<Integer> pages = pageOrder(objs);
            if (pageIndex < 0 || pageIndex >= pages.size()) return null;
            int pageNum = pages.get(pageIndex);
            Obj page = objs.get(pageNum);
            if (page == null) return null;

            int maxObj = 0;
            for (Integer k : objs.keySet()) maxObj = Math.max(maxObj, k);
            int fontObj = maxObj + 1;
            int streamObj = maxObj + 2;

            float r = ((rgb >> 16) & 255) / 255f;
            float g = ((rgb >> 8) & 255) / 255f;
            float b = (rgb & 255) / 255f;

            StringBuilder cs = new StringBuilder();
            cs.append("q\nBT\n")
              .append(String.format(java.util.Locale.US, "%.3f %.3f %.3f rg\n", r, g, b))
              .append("/SlateF1 ").append(fmt(size)).append(" Tf\n")
              .append("1 0 0 1 ").append(fmt(x)).append(' ').append(fmt(y))
              .append(" Tm\n");
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) cs.append("0 ").append(fmt(-size * 1.25f)).append(" Td\n");
                cs.append('(').append(escapeLiteral(lines[i])).append(") Tj\n");
            }
            cs.append("ET\nQ\n");
            byte[] stream = cs.toString().getBytes(
                    java.nio.charset.StandardCharsets.ISO_8859_1);

            String fontDict = "\n<< /Type /Font /Subtype /Type1 "
                    + "/BaseFont /Helvetica /Encoding /WinAnsiEncoding >>\n";
            String streamDict = "\n<< /Length " + stream.length + " >>\n";

            String newPageDict = addResourceAndContent(page.dict, fontObj, streamObj);
            if (newPageDict == null) return null;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(data, 0, data.length);
            Map<Integer, Integer> offsets = new HashMap<Integer, Integer>();

            offsets.put(fontObj, out.size());
            out.write((fontObj + " 0 obj").getBytes("ISO-8859-1"));
            out.write(fontDict.getBytes("ISO-8859-1"));
            out.write("endobj\n".getBytes("ISO-8859-1"));

            offsets.put(streamObj, out.size());
            out.write((streamObj + " 0 obj").getBytes("ISO-8859-1"));
            out.write(streamDict.getBytes("ISO-8859-1"));
            out.write("stream\n".getBytes("ISO-8859-1"));
            out.write(stream, 0, stream.length);
            out.write("\nendstream\nendobj\n".getBytes("ISO-8859-1"));

            offsets.put(pageNum, out.size());
            out.write((pageNum + " 0 obj").getBytes("ISO-8859-1"));
            out.write(newPageDict.getBytes("ISO-8859-1"));
            out.write("endobj\n".getBytes("ISO-8859-1"));

            writeXref(out, data, offsets);
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Covers a rectangle with an opaque block — the whiteout tool. */
    public static byte[] cover(byte[] data, int pageIndex, float x, float y,
                               float w, float h, int rgb) {
        try {
            Map<Integer, Obj> objs = scanObjects(data);
            expandObjectStreams(objs);
            List<Integer> pages = pageOrder(objs);
            if (pageIndex < 0 || pageIndex >= pages.size()) return null;
            int pageNum = pages.get(pageIndex);
            Obj page = objs.get(pageNum);
            if (page == null) return null;

            int maxObj = 0;
            for (Integer k : objs.keySet()) maxObj = Math.max(maxObj, k);
            int streamObj = maxObj + 1;

            float r = ((rgb >> 16) & 255) / 255f;
            float g = ((rgb >> 8) & 255) / 255f;
            float b = (rgb & 255) / 255f;
            String cs = "q\n"
                    + String.format(java.util.Locale.US, "%.3f %.3f %.3f rg\n", r, g, b)
                    + fmt(x) + ' ' + fmt(y) + ' ' + fmt(w) + ' ' + fmt(h) + " re f\nQ\n";
            byte[] stream = cs.getBytes("ISO-8859-1");

            String newPageDict = addContentOnly(page.dict, streamObj);
            if (newPageDict == null) return null;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(data, 0, data.length);
            Map<Integer, Integer> offsets = new HashMap<Integer, Integer>();

            offsets.put(streamObj, out.size());
            out.write((streamObj + " 0 obj\n<< /Length " + stream.length + " >>\nstream\n")
                    .getBytes("ISO-8859-1"));
            out.write(stream, 0, stream.length);
            out.write("\nendstream\nendobj\n".getBytes("ISO-8859-1"));

            offsets.put(pageNum, out.size());
            out.write((pageNum + " 0 obj").getBytes("ISO-8859-1"));
            out.write(newPageDict.getBytes("ISO-8859-1"));
            out.write("endobj\n".getBytes("ISO-8859-1"));

            writeXref(out, data, offsets);
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Page size, so taps on screen can be mapped into PDF coordinates. */
    public static float[] pageSize(byte[] data, int pageIndex) {
        try {
            Map<Integer, Obj> objs = scanObjects(data);
            expandObjectStreams(objs);
            List<Integer> pages = pageOrder(objs);
            if (pageIndex < 0 || pageIndex >= pages.size()) return null;
            Obj page = objs.get(pages.get(pageIndex));
            String dict = page.dict;
            int m = dict.indexOf("/MediaBox");
            // inherited from the parent when the page does not say
            int guard = 0;
            while (m < 0 && guard++ < 8) {
                int p = dict.indexOf("/Parent");
                if (p < 0) break;
                int[] ref = refsIn(dict.substring(p + 7,
                        Math.min(dict.length(), p + 30)));
                if (ref.length == 0) break;
                Obj parent = objs.get(ref[0]);
                if (parent == null) break;
                dict = parent.dict;
                m = dict.indexOf("/MediaBox");
            }
            if (m < 0) return new float[]{612, 792};
            int open = dict.indexOf('[', m), close = dict.indexOf(']', open);
            String[] p = dict.substring(open + 1, close).trim().split("\\s+");
            if (p.length < 4) return new float[]{612, 792};
            float x0 = Float.parseFloat(p[0]), y0 = Float.parseFloat(p[1]);
            float x1 = Float.parseFloat(p[2]), y1 = Float.parseFloat(p[3]);
            return new float[]{Math.abs(x1 - x0), Math.abs(y1 - y0)};
        } catch (Throwable t) {
            return new float[]{612, 792};
        }
    }

    // ---- serialisation helpers ---------------------------------------------

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static String escapeLiteral(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == ')' || c == '\\') sb.append('\\').append(c);
            else if (c < 32 || c > 126) sb.append('\\')
                    .append(String.format("%03o", c & 255));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Removes compression from a rewritten stream and fixes its length. */
    private static String stripFilter(String dict, int length) {
        String d = dict;
        d = d.replaceAll("/Filter\\s*/[A-Za-z0-9]+", "");
        d = d.replaceAll("/Filter\\s*\\[[^\\]]*\\]", "");
        d = d.replaceAll("/DecodeParms\\s*<<[^>]*>>", "");
        d = d.replaceAll("/Length\\s+\\d+\\s+\\d+\\s+R", "/Length " + length);
        if (d.matches("(?s).*/Length\\s+\\d+.*")) {
            d = d.replaceFirst("/Length\\s+\\d+", "/Length " + length);
        } else {
            int i = d.indexOf(">>");
            if (i < 0) return null;
            d = d.substring(0, i) + " /Length " + length + " " + d.substring(i);
        }
        return "\n" + d.trim() + "\nstream\n";
    }

    /** Adds a font to /Resources and a stream to /Contents. */
    private static String addResourceAndContent(String pageDict, int fontObj,
                                                int streamObj) {
        String d = addContentOnly(pageDict, streamObj);
        if (d == null) return null;

        int r = d.indexOf("/Resources");
        String fontEntry = "/SlateF1 " + fontObj + " 0 R";
        if (r < 0) {
            int i = d.indexOf(">>");
            if (i < 0) return null;
            return d.substring(0, i) + " /Resources << /Font << " + fontEntry
                    + " >> >> " + d.substring(i);
        }
        int f = d.indexOf("/Font", r);
        if (f < 0) {
            int open = d.indexOf("<<", r);
            if (open < 0) return null;
            return d.substring(0, open + 2) + " /Font << " + fontEntry + " >> "
                    + d.substring(open + 2);
        }
        int open = d.indexOf("<<", f);
        if (open < 0) return null;
        return d.substring(0, open + 2) + " " + fontEntry + " " + d.substring(open + 2);
    }

    /** Appends a stream reference to the page's /Contents. */
    private static String addContentOnly(String pageDict, int streamObj) {
        String d = pageDict.trim();
        int c = d.indexOf("/Contents");
        String ref = streamObj + " 0 R";
        if (c < 0) {
            int i = d.indexOf(">>");
            if (i < 0) return null;
            return "\n" + d.substring(0, i) + " /Contents [ " + ref + " ] "
                    + d.substring(i) + "\n";
        }
        int after = c + 9;
        while (after < d.length() && isSpaceChar(d.charAt(after))) after++;
        if (after < d.length() && d.charAt(after) == '[') {
            int close = d.indexOf(']', after);
            if (close < 0) return null;
            return "\n" + d.substring(0, close) + " " + ref + d.substring(close) + "\n";
        }
        // a single reference becomes an array of two
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+\\s+\\d+\\s+R)").matcher(d.substring(after));
        if (!m.find()) return null;
        int s = after + m.start(), e = after + m.end();
        return "\n" + d.substring(0, s) + "[ " + d.substring(s, e) + " " + ref + " ]"
                + d.substring(e) + "\n";
    }

    /** The trailing xref section and trailer of an incremental update. */
    private static void writeXref(ByteArrayOutputStream out, byte[] original,
                                  Map<Integer, Integer> offsets) throws Exception {
        int xrefAt = out.size();
        List<Integer> nums = new ArrayList<Integer>(offsets.keySet());
        java.util.Collections.sort(nums);

        StringBuilder x = new StringBuilder("xref\n");
        // Contiguous runs only; an xref subsection header lies about the
        // count otherwise and readers reject the whole update.
        int i = 0;
        while (i < nums.size()) {
            int start = nums.get(i), n = 1;
            while (i + n < nums.size() && nums.get(i + n) == start + n) n++;
            x.append(start).append(' ').append(n).append('\n');
            for (int k = 0; k < n; k++) {
                x.append(String.format("%010d 00000 n \n", offsets.get(start + k)));
            }
            i += n;
        }

        int size = 0;
        for (Integer n : nums) size = Math.max(size, n + 1);
        String root = findRoot(original);
        int prev = findStartXref(original);
        int origSize = findSize(original);
        size = Math.max(size, origSize);

        x.append("trailer\n<< /Size ").append(size);
        if (root != null) x.append(" /Root ").append(root);
        if (prev >= 0) x.append(" /Prev ").append(prev);
        x.append(" >>\nstartxref\n").append(xrefAt).append("\n%%EOF\n");

        out.write(x.toString().getBytes("ISO-8859-1"));
    }

    private static String findRoot(byte[] d) {
        String tail = new String(d, Math.max(0, d.length - 8192),
                Math.min(8192, d.length), java.nio.charset.StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/Root\\s+(\\d+\\s+\\d+\\s+R)").matcher(tail);
        String last = null;
        while (m.find()) last = m.group(1);
        if (last != null) return last;
        // Some files only name the catalog in an xref stream; find the object.
        String all = new String(d, java.nio.charset.StandardCharsets.ISO_8859_1);
        m = java.util.regex.Pattern
                .compile("(\\d+)\\s+(\\d+)\\s+obj\\s*<<[^>]*?/Type\\s*/Catalog")
                .matcher(all);
        if (m.find()) return m.group(1) + " " + m.group(2) + " R";
        return null;
    }

    private static int findStartXref(byte[] d) {
        String tail = new String(d, Math.max(0, d.length - 2048),
                Math.min(2048, d.length), java.nio.charset.StandardCharsets.ISO_8859_1);
        int i = tail.lastIndexOf("startxref");
        if (i < 0) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("startxref\\s+(\\d+)").matcher(tail.substring(i));
        if (!m.find()) return -1;
        try { return Integer.parseInt(m.group(1)); } catch (Throwable t) { return -1; }
    }

    private static int findSize(byte[] d) {
        String tail = new String(d, Math.max(0, d.length - 8192),
                Math.min(8192, d.length), java.nio.charset.StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/Size\\s+(\\d+)").matcher(tail);
        int best = 0;
        while (m.find()) {
            try { best = Math.max(best, Integer.parseInt(m.group(1))); }
            catch (Throwable ignored) {}
        }
        return best;
    }

    /** An incremental update carrying one replaced object. */
    private static byte[] appendObject(byte[] original, int objNum, String dict,
                                       byte[] stream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                original.length + stream.length + 1024);
        out.write(original, 0, original.length);
        if (original.length > 0 && original[original.length - 1] != '\n') {
            out.write('\n');
        }
        Map<Integer, Integer> offsets = new HashMap<Integer, Integer>();
        offsets.put(objNum, out.size());
        out.write((objNum + " 0 obj").getBytes("ISO-8859-1"));
        out.write(dict.getBytes("ISO-8859-1"));
        out.write(stream, 0, stream.length);
        out.write("\nendstream\nendobj\n".getBytes("ISO-8859-1"));
        writeXref(out, original, offsets);
        return out.toByteArray();
    }
}
