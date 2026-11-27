#!/usr/bin/env bash
# Everything from the phone in one command: recompile WWE 2K15 and start the APK build on GitHub.
#
# From plain Termux, in this folder:
#   GAME="/sdcard/Download/2K15/545408B6" TOKEN="ghp_..." bash tools/codegen/phone_all.sh
#
#   GAME   your own WWE 2K15 folder (default.xex). Title Update 4 is found by itself: default.xexp beside it, or the
#          tu00000004_00000000 package in that folder or the one above it.
#   TOKEN  a GitHub token (repo + workflow) for the private repository. Without it, the GitHub steps use Termux's
#          saved GitHub login and you tap "Run workflow" on GitHub at the end.
#   REPO   optional, default Geeeganeeega/wwe2k15-android
#   BUILD_TOOL=1  compile the recompiler on the phone instead of downloading it (slow: 30-60 minutes)
#
# Fast path: the recompiler is downloaded ready-made (built by GitHub from the same SDK patches, release
# "rexglue-tool" of the public repository: .github/workflows/rexglue-tool.yml), so the phone only runs codegen.
set -euo pipefail
REPO="${REPO:-Geeeganeeega/wwe2k15-android}"
TOOL_URL="${TOOL_URL:-https://github.com/Geeeganeeega/wwe2k15-recomp/releases/download/rexglue-tool/rexglue-linux-arm64.tar.gz}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
: "${GAME:?set GAME=/path/to/your WWE 2K15 folder (the one with default.xex)}"
TOKEN="${TOKEN:-}"
case "$TOKEN" in *yourtoken*|*REAL_TOKEN*|*paste*|*your-token*) TOKEN="";; esac  # example texts: ignore them
export GIT_TERMINAL_PROMPT=0

say() { printf '\n\033[1;31m==> %s\033[0m\n' "$*"; }
in_termux() { [ "$(uname -o 2>/dev/null)" = "Android" ] || [ -n "${TERMUX_VERSION:-}" ]; }
t0=$(date +%s)
took() { echo "($(( ($(date +%s) - t0) / 60 )) min so far)"; }

# ---------------------------------------------------------------------------------------------------------------
# Inside Ubuntu: get the recompiler (download, or build) and run codegen. Called by the Termux part below.
if [ "${1:-}" = "--ubuntu-part" ]; then
  need_pkgs=()
  for p in python3 curl ca-certificates; do dpkg -s "$p" >/dev/null 2>&1 || need_pkgs+=("$p"); done
  if [ -n "${BUILD_TOOL:-}" ]; then
    for p in git cmake ninja-build clang; do dpkg -s "$p" >/dev/null 2>&1 || need_pkgs+=("$p"); done
  fi
  if [ ${#need_pkgs[@]} -gt 0 ]; then
    say "Installing ${need_pkgs[*]}"
    apt-get update -q && apt-get install -y -q "${need_pkgs[@]}"
  fi

  say "3/6 The recompiler"
  WANT="$(cat "$HERE"/patches/rexglue-sdk/full/*.diff "$HERE/tools/android/env.sh" | sha256sum | cut -c1-64)"
  DL="$HERE/out/host/rexglue-linux-arm64"
  REXGLUE=""
  if [ -z "${BUILD_TOOL:-}" ]; then
    if [ "$(cat "$DL/sdk-patches.sha" 2>/dev/null)" = "$WANT" ]; then
      echo "ready-made recompiler already here"
    else
      echo "downloading the ready-made recompiler..."
      rm -rf "$DL"; mkdir -p "$HERE/out/host"
      if curl -fsSL "$TOOL_URL" | tar -xz -C "$HERE/out/host"; then :; else echo "(download failed)"; fi
    fi
    if [ "$(cat "$DL/sdk-patches.sha" 2>/dev/null)" = "$WANT" ] && "$DL/rexglue" --help >/dev/null 2>&1; then
      REXGLUE="$DL/rexglue"
    else
      echo "The ready-made recompiler is missing or from other SDK patches (GitHub may still be building it:"
      echo "github.com/Geeeganeeega/wwe2k15-recomp/actions). Building it here instead (30-60 minutes)."
    fi
  fi
  if [ -z "$REXGLUE" ]; then
    apt-get update -q && apt-get install -y -q git cmake ninja-build clang pkg-config libx11-dev libxext-dev libxrandr-dev libxcursor-dev libxfixes-dev libxi-dev libxss-dev libxtst-dev libxkbcommon-dev libx11-xcb-dev libxcb1-dev libwayland-dev libwayland-bin wayland-protocols libdecor-0-dev libegl-dev libdrm-dev libgbm-dev libasound2-dev libpulse-dev libpipewire-0.3-dev libdbus-1-dev libudev-dev
    git config --global --add safe.directory '*'
    STAMP="$HERE/out/host/sdk-patches.sha"
    REXGLUE="$(find "$HERE/out/host/rexglue-sdk-src/out" "$HERE/out/host/sdk-build" -type f -name rexglue -perm -u+x 2>/dev/null | head -1 || true)"
    if [ -z "$REXGLUE" ] || [ "$(cat "$STAMP" 2>/dev/null)" != "$WANT" ]; then
      rm -rf "$HERE/out/host/sdk-build"
      CC=clang CXX=clang++ "$HERE/tools/codegen/build_rexglue.sh"
      echo "$WANT" > "$STAMP"
      REXGLUE="$(find "$HERE/out/host/rexglue-sdk-src/out" "$HERE/out/host/sdk-build" -type f -name rexglue -perm -u+x | head -1)"
    fi
  fi
  echo "recompiler: $REXGLUE"

  say "4/6 Recompiling WWE 2K15 (codegen)"
  REXGLUE="$REXGLUE" "$HERE/tools/codegen/run_codegen.sh" "$GAME"
  exit 0
fi

# ---------------------------------------------------------------------------------------------------------------
# Termux part: GitHub steps with Termux's git (and its saved login, or TOKEN).
in_termux || { echo "Run this from plain Termux (it enters Ubuntu by itself)." >&2; exit 2; }
if [ -n "$TOKEN" ]; then URL="https://${TOKEN}@github.com/${REPO}"; else URL="https://github.com/${REPO}"; fi

say "1/6 Updating the port from GitHub"
cd "$HERE"
git pull -q --ff-only "$URL" main || echo "(could not update; going on with this copy)"

say "2/6 Entering Ubuntu"
command -v proot-distro >/dev/null || pkg install -y proot-distro
proot-distro login ubuntu -- true >/dev/null 2>&1 || { say "Installing Ubuntu"; proot-distro install ubuntu; }
proot-distro login ubuntu --shared-tmp -- env GAME="$GAME" BUILD_TOOL="${BUILD_TOOL:-}" TOOL_URL="$TOOL_URL" \
  bash "$HERE/tools/codegen/phone_all.sh" --ubuntu-part
took

say "5/6 Pushing the generated code to the private 'generated' branch"
cd "$HERE/port/generated/default"
rm -rf .git
git init -q -b generated
git add -A
git -c user.name=wwe2k15 -c user.email=wwe2k15@localhost commit -q -m "Generated code"
git push -q -f "$URL" generated
took

say "6/6 Starting the APK build"
started=""
if [ -n "$TOKEN" ]; then
  code=$(curl -s -o "$HERE/out/dispatch.txt" -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$REPO/actions/workflows/android.yml/dispatches" -d '{"ref":"main"}' || true)
  [ "$code" = "204" ] && started=1
fi
if [ -n "$started" ]; then
  echo "Build started: https://github.com/$REPO/actions"
else
  echo "Start it on GitHub: https://github.com/$REPO/actions -> Android build -> Run workflow"
fi
say "ALL DONE $(took) - the APK appears under Releases -> dev-latest when GitHub finishes"
