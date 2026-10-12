# Slate

A file viewer and editor for Android that opens anything: PDF, Markdown, Word,
spreadsheets, source code in sixteen languages, images, archives, and whatever
else by falling back to text or hex.

- **PDF editing.** Tap a line on the page and change it. Add text anywhere,
  cover anything. Written as an incremental update, so the original bytes are
  never touched.
- **Real text extraction.** A PDF made by a word processor already contains its
  text; Slate reads it rather than running OCR over a picture of it.
- **OCR** for actual scans — pure Java, no native code.
- Syntax highlighting, word wrap, find, and editing with save for every text
  format.

Draws its own views and parses its own formats. No third-party libraries.

## Build

```sh
./build.sh
```

`aapt → javac → d8 → zipalign → apksigner`, driven by one shell script. No
Gradle, no NDK. The APK lands in `out/`.

## Requirements

Android 8.0 (API 26) or newer.

---

**DESIGNED BY MOHAMMAD SHADAB, CODED WITH CLAUDE**
