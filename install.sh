#!/system/bin/sh
# 把构建产物装到设备上（pm install 读不了 /storage，必须先复制到 /data/local/tmp）
# 用法：sh install.sh [apk 路径]；不给路径就装 out/ 下最新的那个
set -e
PROJ=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -n "$1" ]; then
  APK=$1
else
  APK=$(ls -t "$PROJ"/out/MCTGA-*.apk 2>/dev/null | head -1)
fi
[ -n "$APK" ] && [ -f "$APK" ] || { echo "找不到 APK: ${APK:-$PROJ/out/}"; exit 1; }
TMP=/data/local/tmp/mctga-install.apk
cp -f "$APK" "$TMP"
pm install -r -t "$TMP"
rm -f "$TMP"
dumpsys package xwdy.deepseek.test | grep -E "versionCode|versionName" | head -2
