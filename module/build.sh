#!/data/data/com.termux/files/usr/bin/bash
set -e
export ANDROID_DATA=/data
export ANDROID_ROOT=/system
cd "$(dirname "$0")"

rm -rf classes dex unsigned.apk signed.apk
mkdir -p classes dex assets

javac -source 8 -target 8 -encoding UTF-8 \
  -cp android.jar:XposedBridgeAPI-82.jar \
  -d classes src/main/java/xwdy/deepseek/test/*.java

jar cf app.jar -C classes .
d8 --release --lib XposedBridgeAPI-82.jar \
  --output dex app.jar

aapt package -f -M AndroidManifest.xml -S res \
  -I /system/framework/framework-res.apk \
  -F unsigned.apk

( cd dex && aapt add ../unsigned.apk classes.dex )

echo "xwdy.deepseek.test.Hook" > assets/xposed_init
aapt add unsigned.apk assets/xposed_init

if [ ! -f eta.keystore ]; then
  keytool -genkeypair -keystore eta.keystore -alias eta \
    -storepass etatata -keypass etatata -dname "CN=Eta" \
    -keyalg RSA -keysize 2048 -validity 10000
fi

apksigner sign --ks eta.keystore --ks-pass pass:etatata --key-pass pass:etatata \
  --out signed.apk unsigned.apk

apksigner verify --print-certs signed.apk | head -5
ls -la signed.apk
