// wwe2k15 Android: what runs once per guest frame on the phone, and the frame counter the launcher's FPS overlay
// (FpsOverlay.java) reads. Included once, from wwe2k15_app.h (main.cpp): the headers below define cvars.
//
// framerate_hook.cpp already sees every guest present (Wwe2k15OnGuestPresent, the D3D Swap hook) and counts it in
// g_wwe2k15_present_count; on Android it then calls Wwe2k15AndroidOnPresent below. The pieces come from the user's
// WWE 2K17 Android port (frame_counter.h there), minus the parts tied to WWE 2K17's memory layout.
#pragma once

#if defined(__ANDROID__)

#include <jni.h>

#include <atomic>
#include <chrono>
#include <cstdint>

#include <rex/logging.h>
#include <rex/memory/utils.h>
#include <rex/system/kernel_state.h>

#include "android_perf.h"
#include "android_perf_hint.h"
#include "thread_stats.h"
#include "dynamic_res.h"

extern std::atomic<uint64_t> g_wwe2k15_present_count;  // framerate_hook.cpp
bool Wwe2k15SceneAt60();                                // framerate_hook.cpp
uint32_t Wwe2k15StepEffectiveRate();
void Wwe2k15ResolveCoherencyOnFrame();                // resolve_coherency.cpp                    // framerate_hook.cpp: 30 while the real-time 30 step is on

#include <cmath>
#include <dlfcn.h>

REXCVAR_DEFINE_BOOL(wwe2k15_thermal_guard, true, "wwe2k15",
                    "Hold the game to 30 FPS while Android reports the phone is close to throttling (thermal headroom)");

namespace wwe2k15::android_frame {

// The phone's thermal headroom (ADPF, Android 11+; looked up in libandroid.so, so older phones just get no reading).
// Above 0.95 for 5 s: 30 FPS cap on (android_perf.h LimitFrame). Below 0.75 for 20 s: off again.
inline void ThermalTick() {
  if (!REXCVAR_GET(wwe2k15_thermal_guard)) return;
  using Acquire = void* (*)();
  using Headroom = float (*)(void*, int);
  static void* mgr = nullptr;
  static Headroom headroom = nullptr;
  static bool tried = false;
  if (!tried) {
    tried = true;
    if (void* lib = dlopen("libandroid.so", RTLD_NOW)) {
      auto acquire = reinterpret_cast<Acquire>(dlsym(lib, "AThermal_acquireManager"));
      headroom = reinterpret_cast<Headroom>(dlsym(lib, "AThermal_getThermalHeadroom"));
      if (acquire && headroom) mgr = acquire();
    }
    REXLOG_INFO("[wwe2k15][thermal] guard {}", mgr ? "on (reads the phone's thermal headroom)" : "unavailable on this phone");
  }
  if (!mgr) return;
  static auto last = std::chrono::steady_clock::now() - std::chrono::seconds(2);
  const auto now = std::chrono::steady_clock::now();
  if (now - last < std::chrono::seconds(1)) return;
  last = now;
  const float h = headroom(mgr, 0);
  if (std::isnan(h)) return;
  wwe2k15::perf::g_thermal_headroom.store(h, std::memory_order_relaxed);
  static int hot_s = 0, cool_s = 0;
  hot_s = h > 0.95f ? hot_s + 1 : 0;
  cool_s = h < 0.75f ? cool_s + 1 : 0;
  bool cap = wwe2k15::perf::g_thermal_cap.load(std::memory_order_relaxed);
  if (!cap && hot_s >= 5) {
    wwe2k15::perf::g_thermal_cap.store(true, std::memory_order_relaxed);
    REXLOG_INFO("[wwe2k15][thermal] phone is hot (headroom {:.2f}): 30 FPS until it cools down", h);
  } else if (cap && cool_s >= 20) {
    wwe2k15::perf::g_thermal_cap.store(false, std::memory_order_relaxed);
    REXLOG_INFO("[wwe2k15][thermal] cooled down (headroom {:.2f}): normal frame rate again", h);
  }
}


// The game's timing struct (framerate_hook.cpp, TU4): +8 = the fps the game runs at (60, or 30 in entrances and
// victory scenes unless wwe2k15_60fps keeps them at 60).
inline constexpr uint32_t kTimingStruct = 0x838DC7A8u;

inline uint32_t GameFps() {
  auto* memory = REX_KERNEL_MEMORY();
  if (!memory) return 60;
  const uint32_t fps =
      rex::memory::load_and_swap<uint32_t>(memory->TranslateVirtual<const uint8_t*>(kTimingStruct + 8));
  if (const uint32_t step = Wwe2k15StepEffectiveRate()) return step;  // real-time 30 step (word kept at 60)
  if (Wwe2k15SceneAt60()) return 60;
  return fps == 30 || fps == 25 ? 30 : 60;
}

inline void OnPresent() {
  const uint64_t n = g_wwe2k15_present_count.load(std::memory_order_relaxed);
  if (n == 1 || n % 1800 == 0) REXLOG_INFO("[wwe2k15] guest frame {}", n);
  if (n % 900 == 0) wwe2k15::threadstats::Report(n);
  const uint32_t game_fps = GameFps();
  {  // the phone's performance hint (android_perf_hint.h): tell the CPU governor the frame deadline
    static auto last = std::chrono::steady_clock::now();
    const auto now = std::chrono::steady_clock::now();
    wwe2k15::perf_hint::g_target_fps.store(int(game_fps), std::memory_order_relaxed);
    if (n > 60) wwe2k15::perf_hint::g_perf_hint.OnFrame(std::chrono::duration<double, std::milli>(now - last).count());
    last = now;
  }
  Wwe2k15ResolveCoherencyOnFrame();
  ThermalTick();
  wwe2k15::perf::LimitFrame(game_fps);
  wwe2k15::dynres::g_controller.OnFrame(wwe2k15::perf::g_last_work_ms.load(std::memory_order_relaxed),
                                        1000.0 / double(game_fps ? game_fps : 60));
  {  // every 5 s: what the frame rate is and why ("[wwe2k15][fps]" in the log, for FPS reports)
    static auto t0 = std::chrono::steady_clock::now();
    static uint64_t n0 = n;
    const auto now = std::chrono::steady_clock::now();
    const double secs = std::chrono::duration<double>(now - t0).count();
    if (secs >= 5.0) {
      REXLOG_INFO("[wwe2k15][fps] {:.1f} fps | game mode {} | real-speed 30 {} | heat {:.2f}{} | pacing cap {} | frame work p50 {:.1f} ms p90 {:.1f} ms",
                  double(n - n0) / secs, game_fps, Wwe2k15StepEffectiveRate() ? "ON" : "off",
                  wwe2k15::perf::g_thermal_headroom.load(std::memory_order_relaxed),
                  wwe2k15::perf::g_thermal_cap.load(std::memory_order_relaxed) ? " (30 FPS cap)" : "",
                  wwe2k15::perf::g_even_rate.load(std::memory_order_relaxed),
                  wwe2k15::perf::g_work_p50_ms.load(std::memory_order_relaxed),
                  wwe2k15::perf::g_work_p90_ms.load(std::memory_order_relaxed));
      t0 = now, n0 = n;
    }
  }
}

}  // namespace wwe2k15::android_frame

void Wwe2k15AndroidOnPresent() { wwe2k15::android_frame::OnPresent(); }

extern "C" JNIEXPORT jlong JNICALL Java_io_github_xav12k_wwe2k15_WWE2K15Activity_nativeFrameCount(JNIEnv*, jclass) {
  static bool logged = false;
  if (!logged) {
    logged = true;
    REXLOG_INFO("[wwe2k15] loading screen is reading the frame counter");
  }
  return static_cast<jlong>(g_wwe2k15_present_count.load(std::memory_order_relaxed));
}

#endif  // __ANDROID__
