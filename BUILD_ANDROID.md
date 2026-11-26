# WWE 2K15 for Android: building the APK

This branch (`android`) turns the WWE 2K15 PC recompilation (iMrBurn/wwe2k15-pc, GPL-3.0) into an Android app. It
borrows from two other ports:

| From | What |
|---|---|
| **WWE 2K14 recomp** (Jarrodfeng/wwe2k14-recomp, BSD 3-Clause) | the ReXGlue SDK with its Android support (`patches/rexglue-sdk/full/01-...` up to `73-...`): ARM64 code, Adreno fixes, SDL on Android, custom Vulkan drivers, touch pad, phone frame pacing |
| **WWE 2K17 Android port** (the same SDK series + `88-...` to `95-...`) | the Linux / GitHub Actions build, the launcher app, touch controls, FPS overlay, driver import, DLC manager, frame generation, screen shapes, Android audio latency fix |
| **WWE 2K15 PC** (`port/`) | the game-specific code: TU4 function hints, 60 FPS entrances, the Create-a-Championship freeze fix (#33), crash guards, text / keyboard hooks, mods folder, ISO + title update loading |

`patches/rexglue-sdk/full/96-wwe2k15-port.diff` holds the WWE 2K15 PC port's SDK patches that the game needs on top
of that: the WWE 2K15 memory range, the VFS overlay (mods / title update), the kernel object type check (#24) and the
smaller ones that apply as they are. The PC port's own SDK patches stay in `port/patches/` for the Windows build.

The APK is built by GitHub Actions, not on the phone. Nothing from the game is ever stored in this repository.

## 1. Make the generated code (once, and again after a hint change)

On a Linux PC, or in Termux's Ubuntu on the phone:

    apt install git cmake ninja-build clang python3
    tools/codegen/build_rexglue.sh                       # the recompiler, from the same SDK the APK uses
    tools/codegen/run_codegen.sh "/path/to/WWE 2K15"     # your own dump: default.xex + Title Update 4's default.xexp

The code must come from **this** SDK's `rexglue` (the WWE 2K14 patches change what codegen writes for ARM). The game
folder must hold Title Update 4's `default.xexp` beside `default.xex`, exactly as for the PC port.

## 2. Push it to the private `generated` branch

    cd port/generated/default
    git init -b generated                                 # first time only
    git remote add origin https://github.com/Geeeganeeega/wwe2k15-recomp   # first time only
    git add -A
    git commit -q -m "Generated code"                     # later: git commit -q --amend -m "Generated code"
    git push -f origin generated

**Only push the `generated` branch to a private repository.** The generated code is derived from the game. A fork of
a public repository cannot be made private on GitHub, so build from a private (non-fork) copy of this branch.

## 3. Build the APK

GitHub website: Actions -> "Android build" -> Run workflow (branch `android`). Or with `gh`:

    gh workflow run android.yml --ref android
    gh run watch

The first run builds the SDK (long, cached afterwards). The APK lands on the **dev-latest** pre-release (Releases page).

## 4. On the phone

1. Install the APK, open it, give "All files access".
2. Pick your WWE 2K15 folder (`default.xex` + `default.xexp`), or an `.iso`. An ISO cannot hold the title update:
   import the Title Update 4 package (and any DLC packages) in the DLC screen.
3. Choose touch controls, resolution, GPU profile, driver (Turnip on Adreno) and press PLAY.
4. Logs: the launcher's COPY LOGS button puts them in `Download/WWE2K15_logs`.

## "Call to invalid or unregistered function" / "No function registered at ..."

The game reached code that codegen did not see (code called only through pointers in the game's data). Every start
logs those pointers as `[wwe2k15][scan] ...` lines.

    GEN=port/generated/default python3 tools/analysis/scan_hints.py <log> port/hints_scan_001.toml --lone

Add the new file to `includes = [...]` in `port/wwe2k15_manifest.example.toml`, run codegen again, push the
`generated` branch, run the workflow.

## Local build (Linux with the Android SDK / NDK)

    tools/android/build_sdk.sh     # ReXGlue SDK for Android arm64 (upstream commit + patches/rexglue-sdk/full)
    git clone --recursive --depth 1 https://github.com/bylaws/libadrenotools port/thirdparty/libadrenotools
    tools/android/build_game.sh    # libmain.so from port/generated/default/
    tools/android/build_apk.sh     # package + sign -> out/android/apk/wwe2k15-android.apk

## Licences

WWE 2K15 PC port: GPL-3.0 (`LICENSE`). WWE 2K14 port code: BSD 3-Clause, Copyright (c) 2026 Jarrod
(github.com/Jarrodfeng), full text in `docs/LICENSE_wwe2k14-recomp.txt`; its notices must stay with the files taken from it (the SDK patches, `port/src/android_*.h`,
`crash_trace.h`, `thread_stats.h`, the Java touch controls and driver loader). Bundled drivers: Turnip (Mesa, MIT),
PanVK (MIT, `docs/LICENSE_mali-panvk.txt`). SDL's Java activity: zlib. libadrenotools: BSD-2-Clause.
