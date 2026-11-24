#!/system/bin/sh
# MCTGA · 深色主题适配 + 主题常驻 · 构建脚本
# 用法：sh build.sh（工程根目录；TOOLCHAIN_PREFIX / ANDROID_JAR / XPOSED_API / KEYSTORE 可覆盖）
set -e

PROJ=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MOD="$PROJ/module"
P=${TOOLCHAIN_PREFIX:-/data/local/tmp/tc/prefix}
. "$PROJ/tools/build-common.sh"
XP=${XPOSED_API:-$PROJ/tools/XposedBridgeAPI-82.jar}
SH="$MOD/dependencies"
LIBS="$SH/api.jar:$SH/provider.jar:$SH/aidl.jar:$SH/shared.jar:$SH/commons-compress-1.21.jar:$SH/xz-1.9.jar"

OUT="$PROJ/out"
B="$OUT/build"
rm -rf "$B"
mkdir -p "$B/classes" "$B/dex" "$B/assets" "$OUT"

export LD_LIBRARY_PATH="$JAVA_HOME/lib:$JAVA_HOME/lib/server:$P/lib:${LD_LIBRARY_PATH:-}"

# javac / d8 都是“跑几秒就退出”的短进程：C1-only + 串行 GC 比默认分层编译快约 30%（同机实测），
# 产物不受影响（只是编译器自身跑得快）。长驻的服务不要用这套参数。
JFAST="-XX:TieredStopAtLevel=1 -XX:+UseSerialGC"
now() { date +%s%N; }
ms() { echo $(( ($(now) - $1) / 1000000 )); }

for F in "$AJ" "$XP" "$SH/api.jar" "$SH/provider.jar" "$SH/aidl.jar" "$SH/shared.jar" \
         "$SH/commons-compress-1.21.jar" "$SH/xz-1.9.jar" "$MOD/assets/xposed_init"; do
  [ -f "$F" ] || { echo "缺少依赖: $F"; exit 1; }
done

echo "== 工具链已校验: javac $JV / d8 $PIN_D8_MD5 =="

echo "== aapt2 =="
T0=$(now)
"$P/bin/aapt2" compile --dir "$MOD/res" -o "$B/res.zip"
"$P/bin/aapt2" link -o "$B/base.apk" -I "$AJ" --manifest "$MOD/AndroidManifest.xml" \
  "$B/res.zip" --auto-add-overlay
T_AAPT=$(ms $T0)

echo "== javac =="
T0=$(now)
find "$MOD/src" -name '*.java' > "$B/sources.txt"
"$JAVA/javac" -J-XX:TieredStopAtLevel=1 -J-XX:+UseSerialGC \
  --release 8 -encoding UTF-8 -nowarn -proc:none \
  -classpath "$AJ:$XP:$LIBS" -d "$B/classes" @"$B/sources.txt"
T_JAVAC=$(ms $T0)

echo "== d8 =="
T0=$(now)
# 依赖 jar 是固定内容，dex 一次就缓存住：只重编本工程的类，再把缓存包合并进去。
# 缓存键 = 6 个依赖 jar 的 md5 串接，依赖一换自动失效。
DEPS="$SH/api.jar $SH/provider.jar $SH/aidl.jar $SH/shared.jar $SH/commons-compress-1.21.jar $SH/xz-1.9.jar"
CACHE="$OUT/.cache"
mkdir -p "$CACHE"
# Include every input that affects dependency dex, not just dependency contents.
DEPKEY=$({ sha256sum $DEPS "$D8" "$AJ"; printf '%s\n' "min-api=$MIN_SDK release=true"; } | sha256sum | cut -d' ' -f1)
DEPSDEX="$CACHE/deps-$DEPKEY.jar"
CACHE_HIT=1
if [ ! -f "$DEPSDEX" ]; then
  CACHE_HIT=0
  echo "依赖 dex 缓存未命中（$DEPKEY），首次生成…"
  "$JAVA/java" $JFAST -cp "$D8" com.android.tools.r8.D8 \
    --release --lib "$AJ" --min-api $MIN_SDK --output "$DEPSDEX.tmp.jar" $DEPS
  mv -f "$DEPSDEX.tmp.jar" "$DEPSDEX"
  for OLD in "$CACHE"/deps-*.jar; do
    [ "$OLD" = "$DEPSDEX" ] || rm -f "$OLD"
  done
else
  echo "依赖 dex 缓存命中（$DEPKEY）"
fi
"$JAVA/java" $JFAST -cp "$D8" com.android.tools.r8.D8 \
  --release --lib "$AJ" --min-api $MIN_SDK --output "$B/dex" \
  "$DEPSDEX" $(find "$B/classes" -name '*.class')
T_D8=$(ms $T0)

echo "== 打包 =="
T0=$(now)
cp -f "$B/base.apk" "$B/unsigned.apk"
cp -f "$MOD/assets/xposed_init" "$B/assets/xposed_init"
cd "$B" && "$JAVA/jar" uf "$B/unsigned.apk" -C "$B/dex" classes.dex -C "$B" assets/xposed_init
cd "$PROJ"
echo "== 对齐 =="
mkdir -p "$B/align"
"$JAVA/javac" --release 8 -nowarn -d "$B/align" "$PROJ/tools/Align.java"
"$JAVA/java" $JFAST -cp "$B/align" Align "$B/unsigned.apk" "$B/aligned.apk"
SIGN_IN="$B/aligned.apk"

echo "== 签名 =="
"$JAVA/java" $JFAST -jar "$P/share/java/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:etatata --key-pass pass:etatata \
  --out "$OUT/MCTGA-$VERSION.apk" "$SIGN_IN"
"$JAVA/java" $JFAST -jar "$P/share/java/apksigner.jar" verify --print-certs "$OUT/MCTGA-$VERSION.apk" > "$B/certs.txt"
head -4 "$B/certs.txt"
T_SIGN=$(ms $T0)
ls -l "$OUT/MCTGA-$VERSION.apk"
T_ALL=$(( T_AAPT + T_JAVAC + T_D8 + T_SIGN ))
echo "阶段耗时: aapt2 ${T_AAPT}ms / javac ${T_JAVAC}ms / d8 ${T_D8}ms / 打包签名 ${T_SIGN}ms ≈ $(( T_ALL / 1000 ))s"

echo "== 构建记录 =="
APK="$OUT/MCTGA-$VERSION.apk"
VC=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$MOD/AndroidManifest.xml" | head -1)
JAVACV=$("$JAVA/javac" -version 2>&1)
MD5D8=$(md5sum "$D8" 2>/dev/null | cut -d' ' -f1)
MD5AJ=$(md5sum "$AJ" 2>/dev/null | cut -d' ' -f1)
APKSIZE=$(stat -c %s "$APK" 2>/dev/null || wc -c < "$APK")
APKSHA=$(sha256sum "$APK" | cut -d' ' -f1)
{
  echo "# MCTGA 构建记录（build.sh 自动生成，勿手改）"
  echo "versionName: $VERSION"
  echo "versionCode: $VC"
  echo "构建时间: $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "构建环境: $(getprop ro.product.model 2>/dev/null) / Android $(getprop ro.build.version.release 2>/dev/null) / $(uname -m)"
  echo "工具链: $P"
  case "$JAVACV" in
    "javac $PIN_JAVA_VERSION"*) echo "java 基线: $PIN_JAVA_VERSION（一致：$JAVACV）" ;;
    *) echo "java 基线: ${PIN_JAVA_VERSION:-未设置}（不一致：$JAVACV）" ;;
  esac
  echo "d8.jar md5: $MD5D8"
  echo "d8 依赖缓存: hit=$CACHE_HIT $(basename "$DEPSDEX")"
  echo "d8 路径: $D8"
  echo "阶段耗时: aapt2 ${T_AAPT}ms / javac ${T_JAVAC}ms / d8 ${T_D8}ms / 打包签名 ${T_SIGN}ms"
  echo "android.jar md5: $MD5AJ"
  echo "产物: $(basename "$APK") $APKSIZE 字节"
  echo "产物 sha256: $APKSHA"
  echo
  echo "说明: classes.dex 的字节大小随 javac / d8 / android.jar 版本变化，同一份源码在不同工具链上"
  echo "重建后大小可能不同，不能用 dex 大小判断是否同源；请比对 SHA256SUMS 里 .java 的哈希。"
} > "$OUT/BUILD.txt"
cp -f "$OUT/BUILD.txt" "$PROJ/BUILD.txt"
cat "$PROJ/BUILD.txt"
echo "完成：$APK"
