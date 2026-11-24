#!/system/bin/sh
# Install the main module only. Never select an editor by timestamp.
set -e
PROJ=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MOD="$PROJ/module"
P=${TOOLCHAIN_PREFIX:-/data/local/tmp/tc/prefix}
. "$PROJ/tools/build-common.sh"
APK=${1:-$PROJ/out/MCTGA-$VERSION.apk}
[ -f "$APK" ] || { echo "找不到 APK: $APK"; exit 1; }
TMP=$(mktemp -d /data/local/tmp/mctga-install.XXXXXX)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
cp "$APK" "$TMP/main.apk"
"$P/bin/aapt2" dump badging "$TMP/main.apk" > "$TMP/badging"
grep -q "^package: name='xwdy.deepseek.test' " "$TMP/badging" || { echo '拒绝安装：不是 MCTGA 主包'; exit 1; }
if [ -z "${1:-}" ]; then
  grep -q "versionCode='$VC'" "$TMP/badging" || { echo 'APK 与 manifest 版本不符'; exit 1; }
fi
"$JAVA/java" -jar "$P/share/java/apksigner.jar" verify --print-certs "$TMP/main.apk" > "$TMP/new-certs"
OLD=$(pm path xwdy.deepseek.test | sed -n 's/^package://p' | head -1)
if [ -n "$OLD" ]; then
  "$JAVA/java" -jar "$P/share/java/apksigner.jar" verify --print-certs "$OLD" > "$TMP/old-certs"
  grep 'certificate SHA-256 digest:' "$TMP/new-certs" > "$TMP/new-digest"
  grep 'certificate SHA-256 digest:' "$TMP/old-certs" > "$TMP/old-digest"
  cmp -s "$TMP/new-digest" "$TMP/old-digest" || { echo '签名与装机版不同，拒绝安装'; exit 1; }
fi
pm install -r -t "$TMP/main.apk"
dumpsys package xwdy.deepseek.test > "$TMP/installed"
grep -E 'versionCode=|versionName=' "$TMP/installed" | head -2
