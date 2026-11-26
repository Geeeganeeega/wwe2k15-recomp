// WWE 2K15 Android: the launcher's file work, in Java (the WWE '13 Android port does this in a native library,
// LauncherNative; WWE 2K15 needs much less of it):
//   - the game: installed from the player's own disc image (.iso, XDVDFS) into <files>/WWE 2K15, resumable;
//   - Title Update 4 and DLC: Xbox 360 packages (STFS) copied into <files>/dlc. The game unpacks the title update
//     into <files>/update and installs the DLC itself at start (port/src/dlc_install.h);
//   - save backups: zips of the game's user folder (<files>/.local/share/wwe2k15) without the installed DLC.
// Progress and cancel work like LauncherNative's (progress() / cancel()), so the launcher's dialog is unchanged.
package io.github.xav12k.wwe2k15;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class GameFiles {
    static final String GAME_DIR = "WWE 2K15";
    /** WWE 2K15's Xbox 360 title id (its DLC folder on a console is 545408B6\00000002). */
    static final int TITLE_ID = 0x545408B6;
    static final int TYPE_DLC = 0x00000002, TYPE_TITLE_UPDATE = 0x000B0000;

    private static File root;
    private static volatile long done, total;
    private static volatile String label = "";
    private static volatile boolean cancelled;

    private GameFiles() {}

    static void init(File filesRoot) { root = filesRoot; }

    static File root() { return root; }
    static File gameDir() { return new File(root, GAME_DIR); }
    static File dlcDir() { File d = new File(root, "dlc"); d.mkdirs(); return d; }
    static File updateDir() { return new File(root, "update"); }
    static File userDir() { return new File(root, ".local/share/wwe2k15"); }
    static File backupDir() { File d = new File(root, "save_backups"); d.mkdirs(); return d; }

    // ------------------------------------------------------------------------------------------ progress

    static String progress() { return done + "|" + total + "|" + label; }
    static void cancel() { cancelled = true; }
    private static void reset(String what) { cancelled = false; done = 0; total = 0; label = what; }

    // ------------------------------------------------------------------------------------------ status

    static boolean gameInstalled() { return new File(gameDir(), "default.xex").isFile(); }

    /** Title Update 4: default.xexp beside default.xex, already unpacked by the game, or an imported package. */
    static boolean titleUpdatePresent() {
        if (new File(gameDir(), "default.xexp").isFile() || new File(updateDir(), "default.xexp").isFile()) return true;
        for (Package p : packages()) if (p.type == TYPE_TITLE_UPDATE) return true;
        return false;
    }

    static int dlcCount() {
        int n = 0;
        for (Package p : packages()) if (p.type == TYPE_DLC) ++n;
        return n;
    }

    // ------------------------------------------------------------------------------------------ disc image (XDVDFS)

    private static final long[] BASES = {0L, 0xFD90000L, 0x2080000L, 0x18300000L};
    private static final String MAGIC = "MICROSOFT*XBOX*MEDIA";

    private static final class Entry {
        final String path; final long sector, size; final boolean dir;
        Entry(String path, long sector, long size, boolean dir) { this.path = path; this.sector = sector; this.size = size; this.dir = dir; }
    }

    /** Copies every file of the disc image into <files>/WWE 2K15. Files already complete are skipped (resumable). */
    static String extractIso(String isoPath) {
        reset("Reading the disc image…");
        File out = gameDir();
        try (RandomAccessFile iso = new RandomAccessFile(isoPath, "r")) {
            long base = -1;
            byte[] hdr = new byte[0x20];
            for (long b : BASES) {
                long at = b + 32L * 2048;
                if (at + hdr.length > iso.length()) continue;
                iso.seek(at);
                iso.readFully(hdr);
                if (new String(hdr, 0, 20, StandardCharsets.US_ASCII).equals(MAGIC)) { base = b; break; }
            }
            if (base < 0) return "This is not an Xbox 360 disc image (no XDVDFS volume found).";
            List<Entry> files = new ArrayList<>();
            walk(iso, base, le32(hdr, 0x14), le32(hdr, 0x18), "", files);
            boolean hasXex = false;
            long sum = 0;
            for (Entry e : files) {
                if (!e.dir) sum += e.size;
                if (!e.dir && e.path.equalsIgnoreCase("default.xex")) hasXex = true;
            }
            if (!hasXex) return "This disc image has no default.xex: it is not a game disc.";
            total = sum;
            byte[] buf = new byte[4 << 20];
            for (Entry e : files) {
                if (cancelled) return "Stopped. Pick the disc image again to continue where it stopped.";
                File f = new File(out, e.path);
                if (e.dir) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                label = e.path;
                if (f.isFile() && f.length() == e.size) { done += e.size; continue; }
                File part = new File(f.getPath() + ".part");
                try (OutputStream os = new FileOutputStream(part)) {
                    long left = e.size, off = base + e.sector * 2048;
                    while (left > 0) {
                        if (cancelled) break;
                        int n = (int) Math.min(buf.length, left);
                        iso.seek(off);
                        iso.readFully(buf, 0, n);
                        os.write(buf, 0, n);
                        left -= n; off += n; done += n;
                    }
                }
                if (cancelled) { part.delete(); continue; }
                f.delete();
                if (!part.renameTo(f)) return "Could not write " + e.path + ".";
            }
            if (cancelled) return "Stopped. Pick the disc image again to continue where it stopped.";
            label = "Done";
            return "";
        } catch (IOException ex) {
            return "Could not read the disc image: " + ex.getMessage();
        }
    }

    private static void walk(RandomAccessFile iso, long base, long sector, long size, String prefix, List<Entry> out)
            throws IOException {
        if (size <= 0 || size > (64 << 20)) return;
        byte[] d = new byte[(int) size];
        iso.seek(base + sector * 2048);
        iso.readFully(d);
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        stack.push(0);
        while (!stack.isEmpty()) {
            int o = stack.pop();
            if (o * 4 + 14 > size || !seen.add(o)) continue;
            int e = o * 4;
            int l = le16(d, e), r = le16(d, e + 2);
            if (l == 0xFFFF) continue;
            long s = le32(d, e + 4), sz = le32(d, e + 8);
            int attr = d[e + 12] & 0xFF, n = d[e + 13] & 0xFF;
            if (e + 14 + n > size) continue;
            String name = new String(d, e + 14, n, StandardCharsets.ISO_8859_1);
            if (name.isEmpty() || name.contains("/") || name.equals("..") || name.equals(".")) continue;
            String path = prefix.isEmpty() ? name : prefix + "/" + name;
            if ((attr & 0x10) != 0) {
                out.add(new Entry(path, s, 0, true));
                walk(iso, base, s, sz, path, out);
            } else {
                out.add(new Entry(path, s, sz, false));
            }
            if (l != 0) stack.push(l);
            if (r != 0) stack.push(r);
        }
    }

    private static int le16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }
    private static long le32(byte[] b, int o) {
        return (b[o] & 0xFFL) | (b[o + 1] & 0xFFL) << 8 | (b[o + 2] & 0xFFL) << 16 | (b[o + 3] & 0xFFL) << 24;
    }
    private static long be32(byte[] b, int o) {
        return (b[o] & 0xFFL) << 24 | (b[o + 1] & 0xFFL) << 16 | (b[o + 2] & 0xFFL) << 8 | (b[o + 3] & 0xFFL);
    }

    // ------------------------------------------------------------------------------------------ packages (STFS)

    static final class Package {
        File file; int type, titleId; String name;
        String kind() { return type == TYPE_TITLE_UPDATE ? "Title update" : type == TYPE_DLC ? "DLC" : "Package"; }
    }

    /** Reads an Xbox 360 package's header (LIVE / PIRS / CON): content type, title id and display name. */
    static Package inspect(File f) {
        byte[] h = new byte[0x412 + 256];
        try (InputStream in = new FileInputStream(f)) {
            int got = 0, n;
            while (got < h.length && (n = in.read(h, got, h.length - got)) > 0) got += n;
            if (got < h.length) return null;
        } catch (IOException e) {
            return null;
        }
        String magic = new String(h, 0, 4, StandardCharsets.US_ASCII);
        if (!magic.equals("LIVE") && !magic.equals("PIRS") && !magic.equals("CON ")) return null;
        Package p = new Package();
        p.file = f;
        p.type = (int) be32(h, 0x344);
        p.titleId = (int) be32(h, 0x360);
        String name = new String(h, 0x411, 256, StandardCharsets.UTF_16BE);
        int z = name.indexOf('\0');
        p.name = (z >= 0 ? name.substring(0, z) : name).trim();
        if (p.name.isEmpty()) p.name = f.getName();
        return p;
    }

    static List<Package> packages() {
        List<Package> list = new ArrayList<>();
        File[] files = dlcDir().listFiles(File::isFile);
        if (files == null) return list;
        Arrays.sort(files);
        for (File f : files) {
            Package p = inspect(f);
            if (p != null) list.add(p);
        }
        return list;
    }

    /** Checks a picked package (already copied to the cache) and moves it into <files>/dlc. "" = done. */
    static String importPackage(File copy) {
        Package p = inspect(copy);
        if (p == null) return "not an Xbox 360 package (title update or DLC).";
        if (p.titleId != TITLE_ID)
            return String.format(Locale.ROOT, "made for another game (title id %08X; WWE 2K15 is %08X).", p.titleId, TITLE_ID);
        if (p.type != TYPE_TITLE_UPDATE && p.type != TYPE_DLC)
            return String.format(Locale.ROOT, "not a title update or DLC (content type %08X).", p.type);
        if (p.type == TYPE_TITLE_UPDATE) {
            for (Package old : packages()) if (old.type == TYPE_TITLE_UPDATE) old.file.delete();  // one title update
            deleteTree(updateDir());  // unpacked again by the game at its next start
        }
        File dest = new File(dlcDir(), copy.getName());
        dest.delete();
        if (!copy.renameTo(dest)) {
            try (InputStream in = new FileInputStream(copy); OutputStream out = new FileOutputStream(dest)) {
                copyStream(in, out);
            } catch (IOException e) {
                return "could not copy it: " + e.getMessage();
            }
            copy.delete();
        }
        return "";
    }

    static void removePackage(Package p) {
        p.file.delete();
        if (p.type == TYPE_TITLE_UPDATE) deleteTree(updateDir());
    }

    // ------------------------------------------------------------------------------------------ save backups

    /** Zips the game's user folder (saves, profile) without installed DLC; auto = an automatic backup before PLAY. */
    static String backupSaves(boolean auto) {
        File user = userDir();
        if (!user.isDirectory()) return auto ? "" : "No saves yet: play once first.";
        String stamp = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.ROOT).format(new Date());
        File zip = new File(backupDir(), "saves-" + stamp + (auto ? "-auto" : "") + ".zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            addTree(z, user, "");
        } catch (IOException e) {
            zip.delete();
            return "Could not back up the saves: " + e.getMessage();
        }
        if (auto) deleteOldBackups(20);
        return "";
    }

    private static void addTree(ZipOutputStream z, File dir, String prefix) throws IOException {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            String rel = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            // installed DLC (content type 00000002) and caches are not saves
            if (f.isDirectory() && (f.getName().equals("00000002") || f.getName().equals("cache")
                    || f.getName().equals("xbox_cache"))) continue;
            if (f.isDirectory()) { addTree(z, f, rel); continue; }
            z.putNextEntry(new ZipEntry(rel));
            try (InputStream in = new FileInputStream(f)) { copyStream(in, z); }
            z.closeEntry();
        }
    }

    /** "path|date|size|auto" per backup, newest first. */
    static List<String[]> listBackups() {
        List<String[]> out = new ArrayList<>();
        File[] list = backupDir().listFiles((d, n) -> n.startsWith("saves-") && n.endsWith(".zip"));
        if (list == null) return out;
        Arrays.sort(list, (a, b) -> b.getName().compareTo(a.getName()));
        for (File f : list) {
            String n = f.getName().substring(6, f.getName().length() - 4);
            boolean auto = n.endsWith("-auto");
            if (auto) n = n.substring(0, n.length() - 5);
            out.add(new String[] {f.getPath(), n.replace('T', ' '), Long.toString(f.length()), auto ? "1" : "0"});
        }
        return out;
    }

    static int deleteOldBackups(int keep) {
        List<String[]> all = listBackups();
        int n = 0;
        for (int i = keep; i < all.size(); ++i) if (new File(all.get(i)[0]).delete()) ++n;
        return n;
    }

    /** Puts a backup's files back (the current saves are backed up first). */
    static String restoreBackup(String path) {
        String err = backupSaves(true);
        if (!err.isEmpty()) return err;
        File user = userDir();
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(path))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory() || e.getName().contains("..")) continue;
                File f = new File(user, e.getName());
                f.getParentFile().mkdirs();
                try (OutputStream os = new FileOutputStream(f)) { copyStream(z, os); }
            }
        } catch (IOException ex) {
            return "Could not restore the backup: " + ex.getMessage();
        }
        return "";
    }

    // ------------------------------------------------------------------------------------------ helpers

    static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 20];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    static void deleteTree(File f) {
        File[] list = f.listFiles();
        if (list != null) for (File c : list) deleteTree(c);
        f.delete();
    }
}
