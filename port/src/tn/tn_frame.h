// The one piece of the WWE 2K17 port's frame_stats.h the ported D3D layer reads: the guest frame counter.
// wwe2k15's OnPresent (android_frame.h) stores it every presented frame.
#pragma once
#include <atomic>
#include <cstdint>

namespace wwe2k14 {
inline std::atomic<uint64_t> g_guest_frame{0};
}  // namespace wwe2k14
