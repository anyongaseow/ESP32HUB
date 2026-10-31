#!/usr/bin/env bash
# Build APK langsung dengan tool Android SDK (tanpa Gradle, tanpa library) -> APK sangat kecil.
# Pakai: bash build.sh            (APK ditandatangani dengan debug key, untuk uji coba)
#        KEYSTORE=rilis.jks KS_PASS=... KEY_ALIAS=... bash build.sh   (untuk rilis)
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
PLATFORM="${PLATFORM:-android-34}"
BT="${BUILD_TOOLS:-$(ls "$SDK/build-tools" | sort -V | tail -1)}"
JAR="$SDK/platforms/$PLATFORM/android.jar"
TOOLS="$SDK/build-tools/$BT"
AAPT2="$TOOLS/aapt2"; [ -f "$AAPT2.exe" ] && AAPT2="$AAPT2.exe"
ZIPALIGN="$TOOLS/zipalign"; [ -f "$ZIPALIGN.exe" ] && ZIPALIGN="$ZIPALIGN.exe"
SRC=app/src/main
OUT=build
APK=ESP32Hub.apk

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "1/5 resource"
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$AAPT2" link -I "$JAR" --manifest "$SRC/AndroidManifest.xml" \
    --java "$OUT/gen" -o "$OUT/base.apk" "$OUT/res.zip"

echo "2/5 javac"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn --release 8 -Xlint:-options -encoding UTF-8 \
    -classpath "$JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "3/5 d8"
python - "$OUT/classes" "$OUT/classes.jar" <<'PY'
import os, sys, zipfile
root, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, 'w') as z:
    for d, _, files in os.walk(root):
        for f in files:
            p = os.path.join(d, f)
            z.write(p, os.path.relpath(p, root).replace(os.sep, '/'))
PY
java -cp "$TOOLS/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 29 \
    --lib "$JAR" --output "$OUT/dex" "$OUT/classes.jar"

echo "4/5 paket + zipalign"
python - "$OUT/base.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'a', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    z.write(sys.argv[2], 'classes.dex')
PY
"$ZIPALIGN" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "5/5 tanda tangan"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
java -jar "$TOOLS/lib/apksigner.jar" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
    --ks-key-alias "$KEY_ALIAS" --out "$APK" "$OUT/aligned.apk"
java -jar "$TOOLS/lib/apksigner.jar" verify "$APK"

echo "Selesai: $(pwd)/$APK ($(wc -c < "$APK") byte)"
