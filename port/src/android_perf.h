// wwe2k15 - Android CPU placement and frame pacing options (set from the launcher).
//   --wwe2k15_big_cores=true     run the game's threads (the guest CPU, the GPU command thread, ...) on the phone's
//                                faster CPU cores only: the little cores are left to Android and the audio
//   --wwe2k15_fps_limit=0|30|60  hold the guest's frames to at most that many per second (0 = the game's own pace)
#pragma once

#include <rex/cvar.h>
#include <rex/logging.h>

#include "thread_stats.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <map>
#include <unistd.h>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

#if defined(__ANDROID__)
#include <sched.h>
#endif

REXCVAR_DEFINE_BOOL(wwe2k15_big_cores, true, "wwe2k15", "Android: keep the game's threads on the faster CPU cores");
REXCVAR_DEFINE_INT32(wwe2k15_fps_limit, 0, "wwe2k15", "Frame limit: 0 = the game's own pace, 30 or 60");
// 0 = smooth (the even-rate cap below), 1 = as fast as possible (only the 60 limit): more frames, uneven timing.
REXCVAR_DEFINE_INT32(wwe2k15_pacing, 0, "wwe2k15", "Frame pacing: 0 smooth (even rates: 60 / 40 / 30), 1 as fast as possible");

namespace wwe2k15::perf {

// Thermal guard: while the phone's thermal headroom (Android's forecast: 1.0 = throttling) stays high, frames are held
// to 30 per second; back to normal once it has cooled. Read once a second (the system wants at most one call a second).
inline std::atomic<bool> g_thermal_cap{false};
inline std::atomic<float> g_thermal_headroom{-1.f};

// What LimitFrame decided, for the "[wwe2k15][fps]" log line (android_frame.h).
inline std::atomic<int> g_even_rate{60};
inline std::atomic<float> g_work_p50_ms{0}, g_work_p90_ms{0};

// Called on the thread that later creates the game's threads (they inherit its CPU mask).
inline void PlaceOnBigCores() {
#if defined(__ANDROID__)
  if (!REXCVAR_GET(wwe2k15_big_cores)) return;
  const unsigned n = std::thread::hardware_concurrency();
  std::vector<long> freq(n, 0);
  long top = 0;
  for (unsigned i = 0; i < n; ++i) {
    char path[96];
    std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%u/cpufreq/cpuinfo_max_freq", i);
    if (FILE* f = std::fopen(path, "r")) {
      long v = 0;
      if (std::fscanf(f, "%ld", &v) == 1) freq[i] = v;
      std::fclose(f);
    }
    top = std::max(top, freq[i]);
  }
  if (top <= 0) {
    REXLOG_INFO("[wwe2k15][cpu] core speeds not readable: threads left where Android puts them");
    return;
  }
  cpu_set_t set;
  CPU_ZERO(&set);
  int used = 0;
  std::string list;
  for (unsigned i = 0; i < n && i < CPU_SETSIZE; ++i) {
    if (freq[i] * 100 >= top * 80) {  // within 80% of the fastest core: the big and prime cores
      CPU_SET(i, &set);
      ++used;
      list += std::to_string(i) + " ";
    }
  }
  if (used == 0 || used == int(n)) {
    REXLOG_INFO("[wwe2k15][cpu] all {} cores are alike: nothing to pin", n);
    return;
  }
  const int r = sched_setaffinity(0, sizeof(set), &set);
  REXLOG_INFO("[wwe2k15][cpu] game threads on the {} fastest cores ({}): {}", used, list, r == 0 ? "ok" : "failed");
#endif
}

#if defined(__ANDROID__)
// Thread placement: the 3 busiest threads each get a core of their own (the fastest core first), every other thread
// is kept off those cores. In the logs the game's main thread hopped between the prime core and the slower big cores
// and shared them with the GPU threads; a thread alone on the prime core runs measurably faster.
inline void PlacerLoop() {
  const unsigned n = std::thread::hardware_concurrency();
  std::vector<long> freq(n, 0);
  for (unsigned i = 0; i < n; ++i) {
    char path[96];
    std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%u/cpufreq/cpuinfo_max_freq", i);
    if (FILE* f = std::fopen(path, "r")) {
      long v = 0;
      if (std::fscanf(f, "%ld", &v) == 1) freq[i] = v;
      std::fclose(f);
    }
  }
  if (n == 0) return;
  std::vector<int> order;  // fastest first (higher core number first on a tie: the prime core is the last one)
  for (unsigned i = 0; i < n; ++i) order.push_back(int(i));
  std::stable_sort(order.begin(), order.end(), [&](int a, int b) { return freq[a] != freq[b] ? freq[a] > freq[b] : a > b; });
  long top = freq[order[0]];
  int bigs = 0;
  for (unsigned i = 0; i < n; ++i) if (freq[i] * 100 >= top * 80) ++bigs;
  const int dedicated = std::min(3, bigs - 1);
  if (top <= 0 || dedicated < 1) return;
  const pid_t self = gettid();
  std::map<int, uint64_t> last;
  std::map<int, int> placed;  // tid -> core it was last given (-1 = the shared set)
  auto last_time = std::chrono::steady_clock::now();
  bool logged = false;
  for (;;) {
    std::this_thread::sleep_for(std::chrono::seconds(2));
    auto cur = threadstats::Read();
    auto now = std::chrono::steady_clock::now();
    const double secs = std::chrono::duration<double>(now - last_time).count();
    last_time = now;
    struct Row { double pct; int tid; std::string name; };
    std::vector<Row> rows;
    const double hz = double(sysconf(_SC_CLK_TCK));
    for (auto& [tid, t] : cur) {
      auto it = last.find(tid);
      const double pct = it == last.end() ? 0.0 : 100.0 * double(t.ticks - it->second) / hz / secs;
      if (tid != self && t.name.rfind("binder", 0) != 0 && pct >= 20.0) rows.push_back({pct, tid, t.name});
    }
    last.clear();
    for (auto& [tid, t] : cur) last[tid] = t.ticks;
    std::sort(rows.begin(), rows.end(), [](const Row& a, const Row& b) { return a.pct > b.pct; });
    std::map<int, int> want;
    std::map<int, double> pct_of;
    for (auto& r : rows) pct_of[r.tid] = r.pct;
    cpu_set_t shared, shared_big;  // everything else: any core but the dedicated ones; the fairly busy ones: big cores only
    CPU_ZERO(&shared);
    CPU_ZERO(&shared_big);
    for (unsigned i = 0; i < n && i < CPU_SETSIZE; ++i) {
      CPU_SET(i, &shared);
      if (freq[i] * 100 >= top * 80) CPU_SET(i, &shared_big);
    }
    for (int k = 0; k < dedicated && k < int(rows.size()); ++k) {
      want[rows[k].tid] = order[k];
      CPU_CLR(order[k], &shared);
      CPU_CLR(order[k], &shared_big);
    }
    if (CPU_COUNT(&shared_big) == 0) shared_big = shared;
    for (auto& [tid, t] : cur) {
      if (tid == self || t.name.rfind("binder", 0) == 0) continue;
      auto w = want.find(tid);
      const int core = w == want.end() ? -1 : w->second;
      const bool fairly_busy = pct_of.count(tid) && pct_of[tid] >= 10.0;
      const int tag = core >= 0 ? core : (fairly_busy ? -2 : -1);  // -2 = big cores minus the dedicated ones
      auto pl = placed.find(tid);
      if (pl != placed.end() && pl->second == tag && core >= 0) continue;
      cpu_set_t one;
      CPU_ZERO(&one);
      if (core >= 0) CPU_SET(core, &one);
      sched_setaffinity(tid, sizeof(cpu_set_t), core >= 0 ? &one : (fairly_busy ? &shared_big : &shared));
      placed[tid] = tag;
    }
    if (!logged && !want.empty()) {
      logged = true;
      std::string m;
      for (int k = 0; k < dedicated && k < int(rows.size()); ++k)
        m += " [" + rows[k].name + " -> core " + std::to_string(order[k]) + "]";
      REXLOG_INFO("[wwe2k15][cpu] busiest threads placed on their own cores:{}", m);
    }
  }
}

inline void StartThreadPlacer() {
  if (!REXCVAR_GET(wwe2k15_big_cores)) return;
  std::thread(PlacerLoop).detach();
}
#else
inline void StartThreadPlacer() {}
#endif

REXCVAR_DEFINE_INT32(wwe2k15_display_hz, 60, "wwe2k15", "The screen's refresh rate (the launcher passes it): frame pacing aims at whole divisions of it");

// Every guest frame (VdSwap): hold to the frame limit. 30 / 60 as chosen; "the game's own pace" is still capped at
// 60 (menus and loading screens ran at 150-290 frames per second).
// game_fps: the game's own frame-rate mode (60 in matches and menus, 30 in entrances and cutscenes); the cap follows it.
//
// Smoothness: a game that renders 40-50 FPS on a 60 or 120 Hz screen shows frames for uneven times (2 refreshes, then
// 3, then 2...) and moves in jerks even though the average is fine. So below the limit the cap is not "as fast as
// possible" but the highest EVEN rate (the refresh rate divided by a whole number: 60, 40, 30, 24, 20 on a 120 Hz
// screen) that 90% of the frames' own work fits into. Every frame then lasts the same time. Down at once, up after a
// steady stretch.
inline void LimitFrame(uint32_t game_fps = 60) {
  static const int user = [] { const int l = REXCVAR_GET(wwe2k15_fps_limit); return l == 30 ? 30 : 60; }();
  const bool hot = g_thermal_cap.load(std::memory_order_relaxed);
  const int limit = (user == 30 || game_fps == 30 || hot) ? 30 : 60;
  using clock = std::chrono::steady_clock;
  static clock::time_point next = clock::now(), last_entry = clock::now();
  static double waited = 0;                 // seconds the last call slept
  static std::vector<double> work;          // each frame's own work time, seconds
  static double target = 60;                // the even rate in force
  static int up_votes = 0, since_eval = 0;
  const auto now = clock::now();
  const double interval = std::chrono::duration<double>(now - last_entry).count();
  last_entry = now;
  const double own = std::max(0.0, interval - waited);
  if (own < 0.5) {  // (a pause of half a second or more is a loading screen, not a frame)
    work.push_back(own);
    if (work.size() > 90) work.erase(work.begin());
    if (++since_eval >= 30 && work.size() >= 60) {
      since_eval = 0;
      std::vector<double> sorted = work;
      std::sort(sorted.begin(), sorted.end());
      const double p90 = sorted[sorted.size() * 9 / 10];
      const double p50 = sorted[sorted.size() / 2];
      g_work_p50_ms.store(float(p50 * 1000), std::memory_order_relaxed);
      g_work_p90_ms.store(float(p90 * 1000), std::memory_order_relaxed);
      const double hz = std::max(30, REXCVAR_GET(wwe2k15_display_hz));
      double best = 20;
      for (int n = 1; n <= 8; ++n) {
        const double rate = hz / n;
        if (rate > 60.5 || rate < 20) continue;
        // the highest even rate most frames fit into: the median with 10 % slack, and 90 % of frames within 1.4x
        // (single slow frames, a loading hitch, no longer pull 60 down to 40)
        if (1.0 / rate >= p50 * 1.10 && 1.0 / rate * 1.4 >= p90) { best = rate; break; }
      }
      if (best < target) { target = best; up_votes = 0; }
      else if (best > target && target < 60 && ++up_votes >= 2) { target = best; up_votes = 0; }
      else if (best > target && ++up_votes >= 4) { target = best; up_votes = 0; }
      else if (best <= target) up_votes = 0;
    }
  }
  double rate = REXCVAR_GET(wwe2k15_pacing) == 1 ? double(limit) : std::min<double>(limit, target);
  g_even_rate.store(int(rate + 0.5), std::memory_order_relaxed);
  if (game_fps == 30 || user == 30 || hot) rate = 30;
  const auto period = std::chrono::nanoseconds(static_cast<long long>(1e9 / rate));
  waited = 0;
  if (now < next) {
    const auto spin = std::chrono::microseconds(1500);
    if (next - now > spin) std::this_thread::sleep_for(next - now - spin);
    while (clock::now() < next) std::this_thread::yield();
    waited = std::chrono::duration<double>(clock::now() - now).count();
    next += period;
  } else {
    next = now + period;  // late: no catching up with a burst of frames
  }
}

}  // namespace wwe2k15::perf
