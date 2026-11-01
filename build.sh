#!/system/bin/sh
# MCTGA 0.5.4-beta · 深色主题适配 + 主题常驻 融合版构建脚本
# 用法：sh build.sh（在工程根目录；可用 TOOLCHAIN_PREFIX / ANDROID_JAR / XPOSED_API / KEYSTORE 覆盖）
set -e

PROJ=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MOD="$PROJ/module"
P=${TOOLCHAIN_PREFIX:-/data/local/tmp/tc/prefix}
JAVA_HOME=${JAVA_HOME:-$P/lib/jvm/java-21-openjdk}
JAVA="$JAVA_HOME/bin"
AJ=${ANDROID_JAR:-/data/local/tmp/sdk/android-35/android.jar}
XP=${XPOSED_API:-/data/local/tmp/cosnight/XposedBridgeAPI-82.jar}
[ -f "$XP" ] || XP="$PROJ/tools/XposedBridgeAPI-82.jar"
KS=${KEYSTORE:-/data/local/tmp/cosnight/eta.keystore}
[ -f "$KS" ] || KS="$PROJ/tools/eta.keystore"
SH="$MOD/dependencies"
LIBS="$SH/api.jar:$SH/provider.jar:$SH/aidl.jar:$SH/shared.jar"
VERSION=0.5.4-beta

OUT="$PROJ/out"
B="$OUT/build"
rm -rf "$B"
mkdir -p "$B/classes" "$B/dex" "$B/assets" "$OUT"

export LD_LIBRARY_PATH="$JAVA_HOME/lib:$JAVA_HOME/lib/server:$P/lib:${LD_LIBRARY_PATH:-}"

for F in "$AJ" "$XP" "$SH/api.jar" "$SH/provider.jar" "$SH/aidl.jar" "$SH/shared.jar" \
         "$MOD/assets/bg_day.png" "$MOD/assets/bg_night.png"; do
  [ -f "$F" ] || { echo "缺少依赖: $F"; exit 1; }
done

echo "== aapt2 =="
"$P/bin/aapt2" compile --dir "$MOD/res" -o "$B/res.zip"
"$P/bin/aapt2" link -o "$B/base.apk" -I "$AJ" --manifest "$MOD/AndroidManifest.xml" \
  "$B/res.zip" --min-sdk-version 29 --target-sdk-version 35 --auto-add-overlay

echo "== javac =="
find "$MOD/src" -name '*.java' > "$B/sources.txt"
"$JAVA/javac" --release 8 -encoding UTF-8 -nowarn -proc:none \
  -classpath "$AJ:$XP:$LIBS" -d "$B/classes" @"$B/sources.txt"

echo "== d8 =="
"$JAVA/java" -cp "$P/share/java/d8.jar" com.android.tools.r8.D8 \
  --release --lib "$AJ" --min-api 26 --output "$B/dex" \
  "$SH/api.jar" "$SH/provider.jar" "$SH/aidl.jar" "$SH/shared.jar" \
  $(find "$B/classes" -name '*.class')
ls -l "$B/dex"

echo "== 打包 =="
cp -f "$B/base.apk" "$B/unsigned.apk"
cp -f "$MOD/assets/bg_day.png" "$MOD/assets/bg_night.png" "$B/"
printf 'xwdy.deepseek.test.Hook\n' > "$B/assets/xposed_init"
cd "$B" && "$JAVA/jar" uf "$B/unsigned.apk" -C "$B/dex" classes.dex
"$JAVA/jar" uf "$B/unsigned.apk" -C "$B" bg_day.png -C "$B" bg_night.png -C "$B" assets/xposed_init
cd "$PROJ"

echo "== 签名 =="
if [ ! -f "$KS" ]; then
  "$JAVA/keytool" -genkeypair -keystore "$KS" -alias eta -storepass etatata -keypass etatata \
    -dname "CN=Eta" -keyalg RSA -keysize 2048 -validity 10000
fi
"$JAVA/java" -jar "$P/share/java/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:etatata --key-pass pass:etatata \
  --out "$OUT/MCTGA-$VERSION.apk" "$B/unsigned.apk"
"$JAVA/java" -jar "$P/share/java/apksigner.jar" verify --print-certs "$OUT/MCTGA-$VERSION.apk" | head -4
ls -l "$OUT/MCTGA-$VERSION.apk"
echo "完成：$OUT/MCTGA-$VERSION.apk"
