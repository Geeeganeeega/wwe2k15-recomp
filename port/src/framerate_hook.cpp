// wwe2k15 - frame-rate control
//
// sub_82AB14C8(fps) is the title's SetFrameRate (TU4 address): it accepts 25/30/50/60 and
// fills the timing struct at 0x838DC7A8 (+8 = fps, +40 = ms per frame, float steps at
// +28/+32/+60/+64), the same design as WWE '12's sub_82827918. Game logic and the present
// interval both follow it, so forcing 60 here (cvar wwe2k15_60fps) keeps animation speed
// correct, unlike only presenting more often.

#include "generated/default/wwe2k15_init.h"

#include <algorithm>
#include <atomic>
#include <cstring>
#include <string>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#endif

#include <rex/cvar.h>
#include "platform_time.h"
#include <rex/logging/macros.h>

REXCVAR_DEFINE_BOOL(wwe2k15_show_fps, false, "WWE2K15", "Show an FPS counter in the game");
REXCVAR_DEFINE_BOOL(wwe2k15_mods, true, "WWE2K15", "Load replacement files from the mods folder");

REXCVAR_DEFINE_BOOL(wwe2k15_60fps, true, "WWE2K15",
                    "Entrances and victory scenes at 60 FPS (50 on PAL) instead of the console's 30 (25); false = original 30");

REX_EXTERN(__imp__sub_82AB14C8);
REX_EXTERN(__imp__sub_82BADF20);

// ---- Real-time 30 FPS step (no slow motion on devices that cannot hold 60).
// Ported from the WWE 2K14 port (Jarrodfeng/wwe2k14-recomp, src/step_rate.h, BSD 3-Clause): the game advances by a
// fixed step per update (1/60 s at its word 60), so a device that only manages 40 fps plays the match at 2/3 speed.
// Step 30 rewrites the timing struct's time fields for 30 updates a second (each update covers 1/30 s, so the game
// runs at real speed at 30 fps) while the frame-rate word stays at the game's 60: the game reads a word of 30 as
// "entrance / cinematic" and would take those code paths in the match (2K14 Session 83, GitHub #63). The guest
// vblank runs at 30 Hz to match (the word's present interval is 1). 2K15's timing struct has 2K14's layout
// (0x838DC7A8 here, 0x8392DC38 in 2K14): +8 word, +0x1C s per update, +0x20 rate, +0x24 ms per update (float),
// +0x28 ms per update (int), +0x3C 60/rate, +0x40 rate/60; its three writers are the hooks in this file.
//   wwe2k15_step30 = 0 off (the game's own rates), 1 always, 2 automatic (60 until a match cannot hold it).
// Not ported (2K14 Session 83): its fix for timed cues that wait for an exact update count (a Royal Rumble entrant's
// bell); at step 30 such a cue could be skipped. Watch for it in testing.
REXCVAR_DEFINE_INT32(wwe2k15_step30, 0, "WWE2K15",
                     "Real-time 30 FPS step: 0 off, 1 always, 2 automatic (when the game cannot hold 60 it runs at "
                     "real speed at 30 fps instead of in slow motion)")
    .range(0, 2);
REXCVAR_DEFINE_INT32(wwe2k15_step30_auto_ms, 20, "WWE2K15",
                     "wwe2k15_step30=2: average frame time (ms) over two 90-frame windows that switches to the 30 step")
    .range(17, 40);

namespace wwe2k15::step30 {
constexpr uint32_t kBlock = 0x838DC7A8;
constexpr uint32_t kWord = kBlock + 8;
std::atomic<uint32_t> g_game_word{0};  // the game's own word (60 / 30 / 50 / 25), 0 = not seen yet
std::atomic<bool> g_on{false};         // the 30 step is applied
std::atomic<uint32_t> g_effective{0};  // the update rate when the word is kept at the game's (30 / 25), else 0

inline uint32_t Load(uint8_t* base, uint32_t a) {
  const uint8_t* p = base + a;
  return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3];
}
inline void Store(uint8_t* base, uint32_t a, uint32_t v) {
  uint8_t* p = base + a;
  p[0] = uint8_t(v >> 24), p[1] = uint8_t(v >> 16), p[2] = uint8_t(v >> 8), p[3] = uint8_t(v);
}
inline void StoreF(uint8_t* base, uint32_t a, float f) {
  uint32_t v;
  std::memcpy(&v, &f, 4);
  Store(base, a, v);
}
// Writes the fields for game word g at half its update rate (on) or the game's own (off). A stock 30 (25) fps scene
// would become 15: not a rate the game knows, so it keeps its own 30.
inline void WriteFields(uint8_t* base, uint32_t g, bool on) {
  if (g != 25 && g != 30 && g != 50 && g != 60) return;
  uint32_t s = on ? g / 2 : g;
  if (s < 25) s = g;
  const bool keep_word = s != g;
  g_effective.store(keep_word ? s : 0, std::memory_order_relaxed);
  const float rate = float(s);
  Store(base, kWord, g);
  StoreF(base, kBlock + 0x1C, 1.0f / rate);
  StoreF(base, kBlock + 0x20, rate);
  StoreF(base, kBlock + 0x24, 1000.0f / rate);
  Store(base, kBlock + 0x28, 1000u / s);
  StoreF(base, kBlock + 0x3C, 60.0f / rate);
  StoreF(base, kBlock + 0x40, rate / 60.0f);
  static uint32_t vblank_set = 0;
  const uint32_t vb = keep_word ? s : 0;
  if (vb != vblank_set) {
    rex::cvar::SetFlagByName("gpu_vblank_rate_override_hz", std::to_string(vb));
    vblank_set = vb;
  }
}
// Around each of the game's three timing writers: the original sees the game's own word, then the fields are
// rewritten for the step.
template <typename F>
inline void Wrap(F original, PPCContext& ctx, uint8_t* base) {
  if (!g_on.load(std::memory_order_relaxed)) {
    original(ctx, base);
    if (const uint32_t w = Load(base, kWord); w == 25 || w == 30 || w == 50 || w == 60)
      g_game_word.store(w, std::memory_order_relaxed);
    return;
  }
  const uint32_t g = g_game_word.load(std::memory_order_relaxed);
  if (g) Store(base, kWord, g);
  original(ctx, base);
  const uint32_t g2 = Load(base, kWord);
  if (g2 != g) REXLOG_INFO("[wwe2k15][step30] game word {} -> {}", g, g2);
  g_game_word.store(g2, std::memory_order_relaxed);
  WriteFields(base, g2, true);
}
inline void TurnOn(uint8_t* base, const char* why) {
  if (g_on.exchange(true, std::memory_order_relaxed)) return;
  uint32_t g = g_game_word.load(std::memory_order_relaxed);
  if (!g) g = Load(base, kWord), g_game_word.store(g, std::memory_order_relaxed);
  WriteFields(base, g, true);
  REXLOG_INFO("[wwe2k15][step30] on ({}): game word {}, updates per second now {}", why, g,
              g_effective.load(std::memory_order_relaxed) ? g_effective.load(std::memory_order_relaxed) : g);
}
// Once per presented frame: "always" turns on as soon as the timing struct is set up; "automatic" after two
// 90-frame windows slower than wwe2k15_step30_auto_ms at the game's 60 (slow motion), for the rest of the session.
inline void OnPresent(uint8_t* base, uint64_t now_us) {
  const int32_t mode = REXCVAR_GET(wwe2k15_step30);
  if (!base || mode == 0 || g_on.load(std::memory_order_relaxed)) return;
  const uint32_t w = Load(base, kWord);
  if (w != 60 && w != 50) return;  // not set up yet, or a 30 fps scene (it keeps its own rate)
  if (!g_game_word.load(std::memory_order_relaxed)) g_game_word.store(w, std::memory_order_relaxed);
  if (mode == 1) {
    TurnOn(base, "always");
    return;
  }
  static uint64_t last = 0, sum = 0;
  static uint32_t frames = 0, slow = 0;
  if (last) sum += now_us - last, ++frames;
  last = now_us;
  if (frames < 90) return;
  const double avg_ms = sum / 1000.0 / frames;
  sum = 0, frames = 0;
  slow = avg_ms > REXCVAR_GET(wwe2k15_step30_auto_ms) ? slow + 1 : 0;
  if (slow >= 2) {
    REXLOG_INFO("[wwe2k15][step30] the game cannot hold its 60 ({:.1f} ms per frame): it would run in slow motion",
                avg_ms);
    TurnOn(base, "automatic");
  }
}
}  // namespace wwe2k15::step30

// The update rate when the 30 step keeps the word at 60 (android_frame.h paces frames to it), else 0.
uint32_t Wwe2k15StepEffectiveRate() { return wwe2k15::step30::g_effective.load(std::memory_order_relaxed); }

extern std::atomic<uint64_t> g_wwe2k15_gpu_wait_us;

// Guest presents per second (x10) and the worst frame time (us) over the last second, for the
// in-game FPS counter (wwe2k15_app.h).
std::atomic<uint32_t> g_wwe2k15_fps_x10{0};
std::atomic<uint32_t> g_wwe2k15_worst_frame_us{0};
std::atomic<uint64_t> g_wwe2k15_present_count{0};  // heartbeat for the freeze watchdog

namespace {
std::atomic<uint32_t> g_calls{0};
}

extern "C" REX_FUNC(sub_82AB14C8) {
  const uint32_t requested = ctx.r3.u32;
  uint32_t applied = requested;
  if (REXCVAR_GET(wwe2k15_60fps)) {
    if (requested == 30) applied = 60;
    else if (requested == 25) applied = 50;
  }
  const uint32_t n = g_calls.fetch_add(1);
  if (n < 32 || n % 100 == 0) {
    REXLOG_INFO("SetFrameRate({}) -> {} (call {}, lr {:08X})", requested, applied, n,
                uint32_t(ctx.lr));
  }
  ctx.r3.u64 = applied;
  wwe2k15::step30::Wrap(__imp__sub_82AB14C8, ctx, base);
}


// "Drop to 30 (25) fps" (sub_82AB1668, TU4) and "back to 60 (50)" (sub_82AB1708): the game drops to 30
// for entrances and victory scenes by rewriting its timing struct (0x838DC7A8: +8 fps, +28/+32/+36/+60/+64
// per-frame steps, +40 ms per frame; same design as WWE '12's sub_82827AB8/B58). With wwe2k15_60fps the drop
// is skipped, so game logic and presents both stay at 60 and the scene plays at the right speed.
REX_EXTERN(__imp__sub_82AB1668);
REX_EXTERN(__imp__sub_82AB1708);

REXCVAR_DEFINE_INT32(wwe2k15_60fps_auto_ms, 26, "WWE2K15",
                     "Average frame time (ms) above which a scene at 60 fps is given up (wwe2k15_60fps_auto)")
    .range(10, 60);

REXCVAR_DEFINE_BOOL(wwe2k15_60fps_auto, true, "WWE2K15",
                    "If an entrance/victory scene cannot hold 60 fps (slow GPU or CPU: the scene would run in slow motion), "
                    "go back to the game's 30 fps for it and the rest of the session");

namespace {
std::atomic<bool> g_scene_at60{false};
std::atomic<uint64_t> g_scene_start_us{0};
std::atomic<bool> g_auto_off{false};  // a PC that cannot hold 60 in scenes: they stay at the game's 30
PPCContext g_drop_ctx;                 // registers at the last "drop to 30" call (to run it later)
uint8_t* g_drop_base = nullptr;
uint64_t NowUsEarly() { return wwe2k15::NowUs(); }
}  // namespace

// True while an entrance/victory scene that the game wants at 30 runs at 60 instead.
bool Wwe2k15SceneAt60() { return g_scene_at60.load(std::memory_order_relaxed); }

extern "C" REX_FUNC(sub_82AB1668) {
  const uint64_t mode = REX_LOAD_U32(0x838DC7B0);
  if (REXCVAR_GET(wwe2k15_60fps) && !g_auto_off.load(std::memory_order_relaxed) &&
      !wwe2k15::step30::g_on.load(std::memory_order_relaxed)) {
    g_drop_ctx = ctx;
    g_drop_base = base;
    g_scene_at60.store(true, std::memory_order_relaxed);
    g_scene_start_us.store(NowUsEarly(), std::memory_order_relaxed);
    REXLOG_INFO("[fps] game drops to 30 (mode {}): kept at 60", mode);
    return;
  }
  g_scene_start_us.store(NowUsEarly(), std::memory_order_relaxed);
  REXLOG_INFO("[fps] game drops to 30 (mode {})", mode);
  wwe2k15::step30::Wrap(__imp__sub_82AB1668, ctx, base);
}

extern "C" REX_FUNC(sub_82AB1708) {
  const uint64_t start = g_scene_start_us.exchange(0, std::memory_order_relaxed);
  if (start) REXLOG_INFO("[fps] back to 60 after {:.2f} s", (NowUsEarly() - start) / 1e6);
  g_scene_at60.store(false, std::memory_order_relaxed);
  wwe2k15::step30::Wrap(__imp__sub_82AB1708, ctx, base);
}
// D3D present (sub_82BADF20, base sub_82BA5FA8). Every 5 s, log the guest frame pacing:
// frames, mean/max time between presents, share of it spent waiting for the GPU ring, and
// the fps the title's timing struct asks for.
namespace {
uint64_t NowUs() { return wwe2k15::NowUs(); }
// Called once per present. While a scene runs at 60 instead of the game's 30, if the average frame time stays
// above 26 ms for two windows of 90 frames the PC cannot hold it (the game's logic follows the frame rate, so
// the scene would play in slow motion): run the game's own "drop to 30" now and keep it for the session.
void SceneWatch(uint64_t now) {
  static uint64_t last = 0, sum = 0;
  static uint32_t frames = 0, slow = 0;
  if (!g_scene_at60.load(std::memory_order_relaxed)) {
    last = 0, sum = 0, frames = 0, slow = 0;
    return;
  }
  if (last) {
    sum += now - last;
    ++frames;
  }
  last = now;
  if (frames < 90) return;
  const double avg_ms = sum / 1000.0 / frames;
  sum = 0, frames = 0;
  slow = avg_ms > REXCVAR_GET(wwe2k15_60fps_auto_ms) ? slow + 1 : 0;
  if (slow < 2 || !REXCVAR_GET(wwe2k15_60fps_auto) || !g_drop_base) return;
  g_scene_at60.store(false, std::memory_order_relaxed);
  g_auto_off.store(true, std::memory_order_relaxed);
  PPCContext c = g_drop_ctx;
  __imp__sub_82AB1668(c, g_drop_base);
  REXLOG_INFO("[fps] scene cannot hold 60 fps ({:.1f} ms per frame): the game's 30 fps is used for scenes from now on",
              avg_ms);
}
struct PaceStats {
  uint64_t window_start = 0, last = 0, max_gap = 0, wait_at_start = 0;
  uint32_t frames = 0;
} g_pace;
struct Counter {
  uint64_t start = 0, worst = 0;
  uint32_t frames = 0;
} g_counter;
}  // namespace

// Called once per guest present: by the Swap hook below, or in wwe2k15_native.exe (where re:Blue
// overrides D3DDevice_Swap itself) from the renderer's per-frame tick (Wwe12DisplayOnFrame). `base`
// is null there: the title fps read from guest memory is then left out of the log line.
void Wwe2k15RingLockOnPresent();  // render_wait_hook.cpp (issue #33)
#if defined(__ANDROID__)
void Wwe2k15AndroidOnPresent();  // android_frame.h: frame pacing, performance hint (phone only)
#endif

void Wwe2k15OnGuestPresent(uint8_t* base) {
  Wwe2k15RingLockOnPresent();
  g_wwe2k15_present_count.fetch_add(1, std::memory_order_relaxed);
  const uint64_t now = NowUs();
  SceneWatch(now);
  if (!g_scene_at60.load(std::memory_order_relaxed)) wwe2k15::step30::OnPresent(base, now);
  Counter& c = g_counter;
  if (!c.start) {
    c.start = now;
  } else {
    ++c.frames;
    if (g_pace.last) c.worst = std::max(c.worst, now - g_pace.last);
    if (now - c.start >= 1000000) {
      g_wwe2k15_fps_x10.store(uint32_t(c.frames * 1e7 / double(now - c.start)));
      g_wwe2k15_worst_frame_us.store(uint32_t(c.worst));
      c = Counter{};
      c.start = now;
    }
  }
  PaceStats& p = g_pace;
  if (!p.window_start) {
    p.window_start = p.last = now;
    p.wait_at_start = g_wwe2k15_gpu_wait_us.load();
  } else {
    p.max_gap = std::max(p.max_gap, now - p.last);
    p.last = now;
    ++p.frames;
    const uint64_t span = now - p.window_start;
    if (span >= 5000000) {
      const uint64_t wait = g_wwe2k15_gpu_wait_us.load() - p.wait_at_start;
      REXLOG_INFO("Guest pacing: {} frames in {:.1f} s ({:.1f} fps), max gap {:.1f} ms, "
                  "GPU-ring wait {:.0f}%, title fps {}",
                  p.frames, span / 1e6, p.frames * 1e6 / span, p.max_gap / 1000.0,
                  100.0 * wait / span, base ? REX_LOAD_U32(0x838DC7B0) : 0u);
      p = PaceStats{};
      p.window_start = p.last = now;
      p.wait_at_start = g_wwe2k15_gpu_wait_us.load();
    }
  }
#if defined(__ANDROID__)
  Wwe2k15AndroidOnPresent();
#endif
}

#if !defined(SVR_NATIVE_RENDERER)
extern "C" REX_FUNC(sub_82BADF20) {
  Wwe2k15OnGuestPresent(base);
  __imp__sub_82BADF20(ctx, base);
}
#endif  // !SVR_NATIVE_RENDERER
