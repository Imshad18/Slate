package com.slate.app;

import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.HashSet;
import java.util.Set;

/**
 * Syntax highlighting.
 *
 * A single hand-written scanner driven by a small per-language table, rather
 * than a regex per token class. Regexes over a large buffer are the usual way
 * this is done and the usual reason editors stutter: every pattern rescans the
 * whole region, and backtracking on an unterminated string can go quadratic.
 * One pass, one character at a time, is predictable.
 *
 * Only the visible range is ever highlighted — see CodeView — so this runs on
 * a few thousand characters per frame at most.
 */
public final class Syntax {

    private Syntax() {}

    public static final int NONE = 0, C = 1, JAVA = 2, JS = 3, PY = 4, SHELL = 5,
            RUST = 6, RUBY = 7, PHP = 8, SQL = 9, CSS = 10, XML = 11, JSON = 12,
            YAML = 13, MD = 14, HTMLX = 15;

    /** One language's lexical rules. */
    private static class Rules {
        Set<String> keywords = new HashSet<String>();
        Set<String> types = new HashSet<String>();
        String lineComment = "//";
        String blockOpen = "/*", blockClose = "*/";
        boolean hashComment;      // # starts a comment
        boolean tripleQuote;      // """ and ''' are strings
        boolean markup;           // < > tags rather than code
    }

    private static final Rules[] TABLE = new Rules[16];

    private static Rules rules(int lang, String kw, String ty) {
        Rules r = new Rules();
        for (String s : kw.split(" ")) if (s.length() > 0) r.keywords.add(s);
        for (String s : ty.split(" ")) if (s.length() > 0) r.types.add(s);
        TABLE[lang] = r;
        return r;
    }

    static {
        rules(NONE, "", "");

        rules(C, "if else for while do switch case default break continue return goto "
                + "struct union enum typedef sizeof static const volatile extern inline "
                + "register auto signed unsigned namespace using class public private "
                + "protected virtual template typename new delete this nullptr try catch "
                + "throw operator friend explicit constexpr noexcept",
              "void int char long short float double bool unsigned size_t uint8_t "
                + "uint16_t uint32_t uint64_t int8_t int16_t int32_t int64_t FILE NULL true false");

        rules(JAVA, "if else for while do switch case default break continue return "
                + "class interface enum extends implements import package public private "
                + "protected static final abstract synchronized volatile transient native "
                + "new this super try catch finally throw throws instanceof assert "
                + "fun val var when object data sealed suspend override companion",
              "void int long short byte char float double boolean String Object List Map "
                + "Set Integer Long Double Boolean Float true false null Unit Any Nothing");

        rules(JS, "if else for while do switch case default break continue return "
                + "function const let var class extends new this super try catch finally "
                + "throw typeof instanceof delete in of async await yield export import "
                + "from as default interface type enum implements readonly private public "
                + "protected static get set",
              "true false null undefined NaN Infinity Promise Array Object String Number "
                + "Boolean Math JSON console window document number string boolean any void");

        Rules py = rules(PY, "if elif else for while break continue return def class "
                + "import from as pass raise try except finally with lambda global nonlocal "
                + "yield assert del async await in is not and or match case",
              "True False None self cls int str float bool list dict set tuple bytes "
                + "object range len print type super Exception");
        py.hashComment = true; py.lineComment = "#"; py.tripleQuote = true;
        py.blockOpen = null;

        Rules sh = rules(SHELL, "if then elif else fi for while until do done case esac "
                + "function return break continue in select time coproc local export "
                + "readonly declare typeset unset shift source alias eval exec trap set",
              "echo cd ls cat grep sed awk true false exit test printf read mkdir rm cp mv");
        sh.hashComment = true; sh.lineComment = "#"; sh.blockOpen = null;

        rules(RUST, "fn let mut const static if else match for while loop break continue "
                + "return struct enum impl trait pub use mod crate self super where as dyn "
                + "ref move unsafe async await type extern func go defer chan select package "
                + "var range import interface map",
              "i8 i16 i32 i64 u8 u16 u32 u64 usize isize f32 f64 bool char str String Vec "
                + "Option Some None Result Ok Err true false nil error byte rune string int");

        Rules rb = rules(RUBY, "def end if elsif else unless while until for in do begin "
                + "rescue ensure raise class module require require_relative return yield "
                + "break next redo retry then case when self super lambda proc attr_accessor "
                + "local function and or not elseif repeat",
              "true false nil puts print p new String Integer Float Array Hash Symbol");
        rb.hashComment = true; rb.lineComment = "#"; rb.blockOpen = null;

        rules(PHP, "if else elseif endif for foreach while do switch case default break "
                + "continue return function class extends implements interface trait new "
                + "public private protected static final abstract try catch finally throw "
                + "namespace use echo print require include global",
              "true false null array string int float bool void self parent this");

        rules(SQL, "SELECT FROM WHERE INSERT INTO VALUES UPDATE SET DELETE CREATE TABLE "
                + "ALTER DROP INDEX VIEW JOIN LEFT RIGHT INNER OUTER FULL ON GROUP BY "
                + "ORDER HAVING LIMIT OFFSET UNION ALL DISTINCT AS AND OR NOT NULL IS IN "
                + "BETWEEN LIKE EXISTS CASE WHEN THEN ELSE END PRIMARY KEY FOREIGN "
                + "REFERENCES CONSTRAINT DEFAULT CASCADE BEGIN COMMIT ROLLBACK",
              "INT INTEGER VARCHAR CHAR TEXT BLOB REAL FLOAT DOUBLE DECIMAL NUMERIC DATE "
                + "TIME TIMESTAMP BOOLEAN SERIAL BIGINT SMALLINT COUNT SUM AVG MIN MAX");
        TABLE[SQL].lineComment = "--";

        rules(CSS, "import media keyframes supports charset font-face namespace "
                + "page include mixin extend if else for each function return",
              "px em rem vh vw pt deg ms important inherit initial unset auto none");
        TABLE[CSS].lineComment = "//";

        Rules xml = rules(XML, "", "");
        xml.markup = true; xml.lineComment = null;
        xml.blockOpen = "<!--"; xml.blockClose = "-->";

        Rules html = rules(HTMLX, "", "");
        html.markup = true; html.lineComment = null;
        html.blockOpen = "<!--"; html.blockClose = "-->";

        Rules json = rules(JSON, "true false null", "");
        json.lineComment = "//"; json.blockOpen = null;

        Rules yaml = rules(YAML, "true false null yes no on off", "");
        yaml.hashComment = true; yaml.lineComment = "#"; yaml.blockOpen = null;

        rules(MD, "", "");
    }

    // =====================================================================

    /**
     * Applies colour to one region of a Spannable.
     *
     * Existing spans in the region are cleared first. Leaving them would make
     * every rehighlight pile new spans on old ones, and a Spannable with tens
     * of thousands of overlapping spans gets slower on every single edit —
     * which is the classic way a syntax-highlighted EditText turns to treacle
     * after a few minutes of typing.
     */
    public static void apply(Spannable s, int from, int to, int lang) {
        from = Math.max(0, from);
        to = Math.min(s.length(), to);
        if (to <= from) return;

        ForegroundColorSpan[] old = s.getSpans(from, to, ForegroundColorSpan.class);
        for (ForegroundColorSpan sp : old) s.removeSpan(sp);
        StyleSpan[] oldB = s.getSpans(from, to, StyleSpan.class);
        for (StyleSpan sp : oldB) s.removeSpan(sp);

        Rules r = TABLE[lang < 0 || lang >= TABLE.length ? NONE : lang];
        if (lang == MD) { markdown(s, from, to); return; }
        if (lang == NONE) return;
        if (r.markup) { markup(s, from, to, r); return; }

        scan(s, from, to, r);
    }

    private static void colour(Spannable s, int a, int b, int c) {
        if (b > a) s.setSpan(new ForegroundColorSpan(c), a, b,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** The general code scanner: comments, strings, numbers, words. */
    private static void scan(Spannable s, int from, int to, Rules r) {
        int i = from;
        while (i < to) {
            char ch = s.charAt(i);

            // line comment
            if (r.lineComment != null && matches(s, i, to, r.lineComment)) {
                int e = lineEnd(s, i, to);
                colour(s, i, e, Ink.COMMENT);
                i = e;
                continue;
            }
            // block comment
            if (r.blockOpen != null && matches(s, i, to, r.blockOpen)) {
                int e = indexOf(s, i + r.blockOpen.length(), to, r.blockClose);
                e = e < 0 ? to : e + r.blockClose.length();
                colour(s, i, e, Ink.COMMENT);
                i = e;
                continue;
            }
            // triple-quoted string
            if (r.tripleQuote && (matches(s, i, to, "\"\"\"") || matches(s, i, to, "'''"))) {
                String q = s.subSequence(i, i + 3).toString();
                int e = indexOf(s, i + 3, to, q);
                e = e < 0 ? to : e + 3;
                colour(s, i, e, Ink.STRING);
                i = e;
                continue;
            }
            // string
            if (ch == '"' || ch == '\'' || ch == '`') {
                int e = stringEnd(s, i, to, ch);
                colour(s, i, e, Ink.STRING);
                i = e;
                continue;
            }
            // number
            if (Character.isDigit(ch)
                    && (i == from || !isWordChar(s.charAt(i - 1)))) {
                int e = i;
                while (e < to && (Character.isLetterOrDigit(s.charAt(e))
                        || s.charAt(e) == '.' || s.charAt(e) == 'x'
                        || s.charAt(e) == '_')) e++;
                colour(s, i, e, Ink.NUMBER);
                i = e;
                continue;
            }
            // word
            if (isWordStart(ch)) {
                int e = i;
                while (e < to && isWordChar(s.charAt(e))) e++;
                String w = s.subSequence(i, e).toString();
                if (r.keywords.contains(w)) {
                    colour(s, i, e, Ink.KEYWORD);
                    s.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), i, e,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (r.types.contains(w)) {
                    colour(s, i, e, Ink.TYPE);
                } else if (e < to && s.charAt(e) == '(') {
                    colour(s, i, e, Ink.FUNC);
                } else if (w.length() > 0 && Character.isUpperCase(w.charAt(0))) {
                    colour(s, i, e, Ink.TYPE);
                }
                i = e;
                continue;
            }
            // annotation or decorator
            if (ch == '@' && i + 1 < to && isWordStart(s.charAt(i + 1))) {
                int e = i + 1;
                while (e < to && isWordChar(s.charAt(e))) e++;
                colour(s, i, e, Ink.ANNOT);
                i = e;
                continue;
            }
            i++;
        }
    }

    /** Tags, attributes and entities. */
    private static void markup(Spannable s, int from, int to, Rules r) {
        int i = from;
        while (i < to) {
            if (matches(s, i, to, "<!--")) {
                int e = indexOf(s, i + 4, to, "-->");
                e = e < 0 ? to : e + 3;
                colour(s, i, e, Ink.COMMENT);
                i = e;
                continue;
            }
            if (s.charAt(i) == '<') {
                int e = i;
                while (e < to && s.charAt(e) != '>') e++;
                if (e < to) e++;
                // tag name in one colour, attributes and values in others
                int nameStart = i + 1;
                if (nameStart < e && s.charAt(nameStart) == '/') nameStart++;
                int nameEnd = nameStart;
                while (nameEnd < e && isWordChar(s.charAt(nameEnd))) nameEnd++;
                colour(s, i, nameEnd, Ink.KEYWORD);
                int j = nameEnd;
                while (j < e) {
                    char c = s.charAt(j);
                    if (c == '"' || c == '\'') {
                        int q = stringEnd(s, j, e, c);
                        colour(s, j, q, Ink.STRING);
                        j = q;
                    } else if (isWordStart(c)) {
                        int a = j;
                        while (j < e && isWordChar(s.charAt(j))) j++;
                        colour(s, a, j, Ink.ATTR);
                    } else j++;
                }
                colour(s, e - 1, e, Ink.KEYWORD);
                i = e;
                continue;
            }
            if (s.charAt(i) == '&') {
                int e = i;
                while (e < to && e < i + 10 && s.charAt(e) != ';') e++;
                if (e < to && s.charAt(e) == ';') {
                    colour(s, i, e + 1, Ink.NUMBER);
                    i = e + 1;
                    continue;
                }
            }
            i++;
        }
    }

    /** Headings, emphasis, code spans, links. */
    private static void markdown(Spannable s, int from, int to) {
        int i = from;
        while (i < to) {
            char ch = s.charAt(i);
            boolean atLineStart = i == 0 || s.charAt(i - 1) == '\n';

            if (atLineStart && ch == '#') {
                int e = lineEnd(s, i, to);
                colour(s, i, e, Ink.KEYWORD);
                s.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), i, e,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = e;
                continue;
            }
            if (atLineStart && (ch == '>' || ch == '-' || ch == '*' || ch == '+')) {
                colour(s, i, Math.min(to, i + 1), Ink.ANNOT);
                i++;
                continue;
            }
            if (matches(s, i, to, "```")) {
                int e = indexOf(s, i + 3, to, "```");
                e = e < 0 ? to : e + 3;
                colour(s, i, e, Ink.STRING);
                i = e;
                continue;
            }
            if (ch == '`') {
                int e = i + 1;
                while (e < to && s.charAt(e) != '`' && s.charAt(e) != '\n') e++;
                if (e < to) e++;
                colour(s, i, e, Ink.STRING);
                i = e;
                continue;
            }
            if (matches(s, i, to, "**") || matches(s, i, to, "__")) {
                String q = s.subSequence(i, i + 2).toString();
                int e = indexOf(s, i + 2, to, q);
                e = e < 0 ? Math.min(to, i + 2) : e + 2;
                s.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), i, e,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                colour(s, i, e, Ink.TYPE);
                i = e;
                continue;
            }
            if (ch == '[') {
                int close = indexOf(s, i, to, "]");
                if (close > 0 && close + 1 < to && s.charAt(close + 1) == '(') {
                    int paren = indexOf(s, close, to, ")");
                    if (paren > 0) {
                        colour(s, i, close + 1, Ink.FUNC);
                        colour(s, close + 1, paren + 1, Ink.LINK);
                        i = paren + 1;
                        continue;
                    }
                }
            }
            i++;
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '-';
    }

    private static boolean matches(CharSequence s, int at, int to, String what) {
        if (at + what.length() > to) return false;
        for (int k = 0; k < what.length(); k++) {
            if (s.charAt(at + k) != what.charAt(k)) return false;
        }
        return true;
    }

    private static int indexOf(CharSequence s, int from, int to, String what) {
        for (int i = from; i + what.length() <= to; i++) {
            if (matches(s, i, to, what)) return i;
        }
        return -1;
    }

    private static int lineEnd(CharSequence s, int from, int to) {
        int i = from;
        while (i < to && s.charAt(i) != '\n') i++;
        return i;
    }

    /** End of a quoted run, honouring backslash escapes and stopping at EOL. */
    private static int stringEnd(CharSequence s, int start, int to, char quote) {
        int i = start + 1;
        while (i < to) {
            char c = s.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == quote) return i + 1;
            // An unterminated quote must not swallow the rest of the file.
            if (c == '\n' && quote != '`') return i;
            i++;
        }
        return to;
    }
}
