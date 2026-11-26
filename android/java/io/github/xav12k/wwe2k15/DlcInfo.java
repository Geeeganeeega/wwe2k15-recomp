package io.github.xav12k.wwe2k15;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

// What the launcher shows of an Xbox 360 content package (STFS: "LIVE", "PIRS" or "CON "): its name, type and size.
final class DlcInfo {
    static final int TYPE_MARKETPLACE = 0x2, TYPE_UPDATE = 0xB0000;

    File file;
    String title;
    int type;
    long size;
    boolean enabled = true;

    boolean isUpdate() {
        return type == TYPE_UPDATE;
    }

    // null when the file is not a package.
    static DlcInfo read(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            if (r.length() < 0x1800) return null;
            byte[] magic = new byte[4];
            r.readFully(magic);
            String m = new String(magic, StandardCharsets.US_ASCII);
            if (!m.equals("LIVE") && !m.equals("PIRS") && !m.equals("CON ")) return null;
            DlcInfo i = new DlcInfo();
            i.file = f;
            i.size = f.length();
            r.seek(0x344);
            i.type = r.readInt();
            i.title = text(r, 0x411);
            if (i.title.isEmpty()) i.title = text(r, 0x1691);
            if (i.title.isEmpty()) i.title = f.getName();
            return i;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(RandomAccessFile r, long at) throws Exception {
        byte[] b = new byte[0x80];
        r.seek(at);
        r.readFully(b);
        String s = new String(b, StandardCharsets.UTF_16BE);
        int z = s.indexOf('\0');
        return (z >= 0 ? s.substring(0, z) : s).trim();
    }
}
