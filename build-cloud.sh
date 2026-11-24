#!/system/bin/sh
# 云控编辑器构建：sh build-cloud.sh
set -e
PROJ=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MOD="$PROJ/cloud-editor"
P=${TOOLCHAIN_PREFIX:-/data/local/tmp/tc/prefix}
. "$PROJ/tools/build-common.sh"
OUT="$PROJ/out"
B="$OUT/cloud"
rm -rf "$B"
mkdir -p "$B/classes" "$B/dex" "$OUT"
export LD_LIBRARY_PATH="$JAVA_HOME/lib:$JAVA_HOME/lib/server:$P/lib:${LD_LIBRARY_PATH:-}"

VER=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$MOD/AndroidManifest.xml" | head -1)
[ -n "$VER" ] || VER=1.0
echo "== aapt2 link (v$VER) =="
"$P/bin/aapt2" link -o "$B/base.apk" -I "$AJ" --manifest "$MOD/AndroidManifest.xml" \
  --auto-add-overlay

echo "== javac =="
find "$MOD/src" -name '*.java' > "$B/sources.txt"
"$JAVA/javac" --release 8 -encoding UTF-8 -nowarn -proc:none \
  -classpath "$AJ" -d "$B/classes" @"$B/sources.txt"

echo "== d8 =="
"$JAVA/java" -cp "$D8" com.android.tools.r8.D8 \
  --release --lib "$AJ" --min-api "$MIN_SDK" --output "$B/dex" \
  $(find "$B/classes" -name '*.class')

echo "== 打包 =="
cp -f "$B/base.apk" "$B/unsigned.apk"
cd "$B" && "$JAVA/jar" uf "$B/unsigned.apk" -C "$B/dex" classes.dex
cd "$PROJ"

echo "== 签名 =="
"$JAVA/java" -jar "$P/share/java/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:etatata --key-pass pass:etatata \
  --out "$OUT/MCTGA-cloud-$VER.apk" "$B/unsigned.apk"
ls -l "$OUT/MCTGA-cloud-$VER.apk"
