// wwe2k15 (from the user's WWE 2K17 Android port) - GPU setting profiles for Android phones (Adreno has no fragment shader interlock, so the SDK's default
// "accurate" render-target path is the wrong one there). The settings are an earlier port's Android defaults
// (wwe2k14_app.h / gfx_settings.h NativeRendererFlags, BSD 3-Clause), minus the parts tied
// to that game (its crowd shader tricks, its D3D-library draw skipping). All of them are SDK settings.
//
//   --wwe2k15_gpu_profile=0   SAFE      nothing set: the SDK's own defaults
//   --wwe2k15_gpu_profile=1   BALANCED  the native renderer on the host render-target path + the generic speed-ups
//   --wwe2k15_gpu_profile=2   FAST      BALANCED + the native renderer's resolve / texture-load shortcuts
// A setting given on the command line (extra arguments) always wins: only settings left at default are changed.
#pragma once

#include <rex/cvar.h>
#include <rex/logging.h>

#include <string>
#include <utility>
#include <algorithm>
#include <vector>

REXCVAR_DEFINE_INT32(wwe2k15_gpu_profile, 1, "wwe2k15", "Android GPU profile: 0 safe, 1 balanced, 2 fast");

namespace wwe2k15::gpu_profile {

inline void Apply() {
  // the Safe profile (0) is gone: it skipped draws with "invalid" texture types (missing geometry), so 0 acts as 1
  const int profile = std::max(1, int(REXCVAR_GET(wwe2k15_gpu_profile)));
  using KV = std::pair<const char*, const char*>;
  std::vector<KV> set;
  if (profile >= 1) {
    for (KV kv : {KV{"resolution_scale", "1"}, KV{"gpu_native", "2"}, KV{"gpu_fast_clear", "true"}, // WWE 2K15: must stay false (true showed stale textures on the PC port)
                  KV{"gpu_allow_invalid_fetch_constants", "false"},
                  // the PC port's wwe2k15.toml (a config file beside the exe is not read on Android)
                  KV{"async_shader_compilation", "true"}, KV{"use_fuzzy_alpha_epsilon", "true"},
                  KV{"gpu_native_truth", "true"}, KV{"gpu_native_truth_host_rt", "true"},
                  KV{"gpu_native_gpu_timers", "false"}, KV{"vulkan_merge_adjacent_barriers", "true"},
                  // no vertex-shader stores / non-solid fill needed; lets more phone GPUs start
                  KV{"vulkan_require_vertex_pipeline_stores_and_atomics", "false"},
                  KV{"vulkan_require_fill_mode_non_solid", "false"},
                  // command-processor speed-ups (each verified identical on the PC by an earlier port)
                  KV{"gpu_wait_reg_mem_spin", "true"}, KV{"vulkan_async_submit", "true"},
                  KV{"vulkan_submit_on_wait", "true"}, KV{"vulkan_deferred_descriptor_writes", "true"},
                  KV{"gpu_type0_bulk_writes", "true"}, KV{"gpu_type0_block_split", "true"},
                  KV{"gpu_request_ranges_fast", "true"}, KV{"vulkan_constants_dynamic_offsets", "true"},
                  KV{"vulkan_system_constants_skip", "true"}, KV{"vulkan_viewport_info_cache", "true"},
                  KV{"vulkan_vertex_residency_cache_off", "true"}, KV{"vulkan_async_swap", "true"},
                  KV{"gpu_vblank_precise_timer", "true"}})
      set.push_back(kv);
  }
  // Less log writing: the native renderer reports every 60 frames (thousands of lines in a match)
  if (profile >= 1) set.push_back(KV{"gpu_native_report_frames", "1800"});
  if (profile >= 2) {
    // a draw whose pipeline is still being compiled is skipped (the frame is shown again) instead of waiting for it:
    // fewer stalls, but a few black frames at the start of a match
    // (vulkan_pipeline_no_submit_wait is no longer set: it caused black frames at the start of a match)
    // texture caches that skip work when nothing "changed": a stale texture shows as flicker / black textures, so FAST only
    for (KV kv : {KV{"vulkan_reuse_texture_bindings", "true"}, KV{"vulkan_sampler_param_cache", "true"},
                  KV{"gpu_request_textures_skip_unchanged", "true"}, KV{"gpu_upload_skip_unchanged", "true"}})
      set.push_back(kv);
    // Texture loads (the game re-loads ~24 textures / ~23 MB every frame, nearly all rewritten whole from GPU
    // resolves): skipping the old contents saves memory traffic on tile-based GPUs, the scratch ring overlaps loads.
    // FAST only: not verified on Mali (Dimensity) GPUs, so they are not on by default.
    for (KV kv : {KV{"vulkan_texture_load_discard_old", "true"}, KV{"vulkan_texture_load_scratch_ring", "true"}})
      set.push_back(kv);
    for (KV kv : {KV{"gpu_native_truth_skip_writeback", "true"}, KV{"gpu_native_truth_fill_direct", "true"},
                  KV{"vulkan_texture_load_direct_unscaled", "true"},
                  KV{"vulkan_texture_load_direct_unscaled_any_page", "true"},
                  KV{"gpu_native_truth_defer_dest_use", "true"}, KV{"gpu_native_truth_batch_resolves", "true"},
                  KV{"gpu_native_truth_batch_rename", "true"}, KV{"gpu_native_truth_lazy_pass_deps", "true"},
                  KV{"gpu_native_truth_lazy_pass_style", "1"}, KV{"gpu_native_truth_rename_depth", "true"},
                  KV{"vulkan_texture_sample_written_early", "true"}, KV{"gpu_native_truth_batch_fills", "true"},
                  KV{"gpu_native_truth_direct_resolve_full_1x", "true"}, KV{"gpu_native_truth_pass_dont_care", "true"},
                  KV{"gpu_native_truth_color_none", "true"}, KV{"gpu_native_truth_skip_restores", "true"},
                  KV{"gpu_native_truth_skip_repeat_resolves", "true"}, KV{"gpu_native_truth_fill_rename_check", "true"}})
      set.push_back(kv);
  }
  int applied = 0;
  std::string failed, kept;
  for (const auto& [name, value] : set) {
    if (rex::cvar::GetFlagSource(name) != rex::cvar::Source::kDefault) {
      kept += std::string(" ") + name;  // set by the command line / config: left alone
    } else if (rex::cvar::SetFlagByName(name, value)) {
      ++applied;
    } else {
      failed += std::string(" ") + name;  // not a setting of this SDK build
    }
  }
  if (!failed.empty()) REXLOG_WARN("[wwe2k15][gpu] not applied (unknown or rejected):{}", failed);
  if (!kept.empty()) REXLOG_INFO("[wwe2k15][gpu] left as set elsewhere:{}", kept);
  REXLOG_INFO("[wwe2k15][gpu] profile {} ({}): {} settings applied", profile,
              profile == 0 ? "safe" : profile == 1 ? "balanced" : "fast", applied);
}

}  // namespace wwe2k15::gpu_profile
