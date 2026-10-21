#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
sdk_root="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
tools_dir="$sdk_root/build-tools/37.0.0"
android_jar="$sdk_root/platforms/android-37.0/android.jar"
build_dir="$project_dir/app/build/local"

for tool in aapt2 d8 zipalign apksigner; do
  test -x "$tools_dir/$tool" || { echo "Missing $tools_dir/$tool" >&2; exit 1; }
done
test -f "$android_jar" || { echo "Missing $android_jar" >&2; exit 1; }
mkdir -p "$build_dir/compiled" "$build_dir/generated" "$build_dir/classes" "$build_dir/dex" "$project_dir/dist"

"$tools_dir/aapt2" compile --dir "$project_dir/app/src/main/res" -o "$build_dir/compiled"
"$tools_dir/aapt2" link \
  -o "$build_dir/unsigned.apk" \
  --manifest "$project_dir/app/src/main/AndroidManifest.xml" \
  -I "$android_jar" \
  --java "$build_dir/generated" \
  --min-sdk-version 26 \
  --target-sdk-version 36 \
  -A "$project_dir/app/src/main/assets" \
  "$build_dir/compiled"/*.flat

sources=()
while IFS= read -r source; do sources+=("$source"); done < <(rg --files "$project_dir/app/src/main/java" -g '*.java')
kotlin_sources=()
while IFS= read -r source; do kotlin_sources+=("$source"); done < <(rg --files "$project_dir/app/src/main/java" -g '*.kt')
gradle_lib=""
for candidate in "$HOME"/.gradle/wrapper/dists/gradle-7.4.2-bin/*/gradle-7.4.2/lib; do
  if [ -f "$candidate/kotlin-compiler-embeddable-1.5.31-patched-for-gradle-7.4.2.jar" ]; then
    gradle_lib="$candidate"
    break
  fi
done
test -n "$gradle_lib" || { echo "Missing cached Kotlin compiler from Gradle 7.4.2" >&2; exit 1; }
stdlib="$gradle_lib/kotlin-stdlib-1.5.31.jar"
stdlib_jdk7="$gradle_lib/kotlin-stdlib-jdk7-1.5.31.jar"
stdlib_jdk8="$gradle_lib/kotlin-stdlib-jdk8-1.5.31.jar"
java -cp "$gradle_lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 1.8 \
  -classpath "$android_jar:$stdlib:$stdlib_jdk7:$stdlib_jdk8" \
  -d "$build_dir/classes" "${kotlin_sources[@]}" "${sources[@]}" \
  "$build_dir/generated/com/zhundian/clock/R.java"
javac --release 8 -cp "$android_jar:$build_dir/classes:$stdlib" -d "$build_dir/classes" \
  "$build_dir/generated/com/zhundian/clock/R.java" "${sources[@]}"
classes=()
while IFS= read -r class; do classes+=("$class"); done < <(rg --files "$build_dir/classes" -g '*.class')
"$tools_dir/d8" --min-api 26 --lib "$android_jar" --output "$build_dir/dex" \
  "${classes[@]}" "$stdlib" "$stdlib_jdk7" "$stdlib_jdk8"
cp "$build_dir/unsigned.apk" "$build_dir/with-dex.apk"
(cd "$build_dir/dex" && zip -q -j "$build_dir/with-dex.apk" classes.dex)
"$tools_dir/zipalign" -f 4 "$build_dir/with-dex.apk" "$build_dir/aligned.apk"

keystore="$build_dir/debug.keystore"
if [ ! -f "$keystore" ]; then
  keytool -genkeypair -keystore "$keystore" -storepass android -keypass android \
    -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
apk="$project_dir/dist/zhundian-debug.apk"
"$tools_dir/apksigner" sign --ks "$keystore" --ks-pass pass:android \
  --key-pass pass:android --out "$apk" "$build_dir/aligned.apk"
"$tools_dir/apksigner" verify --verbose "$apk"
echo "$apk"
