#!/system/bin/sh
# 把构建产物装到设备上（pm install 读不了 /storage，必须先复制到 /data/local/tmp）
set -e
PROJ=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APK=${1:-$PROJ/out/MCTGA-0.5.4-beta.apk}
[ -f "$APK" ] || { echo "找不到 APK: $APK"; exit 1; }
TMP=/data/local/tmp/mctga-install.apk
cp -f "$APK" "$TMP"
pm install -r -t "$TMP"
rm -f "$TMP"
dumpsys package xwdy.deepseek.test | grep -E "versionCode|versionName" | head -2
