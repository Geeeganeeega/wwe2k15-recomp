// wwe2k15: every 900 frames, logs which threads of the process used the most CPU (and the core each last ran on),
// so a slow match can be traced to the thread that limits it. Reads /proc/self/task/<tid>/stat (Android / Linux).
#pragma once

#include <rex/logging.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <map>
#include <string>
#include <unistd.h>
#include <vector>

namespace wwe2k15::threadstats {

struct Sample {
  uint64_t ticks = 0;
  std::string name;
  int core = -1;
};

inline std::map<int, Sample> Read() {
  std::map<int, Sample> out;
  DIR* d = opendir("/proc/self/task");
  if (!d) return out;
  while (dirent* e = readdir(d)) {
    int tid = std::atoi(e->d_name);
    if (tid <= 0) continue;
    char path[64];
    std::snprintf(path, sizeof path, "/proc/self/task/%d/stat", tid);
    FILE* f = std::fopen(path, "r");
    if (!f) continue;
    char buf[1024];
    size_t n = std::fread(buf, 1, sizeof buf - 1, f);
    std::fclose(f);
    buf[n] = 0;
    char* open = std::strchr(buf, '(');
    char* close = std::strrchr(buf, ')');
    if (!open || !close) continue;
    Sample s;
    s.name.assign(open + 1, close);
    unsigned long long ut = 0, st = 0;
    int core = -1;
    // after ") ": state ppid pgrp session tty tpgid flags minflt cminflt majflt cmajflt utime stime ... processor (field 39)
    std::sscanf(close + 2, "%*c %*d %*d %*d %*d %*d %*u %*u %*u %*u %*u %llu %llu %*d %*d %*d %*d %*d %*d %*u %*u %*d %*u %*u %*u %*u %*u %*u %*u %*u %*u %*u %*u %*u %*u %*d %d", &ut, &st, &core);
    s.ticks = ut + st;
    s.core = core;
    out[tid] = s;
  }
  closedir(d);
  return out;
}

inline void Report(uint64_t frame) {
  static std::map<int, Sample> last;
  static auto last_time = std::chrono::steady_clock::now();
  auto now = std::chrono::steady_clock::now();
  auto cur = Read();
  double secs = std::chrono::duration<double>(now - last_time).count();
  if (!last.empty() && secs > 0.5) {
    struct Row { double pct; int tid; Sample s; };
    std::vector<Row> rows;
    double hz = double(sysconf(_SC_CLK_TCK));
    for (auto& [tid, s] : cur) {
      auto it = last.find(tid);
      uint64_t before = it == last.end() ? 0 : it->second.ticks;
      double pct = 100.0 * double(s.ticks - before) / hz / secs;
      if (pct >= 3.0) rows.push_back({pct, tid, s});
    }
    std::sort(rows.begin(), rows.end(), [](const Row& a, const Row& b) { return a.pct > b.pct; });
    std::string line;
    for (size_t i = 0; i < rows.size() && i < 10; ++i) {
      char tmp[96];
      std::snprintf(tmp, sizeof tmp, " [%s t%d core %d: %.0f%%]", rows[i].s.name.c_str(), rows[i].tid, rows[i].s.core, rows[i].pct);
      line += tmp;
    }
    REXLOG_INFO("[wwe2k15][threads] frame {} ({:.1f} s, {:.1f} fps): busiest threads (100% = one full core):{}", frame, secs,
                900.0 / secs, line);
  }
  last = std::move(cur);
  last_time = now;
}

}  // namespace wwe2k15::threadstats
