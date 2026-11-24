#!/system/bin/sh
# Isolated JVM tests; minimal Android view substitutes are not a device UI test.
set -e
PROJ=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
P=${TOOLCHAIN_PREFIX:-/data/local/tmp/tc/prefix}
JAVA_HOME=${JAVA_HOME:-$P/lib/jvm/java-21-openjdk}
J="$JAVA_HOME/bin"
export LD_LIBRARY_PATH="$JAVA_HOME/lib:$JAVA_HOME/lib/server:$P/lib:${LD_LIBRARY_PATH:-}"
AJ=${ANDROID_JAR:-/data/local/tmp/sdk/android-35/android.jar}
B="$PROJ/out/tests"
mkdir -p "$B/main" "$B/test"
CP="$AJ:$PROJ/tools/XposedBridgeAPI-82.jar"
for f in "$PROJ"/module/dependencies/*.jar; do CP="$CP:$f"; done
"$J/javac" --release 8 -nowarn -encoding UTF-8 -cp "$CP" -d "$B/main" "$PROJ"/module/src/main/java/xwdy/deepseek/test/*.java
"$J/javac" --release 8 -nowarn -encoding UTF-8 -cp "$B/main:$AJ" -d "$B/test" "$PROJ"/tests/stubs/android/view/*.java "$PROJ"/tests/stubs/android/widget/*.java "$PROJ/tests/Regression.java" "$PROJ/tests/AppliedThemeRegression.java"
"$J/java" -cp "$B/test:$B/main:$AJ" xwdy.deepseek.test.Regression
"$J/java" -Djava.io.tmpdir="$B" -cp "$B/test:$B/main:$AJ" xwdy.deepseek.test.AppliedThemeRegression
