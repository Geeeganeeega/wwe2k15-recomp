#!/usr/bin/env bash
# Recompiles WWE 2K15 (+ Title Update 4) into C++ for the Android build, then repairs the known false-fatal branches.
#   tools/codegen/run_codegen.sh /path/to/game_folder
# The game folder is YOUR OWN dump: default.xex with Title Update 4's default.xexp beside it (the PC port's layout).
# Output: port/generated/default/ - never publish it. Then push it to the private 'generated' branch (BUILD_ANDROID.md).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GAME="${1:?usage: run_codegen.sh /path/to/game_folder (default.xex + default.xexp)}"
GAME="$(cd "$GAME" && pwd)"
[ -f "$GAME/default.xex" ] || { echo "no default.xex in $GAME" >&2; exit 2; }
[ -f "$GAME/default.xexp" ] || { echo "no default.xexp (Title Update 4) in $GAME" >&2; exit 2; }
TOOL="${REXGLUE:-$(find "$ROOT/out/host/sdk-build" -type f -name rexglue -perm -u+x 2>/dev/null | head -1)}"
[ -n "$TOOL" ] || { echo "run tools/codegen/build_rexglue.sh first (or set REXGLUE=/path/to/rexglue)" >&2; exit 2; }

cd "$ROOT/port"
# The PC port's manifest template, pointed at the given game folder.
sed -e "s|^game_root = .*|game_root = \"$GAME\"|" -e "s|^file_path = .*|file_path = \"$GAME/default.xex\"|" \
  wwe2k15_manifest.example.toml > wwe2k15_manifest.toml
"$TOOL" codegen wwe2k15_manifest.toml
python3 tools/patch_codegen.py
echo "generated code in $ROOT/port/generated/default ($(ls generated/default | wc -l) files)"
