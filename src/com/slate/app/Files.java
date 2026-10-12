package com.slate.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Reading and writing, through whichever door the system opened.
 *
 * A file arrives as either a real path or a content:// URI from the Storage
 * Access Framework, and the two behave differently in every way that matters:
 * one has a length and a name, the other has to be asked, and only one can be
 * written to without a persisted permission grant. Everything here exists to
 * keep that distinction out of the viewers.
 */
public final class Files {

    private Files() {}

    /** Beyond this, a text file is opened as a preview rather than in full. */
    public static final int PREVIEW_LIMIT = 2 * 1024 * 1024;

    public static String displayName(Context ctx, Uri uri) {
        if (uri == null) return "untitled";
        if ("file".equals(uri.getScheme())) {
            String p = uri.getPath();
            return p == null ? "untitled" : new File(p).getName();
        }
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && n.length() > 0) return n;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        String p = uri.getLastPathSegment();
        return p == null ? "untitled" : p;
    }

    public static long length(Context ctx, Uri uri) {
        if (uri == null) return -1;
        if ("file".equals(uri.getScheme())) {
            String p = uri.getPath();
            return p == null ? -1 : new File(p).length();
        }
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        // No size column. Ask the descriptor, which most providers can answer.
        try {
            android.os.ParcelFileDescriptor fd =
                    ctx.getContentResolver().openFileDescriptor(uri, "r");
            if (fd != null) { long n = fd.getStatSize(); fd.close(); return n; }
        } catch (Throwable ignored) {}
        return -1;
    }

    public static InputStream open(Context ctx, Uri uri) throws Exception {
        if ("file".equals(uri.getScheme())) {
            return new FileInputStream(uri.getPath());
        }
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("provider returned no stream");
        return in;
    }

    /** Reads at most {@code max} bytes. Returns what it got. */
    public static byte[] read(Context ctx, Uri uri, int max) throws Exception {
        InputStream in = null;
        try {
            in = open(ctx, uri);
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    Math.min(max, 1 << 16));
            byte[] buf = new byte[32768];
            int total = 0, n;
            while (total < max && (n = in.read(buf, 0,
                    Math.min(buf.length, max - total))) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            return out.toByteArray();
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /** Just the first bytes, for magic-number sniffing. */
    public static byte[] head(Context ctx, Uri uri, int n) {
        try { return read(ctx, uri, n); } catch (Throwable t) { return new byte[0]; }
    }

    public static boolean write(Context ctx, Uri uri, byte[] data) {
        OutputStream out = null;
        try {
            if ("file".equals(uri.getScheme())) {
                out = new FileOutputStream(uri.getPath());
            } else {
                // "wt" truncates. Without it a shorter save leaves the tail of
                // the previous contents on the end of the file, which is a
                // genuinely destructive bug and an easy one to miss, because
                // it only shows when the new text is shorter than the old.
                ContentResolver cr = ctx.getContentResolver();
                out = cr.openOutputStream(uri, "wt");
                if (out == null) out = cr.openOutputStream(uri);
            }
            if (out == null) return false;
            out.write(data);
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
        }
    }

    /** Can this URI be written to at all? */
    public static boolean writable(Context ctx, Uri uri) {
        if (uri == null) return false;
        if ("file".equals(uri.getScheme())) {
            String p = uri.getPath();
            return p != null && new File(p).canWrite();
        }
        try {
            OutputStream o = ctx.getContentResolver().openOutputStream(uri, "wa");
            if (o != null) { o.close(); return true; }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Decodes bytes as text, detecting the common encodings.
     *
     * A BOM is believed when present. Otherwise UTF-8 is tried and only
     * rejected if it produces replacement characters, because mis-decoding
     * UTF-8 as Latin-1 silently mangles every non-ASCII character in the file
     * and the user has no way to tell it happened.
     */
    public static String decode(byte[] b, int len) {
        if (len >= 3 && (b[0] & 255) == 0xEF && (b[1] & 255) == 0xBB
                && (b[2] & 255) == 0xBF) {
            return new String(b, 3, len - 3, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (len >= 2 && (b[0] & 255) == 0xFF && (b[1] & 255) == 0xFE) {
            return new String(b, 2, len - 2, java.nio.charset.StandardCharsets.UTF_16LE);
        }
        if (len >= 2 && (b[0] & 255) == 0xFE && (b[1] & 255) == 0xFF) {
            return new String(b, 2, len - 2, java.nio.charset.StandardCharsets.UTF_16BE);
        }
        String s = new String(b, 0, len, java.nio.charset.StandardCharsets.UTF_8);
        if (s.indexOf('�') >= 0) {
            return new String(b, 0, len, java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        return s;
    }

    /** Where to put a derived file — an OCR transcript, an extracted entry. */
    public static File scratch(Context ctx, String name) {
        File dir = new File(ctx.getExternalFilesDir(null), "slate");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }
}
