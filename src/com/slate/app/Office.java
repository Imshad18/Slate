package com.slate.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Text out of office documents.
 *
 * Every modern office format is a zip of XML, which means they can be read
 * with nothing but java.util.zip and a scanner — no library, no native code,
 * no dependency that would not build here anyway. What this does NOT do is
 * reproduce layout: a docx comes back as text with its paragraph breaks and
 * a spreadsheet as a grid of values. That is the honest limit of the
 * approach, and for reading a document on a phone it is most of the value.
 *
 * The XML is scanned rather than parsed into a tree. Office files nest
 * deeply and a DOM of a long document is tens of megabytes of objects for
 * information that is entirely contained in a handful of tags.
 */
public final class Office {

    private Office() {}

    public static class Sheet {
        public String name = "";
        public List<List<String>> rows = new ArrayList<List<String>>();
    }

    public static class Content {
        public String text = "";
        public List<Sheet> sheets = new ArrayList<Sheet>();
        public String note = "";
        public boolean ok;
    }

    /** Reads whatever the extension says this is. */
    public static Content read(byte[] data, String ext) {
        Content c = new Content();
        String e = ext == null ? "" : ext.toLowerCase(Locale.US);
        try {
            if (e.equals("docx")) { c.text = docx(data); c.ok = true; }
            else if (e.equals("xlsx")) { c.sheets = xlsx(data); c.ok = !c.sheets.isEmpty(); }
            else if (e.equals("pptx")) { c.text = pptx(data); c.ok = true; }
            else if (e.equals("odt") || e.equals("odp")) { c.text = odf(data); c.ok = true; }
            else if (e.equals("ods")) { c.sheets = ods(data); c.ok = !c.sheets.isEmpty(); }
            else if (e.equals("epub")) { c.text = epub(data); c.ok = true; }
            else if (e.equals("rtf")) { c.text = rtf(data); c.ok = true; }
            else {
                // doc, xls, ppt and the rest are undocumented binary formats
                // from the nineties. Pulling the readable runs out of them
                // recovers most of the prose and none of the structure, which
                // is worth more than refusing to open the file.
                c.text = salvage(data);
                c.note = "Legacy binary format — text recovered, layout lost.";
                c.ok = c.text.length() > 0;
            }
        } catch (Throwable t) {
            c.note = "Could not read: " + t.getClass().getSimpleName();
        }
        if (c.text.length() == 0 && c.sheets.isEmpty() && c.note.length() == 0) {
            c.note = "No readable text found.";
        }
        return c;
    }

    // =====================================================================
    // ZIP
    // =====================================================================

    /** Pulls named entries out of a zip in one pass. */
    private static Map<String, byte[]> unzip(byte[] data, String... wanted) {
        Map<String, byte[]> out = new HashMap<String, byte[]>();
        ZipInputStream zin = null;
        try {
            zin = new ZipInputStream(new java.io.ByteArrayInputStream(data));
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                boolean take = wanted.length == 0;
                for (String w : wanted) {
                    // Prefix OR exact. Requiring an explicit trailing star was
                    // a trap: every caller asking for "xl/worksheets/sheet"
                    // meant the prefix, got exact matching, and silently
                    // extracted nothing at all.
                    if (n.equals(w) || n.startsWith(w)) { take = true; break; }
                }
                if (!take) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                int r;
                while ((r = zin.read(buf)) > 0) bo.write(buf, 0, r);
                out.put(n, bo.toByteArray());
            }
        } catch (Throwable ignored) {
        } finally {
            if (zin != null) try { zin.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    /** Every entry name, for the archive browser. */
    public static List<String[]> list(InputStream in) {
        List<String[]> out = new ArrayList<String[]>();
        ZipInputStream zin = new ZipInputStream(in);
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                out.add(new String[]{
                    e.getName(),
                    e.isDirectory() ? "" : Doc.size(e.getSize()),
                    e.isDirectory() ? "dir" : "file"
                });
                if (out.size() > 5000) break;
            }
        } catch (Throwable ignored) {
        } finally {
            try { zin.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    /** Extracts one entry by name. */
    public static byte[] entry(InputStream in, String name) {
        ZipInputStream zin = new ZipInputStream(in);
        try {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zin.getNextEntry()) != null) {
                if (!e.getName().equals(name)) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                int r;
                while ((r = zin.read(buf)) > 0) bo.write(buf, 0, r);
                return bo.toByteArray();
            }
        } catch (Throwable ignored) {
        } finally {
            try { zin.close(); } catch (Throwable ignored) {}
        }
        return null;
    }

    // =====================================================================
    // OOXML
    // =====================================================================

    private static String docx(byte[] data) {
        Map<String, byte[]> z = unzip(data, "word/document.xml");
        byte[] d = z.get("word/document.xml");
        if (d == null) return "";
        String xml = new String(d, java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < xml.length()) {
            int p = xml.indexOf("<w:p", i);
            if (p < 0) break;
            int pEnd = xml.indexOf("</w:p>", p);
            if (pEnd < 0) pEnd = xml.length();
            appendRuns(xml, p, pEnd, "<w:t", sb);
            sb.append('\n');
            i = pEnd + 6;
        }
        return collapse(sb.toString());
    }

    private static String pptx(byte[] data) {
        Map<String, byte[]> z = unzip(data, "ppt/slides/slide");
        List<String> names = new ArrayList<String>(z.keySet());
        java.util.Collections.sort(names, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) {
                return numIn(a) - numIn(b);
            }
        });
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String name : names) {
            if (!name.endsWith(".xml")) continue;
            String xml = new String(z.get(name), java.nio.charset.StandardCharsets.UTF_8);
            sb.append("── Slide ").append(++n).append(" ──\n");
            appendRuns(xml, 0, xml.length(), "<a:t", sb);
            sb.append("\n\n");
        }
        return collapse(sb.toString());
    }

    private static int numIn(String s) {
        int n = 0;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') { n = n * 10 + (c - '0'); any = true; }
            else if (any) break;
        }
        return n;
    }

    private static List<Sheet> xlsx(byte[] data) {
        Map<String, byte[]> z = unzip(data, "xl/sharedStrings.xml",
                "xl/worksheets/sheet", "xl/workbook.xml");

        // shared strings: xlsx stores every repeated cell value once
        List<String> shared = new ArrayList<String>();
        byte[] ss = z.get("xl/sharedStrings.xml");
        if (ss != null) {
            String xml = new String(ss, java.nio.charset.StandardCharsets.UTF_8);
            int i = 0;
            while (true) {
                int si = xml.indexOf("<si", i);
                if (si < 0) break;
                int se = xml.indexOf("</si>", si);
                if (se < 0) se = xml.length();
                StringBuilder sb = new StringBuilder();
                appendRuns(xml, si, se, "<t", sb);
                shared.add(sb.toString());
                i = se + 5;
            }
        }

        List<Sheet> out = new ArrayList<Sheet>();
        List<String> names = new ArrayList<String>(z.keySet());
        java.util.Collections.sort(names);
        for (String name : names) {
            if (!name.startsWith("xl/worksheets/sheet") || !name.endsWith(".xml")) continue;
            String xml = new String(z.get(name), java.nio.charset.StandardCharsets.UTF_8);
            Sheet sh = new Sheet();
            sh.name = "Sheet " + Math.max(1, numIn(name.substring(19)));
            parseSheetXml(xml, shared, sh);
            if (!sh.rows.isEmpty()) out.add(sh);
        }
        return out;
    }

    /**
     * Reads one worksheet.
     *
     * Cell references are honoured rather than assuming cells arrive in
     * order: xlsx omits empty cells entirely, so a row written as A1, C1, F1
     * would otherwise collapse to three adjacent columns and silently shift
     * every value into the wrong place.
     */
    private static void parseSheetXml(String xml, List<String> shared, Sheet sh) {
        int i = 0;
        while (true) {
            int r = xml.indexOf("<row", i);
            if (r < 0) break;
            int rEnd = xml.indexOf("</row>", r);
            if (rEnd < 0) rEnd = xml.length();

            List<String> row = new ArrayList<String>();
            int c = r;
            while (true) {
                c = xml.indexOf("<c ", c);
                if (c < 0 || c > rEnd) break;
                int cTagEnd = xml.indexOf('>', c);
                if (cTagEnd < 0) break;
                String tag = xml.substring(c, cTagEnd);
                boolean selfClosing = xml.charAt(cTagEnd - 1) == '/';
                int cEnd = selfClosing ? cTagEnd : xml.indexOf("</c>", cTagEnd);
                if (cEnd < 0) cEnd = rEnd;

                int col = columnOf(attr(tag, "r="));
                while (row.size() < col) row.add("");

                String type = attr(tag, "t=");
                String value = "";
                if (!selfClosing) {
                    StringBuilder sb = new StringBuilder();
                    appendRuns(xml, cTagEnd, cEnd, "<v", sb);
                    if (sb.length() == 0) appendRuns(xml, cTagEnd, cEnd, "<t", sb);
                    value = sb.toString();
                    if ("s".equals(type)) {
                        try {
                            int idx = Integer.parseInt(value.trim());
                            value = idx >= 0 && idx < shared.size() ? shared.get(idx) : "";
                        } catch (Throwable ignored) {}
                    }
                }
                if (row.size() == col) row.add(value); else row.set(col, value);
                c = cEnd;
            }
            sh.rows.add(row);
            if (sh.rows.size() > 20000) break;
            i = rEnd + 6;
        }
    }

    /** "BC12" to a zero-based column index. */
    private static int columnOf(String ref) {
        int col = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = Character.toUpperCase(ref.charAt(i));
            if (c < 'A' || c > 'Z') break;
            col = col * 26 + (c - 'A' + 1);
        }
        return Math.max(0, col - 1);
    }

    private static String attr(String tag, String key) {
        int i = tag.indexOf(key);
        if (i < 0) return "";
        int q = tag.indexOf('"', i);
        if (q < 0) return "";
        int e = tag.indexOf('"', q + 1);
        return e < 0 ? "" : tag.substring(q + 1, e);
    }

    // =====================================================================
    // OPEN DOCUMENT
    // =====================================================================

    private static String odf(byte[] data) {
        byte[] d = unzip(data, "content.xml").get("content.xml");
        if (d == null) return "";
        String xml = new String(d, java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < xml.length()) {
            int p = xml.indexOf("<text:p", i);
            if (p < 0) break;
            int pEnd = xml.indexOf("</text:p>", p);
            if (pEnd < 0) pEnd = xml.length();
            sb.append(stripTags(xml.substring(p, pEnd))).append('\n');
            i = pEnd + 9;
        }
        return collapse(sb.toString());
    }

    private static List<Sheet> ods(byte[] data) {
        List<Sheet> out = new ArrayList<Sheet>();
        byte[] d = unzip(data, "content.xml").get("content.xml");
        if (d == null) return out;
        String xml = new String(d, java.nio.charset.StandardCharsets.UTF_8);

        int t = 0, n = 0;
        while (true) {
            int ts = xml.indexOf("<table:table ", t);
            if (ts < 0) break;
            int te = xml.indexOf("</table:table>", ts);
            if (te < 0) te = xml.length();

            Sheet sh = new Sheet();
            sh.name = "Sheet " + (++n);
            int r = ts;
            while (true) {
                r = xml.indexOf("<table:table-row", r);
                if (r < 0 || r > te) break;
                int re = xml.indexOf("</table:table-row>", r);
                if (re < 0 || re > te) re = te;
                List<String> row = new ArrayList<String>();
                int c = r;
                while (true) {
                    c = xml.indexOf("<table:table-cell", c);
                    if (c < 0 || c > re) break;
                    int ce = xml.indexOf("</table:table-cell>", c);
                    boolean self = ce < 0 || ce > re;
                    int stop = self ? Math.min(re, xml.indexOf('>', c) + 1) : ce;
                    String cellXml = xml.substring(c, Math.max(c, stop));
                    // repeated columns are compressed in ODS
                    int rep = 1;
                    String repAttr = attr(cellXml, "number-columns-repeated=");
                    if (repAttr.length() > 0) {
                        try { rep = Math.min(512, Integer.parseInt(repAttr)); }
                        catch (Throwable ignored) {}
                    }
                    String v = self ? "" : stripTags(xml.substring(c, ce));
                    for (int k = 0; k < rep; k++) row.add(v);
                    c = self ? stop : ce + 19;
                }
                while (!row.isEmpty() && row.get(row.size() - 1).isEmpty()) {
                    row.remove(row.size() - 1);
                }
                if (!row.isEmpty()) sh.rows.add(row);
                if (sh.rows.size() > 20000) break;
                r = re + 18;
            }
            if (!sh.rows.isEmpty()) out.add(sh);
            t = te + 14;
        }
        return out;
    }

    private static String epub(byte[] data) {
        Map<String, byte[]> z = unzip(data);
        List<String> names = new ArrayList<String>(z.keySet());
        java.util.Collections.sort(names);
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            String low = n.toLowerCase(Locale.US);
            if (!low.endsWith(".xhtml") && !low.endsWith(".html")
                    && !low.endsWith(".htm")) continue;
            sb.append(stripTags(new String(z.get(n),
                    java.nio.charset.StandardCharsets.UTF_8)));
            sb.append("\n\n");
            if (sb.length() > 4_000_000) break;
        }
        return collapse(sb.toString());
    }

    // =====================================================================
    // FALLBACKS
    // =====================================================================

    private static String rtf(byte[] data) {
        String s = new String(data, java.nio.charset.StandardCharsets.ISO_8859_1);
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') { depth++; continue; }
            if (c == '}') { depth--; continue; }
            if (c == '\\') {
                int j = i + 1;
                while (j < s.length() && Character.isLetter(s.charAt(j))) j++;
                String word = s.substring(i + 1, j);
                if (word.equals("par") || word.equals("line")) sb.append('\n');
                while (j < s.length() && (Character.isDigit(s.charAt(j))
                        || s.charAt(j) == '-')) j++;
                if (j < s.length() && s.charAt(j) == ' ') j++;
                i = j - 1;
                continue;
            }
            sb.append(c);
        }
        return collapse(sb.toString());
    }

    /**
     * Readable runs out of an unknown binary.
     *
     * Legacy .doc and .xls are structured binary formats nobody should parse
     * by hand. Everything printable and long enough to be a word is pulled
     * out in order, which recovers the prose from a .doc surprisingly well
     * and recovers nothing useful from, say, a compressed stream — in which
     * case the caller is told so rather than being shown mojibake.
     */
    private static String salvage(byte[] data) {
        StringBuilder sb = new StringBuilder();
        StringBuilder run = new StringBuilder();

        // Word stores text as UTF-16LE, so ASCII appears as 'a',0,'b',0.
        // Detecting that and stepping two bytes at a time is what turns
        // "H e l l o" into "Hello".
        int zeros = 0, sampled = 0;
        for (int i = 1; i < Math.min(data.length, 20000); i += 2) {
            sampled++;
            if (data[i] == 0) zeros++;
        }
        // A PROPORTION, not a count. An absolute threshold meant a short
        // document never cleared it, so its UTF-16 text was read a byte at a
        // time and every run broke on the interleaved NULs.
        int step = (sampled > 8 && zeros * 100 > sampled * 35) ? 2 : 1;

        for (int i = 0; i < data.length; i += step) {
            int v = data[i] & 255;
            boolean printable = v == 9 || v == 10 || v == 13
                    || (v >= 32 && v < 127) || (v >= 160 && v < 255);
            if (printable) {
                run.append((char) v);
            } else {
                if (run.length() >= 4) sb.append(run).append('\n');
                run.setLength(0);
            }
        }
        if (run.length() >= 4) sb.append(run);
        return collapse(sb.toString());
    }

    // =====================================================================

    /** Appends the text content of every {@code tag} between two offsets. */
    private static void appendRuns(String xml, int from, int to, String tag,
                                   StringBuilder sb) {
        int i = from;
        while (i < to) {
            int t = xml.indexOf(tag, i);
            if (t < 0 || t >= to) break;
            int gt = xml.indexOf('>', t);
            if (gt < 0 || gt >= to) break;
            // a self-closing tag has no content
            if (xml.charAt(gt - 1) == '/') { i = gt + 1; continue; }
            int close = xml.indexOf('<', gt);
            if (close < 0 || close > to) close = to;
            sb.append(unescape(xml.substring(gt + 1, close)));
            i = close;
        }
    }

    public static String stripTags(String xml) {
        StringBuilder sb = new StringBuilder(xml.length());
        boolean in = false;
        for (int i = 0; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (c == '<') { in = true; continue; }
            if (c == '>') { in = false; continue; }
            if (!in) sb.append(c);
        }
        return unescape(sb.toString());
    }

    public static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&nbsp;", " ").replace("&amp;", "&");
    }

    /** Trims runaway blank lines without losing paragraph breaks. */
    private static String collapse(String s) {
        String[] lines = s.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int blanks = 0;
        for (String l : lines) {
            String t = l.replace(' ', ' ').replaceAll("[ \\t]+$", "");
            if (t.trim().isEmpty()) {
                if (++blanks > 1) continue;
            } else blanks = 0;
            sb.append(t).append('\n');
        }
        return sb.toString().trim();
    }
}
