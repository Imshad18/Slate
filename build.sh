#!/bin/bash
# ---------------------------------------------------------------------------
# Space OS :: build pipeline
# aapt -> javac -> d8 -> aapt add -> zipalign -> apksigner
# Everything runs out of $HOME; nothing is installed system-wide.
# ---------------------------------------------------------------------------
set -e

PROJ="$HOME/slate"
TC="$HOME/toolchain"
SDK="$TC/sdkroot"
BT="$SDK/usr/lib/android-sdk/build-tools/debian"

export LD_LIBRARY_PATH="$SDK/usr/lib/aarch64-linux-gnu/android:$SDK/usr/lib/7zip:$SDK/usr/lib/aarch64-linux-gnu:$SDK/usr/lib"
JAVA="$TC/jdk/bin/java"
JAVAC="$TC/jdk/bin/javac"
KEYTOOL="$TC/jdk/bin/keytool"
ANDROID_JAR="$TC/android.jar"

OUT="$PROJ/out"
GEN="$PROJ/gen"
CLASSES="$OUT/classes"
DEX="$OUT/dex"

rm -rf "$OUT" "$GEN"
mkdir -p "$OUT" "$GEN" "$CLASSES" "$DEX"

say() { printf "\n\033[36m[ %s ]\033[0m\n" "$1"; }

# --- 0. launcher icon ------------------------------------------------------
say "RENDER ICON"
mkdir -p "$OUT/icontool"
"$JAVAC" -d "$OUT/icontool" "$PROJ/tools/MakeIcon.java"
"$JAVA" -Djava.awt.headless=true -cp "$OUT/icontool" MakeIcon "$PROJ/res"

# --- 1. resources + R.java -------------------------------------------------
say "AAPT :: RESOURCES"
"$BT/aapt" package -f -m \
    -M "$PROJ/AndroidManifest.xml" \
    -S "$PROJ/res" \
    -I "$ANDROID_JAR" \
    -J "$GEN" \
    -F "$OUT/res.ap_" \
    --min-sdk-version 24 \
    --target-sdk-version 34 \
    --version-code 1 \
    --version-name 1.0.0

# --- 2. compile java -------------------------------------------------------
say "JAVAC :: SOURCES"
find "$PROJ/src" "$GEN" -name '*.java' > "$OUT/sources.txt"

# Capture javac's own exit status. Piping into grep would mask a compile
# failure behind grep's status and let a partial, broken APK ship — which is
# exactly what happened once. Fail loudly instead.
set +e
"$JAVAC" \
    -source 8 -target 8 \
    -nowarn \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$ANDROID_JAR" \
    -d "$CLASSES" \
    @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1
JAVAC_RC=$?
set -e

grep -v 'bootstrap class path' "$OUT/javac.log" || true

if [ $JAVAC_RC -ne 0 ]; then
    printf "\n\033[31m*** COMPILE FAILED — no APK produced ***\033[0m\n"
    exit $JAVAC_RC
fi

test -n "$(find "$CLASSES" -name '*.class' -print -quit)" || { echo "no classes produced"; exit 1; }

# --- 3. dex ----------------------------------------------------------------
say "D8 :: DEX"
find "$CLASSES" -name '*.class' > "$OUT/classes.txt"
"$JAVA" -cp "$TC/r8.jar" com.android.tools.r8.D8 \
    --release \
    --min-api 24 \
    --lib "$ANDROID_JAR" \
    --output "$DEX" \
    @"$OUT/classes.txt"

# --- 4. package ------------------------------------------------------------
say "PACKAGE :: APK"
cp "$OUT/res.ap_" "$OUT/slate.unaligned.apk"
( cd "$DEX" && "$BT/aapt" add -f "$OUT/slate.unaligned.apk" classes.dex >/dev/null )

# --- 5. align --------------------------------------------------------------
say "ZIPALIGN"
"$BT/zipalign" -f -p 4 "$OUT/slate.unaligned.apk" "$OUT/slate.aligned.apk"

# --- 6. sign ---------------------------------------------------------------
say "SIGN"
KS="$TC/slate.keystore"
if [ ! -f "$KS" ]; then
    "$KEYTOOL" -genkeypair -v \
        -keystore "$KS" -storepass slatekey -keypass slatekey \
        -alias slate -keyalg RSA -keysize 2048 -validity 10950 \
        -dname "CN=Slate, OU=Viewer, O=Slate, L=Local, C=IN" >/dev/null 2>&1
    echo "  new keystore minted"
fi

"$JAVA" -cp "$SDK/usr/share/java/apksigner.jar:$SDK/usr/share/java/apksig.jar" \
    com.android.apksigner.ApkSignerTool sign \
    --ks "$KS" --ks-pass pass:slatekey --key-pass pass:slatekey --ks-key-alias slate \
    --min-sdk-version 24 \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$OUT/Slate.apk" \
    "$OUT/slate.aligned.apk"

"$JAVA" -cp "$SDK/usr/share/java/apksigner.jar:$SDK/usr/share/java/apksig.jar" \
    com.android.apksigner.ApkSignerTool verify --print-certs "$OUT/Slate.apk" | head -4

say "DONE"
ls -la "$OUT/Slate.apk"
