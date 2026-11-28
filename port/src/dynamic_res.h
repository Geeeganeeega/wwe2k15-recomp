// wwe2k15 Android: dynamic resolution, after the WWE 2K14 port's (Jarrodfeng/wwe2k14-recomp src/dynamic_res.h, BSD 3-Clause,
// docs/LICENSE_wwe2k14-recomp.txt; Session 73). When the frames' own work runs over the game's frame time for a couple of
// seconds, the NATIVE truth passes draw a step smaller (the SDK's gpu_native_truth_draw_height, which may change while the
// game runs; the screen keeps its size), and a step bigger again when there is time to spare. Steps: 100, 90, 80, 75, 67
// and 60 % of the chosen resolution's height (--wwe2k15_dynamic_res_min_percent sets the lowest).
//
// Differences from 2K14's: the time judged is each frame's own work (android_perf.h LimitFrame: the frame interval minus
// the pacing wait), so time to spare shows and a step up needs no blind probe; a step up that runs over still comes back
// down, and the next one waits twice as long (up to 2 min). A step down that does not make the work at least 5 % shorter
// means the CPU is what is slow: it is undone and no step down is tried for 30 s.
// --wwe2k15_dynamic_res=true turns it on (the launcher's switch). Logs "[wwe2k15][dynres]" lines.
#pragma once

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <string>

#include <rex/cvar.h>
#include <rex/logging.h>

REXCVAR_DEFINE_BOOL(wwe2k15_dynamic_res, false, "wwe2k15",
                    "Dynamic resolution: draw the 3D scene smaller while frames run late, bigger again with time to spare");
REXCVAR_DEFINE_INT32(wwe2k15_dynamic_res_min_percent, 60, "wwe2k15", "Dynamic resolution: the lowest step, % of the chosen height")
    .range(50, 100);

namespace wwe2k15::dynres {

class Controller {
 public:
  // Once per presented frame: work_ms = this frame's own work, target_ms = the game's frame time now (16.7 or 33.3).
  void OnFrame(double work_ms, double target_ms) {
    if (!REXCVAR_GET(wwe2k15_dynamic_res)) return;
    if (!started_ && !Start(target_ms)) return;
    if (!active_ || work_ms < 0.0) return;
    ++window_n_all_;
    if (work_ms > 4.0 * target_ms) {
      ++window_skipped_;  // a loading pause or a scene change, not the game's pace
    } else {
      window_ms_ += work_ms;
      ++window_n_;
    }
    target_ = target_ms;
    const auto now = std::chrono::steady_clock::now();
    if (now - window_start_ < std::chrono::seconds(1)) return;
    window_start_ = now;
    const bool loading = window_skipped_ * 4 > window_n_all_;
    const double avg = window_n_ ? window_ms_ / window_n_ : 0.0;
    window_ms_ = 0.0;
    window_n_ = window_n_all_ = window_skipped_ = 0;
    if (loading || avg <= 0.0) return;
    Window(avg, target_ms);
  }

 private:
  static constexpr double kSteps[] = {1.0, 0.9, 0.8, 0.75, 2.0 / 3.0, 0.6};
  static constexpr int kStepCount = int(sizeof(kSteps) / sizeof(kSteps[0]));
  static constexpr int kUpWaitStart = 5, kUpWaitMax = 120, kProbeWindows = 4;

  bool Start(double target_ms) {
    started_ = true;
    window_start_ = std::chrono::steady_clock::now();
    if (rex::cvar::GetFlagByName("gpu_native") != "2" || rex::cvar::GetFlagByName("gpu_native_truth") != "true") {
      REXLOG_INFO("[wwe2k15][dynres] off: needs the NATIVE renderer");
      return false;
    }
    int scale = 1, chosen = 0;
    try { scale = std::max(1, std::stoi(rex::cvar::GetFlagByName("resolution_scale"))); } catch (...) {}
    try { chosen = std::stoi(rex::cvar::GetFlagByName("gpu_native_truth_draw_height")); } catch (...) {}
    full_ = 720 * scale;
    base_ = chosen > 0 && chosen < full_ ? chosen : full_;
    chosen_flag_ = chosen;
    const int min_percent = REXCVAR_GET(wwe2k15_dynamic_res_min_percent);
    lowest_ = 0;
    while (lowest_ + 1 < kStepCount && kSteps[lowest_ + 1] * 100.0 >= min_percent - 0.01) ++lowest_;
    active_ = true;
    REXLOG_INFO("[wwe2k15][dynres] on: {} rows (of {}), steps down to {} %, target {:.1f} ms", base_, full_,
                int(std::lround(kSteps[lowest_] * 100.0)), target_ms);
    return true;
  }

  void Window(double avg, double target) {
    if (hold_ > 0) --hold_;
    if (cooldown_ > 0) {
      --cooldown_;
      return;
    }
    if (check_after_down_) {  // did the step down help?
      check_after_down_ = false;
      if (avg > before_down_ * 0.95) {
        REXLOG_INFO("[wwe2k15][dynres] {:.1f} ms after the step down vs {:.1f} before: not the GPU - back up, no step "
                    "down for 30 s", avg, before_down_);
        Apply(step_ - 1, avg);
        hold_ = 30;
        cooldown_ = 2;
        over_ = under_ = 0;
        return;
      }
    }
    if (probe_left_ > 0) --probe_left_;
    over_ = avg > target * 1.05 ? over_ + 1 : 0;
    under_ = avg < target * 0.80 ? under_ + 1 : 0;
    if (over_ >= 2 && step_ < lowest_ && hold_ == 0) {
      if (probe_left_ > 0) {  // the last step up ran over
        up_wait_ = std::min(up_wait_ * 2, kUpWaitMax);
        REXLOG_INFO("[wwe2k15][dynres] the step up ran over: back down, next try after {} s", up_wait_);
        probe_left_ = 0;
      }
      before_down_ = avg;
      check_after_down_ = true;
      Apply(step_ + 1, avg);
      cooldown_ = 1;
      over_ = under_ = 0;
    } else if (under_ >= up_wait_ && step_ > 0) {
      Apply(step_ - 1, avg);
      probe_left_ = kProbeWindows;
      cooldown_ = 1;
      over_ = under_ = 0;
    } else if (probe_left_ == 1 && over_ == 0) {
      up_wait_ = kUpWaitStart;  // the step up held
    }
  }

  int Height(int step) const {
    const int h = int(std::lround(base_ * kSteps[step] / 2.0)) * 2;
    return std::clamp(h, 2, full_);
  }

  void Apply(int step, double avg) {
    step = std::clamp(step, 0, lowest_);
    if (step == step_) return;
    const int old_rows = Height(step_);
    step_ = step;
    const int rows = Height(step_);
    const int flag = step_ == 0 ? chosen_flag_ : (rows >= full_ ? 0 : rows);
    rex::cvar::SetFlagByName("gpu_native_truth_draw_height", std::to_string(flag));
    REXLOG_INFO("[wwe2k15][dynres] {} -> {} rows ({} %; frame work {:.1f} ms, target {:.1f})", old_rows, rows,
                int(std::lround(kSteps[step_] * 100.0)), avg, target_);
  }

  bool started_ = false, active_ = false, check_after_down_ = false;
  int full_ = 720, base_ = 720, chosen_flag_ = 0, lowest_ = 0, step_ = 0;
  std::chrono::steady_clock::time_point window_start_{};
  double window_ms_ = 0.0, before_down_ = 0.0, target_ = 16.7;
  int window_n_ = 0, window_n_all_ = 0, window_skipped_ = 0;
  int over_ = 0, under_ = 0, cooldown_ = 0, hold_ = 0, probe_left_ = 0, up_wait_ = kUpWaitStart;
};

inline Controller g_controller;

}  // namespace wwe2k15::dynres
