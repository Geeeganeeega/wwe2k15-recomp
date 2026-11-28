// wwe2k15 Android - renderer settings. All of them come from the WWE 2K14 port (Jarrodfeng/wwe2k14-recomp, BSD
// 3-Clause): its Android defaults (src/wwe2k14_app.h, Session 63-95) and its NATIVE renderer's list
// (src/gfx_settings.h NativeRendererFlags). They are SDK settings (Jarrod's SDK patches), not tied to WWE 2K14's code.
//
//   --wwe2k15_renderer=1   NATIVE (default): Jarrod's native renderer - truth frames without EDRAM, the host render-target
//                          path (Adreno has no fragment shader interlock), lean draws, direct resolves / texture loads.
//   --wwe2k15_renderer=0   STANDARD: the SDK's Xenos emulation with only the phone basics (the safe fallback).
//
// Left out of the NATIVE list (WWE 2K14 content or its TRUE NATIVE renderer, which hooks 2K14's own D3D functions):
//   gpu_native_shader_swap: 2K14 post-effect shader copies (2K14 shader hashes)
//   gpu_native_owned_frame: needs TRUE NATIVE (2K14 d3d_owned, game-specific)
//   gpu_scene_crowd_cull: 2K14 crowd scene data
//   gpu_scene_crowd_thin: 2K14 crowd scene data
// WWE 2K15's own settings (the PC port's wwe2k15.toml; a config file beside the exe is not read on Android) are set
// for both. A setting given on the command line (the launcher's extra arguments) always wins.
#pragma once

#include <rex/cvar.h>
#include <rex/logging.h>

#include <string>
#include <utility>
#include <vector>

REXCVAR_DEFINE_INT32(wwe2k15_renderer, 1, "wwe2k15", "Android renderer: 1 NATIVE (WWE 2K14 port's), 0 STANDARD").range(0, 1);

namespace wwe2k15::gpu_profile {

using KV = std::pair<const char*, const char*>;

// The WWE 2K14 port's NATIVE renderer list (gfx_settings.h NativeRendererFlags), minus the items above.
inline const std::vector<KV>& NativeFlags() {
  static const std::vector<KV> f = {
      KV{"gpu_native", "2"}, KV{"gpu_fast_clear", "true"},
      KV{"gpu_native_truth", "true"}, KV{"gpu_native_half2_skip_gpr_draws", "true"},
      KV{"gpu_native_half2", "2"}, KV{"gpu_native_lean_draws", "true"},
      // (not gpu_native_trust_geometry_pages: it keeps vertex pages valid without checking them, and WWE 2K15 rewrites
      // some geometry from the CPU every frame - ring ropes and more - which then drew stale, stretched across the screen)
      KV{"gpu_native_truth_skip_writeback", "true"},
      KV{"vulkan_merge_adjacent_barriers", "true"}, KV{"gpu_native_gpu_timers", "false"},
      KV{"gpu_native_truth_direct_resolve_scaled", "true"}, KV{"gpu_native_truth_fill_direct", "true"},
      KV{"vulkan_texture_load_direct_unscaled", "true"}, KV{"vulkan_texture_load_direct_unscaled_any_page", "true"},
      KV{"gpu_native_truth_direct_resolve_scaled_full", "true"}, KV{"gpu_native_truth_direct_resolve_scaled_texture", "true"},
      KV{"gpu_native_truth_defer_dest_use", "true"}, KV{"gpu_native_truth_texture_fill_scaled", "true"},
      KV{"gpu_native_truth_texture_fill_scaled_kinds", "32"}, KV{"gpu_native_truth_batch_resolves", "true"},
      KV{"gpu_native_truth_batch_rename", "true"}, KV{"gpu_native_truth_lazy_pass_deps", "true"},
      KV{"gpu_native_truth_lazy_pass_style", "1"}, KV{"gpu_native_truth_rename_depth", "true"},
      KV{"vulkan_texture_sample_written_early", "true"}, KV{"gpu_native_lean_benign_writes", "true"},
      KV{"gpu_native_truth_skip_writeback_scaled", "true"}, KV{"gpu_native_truth_defer_dest_use_scaled", "true"},
      KV{"gpu_native_truth_batch_fills", "true"}, KV{"gpu_native_truth_direct_resolve_scaled_full_texture", "true"},
      KV{"gpu_native_truth_direct_resolve_full_1x", "true"}, KV{"vulkan_texture_native_storage_small", "true"},
      KV{"gpu_native_truth_fill_direct_small", "true"}, KV{"gpu_native_truth_fill_direct_depth", "true"},
      KV{"gpu_native_truth_direct_resolve_depth", "true"}, KV{"gpu_native_truth_direct_resolve_scaled_small", "true"},
      KV{"vulkan_uniform_pool_device_local", "true"}, KV{"vulkan_device_local_upload_min_heap_mb", "128"},
      KV{"gpu_native_truth_host_rt", "true"}, KV{"gpu_native_truth_pass_dont_care", "true"},
      KV{"gpu_native_truth_color_none", "true"},
      // (not gpu_native_vertex_endian_spec: on the WWE 2K16 Android port it caused stretched spikes near the camera, as
      // gpu_native_trust_geometry_pages did)
      KV{"gpu_native_ps_texture_spec", "true"}, KV{"gpu_native_ps_mul_mode", "2"},
      KV{"gpu_native_ps_implicit_lod", "true"}, KV{"gpu_native_fma", "3"},
      KV{"gpu_native_truth_drop_dead_resolves", "1"}, KV{"gpu_native_vs_mul_mode", "1"},
      KV{"gpu_native_vs_mul_fetch_finite", "true"}, KV{"gpu_native_ps_epilogue_spec", "true"},
      KV{"gpu_native_truth_skip_restores", "true"}, KV{"gpu_native_truth_skip_repeat_resolves", "true"},
      KV{"gpu_native_truth_fill_rename_check", "true"},
      // From the WWE 2K16 recomp's NATIVE list (src/native_renderer.cpp): submission and waiting settings of the SDK.
      KV{"vulkan_async_submit", "true"}, KV{"vulkan_submit_on_wait", "true"},
      KV{"gpu_type0_bulk_writes", "true"},  // (WWE '13 Android: register ranges in one copy, same results)
      // Crowd batching (crowd_batch.cpp) needs the repeat-draw merge: the crowd member shaders read their constants per
      // instance, so a group's members become instances of one host draw (as the WWE '13 Android port sets them).
      KV{"vulkan_constants_dynamic_offsets", "true"}, KV{"vulkan_repeat_draw_fast", "true"},
      KV{"vulkan_repeat_draw_merge", "true"},
      KV{"vulkan_repeat_draw_merge_shaders", "183CC64A4C4E96AC,5344BF064B17B7C2,D5E2FCC9F76BC40C,C1028A64EFCF8229"},
      // From the WWE 2K16 Android port (NATIVE / NATIVE+ lists; its 1.2.3 had the same pattern as our match logs: ~600
      // uploads a frame made mid-frame, each one ending a render pass, threads at 40-50 %, resolution changing nothing).
      // vulkan_upload_hoist 2 copies those uploads in a stream at the start of the submission instead (1.2.4: back to
      // 45-60 FPS on an Adreno 810, 30-50 passes a frame instead of ~245).
      KV{"vulkan_upload_hoist", "2"}, KV{"gpu_skip_noop_draws", "true"},
      // (not present_guest_frames_once: on this app the UI thread then repaints ~165,000 times a second, one big core at 99 %)
      KV{"vulkan_texture_load_eager", "true"}, KV{"vulkan_texture_load_direct_depth", "true"},
      // (gpu_native_frame: launcher switch, off by default - on the Poco F5 every Turnip run and one Qualcomm run ended in
      // "device lost" within a minute after it came in, and it gave no FPS there)
      // The 2K16 port's "CPU fast path, safe part" and its GPU-wait sleep (cooler than spinning)
      KV{"gpu_type0_block_split", "true"}, KV{"vulkan_reuse_texture_bindings", "true"},
      KV{"vulkan_sampler_param_cache", "true"}, KV{"vulkan_viewport_info_cache", "true"},
      KV{"gpu_request_textures_skip_unchanged", "true"}, KV{"gpu_wait_reg_mem_sleep_us", "50"},
      KV{"vulkan_deferred_descriptor_writes", "true"},  // (not its gpu_wait_reg_mem_spin: busy-waiting heats the phone)
      KV{"vulkan_disk_pipeline_cache", "true"}, KV{"vulkan_vertex_residency_cache_off", "true"},
  };
  return f;
}

inline void Apply() {
  const int renderer = REXCVAR_GET(wwe2k15_renderer);
  std::vector<KV> set = {
      // WWE 2K15 (PC port): must stay false (true showed stale textures); shaders compiled in the background; the
      // alpha test's tolerance.
      KV{"gpu_allow_invalid_fetch_constants", "false"}, KV{"async_shader_compilation", "true"},
      KV{"use_fuzzy_alpha_epsilon", "true"},
      // WWE 2K14 Android (wwe2k14_app.h): 720p unless the launcher picks more; Mali drivers have no vertex shader stores,
      // no line / point fill and (some) no geometry shader, none needed by the game.
      KV{"resolution_scale", "1"},
      KV{"vulkan_require_vertex_pipeline_stores_and_atomics", "false"}, KV{"vulkan_require_fill_mode_non_solid", "false"},
      KV{"vulkan_require_geometry_shader", "false"},
      // WWE 2K14 Session 74: resolves read back in one batch, small one-off copies (16 KB cube faces) on their own.
      KV{"readback_resolve_batch", "true"}, KV{"readback_resolve_batch_min_bytes", "16385"},
      // Less log writing: the native renderer reports every 60 frames otherwise.
      KV{"gpu_native_report_frames", "1800"},
  };
  if (renderer == 1) {
    const auto& n = NativeFlags();
    set.insert(set.end(), n.begin(), n.end());
  }
  int applied = 0;
  std::string failed, kept;
  for (const auto& [name, value] : set) {
    if (rex::cvar::GetFlagSource(name) != rex::cvar::Source::kDefault) {
      kept += std::string(" ") + name;  // set on the command line: left alone
    } else if (rex::cvar::SetFlagByName(name, value)) {
      ++applied;
    } else {
      failed += std::string(" ") + name;  // not a setting of this SDK build
    }
  }
  if (!failed.empty()) REXLOG_WARN("[wwe2k15][gpu] not applied (unknown or rejected):{}", failed);
  if (!kept.empty()) REXLOG_INFO("[wwe2k15][gpu] left as set on the command line:{}", kept);
  REXLOG_INFO("[wwe2k15][gpu] renderer {}: {} settings applied; gpu_native={} truth={} host_rt={}",
              renderer == 1 ? "NATIVE (WWE 2K14 port)" : "STANDARD", applied, rex::cvar::GetFlagByName("gpu_native"),
              rex::cvar::GetFlagByName("gpu_native_truth"), rex::cvar::GetFlagByName("gpu_native_truth_host_rt"));
}

}  // namespace wwe2k15::gpu_profile
