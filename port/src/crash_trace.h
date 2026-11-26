// wwe2k15: on a read or write at a tiny guest address (a null pointer plus an offset: the game's "Unhandled guest access
// violation" crashes), log where in the recompiled code it happened, then hand the signal on to the SDK's own handler
// (which does its usual work and ends the game). The log lines look like
//   [wwe2k15][crash] fault at host 0x100000...: #0 sub_82A63DA0+0x1c  #1 sub_82985850+0x80 ...
// so the guest function (sub_<guest address>) and its callers show which part of the game went wrong.
// Frames are found by walking the frame-pointer chain; names come from dladdr (the recompiled functions are exported).
#pragma once

#if defined(__ANDROID__)

#include <dlfcn.h>
#include <signal.h>
#include <ucontext.h>

#include <cstdint>
#include <cstdio>
#include <string>

#include <rex/logging.h>

namespace wwe2k15::crash_trace {

inline struct sigaction g_old;
inline bool g_installed = false;
inline volatile int g_logged = 0;
inline uintptr_t g_guest_base = 0x100000000ull;  // the guest arena's start (the log's "virtual base")

inline void Handler(int sig, siginfo_t* info, void* ctx) {
  const uintptr_t addr = reinterpret_cast<uintptr_t>(info->si_addr);
  if (addr >= g_guest_base && addr < g_guest_base + 0x10000 && g_logged < 4) {
    ++g_logged;
    auto* uc = static_cast<ucontext_t*>(ctx);
    std::string out;
    char buf[200];
    uintptr_t pc = uc->uc_mcontext.pc;
    uintptr_t fp = uc->uc_mcontext.regs[29];
    uintptr_t lr = uc->uc_mcontext.regs[30];
    auto name = [&](uintptr_t p) {
      Dl_info di;
      if (dladdr(reinterpret_cast<void*>(p), &di) && di.dli_sname) {
        std::snprintf(buf, sizeof(buf), "%s+0x%lx", di.dli_sname,
                      static_cast<unsigned long>(p - reinterpret_cast<uintptr_t>(di.dli_saddr)));
      } else if (dladdr(reinterpret_cast<void*>(p), &di) && di.dli_fname) {
        std::snprintf(buf, sizeof(buf), "%s@0x%lx", di.dli_fname,
                      static_cast<unsigned long>(p - reinterpret_cast<uintptr_t>(di.dli_fbase)));
      } else {
        std::snprintf(buf, sizeof(buf), "0x%lx", static_cast<unsigned long>(p));
      }
      return std::string(buf);
    };
    out += "#0 " + name(pc) + "  lr " + name(lr);
    for (int i = 1; i < 14 && fp > 0x1000 && (fp & 7) == 0; ++i) {
      auto* frame = reinterpret_cast<uintptr_t*>(fp);
      uintptr_t ret = frame[1];
      uintptr_t next = frame[0];
      if (!ret) break;
      out += "  #" + std::to_string(i) + " " + name(ret);
      if (next <= fp) break;
      fp = next;
    }
    REXLOG_ERROR("[wwe2k15][crash] fault at host 0x{:X} ({} of the guest's low memory): {}", addr,
                 info->si_code, out);
  }
  // On to the SDK's handler (or the default action: restore it and return, the fault happens again and ends the process).
  if (g_old.sa_flags & SA_SIGINFO) {
    if (g_old.sa_sigaction) {
      g_old.sa_sigaction(sig, info, ctx);
      return;
    }
  } else if (g_old.sa_handler != SIG_DFL && g_old.sa_handler != SIG_IGN) {
    g_old.sa_handler(sig);
    return;
  }
  signal(sig, SIG_DFL);
}

inline void Install() {
  if (g_installed) return;
  struct sigaction sa = {};
  sa.sa_sigaction = Handler;
  sa.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_NODEFER;
  sigemptyset(&sa.sa_mask);
  if (sigaction(SIGSEGV, &sa, &g_old) == 0) g_installed = true;
}

}  // namespace wwe2k15::crash_trace

#endif  // __ANDROID__
