// wwe2k15 - small time / thread helpers that work on Windows and on Android (Linux).
// The PC port used QueryPerformanceCounter / GetTickCount64 / GetCurrentThreadId directly; the Android build has none
// of them. std::chrono::steady_clock is monotonic on both.
#pragma once

#include <chrono>
#include <cstdint>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <sched.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

namespace wwe2k15 {

inline uint64_t NowUs() {
  return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(
                                   std::chrono::steady_clock::now().time_since_epoch())
                                   .count());
}

inline uint64_t NowMs() { return NowUs() / 1000; }

inline uint32_t ThisThreadId() {
#if defined(_WIN32)
  return static_cast<uint32_t>(GetCurrentThreadId());
#else
  return static_cast<uint32_t>(syscall(SYS_gettid));
#endif
}

// One short CPU pause inside a spin loop.
inline void CpuRelax() {
#if defined(__x86_64__) || defined(_M_X64)
  __builtin_ia32_pause();
#elif defined(__aarch64__)
  asm volatile("yield" ::: "memory");
#endif
}

// Give the core away for a moment (the PC port's 250 us high-resolution timer slice).
inline void SleepSliceUs(uint32_t us) {
#if defined(_WIN32)
  (void)us;
  SwitchToThread();
#else
  struct timespec ts{0, static_cast<long>(us) * 1000L};
  nanosleep(&ts, nullptr);
#endif
}

}  // namespace wwe2k15
