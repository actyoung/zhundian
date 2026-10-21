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
javac --release 8 -cp "$android_jar" -d "$build_dir/classes" \
  "$build_dir/generated/com/zhundian/clock/R.java" "${sources[@]}"
classes=()
while IFS= read -r class; do classes+=("$class"); done < <(rg --files "$build_dir/classes" -g '*.class')
"$tools_dir/d8" --min-api 26 --lib "$android_jar" --output "$build_dir/dex" "${classes[@]}"
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
