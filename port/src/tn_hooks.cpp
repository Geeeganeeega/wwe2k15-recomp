// TRUE NATIVE for WWE 2K15 (Android): the WWE 2K14 port's Direct3D layer (github.com/jarrodfeng/wwe2k14-recomp,
// BSD 3-Clause), taken from the WWE 2K17 port's trimmed copy (src/tn) with every game address translated to WWE 2K15
// TU4 (the D3D library is the same build in all three games: the hooked functions match instruction for instruction).
// Off by default: --wwe2k14_d3d_owned=true turns it on (launcher: GRAPHICS -> TRUE NATIVE). With it off every hook
// calls the game's own function.
#include <cstdint>

#include "tn/half2_hooks.h"  // the hooks of the game's D3D library (includes the whole layer)
#include "tn/d3d_owned.h"    // the one switch and the SDK settings it needs

void Wwe2k15TrueNativeApply() { wwe2k14::d3d_owned::Apply(); }

bool Wwe2k15TrueNativeOn() { return REXCVAR_GET(wwe2k14_d3d_owned); }

void Wwe2k15TnPresentHook(PPCContext& ctx, uint8_t* base) { Wwe2k15TnPresent(ctx, base); }

void Wwe2k15TrueNativeFrame(uint64_t frame) { wwe2k14::g_guest_frame.store(frame, std::memory_order_relaxed); }
