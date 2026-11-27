#!/usr/bin/env bash
# Package the APK: Java (SDL's activity from the SDK + ours) -> dex, the manifest (aapt2), the native libraries,
# zipalign (16 KB for .so files), apksigner with the debug key in android/debug.keystore. No Gradle.
#   -> out/android/apk/wwe2k15-android.apk
# Linux version of an earlier port's tools/android/build_apk.ps1.
#   needs: out/android/sdk-install, out/android/game-build/libmain.so
# VERSION_NAME / VERSION_CODE can be set in the environment.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"
VERSION_NAME="${VERSION_NAME:-0.1.0-dev}"
VERSION_CODE="${VERSION_CODE:-1}"

SDL_JAVA="$SDK_INSTALL/sdl-java"
OURS="$ROOT/android/java"
OBJ="$APK_DIR/obj"
OUT="$APK_DIR/wwe2k15-android.apk"
KEYSTORE="$ROOT/android/debug.keystore"
KEY_ALIAS="wwe2k15-debug"

need "$ANDROID_JAR" "sdkmanager \"platforms;$PLATFORM\""
need "$BUILD_TOOLS/aapt2" "sdkmanager \"build-tools;$BUILD_TOOLS_VERSION\""
need "$SDL_JAVA/org/libsdl/app/SDLActivity.java" "build_sdk.sh copies SDL's Java files there"
need "$KEYSTORE" "the debug key"
LIBMAIN="$(find "$GAME_BUILD" -name libmain.so | head -1)"
[ -n "$LIBMAIN" ] || { echo "no libmain.so: run tools/android/build_game.sh first" >&2; exit 2; }
LIBCXX="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
need "$LIBCXX" "the NDK's libc++_shared.so"
LIBRUNTIME="$(find "$SDK_INSTALL" -name librexruntime.so | head -1)"
LIBGPU="$(find "$SDK_INSTALL" -name librexgpu-xenos.so | head -1)"
[ -n "$LIBRUNTIME" ] && [ -n "$LIBGPU" ] || { echo "SDK libraries missing in $SDK_INSTALL" >&2; exit 2; }

rm -rf "$OBJ"
mkdir -p "$OBJ/classes" "$OBJ/dex" "$OBJ/zip/lib/arm64-v8a"

# 1. Java -> classes -> dex
find "$SDL_JAVA" "$OURS" -name '*.java' > "$OBJ/sources.txt"
javac --release 11 -encoding UTF-8 -nowarn -classpath "$ANDROID_JAR" -d "$OBJ/classes" "@$OBJ/sources.txt"
jar cf "$OBJ/classes.jar" -C "$OBJ/classes" .
"$BUILD_TOOLS/d8" --release --min-api "$MIN_API" --lib "$ANDROID_JAR" --output "$OBJ/dex" "$OBJ/classes.jar"

# 2. Manifest + the app icon -> base APK (the screens themselves are built in code)
mkdir -p "$OBJ/res"
"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/android/res" -o "$OBJ/res/res.zip"
"$BUILD_TOOLS/aapt2" link "$OBJ/res/res.zip" --manifest "$ROOT/android/AndroidManifest.xml" -I "$ANDROID_JAR" \
  --min-sdk-version "$MIN_API" --target-sdk-version "$TARGET_API" \
  --replace-version --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" -o "$OBJ/base.apk"

# 3. classes.dex + the native libraries (debug info stripped)
cp "$OBJ/dex/classes.dex" "$OBJ/zip/classes.dex"
strip_lib() { "$LLVM_BIN/llvm-strip" --strip-debug -o "$OBJ/zip/lib/arm64-v8a/$(basename "$1")" "$1"; }
strip_lib "$LIBCXX"; strip_lib "$LIBRUNTIME"; strip_lib "$LIBGPU"; strip_lib "$LIBMAIN"
# libadrenotools' hook libraries (built with the game): needed to load a custom Vulkan driver
for h in libhook_impl.so libmain_hook.so; do
  H="$(find "$GAME_BUILD" -name "$h" | head -1)"
  [ -n "$H" ] || { echo "$h was not built" >&2; exit 2; }
  strip_lib "$H"
done
[ -d "$ROOT/android/assets" ] && cp -r "$ROOT/android/assets" "$OBJ/zip/assets"  # the launcher's background picture
( cd "$OBJ/zip" && zip -q -r -X "$OBJ/base.apk" classes.dex lib $( [ -d assets ] && echo assets ) )

# 4. Align (16 KB for .so files) and sign
"$BUILD_TOOLS/zipalign" -f -P 16 4 "$OBJ/base.apk" "$OBJ/aligned.apk"
rm -f "$OUT"
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --ks-key-alias "$KEY_ALIAS" \
  --out "$OUT" "$OBJ/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --print-certs "$OUT" | head -3
ls -l "$OUT"
unzip -l "$OUT" | grep -E "\.so|classes.dex"
