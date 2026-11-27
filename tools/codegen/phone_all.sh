#!/usr/bin/env bash
# Everything from the phone in one go: recompile WWE 2K15 and start the APK build on GitHub.
#
# From plain Termux (it enters Termux's Ubuntu by itself):
#   GAME="/sdcard/WWE2K15" TOKEN="ghp_..." bash ~/wwe2k15-android/tools/codegen/phone_all.sh
#
#   GAME   your own WWE 2K15 folder: default.xex + Title Update 4's default.xexp
#   TOKEN  a GitHub token with access to the private repository (repo + workflow scopes)
#   REPO   optional, default Geeeganeeega/wwe2k15-android
#   REBUILD=1  build the recompiler again even if it is already built
#
# Steps: install the tools (Ubuntu) -> update this folder from GitHub -> build `rexglue` (once) -> codegen ->
# push port/generated/default to the private 'generated' branch -> start the "Android build" workflow.
set -euo pipefail
REPO="${REPO:-Geeeganeeega/wwe2k15-android}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
: "${GAME:?set GAME=/path/to/your WWE 2K15 folder (default.xex + default.xexp)}"
: "${TOKEN:?set TOKEN=your GitHub token}"

say() { printf '\n\033[1;31m==> %s\033[0m\n' "$*"; }

# ---- plain Termux: re-run this script inside Termux's Ubuntu (the recompiler must be a normal Linux program)
if [ "$(uname -o 2>/dev/null)" = "Android" ] || [ -n "${TERMUX_VERSION:-}" ]; then
  command -v proot-distro >/dev/null || { say "Installing proot-distro"; pkg install -y proot-distro; }
  proot-distro list 2>/dev/null | grep -q "ubuntu.*installed\|Installed.*ubuntu" || \
    [ -d "$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu" ] || { say "Installing Ubuntu"; proot-distro install ubuntu; }
  say "Entering Ubuntu"
  exec proot-distro login ubuntu --shared-tmp -- env GAME="$GAME" TOKEN="$TOKEN" REPO="$REPO" REBUILD="${REBUILD:-}" \
    bash "$HERE/tools/codegen/phone_all.sh"
fi

URL="https://${TOKEN}@github.com/${REPO}"

say "1/6 Tools"
need_pkgs=()
for p in git cmake ninja-build clang python3 curl; do dpkg -s "$p" >/dev/null 2>&1 || need_pkgs+=("$p"); done
if [ ${#need_pkgs[@]} -gt 0 ]; then apt-get update -q && apt-get install -y -q "${need_pkgs[@]}"; fi
git config --global --add safe.directory '*'

say "2/6 Updating the port from GitHub"
cd "$HERE"
git pull -q --ff-only "$URL" main || echo "(could not update; going on with this copy)"

say "3/6 The recompiler (rexglue)"
TOOL="$(find "$HERE/out/host/sdk-build" -type f -name rexglue -perm -u+x 2>/dev/null | head -1 || true)"
if [ -z "$TOOL" ] || [ -n "${REBUILD:-}" ]; then
  rm -rf "$HERE/out/host/sdk-build"
  CC=clang CXX=clang++ "$HERE/tools/codegen/build_rexglue.sh"
else
  echo "already built: $TOOL"
fi

say "4/6 Recompiling WWE 2K15 (codegen)"
"$HERE/tools/codegen/run_codegen.sh" "$GAME"

say "5/6 Pushing the generated code to the private 'generated' branch"
cd "$HERE/port/generated/default"
rm -rf .git
git init -q -b generated
git add -A
git -c user.name=wwe2k15 -c user.email=wwe2k15@localhost commit -q -m "Generated code"
git push -q -f "$URL" generated

say "6/6 Starting the APK build on GitHub"
code=$(curl -s -o /tmp/wwe2k15_dispatch.txt -w '%{http_code}' -X POST \
  -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$REPO/actions/workflows/android.yml/dispatches" -d '{"ref":"main"}')
if [ "$code" = "204" ]; then
  echo "Build started: https://github.com/$REPO/actions"
  echo "The APK appears under Releases -> dev-latest when it finishes (the first build is long)."
else
  echo "Could not start the build (GitHub answered $code: $(head -c 300 /tmp/wwe2k15_dispatch.txt))."
  echo "Start it by hand: GitHub -> $REPO -> Actions -> Android build -> Run workflow."
fi
say "ALL DONE"
