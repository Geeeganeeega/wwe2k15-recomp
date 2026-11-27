// wwe2k15: DLC and title update packages.
//
// The launcher keeps the packages the player imported in <files>/dlc (enabled) and <files>/dlc/disabled (switched
// off). Before the game starts, Sync() makes the game's content folder match: every enabled DLC package (STFS:
// "LIVE", "PIRS" or "CON ") is installed once with the SDK's ContentManager, and installed DLC whose package is
// gone or disabled is removed. A title update package (content type 0x000B0000) is unpacked into <files>/update,
// which the launcher mounts as update:\ (its data files; its code patch (default.xexp) cannot be used by recompiled
// code unless the game is recompiled with it).
#pragma once

#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <map>
#include <queue>
#include <set>
#include <string>

#include <vector>

#include <fmt/format.h>
#include <rex/cvar.h>
#include <rex/string.h>
#include <rex/filesystem.h>
#include <rex/filesystem/devices/host_path_device.h>
#include <rex/filesystem/devices/stfs_container_device.h>
#include <rex/logging.h>
#include <rex/system/kernel_state.h>
#include <rex/system/user_module.h>
#include <rex/system/xam/content_device.h>
#include <rex/system/xam/content_manager.h>

REXCVAR_DEFINE_STRING(wwe2k15_dlc_dir, "", "wwe2k15", "Folder with the imported DLC / title update packages");
REXCVAR_DEFINE_STRING(wwe2k15_update_dir, "", "wwe2k15", "Folder the title update's files are unpacked into");

using rex::X_RESULT;
using rex::X_STATUS;

namespace wwe2k15::dlc {

inline bool HasStfsMagic(const std::filesystem::path& path) {
  FILE* f = rex::filesystem::OpenFile(path, "rb");
  if (!f) return false;
  char magic[4] = {};
  const bool ok = std::fread(magic, 1, 4, f) == 4;
  std::fclose(f);
  return ok && (!std::memcmp(magic, "LIVE", 4) || !std::memcmp(magic, "PIRS", 4) || !std::memcmp(magic, "CON ", 4));
}

// Unpacks every file of a package into dest (same layout the console would see).
inline bool ExtractAll(const std::filesystem::path& package, const std::filesystem::path& dest) {
  auto device = std::make_unique<rex::filesystem::StfsContainerDevice>("", package);
  if (!device->Initialize()) return false;
  auto* root = device->ResolvePath("");
  if (!root) return false;
  std::queue<rex::filesystem::Entry*> queue;
  queue.push(root);
  bool all_ok = true;
  while (!queue.empty()) {
    auto* entry = queue.front();
    queue.pop();
    for (auto& child : entry->children()) queue.push(child.get());
    const auto target = dest / rex::to_path(rex::string::utf8_fix_path_separators(entry->path()));
    std::error_code ec;
    if (entry->attributes() & rex::filesystem::kFileAttributeDirectory) {
      std::filesystem::create_directories(target, ec);
      continue;
    }
    std::filesystem::create_directories(target.parent_path(), ec);
    rex::filesystem::File* in = nullptr;
    if (entry->Open(rex::filesystem::FileAccess::kFileReadData, &in) != X_STATUS_SUCCESS) {
      all_ok = false;
      continue;
    }
    FILE* out = rex::filesystem::OpenFile(target, "wb");
    if (!out) {
      in->Destroy();
      all_ok = false;
      continue;
    }
    constexpr size_t kBuf = 4 * 1024 * 1024;
    auto buf = std::make_unique<uint8_t[]>(kBuf);
    size_t remaining = entry->size(), offset = 0;
    while (remaining) {
      size_t got = 0;
      in->ReadSync(std::span<uint8_t>(buf.get(), std::min(remaining, kBuf)), offset, &got);
      if (!got) {
        all_ok = false;
        break;
      }
      std::fwrite(buf.get(), 1, got, out);
      offset += got;
      remaining -= got;
    }
    std::fclose(out);
    in->Destroy();
  }
  return all_ok;
}

// What an installed package or the unpacked title update holds, written to the log (a folder summary and the
// first files), so a missing wrestler can be traced to the file the game could not find.
inline void LogInventory(const std::string& label, const std::filesystem::path& root) {
  std::error_code ec;
  if (!std::filesystem::exists(root, ec)) return;
  uint64_t files = 0, bytes = 0;
  std::map<std::string, std::pair<uint64_t, uint64_t>> top;  // first folder -> files, bytes
  std::vector<std::string> listed;
  for (std::filesystem::recursive_directory_iterator it(root, std::filesystem::directory_options::skip_permission_denied, ec), end;
       !ec && it != end; it.increment(ec)) {
    std::error_code fec;
    if (!it->is_regular_file(fec)) continue;
    const auto size = it->file_size(fec);
    const auto rel = std::filesystem::relative(it->path(), root, fec);
    const std::string rel_s = rex::path_to_utf8(rel);
    const auto cut = rel_s.find_first_of("/\\");
    auto& t = top[cut == std::string::npos ? "." : rel_s.substr(0, cut)];
    ++t.first;
    t.second += size;
    ++files;
    bytes += size;
    if (listed.size() < 60) listed.push_back(rel_s + " (" + std::to_string(size) + ")");
  }
  REXLOG_INFO("[wwe2k15][dlc] {}: {} files, {} MB", label, files, bytes >> 20);
  for (const auto& [name, t] : top)
    REXLOG_INFO("[wwe2k15][dlc]   {}/ {} files, {} MB", name, t.first, t.second >> 20);
  for (const auto& f : listed) REXLOG_INFO("[wwe2k15][dlc]   - {}", f);
}

// A title update package is unpacked into wwe2k15_update_dir (once per package file: a stamp remembers it).
// WWE 2K15: its default.xexp is what the game's code was recompiled with, so it must be there before the game's
// paths are chosen; UnpackTitleUpdate() runs from OnConfigurePaths, before the kernel exists (no kernel needed here).
inline bool UnpackTitleUpdatePackage(const std::filesystem::path& package) {
  const std::string up = REXCVAR_GET(wwe2k15_update_dir);
  if (up.empty()) return true;
  std::error_code fec;
  const std::filesystem::path stamp = std::filesystem::path(up) / ".unpacked";
  const std::string want = rex::path_to_utf8(package.filename()) + ":" + std::to_string(std::filesystem::file_size(package, fec));
  std::string have;
  std::getline(std::ifstream(stamp), have);
  if (have == want) return true;
  REXLOG_INFO("[wwe2k15][dlc] unpacking title update {}", rex::path_to_utf8(package.filename()));
  std::filesystem::create_directories(up, fec);
  if (ExtractAll(package, up)) {
    std::ofstream(stamp) << want;
    REXLOG_INFO("[wwe2k15][dlc] title update unpacked into {}", up);
    return true;
  }
  REXLOG_ERROR("[wwe2k15][dlc] title update could not be unpacked");
  return false;
}

inline void UnpackTitleUpdate() {
  const std::string dir_s = REXCVAR_GET(wwe2k15_dlc_dir);
  if (dir_s.empty()) return;
  std::error_code ec;
  for (const auto& it : std::filesystem::directory_iterator(std::filesystem::path(dir_s), ec)) {
    std::error_code fec;
    if (!it.is_regular_file(fec) || !HasStfsMagic(it.path())) continue;
    const auto header = rex::filesystem::StfsContainerDevice::ReadPackageHeader(it.path());
    if (header && header->metadata.content_type == rex::system::XContentType::kInstaller)
      UnpackTitleUpdatePackage(it.path());
  }
}

// game_root: the game folder (not a disc image). DLC is also taken from where a console copy keeps it, like the PC
// port: <game>/dlc, <game>/00000002 and, for a game folder inside the title's folder (545408B6/<game>), the title
// folder's 00000002 - so DLC copied next to the game works without importing it in the launcher.
inline void Sync(const std::filesystem::path& user_data_root, const std::filesystem::path& game_root = {}) {
  const std::string dir_s = REXCVAR_GET(wwe2k15_dlc_dir);
  auto* kernel = REX_KERNEL_STATE();
  auto* content = kernel ? kernel->content_manager() : nullptr;
  if (!content) return;
  const uint32_t title_id = kernel->title_id();
  std::error_code ec;
  std::vector<std::filesystem::path> dirs;
  if (!dir_s.empty()) dirs.emplace_back(dir_s);
  if (!game_root.empty() && std::filesystem::is_directory(game_root, ec)) {
    dirs.push_back(game_root / "dlc");
    dirs.push_back(game_root / "00000002");
    dirs.push_back(game_root.parent_path() / "00000002");
  }
  std::set<std::string> wanted;  // the content folder names of the enabled DLC
  int installed = 0, present = 0, failed = 0, removed = 0;

  for (const auto& dir : dirs) {
  if (!std::filesystem::is_directory(dir, ec)) continue;
  REXLOG_INFO("[wwe2k15][dlc] looking for packages in {}", rex::path_to_utf8(dir));
  for (const auto& it : std::filesystem::directory_iterator(dir, ec)) {
    std::error_code fec;
    if (!it.is_regular_file(fec) || !HasStfsMagic(it.path())) continue;
    const auto header = rex::filesystem::StfsContainerDevice::ReadPackageHeader(it.path());
    if (!header) {
      REXLOG_WARN("[wwe2k15][dlc] {} is not a readable package", rex::path_to_utf8(it.path().filename()));
      ++failed;
      continue;
    }
    const rex::system::XContentType type = header->metadata.content_type;
    if (type == rex::system::XContentType::kInstaller) {  // title update
      if (!UnpackTitleUpdatePackage(it.path())) ++failed;
      continue;
    }
    if (type != rex::system::XContentType::kMarketplaceContent) {
      REXLOG_WARN("[wwe2k15][dlc] {} skipped (content type {:08X} is not DLC)",
                  rex::path_to_utf8(it.path().filename()), static_cast<uint32_t>(type));
      continue;
    }
    if (header->metadata.execution_info.title_id != title_id) {
      REXLOG_WARN("[wwe2k15][dlc] {} skipped (made for game {:08X}, this is {:08X})",
                  rex::path_to_utf8(it.path().filename()), uint32_t(header->metadata.execution_info.title_id),
                  title_id);
      continue;
    }
    rex::system::xam::XCONTENT_AGGREGATE_DATA data;
    data.set_file_name(rex::path_to_utf8(it.path().filename()));
    const std::string name = data.file_name();
    wanted.insert(name);
    rex::system::xam::XCONTENT_AGGREGATE_DATA existing;
    if (content->ReadContentHeaderFile(name, 0, title_id, rex::system::XContentType::kMarketplaceContent,
                                       existing) == X_ERROR_SUCCESS) {
      ++present;
      continue;
    }
    REXLOG_INFO("[wwe2k15][dlc] installing {}", rex::path_to_utf8(it.path().filename()));
    const auto result = content->InstallContent(it.path());
    if (result == X_ERROR_SUCCESS) {
      ++installed;
    } else {
      REXLOG_ERROR("[wwe2k15][dlc] install of {} failed ({:08X})", rex::path_to_utf8(it.path().filename()),
                   uint32_t(result));
      rex::system::xam::XCONTENT_AGGREGATE_DATA partial;
      partial.device_id = static_cast<uint32_t>(rex::system::xam::DummyDeviceId::HDD);
      partial.content_type = rex::system::XContentType::kMarketplaceContent;
      partial.title_id = title_id;
      partial.xuid = 0;
      partial.set_file_name(name);
      content->DeleteContent(0, partial);
      ++failed;
    }
  }
  }  // dirs

  // Installed DLC whose package was deleted or disabled: remove it, so the game no longer sees it.
  const auto installed_dir = user_data_root / "0000000000000000" / fmt::format("{:08X}", title_id) / "00000002";
  std::vector<std::string> stale;
  for (const auto& it : std::filesystem::directory_iterator(installed_dir, ec)) {
    std::error_code fec;
    if (!it.is_directory(fec)) continue;
    const std::string name = rex::path_to_utf8(it.path().filename());
    if (!wanted.count(name)) stale.push_back(name);
  }
  for (const auto& name : stale) {
    rex::system::xam::XCONTENT_AGGREGATE_DATA gone;
    gone.device_id = static_cast<uint32_t>(rex::system::xam::DummyDeviceId::HDD);
    gone.content_type = rex::system::XContentType::kMarketplaceContent;
    gone.title_id = title_id;
    gone.xuid = 0;
    gone.set_file_name(name);
    content->DeleteContent(0, gone);
    ++removed;
    REXLOG_INFO("[wwe2k15][dlc] removed {} (package deleted or disabled)", name);
  }
  REXLOG_INFO("[wwe2k15][dlc] {} installed, {} already there, {} removed, {} failed", installed, present, removed,
              failed);

  const std::string up_s = REXCVAR_GET(wwe2k15_update_dir);
  if (!up_s.empty()) LogInventory("title update", up_s);
  const auto pack_root = user_data_root / "0000000000000000" / fmt::format("{:08X}", title_id) / "00000002";
  for (const auto& it : std::filesystem::directory_iterator(pack_root, ec)) {
    std::error_code dec;
    if (it.is_directory(dec)) LogInventory("DLC " + rex::path_to_utf8(it.path().filename()), it.path());
  }
}

// The game keeps a "cache:" partition of its own (EditEx.cac, CAR.cac ...): data it builds from the DLC present at
// that moment, among them the DLC wrestlers' entries in the roster. The runtime leaves cache: without a device, so
// the game could not build it and the DLC slots stayed empty. Same as the WWE 12 port: cache: is mounted on a
// folder of ours, and the folder is emptied whenever the set of DLC changes so the game rebuilds it.
inline void MountCache(const std::filesystem::path& user_data_root) {
  auto* kernel = REX_KERNEL_STATE();
  auto* fs = kernel ? kernel->file_system() : nullptr;
  if (!fs) return;
  std::error_code ec;
  const auto dir = user_data_root / "xbox_cache";

  // What is installed now: the DLC folders and the title update's stamp.
  std::string sig;
  const auto pack_root =
      user_data_root / "0000000000000000" / fmt::format("{:08X}", kernel->title_id()) / "00000002";
  std::set<std::string> names;
  for (const auto& it : std::filesystem::directory_iterator(pack_root, ec)) {
    std::error_code dec;
    if (it.is_directory(dec)) names.insert(rex::path_to_utf8(it.path().filename()));
  }
  for (const auto& n : names) sig += n + "\n";
  const std::string up_s = REXCVAR_GET(wwe2k15_update_dir);
  if (!up_s.empty()) {
    std::string stamp;
    std::ifstream(std::filesystem::path(up_s) / ".unpacked") >> stamp;
    sig += "TU:" + stamp + "\n";
  }
  const auto sig_file = user_data_root / "dlc_cache_sig.txt";
  std::string old_sig;
  {
    std::ifstream in(sig_file, std::ios::binary);
    old_sig.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
  }
  if (sig != old_sig) {
    uintmax_t removed = 0;
    for (const auto& entry : std::filesystem::directory_iterator(dir, ec))
      removed += std::filesystem::remove_all(entry.path(), ec);
    std::ofstream(sig_file, std::ios::binary) << sig;
    REXLOG_INFO("[wwe2k15][dlc] the set of DLC changed: the game's cache was emptied ({} items)", removed);
  }

  std::filesystem::create_directories(dir, ec);
  auto device = std::make_unique<rex::filesystem::HostPathDevice>("\\CACHE", dir, false);
  if (!device->Initialize() || !fs->RegisterDevice(std::move(device))) {
    REXLOG_WARN("[wwe2k15][dlc] could not mount the game's cache on {}", rex::path_to_utf8(dir));
    return;
  }
  fs->RegisterSymbolicLink("cache:", "\\CACHE");
  fs->RegisterSymbolicLink("cache0:", "\\CACHE");
  fs->RegisterSymbolicLink("cache1:", "\\CACHE");
  REXLOG_INFO("[wwe2k15][dlc] the game's cache is mounted on {}", rex::path_to_utf8(dir));
}

// The game checks its own version against what each DLC needs ("Some of your downloadable content requires a more
// recent version of the game"). The version it sees comes from the executable's header (XamGetExecutionId), which is
// the disc's. A title update raises it on a console: its package carries the version the game becomes. Same here:
// the version in that header is replaced with the title update's.
inline uint32_t Be32(const uint8_t* p) {
  return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | uint32_t(p[3]);
}

// The version the title update's executable patch (default.xexp) turns the game into: the "delta patch descriptor"
// header (key 0x000005FF) of that XEX holds the target version. 0 when there is no such file.
inline uint32_t PatchTargetVersion(const std::filesystem::path& xexp) {
  std::ifstream in(xexp, std::ios::binary);
  uint8_t head[0x400] = {};
  in.read(reinterpret_cast<char*>(head), sizeof(head));
  if (in.gcount() < 32 || std::memcmp(head, "XEX2", 4) != 0) return 0;
  const uint32_t count = Be32(head + 20);
  for (uint32_t i = 0; i < count && 24 + 8 * (i + 1) <= sizeof(head); ++i) {
    const uint32_t key = Be32(head + 24 + 8 * i), value = Be32(head + 28 + 8 * i);
    if (key != 0x000005FF || value + 12 > sizeof(head)) continue;
    return Be32(head + value + 4);
  }
  return 0;
}

inline void ApplyTitleUpdateVersion() {
  const std::string up_s = REXCVAR_GET(wwe2k15_update_dir);
  if (up_s.empty()) return;
  const uint32_t target = PatchTargetVersion(std::filesystem::path(up_s) / "default.xexp");
  auto* kernel = REX_KERNEL_STATE();
  if (!kernel) return;
  auto module = kernel->GetExecutableModule();
  if (!module) return;
  uint32_t guest = 0;
  if (XFAILED(module->GetOptHeader(rex::XEX_HEADER_EXECUTION_INFO, &guest)) || !guest) return;
  auto* info = REX_KERNEL_MEMORY()->TranslateVirtual<rex::xex2_opt_execution_info*>(guest);
  if (!info) return;
  REXLOG_INFO("[wwe2k15][dlc] game version {:08X} (base {:08X}); the title update's patch makes it {:08X}",
              uint32_t(info->version_value), uint32_t(info->base_version_value), target);
  if (target > uint32_t(info->version_value)) {
    info->version_value = target;
    REXLOG_INFO("[wwe2k15][dlc] the game now reports version {:08X}", uint32_t(info->version_value));
  }
}

}  // namespace wwe2k15::dlc
