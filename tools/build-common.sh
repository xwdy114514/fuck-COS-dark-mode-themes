# Shared build preflight. Source after PROJ, P and MOD are set.
. "$PROJ/tools/toolchain.env"
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -x "${JAVA_HOME_DEFAULT:-/nonexistent}/bin/javac" ]; then
    JAVA_HOME=$JAVA_HOME_DEFAULT
  else
    JAVA_HOME="$P/lib/jvm/java-21-openjdk"
  fi
fi
JAVA="$JAVA_HOME/bin"
AJ=${ANDROID_JAR:-/data/local/tmp/sdk/android-35/android.jar}
KS=${KEYSTORE:-$PROJ/tools/eta.keystore}
D8=${D8_JAR:-$P/share/java/d8.jar}
if [ -z "${D8_JAR:-}" ] && [ -f /data/local/tmp/tc/d8-9.4.34.jar ]; then
  D8=/data/local/tmp/tc/d8-9.4.34.jar
fi
export LD_LIBRARY_PATH="$JAVA_HOME/lib:$JAVA_HOME/lib/server:$P/lib:${LD_LIBRARY_PATH:-}"
[ -s "$KS" ] || { echo '缺少原签名密钥，拒绝构建；不会自动生成新证书。'; exit 1; }
[ -x "$JAVA/javac" ] || { echo "缺少 javac: $JAVA"; exit 1; }
JV=$("$JAVA/javac" -version 2>&1 | sed -n 's/^javac //p')
case "$JV" in
  "$PIN_JAVA_VERSION"|"$PIN_JAVA_VERSION".*) ;;
  *) echo "Java 基线不符: $JV != $PIN_JAVA_VERSION"; exit 1 ;;
esac
for SPEC in "${PIN_D8_MD5}:$D8" "${PIN_AJ_MD5}:$AJ"; do
  EXPECT=${SPEC%%:*}; FILE=${SPEC#*:}
  [ -f "$FILE" ] || { echo "缺少依赖: $FILE"; exit 1; }
  ACTUAL=$(md5sum "$FILE" | cut -d' ' -f1)
  [ "$ACTUAL" = "$EXPECT" ] || { echo "工具链基线不符: $FILE ($ACTUAL != $EXPECT)"; exit 1; }
done
VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$MOD/AndroidManifest.xml" | head -1)
VC=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$MOD/AndroidManifest.xml" | head -1)
MIN_SDK=$(sed -n 's/.*android:minSdkVersion="\([0-9]*\)".*/\1/p' "$MOD/AndroidManifest.xml" | head -1)
[ -n "$VERSION" ] && [ -n "$VC" ] && [ -n "$MIN_SDK" ] || { echo 'manifest 缺少版本或 minSdk'; exit 1; }
JFAST="-XX:TieredStopAtLevel=1 -XX:+UseSerialGC"
