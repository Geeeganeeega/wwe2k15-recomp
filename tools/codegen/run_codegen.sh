#!/usr/bin/env bash
# Recompiles WWE 2K15 (+ Title Update 4) into C++ for the Android build, then repairs the known false-fatal branches.
#   tools/codegen/run_codegen.sh /path/to/game_folder [/path/to/title_update_package]
# The game folder is YOUR OWN dump, the folder that holds default.xex (e.g. Download/2K15/545408B6).
# Title Update 4 is found by itself: default.xexp beside default.xex, or the title update package file
# (tu00000004_00000000) in the game folder or the folder above it. The package's default.xexp is extracted
# (tools/codegen/stfs_extract.py) into out/host/tu4/ with a copy of default.xex; your folders are not changed.
# Output: port/generated/default/ - never publish it (private 'generated' branch only, BUILD_ANDROID.md).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GAME="${1:?usage: run_codegen.sh /path/to/game_folder [title_update_package]}"
GAME="$(cd "$GAME" && pwd)"
TU_PKG="${2:-${TU:-}}"
[ -f "$GAME/default.xex" ] || { echo "no default.xex in $GAME" >&2; exit 2; }
TOOL="${REXGLUE:-$(find "$ROOT/out/host/rexglue-sdk-src/out" "$ROOT/out/host/sdk-build" -type f -name rexglue -perm -u+x 2>/dev/null | head -1)}"
[ -n "$TOOL" ] || { echo "run tools/codegen/build_rexglue.sh first (or set REXGLUE=/path/to/rexglue)" >&2; exit 2; }

XEX="$GAME/default.xex"
if [ ! -f "$GAME/default.xexp" ]; then
  if [ -z "$TU_PKG" ]; then
    # The title update package: a WWE 2K15 (545408B6) package of content type 000B0000, in the game folder or the one
    # above it (files named tu* first). Other packages there (AvatarAssetPack, DLC) are not it.
    UP="$(dirname "$GAME")"
    for f in "$GAME"/tu* "$GAME"/TU* "$UP"/tu* "$UP"/TU* "$GAME"/* "$UP"/*; do
      [ -f "$f" ] || continue
      case "$(head -c 4 "$f" 2>/dev/null)" in LIVE|PIRS|"CON ") ;; *) continue;; esac
      if python3 "$ROOT/tools/codegen/stfs_extract.py" "$f" --is-title-update 545408B6; then TU_PKG="$f"; break; fi
    done
  fi
  [ -n "$TU_PKG" ] || { echo "Title Update 4 not found: put tu00000004_00000000 (or default.xexp) next to the game folder" >&2; exit 2; }
  echo "title update package: $TU_PKG"
  WORK="$ROOT/out/host/tu4"
  mkdir -p "$WORK"
  python3 "$ROOT/tools/codegen/stfs_extract.py" "$TU_PKG" default.xexp "$WORK/default.xexp"
  cp -f "$XEX" "$WORK/default.xex"
  XEX="$WORK/default.xex"
  GAME="$WORK"  # codegen wants the XEX inside the game root; it only reads the XEX (+ its .xexp) from there
fi

cd "$ROOT/port"
# The PC port's manifest template, pointed at the game (game_root = your folder; the XEX next to its .xexp).
sed -e "s|^game_root = .*|game_root = \"$GAME\"|" -e "s|^file_path = .*|file_path = \"$XEX\"|" \
  wwe2k15_manifest.example.toml > wwe2k15_manifest.toml
"$TOOL" codegen wwe2k15_manifest.toml
python3 tools/patch_codegen.py
echo "generated code in $ROOT/port/generated/default ($(ls generated/default | wc -l) files)"
