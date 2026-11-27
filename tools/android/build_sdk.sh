#!/usr/bin/env bash
# Build + install the ReXGlue SDK for Android arm64 (upstream commit + an earlier port's patches).
#   tools/android/build_sdk.sh            -> out/android/sdk-install  (librexruntime.so, librexgpu-xenos.so, headers, cmake files)
# Takes a long time the first time (about 820 source files); build output is cached by ccache between runs.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"
need "$NDK_TOOLCHAIN_FILE" "install the NDK: sdkmanager \"ndk;$NDK_VERSION\""

mkdir -p "$WORK"
if [ ! -d "$SDK_SRC/.git" ]; then
  git clone https://github.com/rexglue/rexglue-sdk "$SDK_SRC"
fi
git -C "$SDK_SRC" reset -q --hard
git -C "$SDK_SRC" clean -qfdx
git -C "$SDK_SRC" checkout -q "$SDK_COMMIT"

# The patches first, then the submodules: the patches only touch the SDK's own files.
for p in "$ROOT"/patches/rexglue-sdk/full/*.diff; do
  git -C "$SDK_SRC" apply --whitespace=nowarn "$p" || { echo "patch failed: $p" >&2; exit 1; }
done
echo "applied $(ls "$ROOT"/patches/rexglue-sdk/full/*.diff | wc -l) patches"
git -C "$SDK_SRC" submodule update --init --recursive --depth 1


cmake -S "$SDK_SRC" -B "$SDK_BUILD" "${CMAKE_ANDROID_ARGS[@]}" \
  "-DCMAKE_INSTALL_PREFIX=$SDK_INSTALL" \
  -DREXGLUE_ENABLE_TRACY=OFF -DREXGLUE_ENABLE_FIDELITYFX=OFF -DREXGLUE_BUILD_TESTS=OFF \
  -DCMAKE_EXPORT_NO_PACKAGE_REGISTRY=ON -DCMAKE_FIND_USE_PACKAGE_REGISTRY=OFF \
  "${CCACHE_ARGS[@]}"

cmake --build "$SDK_BUILD" --target install -j "$JOBS"

for so in librexruntime.so librexgpu-xenos.so; do
  f="$(find "$SDK_INSTALL" -name "$so" | head -1)"
  [ -n "$f" ] && ls -l "$f" || { echo "$so is not in the install folder" >&2; exit 1; }
done
need "$SDK_INSTALL/lib/cmake/rexglue/rexglueConfig.cmake" "the SDK's CMake package file"

# SDL's Java activity (zlib licence) ships in the SDK's source tree; the APK needs it. Kept with the install, which is
# what the later steps (and the GitHub cache) carry around.
rm -rf "$SDK_INSTALL/sdl-java"
cp -r "$SDK_SRC/thirdparty/sdl3/android-project/app/src/main/java" "$SDK_INSTALL/sdl-java"
cp "$SDK_SRC"/thirdparty/sdl3/LICENSE* "$SDK_INSTALL/sdl-java/" 2>/dev/null || true
echo "SDK installed in $SDK_INSTALL"
