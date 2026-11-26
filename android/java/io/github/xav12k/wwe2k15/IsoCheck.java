package io.github.xav12k.wwe2k15;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

// Checks an Xbox 360 disc image (XDVDFS): finds the volume, looks up default.xex in the root folder and reads it through,
// reporting 0..100 as it goes. Nothing is changed or copied.
final class IsoCheck {
    interface Progress { void update(int percent, String message); }

    private static final long[] BASES = {0L, 0x82F80000L, 0xFD90000L, 0x2080000L, 0x18300000L};
    private static final String MAGIC = "MICROSOFT*XBOX*MEDIA";

    static boolean run(File iso, Progress p) {
        try (RandomAccessFile r = new RandomAccessFile(iso, "r")) {
            p.update(2, "Looking for the game volume...");
            long base = -1;
            byte[] hdr = new byte[0x20];
            for (int k = 0; k < BASES.length; k++) {
                long at = BASES[k] + 32L * 2048;
                if (at + hdr.length > r.length()) continue;
                r.seek(at);
                r.readFully(hdr);
                if (new String(hdr, 0, 20, StandardCharsets.US_ASCII).equals(MAGIC)) {
                    base = BASES[k];
                    break;
                }
                p.update(2 + k * 2, "Looking for the game volume...");
            }
            if (base < 0) return false;
            long rootSector = le32(hdr, 0x14), rootSize = le32(hdr, 0x18);
            p.update(14, "Finding default.xex...");
            long[] xex = find(r, base, rootSector, rootSize, "default.xex");
            if (xex == null) return false;
            long off = base + xex[0] * 2048, size = xex[1];
            byte[] buf = new byte[1 << 20];
            long done = 0;
            r.seek(off);
            while (done < size) {
                int n = r.read(buf, 0, (int) Math.min(buf.length, size - done));
                if (n <= 0) break;
                done += n;
                p.update(20 + (int) (79 * done / Math.max(1, size)), "Checking default.xex...");
            }
            p.update(100, "Game ready");
            return done == size;
        } catch (Exception e) {
            return false;
        }
    }

    // The directory is a binary tree of entries: left(2) right(2) sector(4) size(4) attributes(1) nameLength(1) name.
    private static long[] find(RandomAccessFile r, long base, long sector, long size, String name) throws Exception {
        byte[] dir = new byte[(int) Math.min(size, 1 << 24)];
        r.seek(base + sector * 2048);
        r.readFully(dir);
        int[] stack = new int[256];
        int sp = 0;
        stack[sp++] = 0;
        while (sp > 0) {
            int pos = stack[--sp];
            if (pos + 14 > dir.length) continue;
            int left = (dir[pos] & 0xFF) | (dir[pos + 1] & 0xFF) << 8;
            int right = (dir[pos + 2] & 0xFF) | (dir[pos + 3] & 0xFF) << 8;
            if (left == 0xFFFF && right == 0xFFFF && (dir[pos + 4] & 0xFF) == 0xFF) continue;  // empty padding
            long sec = le32(dir, pos + 4), len = le32(dir, pos + 8);
            int nameLen = dir[pos + 13] & 0xFF;
            if (pos + 14 + nameLen <= dir.length) {
                String n = new String(dir, pos + 14, nameLen, StandardCharsets.US_ASCII);
                if (n.equalsIgnoreCase(name)) return new long[] {sec, len};
            }
            if (left != 0 && left != 0xFFFF && sp < 255) stack[sp++] = left * 4;
            if (right != 0 && right != 0xFFFF && sp < 255) stack[sp++] = right * 4;
        }
        return null;
    }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xFFL) | (b[o + 1] & 0xFFL) << 8 | (b[o + 2] & 0xFFL) << 16 | (b[o + 3] & 0xFFL) << 24;
    }
}
