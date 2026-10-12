package com.slate.app;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What a file is, and how to open it.
 *
 * Detection is by extension FIRST and magic bytes second, which is the
 * opposite of what you might expect — but the extension is known before a
 * single byte is read, and the whole point of this app is that the right
 * viewer is on screen before the file has finished loading. Magic bytes only
 * arbitrate when the extension is missing or lying.
 */
public final class Doc {

    public static final int TEXT = 0, CODE = 1, MARKDOWN = 2, PDF = 3, IMAGE = 4,
            SHEET = 5, OFFICE = 6, ARCHIVE = 7, BINARY = 8, HTML = 9;

    public static final String[] KIND_NAMES = {
        "TEXT", "CODE", "MARKDOWN", "PDF", "IMAGE", "SHEET", "DOCUMENT",
        "ARCHIVE", "BINARY", "HTML"
    };

    /** Extension to kind. */
    private static final Map<String, Integer> EXT = new HashMap<String, Integer>();

    /** Extension to syntax language id, for highlighting. */
    private static final Map<String, Integer> LANG = new HashMap<String, Integer>();

    static {
        // ---- code, grouped by the highlighter they use ------------------
        put(Syntax.C,      CODE, "c","h","cpp","cc","cxx","hpp","hh","m","mm","cs");
        put(Syntax.JAVA,   CODE, "java","kt","kts","scala","groovy","gradle");
        put(Syntax.JS,     CODE, "js","mjs","cjs","jsx","ts","tsx","json5");
        put(Syntax.PY,     CODE, "py","pyw","pyi");
        put(Syntax.SHELL,  CODE, "sh","bash","zsh","fish","ksh","profile","bashrc");
        put(Syntax.RUST,   CODE, "rs","go","swift","dart","zig");
        put(Syntax.RUBY,   CODE, "rb","pl","pm","lua","tcl","r","jl");
        put(Syntax.PHP,    CODE, "php","php3","php4","php5","phtml");
        put(Syntax.SQL,    CODE, "sql","psql","mysql","ddl");
        put(Syntax.CSS,    CODE, "css","scss","sass","less","styl");
        put(Syntax.XML,    CODE, "xml","svg","plist","xsd","xsl","rss","atom","pom");
        put(Syntax.JSON,   CODE, "json","jsonl","ndjson","webmanifest","ipynb");
        put(Syntax.YAML,   CODE, "yaml","yml","toml","ini","cfg","conf","properties",
                                 "env","editorconfig","gitconfig");
        put(Syntax.NONE,   TEXT, "txt","text","log","nfo","asc","rst","tex","srt","vtt",
                                 "diff","patch","gitignore","dockerignore","lock",
                                 "makefile","mk","cmake","gradlew","list","dat");

        put(Syntax.MD,     MARKDOWN, "md","markdown","mdown","mkd","mdx");
        put(Syntax.HTMLX,  HTML, "html","htm","xhtml");

        put(Syntax.NONE,   PDF, "pdf");
        put(Syntax.NONE,   IMAGE, "png","jpg","jpeg","gif","webp","bmp","heic","heif",
                                  "avif","ico","dng");
        put(Syntax.NONE,   SHEET, "csv","tsv","tab");
        put(Syntax.NONE,   OFFICE, "docx","xlsx","pptx","odt","ods","odp","doc","xls",
                                   "ppt","rtf","epub","pages","numbers","key");
        put(Syntax.NONE,   ARCHIVE, "zip","jar","apk","aar","war","ipa","xpi","crx",
                                    "tar","gz","tgz","bz2","xz","7z","rar");
    }

    private static void put(int lang, int kind, String... exts) {
        for (String e : exts) { EXT.put(e, kind); LANG.put(e, lang); }
    }

    public String name = "";
    public String ext = "";
    public int kind = BINARY;
    public int lang = Syntax.NONE;
    public long size;
    public boolean writable;

    public static String extensionOf(String name) {
        if (name == null) return "";
        String n = name.toLowerCase(Locale.US);
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        if (dot < 0 || dot == n.length() - 1) {
            // Files with no dot at all are often still text: Makefile, LICENSE,
            // Dockerfile. Treat the bare name as the extension so the table
            // above can claim them.
            return n;
        }
        return n.substring(dot + 1);
    }

    /**
     * Classifies from the name alone. Instant, and right the overwhelming
     * majority of the time.
     */
    public static Doc fromName(String name, long size) {
        Doc d = new Doc();
        d.name = name == null ? "file" : name;
        d.ext = extensionOf(d.name);
        d.size = size;
        Integer k = EXT.get(d.ext);
        Integer l = LANG.get(d.ext);
        d.kind = k == null ? BINARY : k;
        d.lang = l == null ? Syntax.NONE : l;
        return d;
    }

    /**
     * Refines the guess using the first bytes of the file.
     *
     * Only ever used to CORRECT a name-based guess, never to make the first
     * one — reading bytes means waiting for IO, and the viewer should already
     * be on screen by then. The common case it fixes is a download named
     * "attachment" that is really a PDF, and an unknown extension that turns
     * out to be plain UTF-8 text.
     */
    public void refine(byte[] head, int len) {
        if (len <= 0) return;

        if (len >= 5 && head[0] == '%' && head[1] == 'P' && head[2] == 'D'
                && head[3] == 'F') { kind = PDF; return; }
        if (len >= 8 && (head[0] & 255) == 0x89 && head[1] == 'P' && head[2] == 'N'
                && head[3] == 'G') { kind = IMAGE; return; }
        if (len >= 3 && (head[0] & 255) == 0xFF && (head[1] & 255) == 0xD8) {
            kind = IMAGE; return;
        }
        if (len >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F') {
            kind = IMAGE; return;
        }
        if (len >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B') {
            kind = IMAGE; return;
        }
        if (len >= 4 && head[0] == 'P' && head[1] == 'K'
                && (head[2] == 3 || head[2] == 5 || head[2] == 7)) {
            // A zip. Office documents are zips too, so only downgrade to
            // ARCHIVE when the name has not already claimed something better.
            if (kind != OFFICE) kind = ARCHIVE;
            return;
        }
        if (kind == BINARY && looksLikeText(head, len)) {
            kind = TEXT;
            lang = Syntax.NONE;
        }
    }

    /**
     * Is this plausibly text?
     *
     * A NUL byte in the first block is the strongest signal of binary there
     * is — no text encoding this app will meet produces one — and a high
     * proportion of control characters is the next. Everything else is
     * allowed through, because misreading a text file as binary is far more
     * annoying than the reverse.
     */
    public static boolean looksLikeText(byte[] b, int len) {
        int control = 0;
        for (int i = 0; i < len; i++) {
            int v = b[i] & 255;
            if (v == 0) return false;
            if (v < 9 || (v > 13 && v < 32)) control++;
        }
        return control * 100 < len * 5;
    }

    public boolean isTextual() {
        return kind == TEXT || kind == CODE || kind == MARKDOWN
                || kind == HTML || kind == SHEET;
    }

    public boolean isEditable() { return isTextual(); }

    public String kindName() { return KIND_NAMES[kind]; }

    /** A short human size. */
    public static String size(long b) {
        if (b < 0) return "?";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        }
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }
}
