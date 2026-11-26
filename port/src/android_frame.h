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

extern std::atomic<uint64_t> g_wwe2k15_present_count;  // framerate_hook.cpp
bool Wwe2k15SceneAt60();                                // framerate_hook.cpp

namespace wwe2k15::android_frame {

// The game's timing struct (framerate_hook.cpp, TU4): +8 = the fps the game runs at (60, or 30 in entrances and
// victory scenes unless wwe2k15_60fps keeps them at 60).
inline constexpr uint32_t kTimingStruct = 0x838DC7A8u;

inline uint32_t GameFps() {
  auto* memory = REX_KERNEL_MEMORY();
  if (!memory) return 60;
  const uint32_t fps =
      rex::memory::load_and_swap<uint32_t>(memory->TranslateVirtual<const uint8_t*>(kTimingStruct + 8));
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
  wwe2k15::perf::LimitFrame(game_fps);
}

}  // namespace wwe2k15::android_frame

void Wwe2k15AndroidOnPresent() { wwe2k15::android_frame::OnPresent(); }

extern "C" JNIEXPORT jlong JNICALL Java_io_github_xav12k_wwe2k15_GameActivity_nativeFrameCount(JNIEnv*, jclass) {
  static bool logged = false;
  if (!logged) {
    logged = true;
    REXLOG_INFO("[wwe2k15] FPS overlay is reading the frame counter");
  }
  return static_cast<jlong>(g_wwe2k15_present_count.load(std::memory_order_relaxed));
}

#endif  // __ANDROID__
