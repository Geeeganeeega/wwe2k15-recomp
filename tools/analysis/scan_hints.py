"""Turn the "[wwe2k15][scan]" lines of a game log into function-entry hints for the manifest.

The game log (made by src/missing_function_scan.h) lists every spot in the game's data that points into code no
function covers. This checks each target against the generated code (generated/default, never committed) and keeps
only the ones that really are the start of a function:

  REGISTERED   already a function: nothing to do
  INSIDE_FUNC  an instruction of some function (a jump-table label or similar): NOT a hint
  HOLE         inside a function's range but not an instruction (inline data): NOT a hint
  GAP_START    uncovered, and right where the code before it ends, and that code ends in an unconditional jump
               / return (blr, b, bctr): it cannot fall through into the target -> a function start
  PAD_START    the same, with exactly one skipped word between (alignment padding) and the target 8-byte aligned
  NEAR_START   a "candidate" (its pointer sits next to pointers to registered functions: a vtable / callback table)
               up to 4 words after code that ends in an unconditional jump / return: the words in between are a
               tiny unreferenced function or padding, and the table slot proves the target is a function
  CHAIN        uncovered, with an accepted target earlier in the same uncovered gap (a run of tiny functions)
  REJECTED     anything else (the reason is printed)

Usage:  python3 tools/analysis/scan_hints.py <game log> [out.toml] [--lone]
  GEN=<generated folder> (default generated/default)  PREFIX=<generated file prefix> (default wwe2k15)
  --lone  also accept pointers from the "lone" group (mostly jump-table labels; only GAP_START/PAD_START/CHAIN count (NEAR_START needs a candidate))
Based on an earlier port's tools/analysis/classify_candidates.py and check_boundaries.py (BSD 3-Clause).
"""
import bisect
import glob
import os
import re
import sys

GEN = os.environ.get("GEN", "generated/default")
PREFIX = os.environ.get("PREFIX", "wwe2k15")
args = [a for a in sys.argv[1:] if not a.startswith("--")]
use_lone = "--lone" in sys.argv
log_path = args[0]
out_path = args[1] if len(args) > 1 else None

# ---- the scan lines -------------------------------------------------------------------------------------------------
targets = {}  # address -> (kind, pointer address); "candidate" wins over "lone"
for line in open(log_path, encoding="utf-8", errors="replace"):
    m = re.search(r"\[scan\] (candidate|lone) 0x([0-9A-Fa-f]{8}) \(pointer at 0x([0-9A-Fa-f]{8})\)", line)
    if m:
        t = int(m.group(2), 16)
        if t not in targets or m.group(1) == "candidate":
            targets[t] = (m.group(1), int(m.group(3), 16))
if not targets:
    raise SystemExit("no [scan] lines in the log")

# ---- the generated code: every instruction with its address ---------------------------------------------------------
# Functions with a name of their own (xstart, ...) get their address from the register file.
named = {}
reg = open(os.path.join(GEN, f"{PREFIX}_register.cpp"), encoding="utf-8").read()
for a, n in re.findall(r"REX_REGISTER_ENTRY\(0x([0-9A-Fa-f]+),\s*(\w+)\)", reg):
    named[n] = int(a, 16)
instr = {}  # address -> (function start, text)
func_start = set()
func_range = {}  # function start -> (first address, end address)
for path in glob.glob(os.path.join(GEN, f"{PREFIX}_recomp.*.cpp")):
    text = open(path, encoding="utf-8").read()
    for m in re.finditer(r"DEFINE_REX_FUNC\((\w+)\)\s*\{", text):
        name = m.group(1)
        if name.startswith("sub_") and re.fullmatch(r"[0-9A-Fa-f]{8}", name[4:]):
            start = int(name[4:], 16)
        elif name in named:
            start = named[name]
        else:
            continue
        end = text.find("\nDEFINE_REX_FUNC(", m.end())
        body = text[m.end(): end if end != -1 else len(text)]
        addr, hi = start, start
        func_start.add(start)
        for ln in body.splitlines():
            s = ln.strip()
            lab = re.match(r"loc_([0-9A-Fa-f]{8}):", s)
            if lab:
                addr = int(lab.group(1), 16)
            elif s.startswith("// "):
                instr[addr] = (start, s[3:])
                addr += 4
                hi = max(hi, addr)
        func_range[start] = (start, hi)
if not func_start:
    raise SystemExit(f"no generated functions found in {GEN} (PREFIX={PREFIX})")
addrs = sorted(instr)
fstarts = sorted(func_start)
UNCOND = re.compile(r"^(blr|bctr|b 0x[0-9a-fA-F]+)$")


def prev_instr(t):
    i = bisect.bisect_left(addrs, t) - 1
    return addrs[i] if i >= 0 else None


def next_instr(t):
    i = bisect.bisect_right(addrs, t)
    return addrs[i] if i < len(addrs) else None


def containing_func(t):
    i = bisect.bisect_right(fstarts, t) - 1
    if i < 0:
        return None
    lo, hi = func_range[fstarts[i]]
    return fstarts[i] if lo <= t < hi else None


accepted, rows, counts = {}, [], {}
for t in sorted(targets):
    kind, where = targets[t]
    if t in func_start:
        cls, why = "REGISTERED", ""
    elif t in instr:
        cls, why = "INSIDE_FUNC", f"sub_{instr[t][0]:08X}"
    elif containing_func(t) is not None:
        cls, why = "HOLE", f"inside sub_{containing_func(t):08X}'s range"
    else:
        p = prev_instr(t)
        if p is None:
            cls, why = "REJECTED", "no code before it"
        else:
            fn, last = instr[p]
            gap = t - (p + 4)
            earlier = [a for a in accepted if p + 4 <= a < t]
            if gap == 0 and UNCOND.match(last):
                cls, why = "GAP_START", f"after sub_{fn:08X}, which ends with '{last}'"
            elif gap == 4 and t % 8 == 0 and UNCOND.match(last):
                cls, why = "PAD_START", f"one padding word after sub_{fn:08X} ('{last}')"
            elif kind == "candidate" and 0 < gap <= 0x10 and UNCOND.match(last):
                cls, why = "NEAR_START", f"{gap // 4} word(s) after sub_{fn:08X} ('{last}'), pointer in a table"
            elif earlier:
                cls, why = "CHAIN", f"after accepted 0x{max(earlier):08X} in the same gap"
            elif not UNCOND.match(last):
                cls, why = "REJECTED", f"sub_{fn:08X} ends with '{last}' (could run into it)"
            else:
                cls, why = "REJECTED", f"gap of 0x{gap:X} after sub_{fn:08X}, nothing accepted before it in the gap"
    if cls in ("GAP_START", "PAD_START", "CHAIN") and kind == "lone":
        # A single uncovered word is padding between two functions (a data word that happens to equal that address);
        # codegen cannot decode it ("not in any code region") and makes an empty stub. Real code is 2+ words.
        nxt = next_instr(t)
        if nxt is not None and nxt - t < 8:
            cls, why = "REJECTED", f"only {(nxt - t) // 4} uncovered word before the next function (padding)"
    ok = cls in ("GAP_START", "PAD_START", "NEAR_START", "CHAIN") and (kind == "candidate" or use_lone)
    if ok:
        accepted[t] = (cls, why, kind)
    counts[(kind, cls)] = counts.get((kind, cls), 0) + 1
    rows.append((t, kind, where, cls, why))

print(f"{len(targets)} targets from {os.path.basename(log_path)}")
for (kind, cls), n in sorted(counts.items()):
    print(f"  {kind:9} {cls:12} {n}")
print(f"\naccepted as function starts: {len(accepted)}" + ("" if use_lone else " (candidates only; --lone adds the rest)"))
print("\n--- REJECTED / not accepted but in a gap (look at these) ---")
for t, kind, where, cls, why in rows:
    if cls == "REJECTED" or (cls in ("GAP_START", "PAD_START", "NEAR_START", "CHAIN") and t not in accepted):
        print(f"  0x{t:08X} {kind:9} ptr@0x{where:08X} {cls}: {why}")

if out_path:
    with open(out_path, "w") as f:
        f.write("# Function-entry hints from the startup scan of a game log (tools/analysis/scan_hints.py).\n")
        f.write("# Each address is the start of code that is only reached through a pointer in the game's data.\n")
        f.write("# Checked against the generated code: not inside a function, and the code before it cannot run into it.\n")
        f.write("[functions]\n")
        for t in sorted(accepted):
            cls, why, kind = accepted[t]
            f.write(f"0x{t:08X} = {{}} # ptr@0x{targets[t][1]:08X} {cls} {kind}\n")
    print(f"\nwrote {out_path}")
