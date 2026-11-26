#!/usr/bin/env bash
# Build the game library for Android arm64 (out/android/game-build/libmain.so) from the already generated code in
# port/generated/default/ (WWE2K15_PREGENERATED=ON: no codegen step, the game's default.xex is not needed here).
#   needs: out/android/sdk-install (build_sdk.sh), port/generated/default/ (the "generated" branch checked out there),
#          port/thirdparty/libadrenotools (git clone --recursive https://github.com/bylaws/libadrenotools)
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"
need "$SDK_INSTALL/lib/cmake/rexglue/rexglueConfig.cmake" "run tools/android/build_sdk.sh first"
need "$PORT/generated/default/sources.cmake" "check out the 'generated' branch into port/generated/default"

# ReXGlue can emit a fatal stub for a branch whose target label is in the same function: the PC build repairs those
# after codegen (port/tools/patch_codegen.py); same here. Safe to run again on code it already repaired.
python3 "$PORT/tools/patch_codegen.py"

cmake -S "$PORT" -B "$GAME_BUILD" "${CMAKE_ANDROID_ARGS[@]}" \
  "-DCMAKE_PREFIX_PATH=$SDK_INSTALL" "-DCMAKE_FIND_ROOT_PATH=$SDK_INSTALL" \
  -DCMAKE_FIND_USE_PACKAGE_REGISTRY=OFF -DWWE2K15_PREGENERATED=ON \
  "${CCACHE_ARGS[@]}"

cmake --build "$GAME_BUILD" --target wwe2k15 -j "$JOBS"

so="$(find "$GAME_BUILD" -name libmain.so | head -1)"
[ -n "$so" ] || { echo "libmain.so was not built" >&2; exit 1; }
ls -l "$so"
