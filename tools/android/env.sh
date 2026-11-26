# Shared settings for the Android build scripts (source this file). Meant for GitHub Actions' Ubuntu runner; the
# same steps work on any Linux machine with the Android command-line tools installed ($ANDROID_HOME).
# Linux versions of an earlier port's tools/android/*.ps1.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PORT="$ROOT/port"   # the game's CMake project (the PC port's layout)
WORK="${WORK:-$ROOT/out/android}"

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
export ANDROID_HOME
NDK_VERSION="29.0.14206865"
BUILD_TOOLS_VERSION="36.0.0"
PLATFORM="android-36"
MIN_API=28
TARGET_API=34

# The ReXGlue SDK the game is built on: upstream commit + the patches of an earlier port (patches/rexglue-sdk/full).
SDK_COMMIT="f5337cdc947ff6d4c4196737e2c807a48f2a1fc2"
SDK_SRC="$WORK/rexglue-sdk-src"
SDK_BUILD="$WORK/sdk-build"
SDK_INSTALL="$WORK/sdk-install"
GAME_BUILD="$WORK/game-build"
APK_DIR="$WORK/apk"

# Never take ANDROID_NDK_HOME from the environment: GitHub's runners preset it to an NDK this build does not use.
ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
export ANDROID_NDK_HOME
NDK_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
LLVM_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
BUILD_TOOLS="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$ANDROID_HOME/platforms/$PLATFORM/android.jar"

JOBS="${JOBS:-$(nproc)}"

# ARMv8.2 (what an earlier port builds for; every phone since ~2018). Never a larger code model (the Android loader
# refuses libraries with more than 65535 sections). -fno-pch-timestamp: lets ccache reuse precompiled headers.
ARCH_FLAGS="-march=armv8.2-a"
PCH_FLAGS="-Xclang -fno-pch-timestamp"

# CMake arguments for the Android toolchain, the same for the SDK and the game (an array: some values hold spaces).
CMAKE_ANDROID_ARGS=(
  -G Ninja
  "-DCMAKE_TOOLCHAIN_FILE=$NDK_TOOLCHAIN_FILE"
  -DANDROID_ABI=arm64-v8a
  "-DANDROID_PLATFORM=android-$MIN_API"
  -DANDROID_STL=c++_shared
  -DCMAKE_BUILD_TYPE=Release
  "-DCMAKE_C_FLAGS=$ARCH_FLAGS"
  "-DCMAKE_CXX_FLAGS=$ARCH_FLAGS $PCH_FLAGS"
)
# Optional compiler cache (set CCACHE=1): flags without spaces, so they can be left unquoted.
CCACHE_ARGS=()
if [ "${CCACHE:-0}" = "1" ]; then
  CCACHE_ARGS=(-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache)
fi

need() { [ -e "$1" ] || { echo "missing: $1 ($2)" >&2; exit 2; }; }
