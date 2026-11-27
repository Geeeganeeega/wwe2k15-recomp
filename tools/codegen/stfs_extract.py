#!/usr/bin/env python3
"""Extract a file from an Xbox 360 STFS package (LIVE / PIRS / CON), e.g. default.xexp from a title update package
(tu00000004_00000000). Pure Python, no dependencies.

    stfs_extract.py <package> <name in the package> <output file>
    stfs_extract.py <package> --list

Layout (Free60 STFS documentation; block mapping as in the Velocity / Xenia readers): 0x1000-byte blocks after the
header; every 170 data blocks are preceded by a hash table (level 0), every 170*170 by a level-1 table, and so on.
A read-only ("female") package has one copy of each hash table, a writable ("male") package two. A file is a chain
of blocks: contiguous when its entry says so, otherwise each block's hash entry gives the next block.
"""
import struct
import sys

BLOCK = 0x1000
PER_TABLE = 0xAA        # 170 entries per hash table
PER_L1 = 0x70E4         # 170 * 170


class Stfs:
    def __init__(self, path):
        self.f = open(path, "rb")
        head = self.f.read(0x971A)
        magic = head[:4]
        if magic not in (b"LIVE", b"PIRS", b"CON "):
            raise SystemExit(f"{path}: not an Xbox 360 package (magic {magic!r})")
        header_size = struct.unpack_from(">I", head, 0x340)[0]
        self.content_type = struct.unpack_from(">I", head, 0x344)[0]
        self.title_id = struct.unpack_from(">I", head, 0x360)[0]
        vd = 0x379
        self.block_sep = head[vd + 2]
        self.ft_blocks = struct.unpack_from("<H", head, vd + 3)[0]
        self.ft_start = int.from_bytes(head[vd + 5:vd + 8], "little")
        self.first_table = (header_size + 0xFFF) & ~0xFFF
        # "sex": 0 = read-only (one hash table copy), 1 = writable (two copies)
        self.sex = (~self.block_sep) & 1
        self.step0 = 0xAB if self.sex == 0 else 0xAC

    # ---- block mapping
    def backing_block(self, n):
        k = self.sex
        r = (((n + PER_TABLE) // PER_TABLE) << k) + n
        if n < PER_TABLE:
            return r
        if n < PER_L1:
            return r + (((n + PER_L1) // PER_L1) << k)
        return (1 << k) + r + (((n + PER_L1) // PER_L1) << k)

    def block_offset(self, n):
        return self.first_table + (self.backing_block(n) << 12)

    def level0_table_block(self, n):
        k = self.sex
        if n < PER_TABLE:
            return 0
        r = (n // PER_TABLE) * self.step0
        r += ((n // PER_L1) + 1) << k
        if n // PER_L1 == 0:
            return r
        return r + (1 << k)

    def next_block(self, n):
        table = self.first_table + (self.level0_table_block(n) << 12)
        self.f.seek(table + (n % PER_TABLE) * 0x18 + 0x15)
        return int.from_bytes(self.f.read(3), "big")

    def read_block(self, n):
        self.f.seek(self.block_offset(n))
        return self.f.read(BLOCK)

    # ---- file table
    def entries(self):
        out = []
        blk = self.ft_start
        for _ in range(self.ft_blocks):
            data = self.read_block(blk)
            for i in range(0, BLOCK, 0x40):
                e = data[i:i + 0x40]
                flags = e[0x28]
                ln = flags & 0x3F
                if ln == 0:
                    continue
                name = e[:ln].decode("latin-1")
                out.append({
                    "name": name,
                    "dir": bool(flags & 0x80),
                    "contiguous": bool(flags & 0x40),
                    "blocks": int.from_bytes(e[0x29:0x2C], "little"),
                    "start": int.from_bytes(e[0x2F:0x32], "little"),
                    "parent": struct.unpack_from(">h", e, 0x32)[0],
                    "size": struct.unpack_from(">I", e, 0x34)[0],
                })
            blk = self.next_block(blk)
        return out

    def read_file(self, e):
        left = e["size"]
        blk = e["start"]
        parts = []
        for i in range(e["blocks"]):
            data = self.read_block(blk)
            parts.append(data[:min(BLOCK, left)])
            left -= BLOCK
            if left <= 0:
                break
            blk = blk + 1 if e["contiguous"] else self.next_block(blk)
        return b"".join(parts)


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    if sys.argv[2] == "--is-title-update":
        # exit 0 when the file is a title update package (content type 000B0000), of title argv[3] if given
        try:
            pkg = Stfs(sys.argv[1])
        except SystemExit:
            sys.exit(1)
        want = int(sys.argv[3], 16) if len(sys.argv) > 3 else None
        sys.exit(0 if pkg.content_type == 0x000B0000 and (want is None or pkg.title_id == want) else 1)
    pkg = Stfs(sys.argv[1])
    files = pkg.entries()
    if sys.argv[2] == "--list":
        print(f"title {pkg.title_id:08X}  content type {pkg.content_type:08X}")
        for e in files:
            print(("<dir> " if e["dir"] else f"{e['size']:>10} ") + e["name"])
        return
    want = sys.argv[2].lower()
    match = [e for e in files if not e["dir"] and e["name"].lower() == want]
    if not match:
        raise SystemExit(f"{want} is not in this package (it has: {', '.join(e['name'] for e in files[:20])})")
    data = pkg.read_file(match[0])
    if want.endswith((".xex", ".xexp")) and data[:4] != b"XEX2":
        raise SystemExit(f"extracted {want} but it does not start with XEX2: the package layout was not read right")
    with open(sys.argv[3], "wb") as out:
        out.write(data)
    print(f"extracted {match[0]['name']} ({len(data)} bytes) from title {pkg.title_id:08X} package -> {sys.argv[3]}")


if __name__ == "__main__":
    main()
