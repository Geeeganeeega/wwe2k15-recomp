// wwe2k15: created superstars, Superstar Threads and other textures the game builds on the GPU and finishes on the CPU.
//
// The game renders the layers, resolves them into guest memory, then untiles them on the CPU with XGUntileSurface.
// The recompiled GPU keeps resolve results in its own memory, so without a readback the CPU untiles stale memory and
// the model draws black. Reading back every resolve costs frame rate; this reads back exactly while the game untiles
// (which only happens while it loads or edits such textures): the first untile turns the SDK's exact readback on
// (readback_resolve_override 3, the WWE 2K14 port's run-time readback switch) and every later one extends it;
// Wwe2k15ResolveCoherencyOnFrame turns it off 3 s after the last.
//
// After the WWE 2K16 recomp's src/resolve_coherency.cpp (itsbybanshee/wwe2k16-pc, fork Savanindurkar7/wwe2k16-pc; its
// author allows forks; docs/NOTICE_wwe2k16-recomp.txt).
//   sub_82BF4740   XGUntileSurface (WWE 2K15 TU4): dest r3, row pitch r4, point r5, source r6, width r7, height r8,
//                  rect r9, texel pitch r10. Found by its arguments and its in-place copy of
//                  align32(width) * align32(height) * texel_pitch bytes (2K16 retail: sub_82C70510).
//
// --wwe2k15_untile_readback: 1 (default) on while untiling, 2 always on (slower), 0 off.

#include <atomic>
#include <chrono>
#include <cstdint>
#include <string>

#include <rex/cvar.h>
#include <rex/logging.h>
#include <rex/ppc/context.h>

#include "generated/default/wwe2k15_init.h"

REXCVAR_DEFINE_INT32(wwe2k15_untile_readback, 1, "wwe2k15",
                     "Exact GPU readback while the game untiles textures it built on the GPU (created superstars, "
                     "attires): 1 while untiling, 2 always, 0 off")
    .range(0, 2);

namespace {

std::atomic<int64_t> g_until_ns{0};
std::atomic<int> g_mode{-1};

int64_t NowNs() {
  return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

void SetMode(int mode) {
  if (g_mode.exchange(mode) != mode) {
    rex::cvar::SetFlagByName("readback_resolve_override", std::to_string(mode));
    REXLOG_INFO("[wwe2k15][untile] exact readback {}", mode == 3 ? "on" : "off");
  }
}

}  // namespace

REX_EXTERN(__imp__sub_82BF4740);
extern "C" REX_FUNC(sub_82BF4740) {
  const int32_t setting = REXCVAR_GET(wwe2k15_untile_readback);
  if (setting > 0 && ctx.r6.u32) {
    g_until_ns.store(NowNs() + 3'000'000'000ll, std::memory_order_relaxed);
    SetMode(3);
    static int logged = 0;
    if (logged < 8) {
      ++logged;
      REXLOG_INFO("[wwe2k15][untile] source {:08X} {}x{} texel pitch {}", ctx.r6.u32, ctx.r7.u32, ctx.r8.u32,
                  ctx.r10.u32);
    }
  }
  __imp__sub_82BF4740(ctx, base);
}

// Once per presented frame (android_frame.h): ends the readback window 3 s after the last untile.
void Wwe2k15ResolveCoherencyOnFrame() {
  const int32_t setting = REXCVAR_GET(wwe2k15_untile_readback);
  if (setting == 2) {
    SetMode(3);
  } else if (g_mode.load(std::memory_order_relaxed) == 3 &&
             (setting == 0 || NowNs() > g_until_ns.load(std::memory_order_relaxed))) {
    SetMode(-1);
  }
}
