#!/usr/bin/env bash
# Builds the `rexglue` command-line tool (the recompiler) for THIS machine - a Linux PC, or Termux's Ubuntu (proot) on
# the phone - from the same SDK the Android APK is built with: upstream ReXGlue at the commit in tools/android/env.sh
# plus every patch in patches/rexglue-sdk/full/. Codegen output must come from this exact SDK: the WWE 2K14 patches
# change what codegen writes (memory fences for ARM, for example), and the APK's runtime expects that.
#   tools/codegen/build_rexglue.sh        -> out/host/sdk-build/.../rexglue
# Needs: git, cmake, ninja, clang, python3 and the SDL desktop development packages (the SDK always builds SDL with
# its X11 / Wayland / audio backends):
#   apt install git cmake ninja-build clang python3 pkg-config libx11-dev libxext-dev libxrandr-dev libxcursor-dev libxfixes-dev libxi-dev libxss-dev libxtst-dev libxkbcommon-dev libx11-xcb-dev libxcb1-dev libwayland-dev libwayland-bin wayland-protocols libdecor-0-dev libegl-dev libdrm-dev libgbm-dev libasound2-dev libpulse-dev libpipewire-0.3-dev libdbus-1-dev libudev-dev
set -euo pipefail
if [ "$(uname -o 2>/dev/null)" = "Android" ] || [ -n "${TERMUX_VERSION:-}" ]; then
  cat >&2 <<'MSG'
This is plain Termux: its compiler builds for Android, and the recompiler must be a normal Linux program.
Run it inside Termux's Ubuntu instead:
    pkg install proot-distro && proot-distro install ubuntu && proot-distro login ubuntu
    apt update && apt install -y git cmake ninja-build clang python3
    cd /data/data/com.termux/files/home/wwe2k15-android && tools/codegen/build_rexglue.sh
MSG
  exit 2
fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SDK_COMMIT="$(sed -n 's/^SDK_COMMIT="\(.*\)"/\1/p' "$ROOT/tools/android/env.sh")"
WORK="${WORK:-$ROOT/out/host}"
SRC="$WORK/rexglue-sdk-src"
BUILD="$WORK/sdk-build"
JOBS="${JOBS:-$(nproc)}"
# ReXGlue only builds with Clang; Ubuntu's default cc / c++ is GCC.
export CC="${CC:-clang}" CXX="${CXX:-clang++}"
command -v "$CXX" >/dev/null || { echo "clang++ not found: apt install clang" >&2; exit 2; }

mkdir -p "$WORK"
[ -d "$SRC/.git" ] || git clone https://github.com/rexglue/rexglue-sdk "$SRC"
git -C "$SRC" reset -q --hard
git -C "$SRC" clean -qfdx
git -C "$SRC" checkout -q "$SDK_COMMIT"
for p in "$ROOT"/patches/rexglue-sdk/full/*.diff; do
  git -C "$SRC" apply --whitespace=nowarn "$p" || { echo "patch failed: $p" >&2; exit 1; }
done
echo "applied $(ls "$ROOT"/patches/rexglue-sdk/full/*.diff | wc -l) patches on $SDK_COMMIT"
git -C "$SRC" submodule update --init --recursive --depth 1

cmake -S "$SRC" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DREXGLUE_ENABLE_TRACY=OFF -DREXGLUE_ENABLE_FIDELITYFX=OFF -DREXGLUE_BUILD_TESTS=OFF
cmake --build "$BUILD" --target rexglue -j "$JOBS"
# the SDK writes its programs to <source>/out/<platform>/ (not into the build folder)
TOOL="$(find "$SRC/out" "$BUILD" -type f -name rexglue -perm -u+x 2>/dev/null | head -1)"
[ -n "$TOOL" ] || { echo "rexglue was not built" >&2; exit 1; }
echo "built: $TOOL"
