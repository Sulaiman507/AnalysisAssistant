#!/bin/bash
# التحقق المحلي من مشروع مساعد التحليل (المرحلة ٢) بدون Gradle.
#
# لماذا بدون Gradle؟ في هذه الحاوية تحديداً، الـ Gradle daemon يبدأ ثم يموت
# فوراً (فشل IPC: Connection reset by peer عند أول إرسال؛ ولا سجلات daemon)،
# رغم أن loopback نفسه يعمل (مُتحقق بـ curl ضد خادم وهمي). البناء الرسمي
# يبقى Gradle (يعمل في CI كما في ArDroid)، وهذا السكربت يتحقق محلياً بنفس
# أدوات المرحلة ٠: kotlinc + aapt2 + d8 + apksigner.
#
# الاستخدام: ./tools/verify-local.sh
# المخرجات: mcp.jar + نتائج الاختبارات + APK debug موقّع في build/manual/.
set -euo pipefail

export JAVA_HOME=~/tools/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
KOTLINC=~/tools/kotlinc/bin/kotlinc
STDLIB=~/tools/kotlinc/lib/kotlin-stdlib.jar
BT=~/tools/android-sdk/build-tools/36.1/android-16
ANDROID_JAR=~/tools/android-sdk/platforms/android-36/android.jar

ROOT=~/workspace/analysis-assistant
cd "$ROOT"
L="$ROOT/build-libs"
WORK="$ROOT/build/manual"
rm -rf "$WORK"
mkdir -p "$WORK/mcp-classes" "$WORK/test-classes" "$WORK/app-classes" "$WORK/dex" "$WORK/generated"

GSON="$L/gson-2.11.0.jar"
JUNIT="$L/junit-4.13.2.jar"
HAMCREST="$L/hamcrest-core-1.3.jar"
CORE="$L/core-extract/classes.jar"
CORE_KTX="$L/core-ktx-extract/classes.jar"
ANNOT="$L/annotation-1.8.2.jar"

for f in "$GSON" "$JUNIT" "$HAMCREST" "$CORE" "$CORE_KTX" "$ANNOT" "$ANDROID_JAR" "$STDLIB"; do
  [ -f "$f" ] || { echo "MISSING: $f"; exit 1; }
done

echo "=== 1) فحص: كل أسماء أدوات MT في الكود موجودة في القائمة الحية ==="
# تاريخ الفحص: في المرحلة ١ كان العميل يجلب tools/list فقط، فكان أي اسم
# مكتوب مسبقاً مرفوضاً. بعد وصول القائمة الحية من الجهاز (67 أداة، MT MCP
# 0.2.0، جُلبت 2026-11-14)، صار اختبار البوابة يستدعي أسماء حقيقية منها.
# القاعدة الجديدة: أي اسم أداة يظهر في الكود يجب أن يكون موجوداً في القائمة
# الحية — وإلا فهو اسم مخترع ويفشل الفحص.
LIVE_LIST=~/workspace/user/files/tools_list_0_r5fz.json
[ -f "$LIVE_LIST" ] || { echo "MISSING live tools list: $LIVE_LIST"; exit 1; }
USED=$(grep -rhoE "mt_[a-z_]+" mcp/src/main app/src/main | sort -u || true)
FAIL=0
for name in $USED; do
  if ! python3 -c "import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if sys.argv[2] in [t['name'] for t in d['tools']] else 1)" "$LIVE_LIST" "$name"; then
    echo "ERROR: $name مستخدم في الكود لكنه غير موجود في القائمة الحية"
    FAIL=1
  fi
done
[ "$FAIL" = 1 ] && exit 1
echo "OK: all MT tool names used in code exist in the live list"

echo "=== 2) تجميع وحدة MCP ==="
"$KOTLINC" -jvm-target 17 -cp "$GSON" -d "$WORK/mcp-classes" mcp/src/main/java/com/musaed/analysis/mcp/*.kt
(cd "$WORK/mcp-classes" && jar cf "$WORK/mcp.jar" .)
echo "OK: $WORK/mcp.jar"

echo "=== 3) اختبارات وحدة MCP ==="
"$KOTLINC" -jvm-target 17 -Xfriend-paths="$WORK/mcp.jar" \
  -cp "$GSON:$WORK/mcp.jar:$JUNIT:$HAMCREST" \
  -d "$WORK/test-classes" mcp/src/test/java/com/musaed/analysis/mcp/*.kt
java -cp "$WORK/test-classes:$WORK/mcp.jar:$GSON:$JUNIT:$HAMCREST:$STDLIB" \
  org.junit.runner.JUnitCore com.musaed.analysis.mcp.McpClientTest com.musaed.analysis.mcp.GateTesterTest com.musaed.analysis.mcp.ToolRunnerTest com.musaed.analysis.mcp.ContextBuilderTest com.musaed.analysis.mcp.SessionLogTest com.musaed.analysis.mcp.StressTestTest com.musaed.analysis.mcp.QuickScanTest com.musaed.analysis.mcp.ModelClientTest com.musaed.analysis.mcp.ProviderSettingsTest com.musaed.analysis.mcp.AnalysisLoopTest com.musaed.analysis.mcp.ToolCatalogTest com.musaed.analysis.mcp.McpResultsTest com.musaed.analysis.mcp.ChatSessionTest com.musaed.analysis.mcp.ChatAgentTest com.musaed.analysis.mcp.ChatPromptTest

echo "=== 4) فحص العميل ضد خادم وهمي (JSON + SSE) — متخطى في هذه الحاوية ==="
echo "SKIP: الـ sandbox يمنع اتصالات TCP المباشرة من عمليات JVM في هذه الحاوية"
echo "      (رسالة الحظر الحرفية كما أظهرها HttpClient: Other TCP connections is"
echo "      turned off for this assistant... switch other_tcp from Deny to Ask)."
echo "      نفس السبب يمنع Gradle daemon IPC هنا. التبادل الحي يُتحقق منه على"
echo "      الهاتف (بوابة قبول المرحلة ١)؛ منطق التحليل مغطى باختبارات الخطوة ٣."
echo "      الخادم الوهمي tools/mock_mcp_server.py يبقى متاحاً لآلة تطوير عادية."

echo "=== 5) تجميع التطبيق (Kotlin + R) ==="
sed -e 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.musaed.analysis">|' \
    -e 's|\${applicationId}|com.musaed.analysis|g' \
    app/src/main/AndroidManifest.xml > "$WORK/AndroidManifest.xml"
# ملاحظة: في البناء اليدوي، package في الـ manifest هو نفسه حزمة R وحزمة
# التثبيت (com.musaed.analysis) — لاحقة .debug الخاصة بـ Gradle غير مستخدمة هنا.
"$BT/aapt2" compile --dir app/src/main/res -o "$WORK/res.zip"
"$BT/aapt2" link -o "$WORK/unsigned.apk" -I "$ANDROID_JAR" \
  --manifest "$WORK/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 36 \
  --version-code 12 --version-name 0.1.11-analysis-limit \
  --java "$WORK/generated" \
  "$WORK/res.zip"
"$KOTLINC" -jvm-target 17 \
  -cp "$ANDROID_JAR:$CORE:$CORE_KTX:$ANNOT:$GSON:$WORK/mcp.jar" \
  -d "$WORK/app-classes" \
  app/src/main/java/com/musaed/analysis/MainActivity.kt \
  "$WORK/generated/com/musaed/analysis/R.java"
(cd "$WORK/app-classes" && jar cf "$WORK/app-classes.jar" .)
echo "OK: app classes compiled"

echo "=== 6) DEX + توقيع APK ==="
"$BT/d8" --release --output "$WORK/dex" \
  --lib "$ANDROID_JAR" --lib "$ANNOT" \
  "$WORK/app-classes.jar" "$CORE" "$CORE_KTX" "$GSON" "$WORK/mcp.jar" "$STDLIB"
[ -f "$WORK/dex/classes.dex" ] || { echo "ERROR: d8 produced no classes.dex"; exit 1; }
(cd "$WORK" && zip -q -j unsigned.apk dex/classes.dex)
"$BT/zipalign" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
# مفتاح التوقيع التجريبي ثابت في جذر المشروع — لا يُحذف أبداً.
# (كان يعيش داخل build/manual فكان يُمسح مع كل تشغيل للسكربت، فيتولد مفتاح
# جديد كل مرة ويرفض أندرويد تثبيت النسخة الجديدة فوق القديمة لاختلاف التوقيع.
# هذا مفتاح debug فقط وليس مفتاح نشر.)
KEYSTORE="$ROOT/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi
java -jar "$BT/lib/apksigner.jar" sign --ks "$KEYSTORE" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$WORK/app-debug.apk" "$WORK/aligned.apk"
java -jar "$BT/lib/apksigner.jar" verify --verbose "$WORK/app-debug.apk"
"$BT/aapt2" dump badging "$WORK/app-debug.apk" | grep -E "package:|launchable-activity"
ls -la "$WORK/app-debug.apk"
echo "ALL VERIFIED"
