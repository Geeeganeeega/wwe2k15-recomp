// WWE 2K15 crowd options (Android performance): the stands crowd is drawn member by member, one draw each (about 190
// draws a frame in a match, a fifth of all). Same engine layout as WWE 2K14 (scene_crowd.h in github.com/jarrodfeng/
// wwe2k14-recomp) and WWE '13 (crowd_options.cpp in the WWE '13 Recomp for Android by dzepdzep, BSD 3-Clause); this
// file follows the WWE '13 one.
//
// 2K15 TU4 addresses (found from 2K14's: same calls at the same offsets inside the same functions):
//   block draw   sub_82487CD8 (2K14 sub_82518340): per group SetStreamSource, then per member c76-c78 + one draw
//   card loop    sub_824828F8 (2K14 sub_82512F70): the far crowd as flat cards, the same per-member pattern
//   op 14        sub_82AAB848 (2K14 sub_82BB0F30): record SetStreamSource (a mesh group starts)
//   op 21        sub_82AAD0F0 (2K14 sub_82BB27D8): record VS float constants (r4 first register, r5 data, r6 vec4 count)
//   op 15        sub_82AAB9D8 (2K14 sub_82BB10C0): record a draw
//
// --wwe2k15_crowd: 0 = crowd hidden, 1 = batched (default: inside a block, each group's first member is recorded as the
// game does; the other members' world matrices go to the SDK's queue (CommandProcessor::SceneCrowdPush), which draws
// them as instances of the first member's draw: one host draw per group instead of one per member), 2 = batched and
// the far half of the crowd thinned (the launcher adds gpu_scene_crowd_cull 2 + gpu_scene_crowd_thin 100).
#include "generated/default/wwe2k15_init.h"  // REX_LOAD_U32 and the generated functions

#include <atomic>
#include <cstdint>
#include <cstring>
#include <vector>

#include <rex/cvar.h>
#include <rex/graphics/command_processor.h>
#include <rex/graphics/graphics_system.h>
#include <rex/hook.h>
#include <rex/logging.h>
#include <rex/ppc/context.h>
#include <rex/runtime.h>
#include <rex/system/kernel_state.h>

REXCVAR_DEFINE_INT32(wwe2k15_crowd, 1, "wwe2k15",
                     "The stands crowd: 0 hidden, 1 batched (one draw per mesh group instead of one per member), "
                     "2 batched with the far half thinned (with gpu_scene_crowd_cull 2 / gpu_scene_crowd_thin 100)")
    .range(0, 2);

namespace {

rex::graphics::CommandProcessor* Cp() {
  static std::atomic<rex::graphics::CommandProcessor*> cached{nullptr};
  rex::graphics::CommandProcessor* cp = cached.load(std::memory_order_relaxed);
  if (!cp) {
    auto* runtime = REX_KERNEL_STATE() ? REX_KERNEL_STATE()->emulator() : nullptr;
    auto* graphics = runtime ? runtime->graphics_system() : nullptr;
    cp = graphics ? static_cast<rex::graphics::GraphicsSystem*>(graphics)->command_processor() : nullptr;
    if (cp) cached.store(cp, std::memory_order_relaxed);
  }
  return cp;
}

bool Hidden() { return REXCVAR_GET(wwe2k15_crowd) == 0; }
bool Blocked() {
  auto* cp = Cp();
  return !cp || cp->scene_crowd_blocked();  // the SDK stands down on its own when a list is replayed twice
}

struct CrowdRecord {
  bool in_block = false;
  bool have_first = false;
  bool skip_draw = false;
  uint32_t first[12] = {};
  std::vector<uint32_t> others;
  void Begin() {
    in_block = true;
    have_first = false;
    skip_draw = false;
    others.clear();
  }
  void Flush() {
    if (have_first && !others.empty()) {
      if (auto* cp = Cp()) {
        if (!cp->scene_crowd_blocked()) {
          static bool said = false;
          if (!said) {
            said = true;
            REXLOG_INFO("[wwe2k15][crowd] batching on: first run of {} members", others.size() / 12 + 1);
          }
          cp->SceneCrowdPush(first, others.data(), uint32_t(others.size() / 12), false);
        }
      }
    }
    have_first = false;
    skip_draw = false;
    others.clear();
  }
};
thread_local CrowdRecord t_crowd;

}  // namespace

REX_EXTERN(__imp__sub_82487CD8);
REX_EXTERN(__imp__sub_824828F8);
REX_EXTERN(__imp__sub_82AAB848);
REX_EXTERN(__imp__sub_82AAD0F0);
REX_EXTERN(__imp__sub_82AAB9D8);

// The block draw (the near crowd, full models).
extern "C" REX_FUNC(sub_82487CD8) {
  if (Hidden()) return;
  if (t_crowd.in_block || Blocked()) {
    __imp__sub_82487CD8(ctx, base);
    return;
  }
  t_crowd.Begin();
  __imp__sub_82487CD8(ctx, base);
  t_crowd.Flush();
  t_crowd.in_block = false;
}

// The card loop (the far crowd, flat cards): the same per-member pattern.
extern "C" REX_FUNC(sub_824828F8) {
  if (Hidden()) return;
  if (t_crowd.in_block || Blocked()) {
    __imp__sub_824828F8(ctx, base);
    return;
  }
  t_crowd.Begin();
  __imp__sub_824828F8(ctx, base);
  t_crowd.Flush();
  t_crowd.in_block = false;
}

// SetStreamSource: a new mesh group starts, the previous group's run goes out.
extern "C" REX_FUNC(sub_82AAB848) {
  if (t_crowd.in_block) t_crowd.Flush();
  __imp__sub_82AAB848(ctx, base);
}

// VS float constants: a member's world matrix (c76-c78) is kept, not recorded, for every member but the group's first.
extern "C" REX_FUNC(sub_82AAD0F0) {
  if (t_crowd.in_block && ctx.r4.u32 == 76 && ctx.r6.u32 == 3) {
    uint32_t m[12];
    for (uint32_t i = 0; i < 12; ++i) m[i] = REX_LOAD_U32(ctx.r5.u32 + 4 * i);
    if (!t_crowd.have_first) {
      std::memcpy(t_crowd.first, m, sizeof(m));
      t_crowd.have_first = true;
    } else {
      t_crowd.others.insert(t_crowd.others.end(), m, m + 12);
      t_crowd.skip_draw = true;
      return;
    }
  }
  __imp__sub_82AAD0F0(ctx, base);
}

// A draw: a kept member's draw is not recorded (the SDK draws it as an instance of the first member's).
extern "C" REX_FUNC(sub_82AAB9D8) {
  if (t_crowd.in_block && t_crowd.skip_draw) {
    t_crowd.skip_draw = false;
    return;
  }
  __imp__sub_82AAB9D8(ctx, base);
}
