package com.slate.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The app.
 *
 * The single rule that shapes this class: the window is on screen before the
 * file is touched. Chrome is built synchronously in onCreate from the URI
 * alone — name, extension, a guess at the kind — and the bytes are read on a
 * worker. An app that opens a file is judged almost entirely on the gap
 * between the tap and something appearing, and reading before drawing puts
 * the whole of that gap in front of the user.
 */
public class Slate extends Activity {

    private Uri uri;
    private Doc doc;
    private byte[] raw;
    private String text;

    private FrameLayout root, content;
    private LinearLayout bar, actions;
    private TextView title, subtitle, status;

    private CodeView code;
    private PageView page;
    private SheetView sheet;
    private HexView hex;
    private ScrollView markdownScroll;
    private TextView markdown;
    private ScrollView listScroll;
    private LinearLayout listBox;

    private boolean renderedMarkdown = true;
    private boolean dirty;

    /**
     * True when the buffer came from somewhere that cannot be written back —
     * OCR output, text pulled out of a docx. The editor stays live, but SAVE
     * always creates a new file, because writing plain text over the
     * original PDF or Word document would destroy it.
     */
    private boolean savesToNewFile;

    /** The PDF being edited, in full, once edit mode is entered. */
    private byte[] pdfBytes;
    private boolean pdfEdited;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private static AndroidGlyphs glyphs;      // templates are expensive; share them

    // =====================================================================

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ink.init(this);
        Ink.applyTheme(Prefs.dark(this));

        buildChrome();

        uri = resolveUri(getIntent());
        if (uri == null) { showWelcome(); return; }

        // Everything cheap and synchronous: a name and a guess. The viewer
        // that appears is chosen from this, before any IO has happened.
        String name = Files.displayName(this, uri);
        long size = Files.length(this, uri);
        doc = Doc.fromName(name, size);
        title.setText(name);
        subtitle.setText(doc.kindName() + (size >= 0 ? "  ·  " + Doc.size(size) : ""));
        status.setText("opening…");
        rebuildActions();

        load();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        Uri u = resolveUri(i);
        if (u == null) return;
        uri = u;
        String name = Files.displayName(this, uri);
        doc = Doc.fromName(name, Files.length(this, uri));
        title.setText(name);
        dirty = false;
        load();
    }

    private Uri resolveUri(Intent i) {
        if (i == null) return null;
        Uri u = i.getData();
        if (u != null) return u;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            Object extra = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (extra instanceof Uri) return (Uri) extra;
        }
        return null;
    }

    // =====================================================================
    // CHROME
    // =====================================================================

    private void buildChrome() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ink.BG);

        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Ink.PANEL);
        bar.setPadding((int) Ink.dp(14), (int) Ink.dp(10),
                (int) Ink.dp(14), (int) Ink.dp(8));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);

        title = new TextView(this);
        title.setTextColor(Ink.TEXT);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        names.addView(title);

        subtitle = new TextView(this);
        subtitle.setTextColor(Ink.TEXT_DIM);
        subtitle.setTextSize(10);
        subtitle.setSingleLine(true);
        names.addView(subtitle);

        titleRow.addView(names, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        status = new TextView(this);
        status.setTextColor(Ink.ACCENT);
        status.setTextSize(10);
        status.setPadding((int) Ink.dp(8), 0, 0, 0);
        titleRow.addView(status);

        bar.addView(titleRow);

        HorizontalScrollView actionScroll = new HorizontalScrollView(this);
        actionScroll.setHorizontalScrollBarEnabled(false);
        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actionScroll.addView(actions);
        bar.addView(actionScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.TOP;
        root.addView(bar, blp);

        // Keep the content clear of the bar whenever the bar changes size.
        //
        // A one-shot post() was not enough: the action row is rebuilt after
        // the file loads, and a long filename wraps to two lines, so the bar
        // grows AFTER the margin was measured. The content then started
        // underneath it and the first heading of every document was sliced
        // in half. Watching for layout changes is the only version of this
        // that cannot go stale.
        bar.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                int h = b - t;
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) content.getLayoutParams();
                if (lp.topMargin != h) {
                    lp.topMargin = h;
                    content.setLayoutParams(lp);
                }
            }
        });

        setContentView(root);
    }

    private TextView button(String label, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(11);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Ink.ACCENT);
        t.setPadding((int) Ink.dp(12), (int) Ink.dp(8),
                (int) Ink.dp(12), (int) Ink.dp(8));
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return t;
    }

    /**
     * The action bar.
     *
     * At most four buttons are ever shown inline and everything else goes
     * behind MORE. The previous version put every action in a scrolling row,
     * which looked like a bar with its last item sliced in half — a control
     * you cannot see is a control that does not exist, and a horizontal
     * scroller full of them reads as broken rather than as scrollable.
     */
    private void rebuildActions() {
        actions.removeAllViews();
        if (doc == null) return;

        final List<String> extra = new ArrayList<String>();
        final List<Runnable> extraRun = new ArrayList<Runnable>();
        int inline = 0;

        // ---- the one or two things this file type is actually for --------
        if (doc.isEditable() && doc.writable) {
            actions.addView(button("SAVE", new Runnable() {
                @Override public void run() { save(); }
            }));
            inline++;
        }
        if (doc.kind == Doc.MARKDOWN) {
            actions.addView(button(renderedMarkdown ? "SOURCE" : "RENDER",
                    new Runnable() {
                @Override public void run() { toggleMarkdown(); }
            }));
            inline++;
        }
        if (doc.kind == Doc.PDF) {
            boolean editing = page != null && page.tool() != PageView.TOOL_NONE;
            actions.addView(button(editing ? "DONE" : "EDIT", new Runnable() {
                @Override public void run() { togglePdfEdit(); }
            }));
            inline++;
            if (pdfEdited) {
                actions.addView(button("SAVE", new Runnable() {
                    @Override public void run() { savePdf(); }
                }));
                inline++;
            }
        }
        if (doc.kind == Doc.IMAGE) {
            actions.addView(button("EXTRACT TEXT", new Runnable() {
                @Override public void run() { runOcr(); }
            }));
            inline++;
        }
        if (doc.kind == Doc.OFFICE && text != null) {
            actions.addView(button("EDIT AS TEXT", new Runnable() {
                @Override public void run() { editExtractedText(); }
            }));
            inline++;
        }
        if (doc.isTextual()) {
            actions.addView(button("FIND", new Runnable() {
                @Override public void run() { find(); }
            }));
            inline++;
        }

        // ---- everything else -----------------------------------------------
        if (doc.isTextual()) {
            extra.add(code != null && code.wrapping() ? "Word wrap  ·  on"
                                                      : "Word wrap  ·  off");
            extraRun.add(new Runnable() { @Override public void run() {
                if (code == null) return;
                code.setWrap(!code.wrapping());
                Prefs.setWrap(Slate.this, code.wrapping());
                show();
            } });

            extra.add(code != null && code.lineNumbers() ? "Line numbers  ·  on"
                                                         : "Line numbers  ·  off");
            extraRun.add(new Runnable() { @Override public void run() {
                if (code == null) return;
                code.setLineNumbers(!code.lineNumbers());
                Prefs.setLineNumbers(Slate.this, code.lineNumbers());
            } });

            extra.add("Bigger text");
            extraRun.add(new Runnable() { @Override public void run() { bumpFont(1); } });
            extra.add("Smaller text");
            extraRun.add(new Runnable() { @Override public void run() { bumpFont(-1); } });
        }
        if (doc.isEditable() && !doc.writable) {
            extra.add("Save a copy…");
            extraRun.add(new Runnable() { @Override public void run() { save(); } });
        }
        if (doc.kind == Doc.OFFICE && text != null) {
            extra.add("Export as .txt");
            extraRun.add(new Runnable() { @Override public void run() {
                pendingSave = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                saveAsNamed(doc.name + ".txt");
            } });
        }
        if (doc.kind == Doc.PDF) {
            extra.add("Extract all text");
            extraRun.add(new Runnable() { @Override public void run() {
                extractPdfText();
            } });
            extra.add("Go to page…");
            extraRun.add(new Runnable() { @Override public void run() { gotoPage(); } });
        }

        extra.add(Prefs.dark(this) ? "Light theme" : "Dark theme");
        extraRun.add(new Runnable() { @Override public void run() { flipTheme(); } });
        extra.add("Share");
        extraRun.add(new Runnable() { @Override public void run() { share(); } });
        extra.add("Open with another app");
        extraRun.add(new Runnable() { @Override public void run() { openWith(); } });
        extra.add("Make Slate the default viewer");
        extraRun.add(new Runnable() { @Override public void run() { defaultAppHelp(); } });
        extra.add("Open a different file…");
        extraRun.add(new Runnable() { @Override public void run() { pickFile(); } });
        extra.add("File details");
        extraRun.add(new Runnable() { @Override public void run() { info(); } });

        actions.addView(button("MORE  ▾", new Runnable() {
            @Override public void run() {
                final String[] items = extra.toArray(new String[0]);
                new AlertDialog.Builder(Slate.this)
                        .setItems(items,
                                new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    android.content.DialogInterface d, int which) {
                                extraRun.get(which).run();
                            }
                        })
                        .show();
            }
        }));
    }

    /**
     * Puts extracted document text into the editor.
     *
     * The original is left untouched — a docx cannot be written back from
     * plain text without destroying its formatting, so this edits a copy and
     * saving goes through Save-a-copy. Pretending otherwise would lose the
     * user's document.
     */
    private void editExtractedText() {
        if (text == null) return;
        Doc d = Doc.fromName(doc.name + ".txt", text.length());
        d.writable = true;
        savesToNewFile = true;
        doc = d;
        raw = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        showText();
        rebuildActions();
        subtitle.setText("extracted text  ·  edits save to a new file");
    }

    // =====================================================================
    // LOADING
    // =====================================================================

    private void load() {
        savesToNewFile = false;
        content.removeAllViews();
        final Uri u = uri;
        new Thread(new Runnable() {
            @Override public void run() {
                byte[] head = Files.head(Slate.this, u, 1024);
                doc.refine(head, head.length);
                doc.writable = Files.writable(Slate.this, u);

                // A picture and a PDF are never read into a byte array: the
                // decoder and PdfRenderer both want the stream or the
                // descriptor, and holding a 40 MB photo as bytes AND as a
                // bitmap is how an image viewer runs out of memory.
                byte[] bytes = null;
                if (doc.kind != Doc.PDF && doc.kind != Doc.IMAGE) {
                    try {
                        int cap = doc.isTextual() ? Files.PREVIEW_LIMIT
                                                  : 8 * 1024 * 1024;
                        bytes = Files.read(Slate.this, u, cap);
                    } catch (Throwable t) {
                        bytes = null;
                    }
                }
                final byte[] data = bytes;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!u.equals(uri)) return;     // superseded
                        raw = data;
                        show();
                    }
                });
            }
        }, "slate-load").start();
    }

    private void show() {
        // Each call rebuilds the viewers, so release what the previous set
        // was holding. A PdfRenderer and its file descriptor are not
        // reclaimed by garbage collection, and a few theme flips would leak
        // enough descriptors to make the next open fail.
        if (page != null) { page.closePdf(); page = null; }
        sheet = null; code = null; hex = null; markdown = null;

        rebuildActions();
        status.setText("");
        subtitle.setText(doc.kindName()
                + (doc.size >= 0 ? "  ·  " + Doc.size(doc.size) : "")
                + (doc.writable ? "" : "  ·  read-only"));

        switch (doc.kind) {
            case Doc.PDF:      showPdf(); break;
            case Doc.IMAGE:    showImage(); break;
            case Doc.SHEET:    showSheet(); break;
            case Doc.OFFICE:   showOffice(); break;
            case Doc.ARCHIVE:  showArchive(); break;
            case Doc.MARKDOWN: showMarkdown(); break;
            case Doc.BINARY:   showHex(); break;
            default:           showText(); break;
        }
    }

    private void fill(View v) {
        content.removeAllViews();
        content.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---- text and code -----------------------------------------------------

    /**
     * The text editor, with its line numbers beside it.
     *
     * Laid out as a vertical scroller holding a row: the gutter on the left,
     * then the text. When wrapping is off the text goes inside a horizontal
     * scroller of its own, so it can be wider than the screen while the
     * numbers stay put.
     */
    private void showText() {
        if (raw == null) { fail("could not read the file"); return; }
        text = Files.decode(raw, raw.length);

        code = new CodeView(this);
        code.setLanguage(doc.lang);
        code.setFontSize(Prefs.fontSize(this));
        code.setWrap(Prefs.wrap(this));
        code.setText(text);
        code.setOnDirty(new CodeView.OnDirty() {
            @Override public void onDirty(boolean d) {
                dirty = d;
                status.setText(d ? "edited" : "");
            }
        });

        boolean previewOnly = doc.size > Files.PREVIEW_LIMIT;
        if (!doc.writable || previewOnly) code.setKeyListener(null);

        final Gutter gutter = new Gutter(this);
        gutter.attach(code);
        boolean showNumbers = Prefs.lineNumbers(this);
        code.setLineNumbers(showNumbers);
        gutter.setVisibility(showNumbers ? View.VISIBLE : View.GONE);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(gutter, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        if (code.wrapping()) {
            row.addView(code, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        } else {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setFillViewport(true);
            hs.addView(code, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(hs, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        fill(sv);

        // The gutter has to be remeasured whenever the text re-lays out, or
        // it keeps the width it guessed before the file was in it.
        code.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int o3, int o4) {
                gutter.requestLayout();
                gutter.invalidate();
            }
        });
        code.post(new Runnable() {
            @Override public void run() {
                code.highlightVisible(true);
                gutter.requestLayout();
            }
        });

        if (previewOnly) {
            toast("showing the first " + Doc.size(Files.PREVIEW_LIMIT)
                    + " — editing is off so a save cannot truncate the file");
            doc.writable = false;
        }
    }

    // ---- markdown -----------------------------------------------------------

    private void showMarkdown() {
        if (raw == null) { fail("could not read the file"); return; }
        text = Files.decode(raw, raw.length);
        if (renderedMarkdown) {
            markdown = new TextView(this);
            markdown.setTextColor(Ink.TEXT);
            markdown.setTextSize(14);
            markdown.setLineSpacing(Ink.dp(3), 1.15f);
            markdown.setPadding((int) Ink.dp(18), (int) Ink.dp(14),
                    (int) Ink.dp(18), (int) Ink.dp(80));
            markdown.setTextIsSelectable(true);
            markdown.setText(Markdown.render(text));
            markdownScroll = new ScrollView(this);
            markdownScroll.addView(markdown);
            fill(markdownScroll);
        } else {
            showText();
        }
    }

    private void toggleMarkdown() {
        renderedMarkdown = !renderedMarkdown;
        showMarkdown();
        rebuildActions();
    }

    // ---- pdf -----------------------------------------------------------------

    private void showPdf() {
        page = new PageView(this);
        fill(page);
        try {
            ParcelFileDescriptor fd = "file".equals(uri.getScheme())
                    ? ParcelFileDescriptor.open(new java.io.File(uri.getPath()),
                            ParcelFileDescriptor.MODE_READ_ONLY)
                    : getContentResolver().openFileDescriptor(uri, "r");
            if (fd == null || !page.openPdf(fd)) {
                fail("this PDF could not be opened");
                return;
            }
            page.setOnPage(new PageView.OnPage() {
                @Override public void onPage(int i, int n) {
                    if (page != null && page.tool() != PageView.TOOL_NONE) {
                        loadRunsForPage();
                    } else {
                        subtitle.setText("PDF  ·  page " + (i + 1) + " of " + n);
                    }
                }
            });
        } catch (Throwable t) {
            fail("this PDF could not be opened");
        }
    }


    private void gotoPage() {
        if (page == null) return;
        final EditText in = new EditText(this);
        in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        in.setHint("1 - " + page.pageCount());
        new AlertDialog.Builder(this)
                .setTitle("Go to page")
                .setView(in)
                .setPositiveButton("GO", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            page.goToPage(Integer.parseInt(in.getText().toString()) - 1);
                        } catch (Throwable ignored) {}
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    // ---- image ----------------------------------------------------------------

    private void showImage() {
        page = new PageView(this);
        fill(page);
        final Uri u = uri;
        new Thread(new Runnable() {
            @Override public void run() {
                final Bitmap bm = decodeScaled(u);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (bm == null) { fail("could not decode this image"); return; }
                        page.setBitmap(bm);
                        subtitle.setText("IMAGE  ·  " + bm.getWidth() + " × "
                                + bm.getHeight() + "  ·  " + Doc.size(doc.size));
                    }
                });
            }
        }, "slate-image").start();
    }

    /**
     * Decodes an image at a size that fits in memory.
     *
     * A modern phone photo is 50 megapixels, which is 200 MB as ARGB_8888 —
     * several times the heap an app is given. Measuring first and asking the
     * decoder to subsample is the difference between showing the picture and
     * being killed trying.
     */
    private Bitmap decodeScaled(Uri u) {
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            java.io.InputStream in = Files.open(this, u);
            BitmapFactory.decodeStream(in, null, probe);
            in.close();

            int target = Math.max(1080, Math.max(
                    getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels)) * 2;
            int sample = 1;
            while (probe.outWidth / (sample * 2) >= target
                    || probe.outHeight / (sample * 2) >= target) sample *= 2;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            java.io.InputStream in2 = Files.open(this, u);
            Bitmap bm = BitmapFactory.decodeStream(in2, null, o);
            in2.close();
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- sheet -----------------------------------------------------------------

    private void showSheet() {
        if (raw == null) { fail("could not read the file"); return; }
        text = Files.decode(raw, raw.length);
        char delim = SheetView.sniffDelimiter(text);
        sheet = new SheetView(this);
        sheet.setRows(SheetView.parseCsv(text, delim));
        sheet.setEditable(doc.writable);
        sheet.setOnEdit(new SheetView.OnEdit() {
            @Override public void onEdit(int r, int c, String cur) { editCell(r, c, cur); }
        });
        fill(sheet);
        int rows = sheet.rows().size();
        subtitle.setText("SHEET  ·  " + rows + " rows  ·  delimiter '"
                + (delim == '\t' ? "tab" : String.valueOf(delim)) + "'"
                + (doc.writable ? "  ·  tap a cell twice to edit" : ""));
    }

    private void editCell(final int r, final int c, String cur) {
        final EditText in = new EditText(this);
        in.setText(cur);
        in.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(SheetView.columnName(c) + (r + 1))
                .setView(in)
                .setPositiveButton("SET", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        sheet.setCell(r, c, in.getText().toString());
                        dirty = true;
                        status.setText("edited");
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    // ---- office -------------------------------------------------------------------

    private void showOffice() {
        if (raw == null) { fail("could not read the file"); return; }
        status.setText("extracting…");
        final byte[] data = raw;
        final String ext = doc.ext;
        new Thread(new Runnable() {
            @Override public void run() {
                final Office.Content c = Office.read(data, ext);
                ui.post(new Runnable() {
                    @Override public void run() {
                        status.setText("");
                        if (!c.sheets.isEmpty()) {
                            sheet = new SheetView(Slate.this);
                            sheet.setRows(c.sheets.get(0).rows);
                            sheet.setEditable(false);
                            fill(sheet);
                            subtitle.setText("SPREADSHEET  ·  " + c.sheets.size()
                                    + " sheet(s)  ·  read-only");
                            return;
                        }
                        if (c.text.length() > 0) {
                            TextView tv = new TextView(Slate.this);
                            tv.setTextColor(Ink.TEXT);
                            tv.setTextSize(14);
                            tv.setLineSpacing(Ink.dp(3), 1.15f);
                            tv.setPadding((int) Ink.dp(18), (int) Ink.dp(14),
                                    (int) Ink.dp(18), (int) Ink.dp(80));
                            tv.setTextIsSelectable(true);
                            tv.setText(c.text);
                            ScrollView sv = new ScrollView(Slate.this);
                            sv.addView(tv);
                            fill(sv);
                            text = c.text;
                            subtitle.setText("DOCUMENT  ·  "
                                    + c.text.length() + " characters"
                                    + (c.note.isEmpty() ? "" : "  ·  " + c.note));
                            return;
                        }
                        fail(c.note.isEmpty() ? "nothing readable in this file" : c.note);
                    }
                });
            }
        }, "slate-office").start();
    }

    // ---- archive -------------------------------------------------------------------

    private void showArchive() {
        status.setText("reading…");
        final Uri u = uri;
        new Thread(new Runnable() {
            @Override public void run() {
                List<String[]> entries;
                try {
                    entries = Office.list(Files.open(Slate.this, u));
                } catch (Throwable t) {
                    entries = new ArrayList<String[]>();
                }
                final List<String[]> list = entries;
                ui.post(new Runnable() {
                    @Override public void run() {
                        status.setText("");
                        buildArchiveList(list);
                    }
                });
            }
        }, "slate-zip").start();
    }

    private void buildArchiveList(List<String[]> entries) {
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, 0, 0, (int) Ink.dp(80));

        subtitle.setText("ARCHIVE  ·  " + entries.size() + " entries");
        for (final String[] e : entries) {
            if ("dir".equals(e[2])) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding((int) Ink.dp(16), (int) Ink.dp(11),
                    (int) Ink.dp(16), (int) Ink.dp(11));
            row.setClickable(true);

            TextView n = new TextView(this);
            n.setText(e[0]);
            n.setTextColor(Ink.TEXT);
            n.setTextSize(12);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(n, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView s = new TextView(this);
            s.setText(e[1]);
            s.setTextColor(Ink.TEXT_DIM);
            s.setTextSize(10);
            row.addView(s);

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openEntry(e[0]); }
            });
            listBox.addView(row);

            View rule = new View(this);
            rule.setBackgroundColor(Ink.LINE);
            listBox.addView(rule, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        listScroll = new ScrollView(this);
        listScroll.addView(listBox);
        fill(listScroll);
    }

    /**
     * Opens one file from inside an archive.
     *
     * Extracted to the app's own storage and reopened as a fresh document,
     * rather than decoded in place. That keeps every viewer working on a real
     * URI and means an entry can itself be a zip without any special case.
     */
    private void openEntry(final String name) {
        status.setText("extracting…");
        final Uri u = uri;
        new Thread(new Runnable() {
            @Override public void run() {
                byte[] b = null;
                try { b = Office.entry(Files.open(Slate.this, u), name); }
                catch (Throwable ignored) {}
                final byte[] data = b;
                ui.post(new Runnable() {
                    @Override public void run() {
                        status.setText("");
                        if (data == null) { toast("could not extract that entry"); return; }
                        try {
                            String base = name.substring(name.lastIndexOf('/') + 1);
                            java.io.File f = Files.scratch(Slate.this, base);
                            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                            o.write(data);
                            o.close();
                            Intent i = new Intent(Slate.this, Slate.class);
                            i.setData(Uri.fromFile(f));
                            startActivity(i);
                        } catch (Throwable t) {
                            toast("could not open that entry");
                        }
                    }
                });
            }
        }, "slate-entry").start();
    }

    // ---- hex -----------------------------------------------------------------------

    private void showHex() {
        hex = new HexView(this);
        hex.setData(raw, doc.size);
        fill(hex);
        subtitle.setText("BINARY  ·  " + Doc.size(doc.size) + "  ·  hex view");
    }

    private void fail(String why) {
        TextView t = new TextView(this);
        t.setText(why);
        t.setTextColor(Ink.TEXT_DIM);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) Ink.dp(30), 0, (int) Ink.dp(30), 0);
        fill(t);
        status.setText("");
    }

    private void showWelcome() {
        title.setText("Slate");
        subtitle.setText("open any file");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        TextView t = new TextView(this);
        t.setText("Pick a file, or open one from any app\nand choose Slate.");
        t.setTextColor(Ink.TEXT_DIM);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        TextView pick = button("CHOOSE A FILE", new Runnable() {
            @Override public void run() { pickFile(); }
        });
        pick.setTextSize(14);
        pick.setPadding((int) Ink.dp(26), (int) Ink.dp(16),
                (int) Ink.dp(26), (int) Ink.dp(16));
        box.addView(pick);

        TextView def = button("SET AS DEFAULT VIEWER", new Runnable() {
            @Override public void run() { defaultAppHelp(); }
        });
        box.addView(def);

        TextView th = button("THEME", new Runnable() {
            @Override public void run() { flipTheme(); }
        });
        box.addView(th);

        fill(box);
        actions.removeAllViews();
    }

    private void pickFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, 1);
        } catch (Throwable t) {
            toast("no file picker available");
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 2 && res == RESULT_OK && data != null && data.getData() != null
                && pendingSave != null) {
            if (Files.write(this, data.getData(), pendingSave)) {
                toast("saved a copy");
                dirty = false;
                if (code != null) code.markClean();
                status.setText("");
            } else {
                toast("could not write that file");
            }
            pendingSave = null;
            return;
        }
        if (req == 1 && res == RESULT_OK && data != null && data.getData() != null) {
            Intent i = new Intent(this, Slate.class);
            i.setData(data.getData());
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivity(i);
        }
    }

    // =====================================================================
    // ACTIONS
    // =====================================================================

    private void save() {
        if (uri == null) return;
        byte[] out;
        if (sheet != null && doc.kind == Doc.SHEET) {
            out = sheet.toCsv().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } else if (code != null) {
            out = code.getText().toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } else {
            toast("nothing to save");
            return;
        }
        if (savesToNewFile || !doc.writable) { saveAs(out); return; }
        if (Files.write(this, uri, out)) {
            dirty = false;
            if (code != null) code.markClean();
            status.setText("saved");
            ui.postDelayed(new Runnable() {
                @Override public void run() { status.setText(""); }
            }, 1600);
        } else {
            saveAs(out);
        }
    }

    private byte[] pendingSave;

    /** Falls back to a new file when the original cannot be written. */
    private void saveAs(byte[] data) {
        pendingSave = data;
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TITLE, doc == null ? "untitled.txt" : doc.name);
            startActivityForResult(i, 2);
        } catch (Throwable t) {
            toast("read-only, and no way to save a copy");
        }
    }

    /** Opens the create-document picker for an already-staged payload. */
    private void saveAsNamed(String name) {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(i, 2);
        } catch (Throwable t) {
            toast("no file picker available");
        }
    }

    // =====================================================================
    // PDF EDITING
    // =====================================================================

    /** Enters or leaves edit mode on a PDF. */
    private void togglePdfEdit() {
        if (page == null) return;
        if (page.tool() != PageView.TOOL_NONE) {
            page.setTool(PageView.TOOL_NONE);
            if (toolRow != null) { content.removeView(toolRow); toolRow = null; }
            rebuildActions();
            subtitle.setText("PDF  ·  page " + (page.pageIndex() + 1)
                    + " of " + page.pageCount());
            return;
        }
        status.setText("reading…");
        new Thread(new Runnable() {
            @Override public void run() {
                final byte[] bytes = pdfBytes != null ? pdfBytes : readWhole();
                ui.post(new Runnable() {
                    @Override public void run() {
                        status.setText("");
                        if (bytes == null) { toast("could not read this PDF"); return; }
                        pdfBytes = bytes;
                        enterPdfEdit();
                    }
                });
            }
        }, "slate-pdfedit").start();
    }

    private byte[] readWhole() {
        try { return Files.read(this, uri, 64 * 1024 * 1024); }
        catch (Throwable t) { return null; }
    }

    private void enterPdfEdit() {
        page.setTool(PageView.TOOL_TEXT);
        page.setOnEditRun(new PageView.OnEditRun() {
            @Override public void onEditRun(Pdf.Run r) { editRun(r); }
        });
        page.setOnTapPoint(new PageView.OnTapPoint() {
            @Override public void onTapPoint(int p, float x, float y) { addTextAt(p, x, y); }
        });
        page.setOnRegion(new PageView.OnRegion() {
            @Override public void onRegion(int p, float x, float y, float w, float h) {
                coverRegion(p, x, y, w, h);
            }
        });
        loadRunsForPage();
        rebuildActions();
        showToolPicker();
    }

    private void loadRunsForPage() {
        if (pdfBytes == null || page == null) return;
        int idx = page.pageIndex();
        java.util.List<Pdf.Run> rs = Pdf.runs(pdfBytes, idx);
        float[] sz = Pdf.pageSize(pdfBytes, idx);
        page.setRuns(rs, idx, sz[0], sz[1]);
        if (rs.isEmpty()) {
            subtitle.setText("EDITING  ·  no text layer — use ADD TEXT or COVER");
        } else {
            subtitle.setText("EDITING  ·  " + rs.size() + " editable lines on page "
                    + (idx + 1));
        }
    }

    private LinearLayout toolRow;

    /** The three tools, pinned under the page. */
    private void showToolPicker() {
        if (toolRow != null) content.removeView(toolRow);
        toolRow = new LinearLayout(this);
        toolRow.setOrientation(LinearLayout.HORIZONTAL);
        toolRow.setGravity(Gravity.CENTER);
        toolRow.setBackgroundColor(Ink.PANEL);
        toolRow.addView(button("EDIT TEXT", new Runnable() {
            @Override public void run() {
                page.setTool(PageView.TOOL_TEXT);
                loadRunsForPage();
            }
        }));
        toolRow.addView(button("ADD TEXT", new Runnable() {
            @Override public void run() { page.setTool(PageView.TOOL_ADD); }
        }));
        toolRow.addView(button("COVER", new Runnable() {
            @Override public void run() { page.setTool(PageView.TOOL_COVER); }
        }));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        content.addView(toolRow, lp);
    }

    /** Rewrites one line of the PDF. */
    private void editRun(final Pdf.Run run) {
        final EditText in = new EditText(this);
        in.setText(run.text);
        in.setSelectAllOnFocus(true);
        in.setTextColor(Ink.TEXT);
        new AlertDialog.Builder(this)
                .setTitle("Edit text")
                .setView(in)
                .setPositiveButton("REPLACE",
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        applyEdit(run, in.getText().toString());
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void applyEdit(Pdf.Run run, String newText) {
        // Warn BEFORE writing. A subset font carries only the glyphs the
        // document already used, so a character it has never drawn simply
        // will not appear — silently, leaving a gap the user cannot explain.
        String missing = Pdf.missingGlyphs(run, newText);
        if (missing.length() > 0) {
            toast("this font has no glyph for: " + missing + "  — dropped");
        }
        byte[] out = Pdf.replaceText(pdfBytes, run, newText);
        if (out == null) { toast("could not rewrite that text"); return; }
        pdfBytes = out;
        pdfEdited = true;
        reloadEditedPdf();
    }

    private void addTextAt(final int pageIdx, final float x, final float y) {
        final EditText in = new EditText(this);
        in.setHint("text to add");
        in.setTextColor(Ink.TEXT);
        new AlertDialog.Builder(this)
                .setTitle("Add text")
                .setView(in)
                .setPositiveButton("ADD",
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String t = in.getText().toString();
                        if (t.isEmpty()) return;
                        byte[] out = Pdf.addText(pdfBytes, pageIdx,
                                x, y, 11f, t, 0x000000);
                        if (out == null) { toast("could not add that text"); return; }
                        pdfBytes = out;
                        pdfEdited = true;
                        reloadEditedPdf();
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void coverRegion(int pageIdx, float x, float y, float w, float h) {
        byte[] out = Pdf.cover(pdfBytes, pageIdx, x, y, w, h, 0xFFFFFF);
        if (out == null) { toast("could not cover that area"); return; }
        pdfBytes = out;
        pdfEdited = true;
        reloadEditedPdf();
    }

    /**
     * Re-opens the edited bytes so the change shows immediately.
     *
     * Through a scratch file, because PdfRenderer needs a seekable file
     * descriptor and there is no way to hand it a byte array.
     */
    private void reloadEditedPdf() {
        try {
            java.io.File f = Files.scratch(this, "editing.pdf");
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            o.write(pdfBytes);
            o.close();

            int keepPage = page.pageIndex();
            int keepTool = page.tool();
            page.closePdf();
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(f,
                    ParcelFileDescriptor.MODE_READ_ONLY);
            if (!page.openPdf(fd)) {
                toast("that edit produced a file the renderer rejected");
                return;
            }
            page.goToPage(keepPage);
            page.setTool(keepTool);
            loadRunsForPage();
            rebuildActions();
            status.setText("edited");
        } catch (Throwable t) {
            toast("could not reload the edited file");
        }
    }

    private void savePdf() {
        if (pdfBytes == null) return;
        if (doc.writable && Files.write(this, uri, pdfBytes)) {
            pdfEdited = false;
            status.setText("saved");
            rebuildActions();
            return;
        }
        pendingSave = pdfBytes;
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/pdf");
            i.putExtra(Intent.EXTRA_TITLE, doc.name);
            startActivityForResult(i, 2);
        } catch (Throwable t) {
            toast("no file picker available");
        }
    }

    /**
     * Pulls the text out of a PDF, preferring the real thing.
     *
     * A PDF made by a word processor already contains its text as characters.
     * Running OCR over a picture of that is throwing away a perfect copy to
     * guess at a blurry one, so the text layer is read first and OCR is kept
     * for what it is actually for: a scan, where no text layer exists.
     */
    private void extractPdfText() {
        status.setText("reading…");
        new Thread(new Runnable() {
            @Override public void run() {
                final byte[] bytes = pdfBytes != null ? pdfBytes : readWhole();
                final Pdf.Text t = bytes == null ? null : Pdf.extract(bytes);
                ui.post(new Runnable() {
                    @Override public void run() {
                        status.setText("");
                        if (bytes != null) pdfBytes = bytes;
                        if (t != null && t.hasTextLayer) {
                            openAsText(t.text, doc.name + ".txt");
                            return;
                        }
                        new AlertDialog.Builder(Slate.this)
                                .setTitle("No text layer")
                                .setMessage("This PDF holds pictures of text rather "
                                        + "than text, so it has to be read by "
                                        + "recognition. That is slower and not "
                                        + "perfect.")
                                .setPositiveButton("RUN OCR",
                                        new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(
                                            android.content.DialogInterface d, int w) {
                                        runOcr();
                                    }
                                })
                                .setNegativeButton("CANCEL", null)
                                .show();
                    }
                });
            }
        }, "slate-pdftext").start();
    }

    private void runOcr() {
        final Bitmap bm = page == null ? null : page.currentBitmap();
        if (bm == null) { toast("nothing to scan"); return; }
        status.setText("scanning…");

        new Thread(new Runnable() {
            @Override public void run() {
                if (glyphs == null) {
                    glyphs = new AndroidGlyphs(Typeface.create(Typeface.SANS_SERIF,
                            Typeface.NORMAL));
                    glyphs.warm();
                }
                // Cap the work. Beyond a few megapixels the extra detail adds
                // nothing to recognition and a great deal to the wait.
                Bitmap src = bm;
                long px = (long) bm.getWidth() * bm.getHeight();
                if (px > Ocr.MAX_PIXELS) {
                    double k = Math.sqrt(Ocr.MAX_PIXELS / (double) px);
                    src = Bitmap.createScaledBitmap(bm,
                            Math.max(1, (int) (bm.getWidth() * k)),
                            Math.max(1, (int) (bm.getHeight() * k)), true);
                }
                int w = src.getWidth(), h = src.getHeight();
                int[] argb = new int[w * h];
                src.getPixels(argb, 0, w, 0, 0, w, h);
                if (src != bm) src.recycle();

                final Ocr.Result r = Ocr.read(argb, w, h, glyphs);
                ui.post(new Runnable() {
                    @Override public void run() {
                        status.setText("");
                        showOcrResult(r);
                    }
                });
            }
        }, "slate-ocr").start();
    }

    private void showOcrResult(final Ocr.Result r) {
        if (r.text.trim().isEmpty()) {
            toast("no text found");
            return;
        }
        final EditText box = new EditText(this);
        box.setText(r.text);
        box.setTextSize(12);
        box.setTypeface(Typeface.MONOSPACE);
        box.setTextColor(Ink.TEXT);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("OCR  ·  " + r.words + " words  ·  "
                        + Math.round(r.confidence * 100) + "% confidence")
                .setView(sv)
                .setPositiveButton("COPY", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager)
                                        getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "ocr", box.getText().toString()));
                        toast("copied");
                    }
                })
                .setNeutralButton("SAVE .txt", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        pendingSave = box.getText().toString()
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        try {
                            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("text/plain");
                            i.putExtra(Intent.EXTRA_TITLE,
                                    (doc == null ? "scan" : doc.name) + ".txt");
                            startActivityForResult(i, 2);
                        } catch (Throwable ignored) {}
                    }
                })
                .setNegativeButton("EDIT",
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        openAsText(box.getText().toString(),
                                (doc == null ? "scan" : doc.name) + ".txt");
                    }
                })
                .show();
    }

    /** Replaces the view with an editable buffer over some extracted text. */
    private void openAsText(String body, String name) {
        Doc d = Doc.fromName(name, body.length());
        d.writable = true;          // typeable
        savesToNewFile = true;      // but never over the original
        doc = d;
        text = body;
        raw = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        showText();
        rebuildActions();
        subtitle.setText("extracted text  ·  edits save to a new file");
    }

    private void find() {
        if (code == null) return;
        final EditText in = new EditText(this);
        in.setHint("find");
        new AlertDialog.Builder(this)
                .setTitle("Find")
                .setView(in)
                .setPositiveButton("NEXT", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        findNext(in.getText().toString());
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private String lastQuery = "";
    private int lastFound = -1;

    private void findNext(String q) {
        if (code == null || q == null || q.isEmpty()) return;
        String hay = code.getText().toString().toLowerCase(java.util.Locale.US);
        String needle = q.toLowerCase(java.util.Locale.US);
        int from = q.equals(lastQuery) ? lastFound + 1 : 0;
        int at = hay.indexOf(needle, Math.max(0, from));
        if (at < 0) at = hay.indexOf(needle);          // wrap
        if (at < 0) { toast("not found"); return; }
        lastQuery = q;
        lastFound = at;
        code.requestFocus();
        code.setSelection(at, at + q.length());
    }

    private void bumpFont(int d) {
        float s = Prefs.fontSize(this) + d;
        Prefs.setFontSize(this, s);
        if (code != null) code.setFontSize(s);
        if (markdown != null) markdown.setTextSize(s + 1);
    }

    private void flipTheme() {
        Prefs.setDark(this, !Prefs.dark(this));
        Ink.applyTheme(Prefs.dark(this));
        // Rebuild rather than recolour: every view holds colours it was given
        // at construction, and chasing them all is how a theme toggle ends up
        // half-applied.
        root.setBackgroundColor(Ink.BG);
        bar.setBackgroundColor(Ink.PANEL);
        title.setTextColor(Ink.TEXT);
        subtitle.setTextColor(Ink.TEXT_DIM);
        status.setTextColor(Ink.ACCENT);
        rebuildActions();
        if (doc != null) show();
    }

    private void share() {
        if (uri == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share"));
        } catch (Throwable t) {
            toast("cannot share this file");
        }
    }

    private void openWith() {
        if (uri == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Open with"));
        } catch (Throwable t) {
            toast("nothing else can open this");
        }
    }

    /**
     * Guides the user to making Slate the default.
     *
     * Android has no API for an app to make itself the default handler for a
     * file type — deliberately, or any app could hijack every document on the
     * device. The only route is the system settings screen, so the honest
     * thing is to say that and open the right page rather than pretending
     * there is a switch here.
     */
    private void defaultAppHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Set as default viewer")
                .setMessage("Android does not let an app make itself the default — "
                        + "that has to come from you, in Settings.\n\n"
                        + "Open by default → Add link / Set as default, "
                        + "or pick Slate and tap ALWAYS the next time a file "
                        + "asks which app to use.")
                .setPositiveButton("OPEN SETTINGS",
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        openAppSettings();
                    }
                })
                .setNegativeButton("CLOSE", null)
                .show();
    }

    private void openAppSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (Throwable ignored) { toast("cannot open settings"); }
        }
    }

    private void info() {
        StringBuilder sb = new StringBuilder();
        sb.append("Name\n").append(doc == null ? "?" : doc.name).append("\n\n");
        sb.append("Type\n").append(doc == null ? "?" : doc.kindName())
          .append(doc != null && doc.ext.length() > 0 ? "  (." + doc.ext + ")" : "")
          .append("\n\n");
        sb.append("Size\n").append(doc == null ? "?" : Doc.size(doc.size)).append("\n\n");
        sb.append("Writable\n").append(doc != null && doc.writable ? "yes" : "no")
          .append("\n\n");
        if (text != null) {
            int lines = 1;
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
            sb.append("Lines\n").append(lines).append("\n\n");
            sb.append("Characters\n").append(text.length()).append("\n\n");
        }
        sb.append("Location\n").append(uri == null ? "?" : uri.toString());

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(11);
        tv.setTextColor(Ink.TEXT);
        tv.setPadding((int) Ink.dp(22), (int) Ink.dp(16),
                (int) Ink.dp(22), (int) Ink.dp(10));
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("File").setView(sv)
                .setPositiveButton("CLOSE", null).show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // =====================================================================

    @Override public void onBackPressed() {
        if (dirty) {
            new AlertDialog.Builder(this)
                    .setTitle("Unsaved changes")
                    .setMessage("Save before closing?")
                    .setPositiveButton("SAVE",
                            new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            save();
                            finish();
                        }
                    })
                    .setNegativeButton("DISCARD",
                            new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            finish();
                        }
                    })
                    .setNeutralButton("CANCEL", null)
                    .show();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (page != null) page.closePdf();
    }

    @Override protected void onPause() {
        super.onPause();
        if (code != null) {
            Prefs.setWrap(this, code.wrapping());
            Prefs.setLineNumbers(this, code.lineNumbers());
        }
    }
}
