package com.MiniOdin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

// Reads header, kernel and ramdisk of a boot image to guess the phone model.
// No Android dependency.
public class BootInfo {
    static final Charset L1 = Charset.forName("ISO-8859-1");
    static final int CAP = 160 << 20;

    public static class Result {
        String file, kind = "";
        int version;
        long kernelSize, ramdiskSize, dtbSize, page;
        String os = "", patch = "", name = "", cmdline = "";
        String kernelVer = "", kernelNote = "", ramdiskComp = "", ramdiskNote = "";
        int ramdiskFiles;
        TreeMap<String, String> props = new TreeMap<String, String>();
        TreeMap<String, String> boot = new TreeMap<String, String>();
        TreeSet<String> soc = new TreeSet<String>();
        List<String> models = new ArrayList<String>();
        List<String> sources = new ArrayList<String>();
        String error;

        String model() { return models.isEmpty() ? null : models.get(0); }
    }

    static long u32(ByteBuffer b, int off) { return b.getInt(off) & 0xFFFFFFFFL; }

    static String cstr(byte[] b, int off, int max) {
        int e = off;
        while (e < off + max && e < b.length && b[e] != 0) e++;
        return new String(b, off, e - off, L1);
    }

    static long roundUp(long v, long a) { return a <= 0 ? v : (v + a - 1) / a * a; }

    public static Result analyze(File f, String label) {
        Result r = new Result();
        r.file = label;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            byte[] hdr = new byte[4096];
            int got = 0;
            while (got < hdr.length) {
                int n = raf.read(hdr, got, hdr.length - got);
                if (n <= 0) break;
                got += n;
            }
            if (got < 2112) { r.error = "fichier trop petit / too small"; return r; }
            String magic = new String(hdr, 0, 8, L1);
            if ("ANDROID!".equals(magic)) android(raf, hdr, r);
            else if ("VNDRBOOT".equals(magic)) vendor(raf, hdr, r);
            else r.error = "magic " + magic.replaceAll("[^\\x20-\\x7e]", "?") + " (pas un boot Android / not an Android boot image)";
            if (r.error == null) deduce(r);
        } catch (Throwable e) {
            r.error = e.toString();
        } finally {
            try { if (raf != null) raf.close(); } catch (IOException e) { /* ignored */ }
        }
        return r;
    }

    static void android(RandomAccessFile raf, byte[] hdr, Result r) throws IOException {
        ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        r.version = (int) u32(h, 40);
        long os;
        if (r.version >= 3) {
            r.kernelSize = u32(h, 8);
            r.ramdiskSize = u32(h, 12);
            os = u32(h, 16);
            r.page = 4096;
            r.cmdline = cstr(hdr, 44, 1536);
        } else {
            r.kernelSize = u32(h, 8);
            r.ramdiskSize = u32(h, 16);
            r.page = u32(h, 36);
            os = u32(h, 44);
            r.name = cstr(hdr, 48, 16);
            r.cmdline = cstr(hdr, 64, 512) + cstr(hdr, 608, 1024);
            if (r.version >= 2) r.dtbSize = u32(h, 1648);
            if (r.page < 2048 || r.page > 65536) { r.error = "page_size ?" + r.page; return; }
        }
        r.kind = r.kernelSize == 0 && r.ramdiskSize > 0 ? "init_boot" : "boot";
        decodeOs(os, r);
        long kOff = r.page;
        long rOff = r.page + roundUp(r.kernelSize, r.page);
        if (r.kernelSize > 0) readKernel(raf, kOff, r);
        if (r.ramdiskSize > 0) readRamdisk(raf, rOff, r.ramdiskSize, r);
        parseCmdline(r);
    }

    static void vendor(RandomAccessFile raf, byte[] hdr, Result r) throws IOException {
        ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        r.kind = "vendor_boot";
        r.version = (int) u32(h, 8);
        r.page = u32(h, 12);
        r.ramdiskSize = u32(h, 24);
        r.cmdline = cstr(hdr, 28, 2048);
        r.name = cstr(hdr, 2080, 16);
        long hs = u32(h, 2096);
        r.dtbSize = u32(h, 2100);
        if (r.page < 2048 || r.page > 65536) { r.error = "page_size ?" + r.page; return; }
        if (hs == 0) hs = r.version >= 4 ? 2128 : 2112;
        if (r.ramdiskSize > 0) readRamdisk(raf, roundUp(hs, r.page), r.ramdiskSize, r);
        parseCmdline(r);
    }

    // os_version = (A<<14|B<<7|C)<<11 | (year-2000)<<4 | month
    static void decodeOs(long os, Result r) {
        if (os == 0) return;
        long ver = os >> 11;
        r.os = ((ver >> 14) & 0x7f) + "." + ((ver >> 7) & 0x7f) + "." + (ver & 0x7f);
        long p = os & 0x7ff;
        r.patch = (2000 + (p >> 4)) + "-" + String.format("%02d", p & 0xf);
    }

    static void readKernel(RandomAccessFile raf, long off, Result r) throws IOException {
        long avail = raf.length() - off;
        int len = (int) Math.min(Math.min(r.kernelSize, avail), 64L << 20);
        if (len <= 0) { r.kernelNote = "tronque / truncated"; return; }
        byte[] k = new byte[len];
        raf.seek(off);
        raf.readFully(k);
        if (len > 2 && k[0] == 0x1f && (k[1] & 0xff) == 0x8b) {
            try { k = gunzip(k, CAP); r.kernelNote = "gzip"; } catch (IOException e) { r.kernelNote = "gzip ?"; }
        } else if (len > 4 && k[0] == 0x02 && k[1] == 0x21 && k[2] == 0x4c && k[3] == 0x18) {
            try { k = lz4Legacy(k, CAP); r.kernelNote = "lz4"; } catch (IOException e) { r.kernelNote = "lz4 ?"; }
        } else {
            r.kernelNote = "brut / raw";
        }
        r.kernelVer = findLinux(k);
    }

    static void readRamdisk(RandomAccessFile raf, long off, long size, Result r) throws IOException {
        long avail = raf.length() - off;
        int len = (int) Math.min(Math.min(size, avail), (long) CAP);
        if (len <= 0) { r.ramdiskNote = "tronque / truncated"; return; }
        byte[] rd = new byte[len];
        raf.seek(off);
        raf.readFully(rd);
        byte[] cpio = null;
        try {
            if (len > 2 && rd[0] == 0x1f && (rd[1] & 0xff) == 0x8b) {
                r.ramdiskComp = "gzip";
                cpio = gunzip(rd, CAP);
            } else if (len > 4 && rd[0] == 0x02 && rd[1] == 0x21 && rd[2] == 0x4c && rd[3] == 0x18) {
                r.ramdiskComp = "lz4 (legacy)";
                cpio = lz4Legacy(rd, CAP);
            } else if (len > 4 && rd[0] == 0x04 && rd[1] == 0x22 && rd[2] == 0x4d && rd[3] == 0x18) {
                r.ramdiskComp = "lz4 (frame)";
                cpio = readAll(Lz4x.wrap(new ByteArrayInputStream(rd)), CAP);
            } else if (len > 6 && rd[0] == '0' && rd[1] == '7' && rd[2] == '0' && rd[3] == '7' && rd[4] == '0') {
                r.ramdiskComp = "cpio";
                cpio = rd;
            } else if (len > 4 && (rd[0] & 0xff) == 0x28 && (rd[1] & 0xff) == 0xb5 && rd[2] == 0x2f && (rd[3] & 0xff) == 0xfd) {
                r.ramdiskComp = "zstd";
                r.ramdiskNote = "non pris en charge / not supported";
            } else if (len > 3 && rd[0] == 'B' && rd[1] == 'Z' && rd[2] == 'h') {
                r.ramdiskComp = "bzip2";
                r.ramdiskNote = "non pris en charge / not supported";
            } else {
                r.ramdiskComp = "? " + hex(rd, 4);
                r.ramdiskNote = "format inconnu / unknown format";
            }
        } catch (Exception e) {
            r.ramdiskNote = "decompression : " + e.getMessage();
        }
        if (cpio != null) cpio(cpio, r);
    }

    static final Pattern SOC = Pattern.compile("^(mt\\d+|s5e\\d+|exynos\\d+|universal\\d+|sm\\d+|msm\\d+|sdm\\d+|sc\\d+|ums\\d+|gs\\d+|qcom|mtk\\w*)$");
    static final Pattern SOCFILE = Pattern.compile("^(?:init|fstab|ueventd)\\.([a-z0-9_]+?)(?:\\.rc)?$");

    // cpio "newc": 110-byte ASCII header, name, data, 4-byte alignment
    static void cpio(byte[] d, Result r) {
        int p = 0;
        while (p + 110 <= d.length) {
            if (!(d[p] == '0' && d[p + 1] == '7' && d[p + 2] == '0' && d[p + 3] == '7' && d[p + 4] == '0')) break;
            long fsz = hex8(d, p + 54), nsz = hex8(d, p + 94);
            if (nsz < 1 || nsz > 4096) break;
            String name = new String(d, p + 110, (int) nsz - 1, L1);
            int dataOff = (int) ((p + 110 + nsz + 3) & ~3L);
            if (name.equals("TRAILER!!!")) break;
            r.ramdiskFiles++;
            String base = name.substring(name.lastIndexOf('/') + 1);
            Matcher m = SOCFILE.matcher(base);
            if (m.matches() && SOC.matcher(m.group(1)).matches()) r.soc.add(m.group(1));
            if ((base.endsWith(".prop") || base.equals("prop.default") || base.equals("default.prop"))
                && fsz > 0 && fsz < 262144 && dataOff + fsz <= d.length) {
                props(new String(d, dataOff, (int) fsz, L1), r);
            }
            long next = (dataOff + fsz + 3) & ~3L;
            if (next <= p || next > d.length) break;
            p = (int) next;
        }
    }

    static long hex8(byte[] d, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int c = d[off + i];
            int x = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : 0;
            v = (v << 4) | x;
        }
        return v;
    }

    static void props(String text, Result r) {
        for (String line : text.split("\n")) {
            int e = line.indexOf('=');
            if (e <= 0 || line.startsWith("#")) continue;
            String k = line.substring(0, e).trim(), v = line.substring(e + 1).trim();
            if (k.startsWith("ro.product.") || k.equals("ro.build.product") || k.equals("ro.build.fingerprint")
                || k.equals("ro.board.platform") || k.equals("ro.hardware") || k.equals("ro.soc.model")
                || k.equals("ro.soc.manufacturer") || k.equals("ro.build.version.release") || k.equals("ro.build.id")) {
                if (!r.props.containsKey(k) && v.length() > 0) r.props.put(k, v);
            }
        }
    }

    static void parseCmdline(Result r) {
        for (String t : r.cmdline.split("\\s+")) {
            if (!t.startsWith("androidboot.")) continue;
            int e = t.indexOf('=');
            if (e < 0) continue;
            if (r.boot.size() < 40) r.boot.put(t.substring(0, e), t.substring(e + 1));
        }
    }

    static final Pattern CODE = Pattern.compile("([A-Z][0-9]{3}[A-Z0-9]?)");
    static final Pattern KCODE = Pattern.compile("-ab([A-Z][0-9]{3}[A-Z0-9]?)");

    // Candidate models, most reliable first
    static void deduce(Result r) {
        add(r, r.props.get("ro.product.model"), "ro.product.model");
        add(r, r.boot.get("androidboot.em.model"), "androidboot.em.model");
        add(r, r.boot.get("androidboot.product.model"), "androidboot.product.model");
        Matcher m = KCODE.matcher(r.kernelVer);
        if (m.find()) add(r, "SM-" + m.group(1), "kernel");
        String bl = r.boot.get("androidboot.bootloader");
        if (bl != null) {
            Matcher b = CODE.matcher(bl);
            if (b.lookingAt()) add(r, "SM-" + b.group(1), "androidboot.bootloader");
        }
    }

    static void add(Result r, String model, String src) {
        if (model == null || model.length() < 3) return;
        if (!r.models.contains(model)) { r.models.add(model); r.sources.add(src); }
    }

    static String findLinux(byte[] k) {
        byte[] pat = "Linux version ".getBytes(L1);
        outer:
        for (int i = 0; i + pat.length + 1 < k.length; i++) {
            if (k[i] != 'L') continue;
            for (int j = 1; j < pat.length; j++) if (k[i + j] != pat[j]) continue outer;
            int s = i + pat.length;
            if (k[s] < '0' || k[s] > '9') continue;
            int e = s;
            while (e < k.length && e - s < 300 && k[e] != 0 && k[e] != '\n') e++;
            return new String(k, i, e - i, L1);
        }
        return "";
    }

    static String hex(byte[] b, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) s.append(String.format("%02X", b[i]));
        return s.toString();
    }

    // ---------- Decompression ----------
    static byte[] gunzip(byte[] src, int cap) throws IOException {
        return readAll(new GZIPInputStream(new ByteArrayInputStream(src)), cap);
    }

    static byte[] readAll(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 16);
        byte[] b = new byte[1 << 16];
        try {
            int n;
            while ((n = in.read(b)) > 0) {
                o.write(b, 0, n);
                if (o.size() >= cap) break;
            }
        } catch (IOException e) {
            if (o.size() == 0) throw e; // keep what was read
        }
        return o.toByteArray();
    }

    // Android legacy lz4 ramdisk: magic 0x184C2102, then [size LE][block]...
    static byte[] lz4Legacy(byte[] src, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        byte[] blk = new byte[8 * 1024 * 1024 + 64];
        int p = 4;
        while (p + 4 <= src.length) {
            int csz = (src[p] & 0xFF) | ((src[p + 1] & 0xFF) << 8) | ((src[p + 2] & 0xFF) << 16) | ((src[p + 3] & 0xFF) << 24);
            p += 4;
            if (csz == 0x184C2102) continue; // repeated magic (concatenated archives)
            if (csz <= 0 || p + csz > src.length) break;
            int n = lz4Block(src, p, csz, blk);
            out.write(blk, 0, n);
            p += csz;
            if (out.size() >= cap) break;
        }
        if (out.size() == 0) throw new IOException("lz4 vide / empty");
        return out.toByteArray();
    }

    static int lz4Block(byte[] s, int sp, int slen, byte[] d) throws IOException {
        int sEnd = sp + slen, dp = 0;
        try {
            while (sp < sEnd) {
                int token = s[sp++] & 0xFF;
                int lit = token >>> 4;
                if (lit == 15) { int b; do { b = s[sp++] & 0xFF; lit += b; } while (b == 255); }
                if (sp + lit > sEnd || dp + lit > d.length) throw new IOException("lz4 corrompu / corrupted");
                System.arraycopy(s, sp, d, dp, lit);
                sp += lit;
                dp += lit;
                if (sp >= sEnd) break; // last sequence: literals only
                int off = (s[sp] & 0xFF) | ((s[sp + 1] & 0xFF) << 8);
                sp += 2;
                int ml = token & 15;
                if (ml == 15) { int b; do { b = s[sp++] & 0xFF; ml += b; } while (b == 255); }
                ml += 4;
                int mp = dp - off;
                if (off == 0 || mp < 0 || dp + ml > d.length) throw new IOException("lz4 corrompu / corrupted");
                for (int i = 0; i < ml; i++) d[dp++] = d[mp++];
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IOException("lz4 corrompu / corrupted");
        }
        return dp;
    }

    // ---------- Report ----------
    static String size(long v) { return v >= 1048576 ? String.format("%.1f MB", v / 1048576.0) : (v >> 10) + " KB"; }

    static String L(boolean en, String fr, String e) { return en ? e : fr; }

    public static String report(Result r, boolean en) {
        StringBuilder s = new StringBuilder();
        s.append(r.file).append("\n");
        if (r.error != null) {
            s.append(L(en, "Erreur : ", "Error: ")).append(r.error).append("\n");
            return s.toString();
        }
        s.append(L(en, "Type : ", "Type: ")).append(r.kind)
            .append(L(en, "   en-tête v", "   header v")).append(r.version)
            .append(L(en, ", page ", ", page ")).append(r.page).append("\n");
        if (r.os.length() > 0) s.append("Android ").append(r.os).append(L(en, "   patch ", "   patch ")).append(r.patch).append("\n");
        if (r.name.length() > 0) s.append(L(en, "Nom : ", "Name: ")).append(r.name).append("\n");
        if (r.kernelSize > 0) {
            s.append("Kernel : ").append(size(r.kernelSize)).append(" (").append(r.kernelNote).append(")\n");
            if (r.kernelVer.length() > 0) s.append("  ").append(r.kernelVer).append("\n");
        } else if (r.kind.equals("init_boot")) {
            s.append(L(en, "Kernel : aucun (image de type init_boot)\n", "Kernel: none (init_boot type image)\n"));
        }
        if (r.ramdiskSize > 0) {
            s.append("Ramdisk : ").append(size(r.ramdiskSize)).append(", ").append(r.ramdiskComp);
            if (r.ramdiskFiles > 0) s.append(", ").append(r.ramdiskFiles).append(L(en, " fichiers", " files"));
            if (r.ramdiskNote.length() > 0) s.append(" - ").append(r.ramdiskNote);
            s.append("\n");
        }
        if (r.dtbSize > 0) s.append("DTB : ").append(size(r.dtbSize)).append("\n");
        if (!r.soc.isEmpty()) s.append(L(en, "Indices de SoC : ", "SoC hints: ")).append(r.soc).append("\n");
        if (!r.props.isEmpty()) {
            s.append(L(en, "Propriétés du ramdisk :\n", "Ramdisk properties:\n"));
            int n = 0;
            for (Map.Entry<String, String> e : r.props.entrySet()) {
                if (n++ >= 14) break;
                s.append("  ").append(e.getKey()).append("=").append(e.getValue()).append("\n");
            }
        }
        if (!r.boot.isEmpty()) {
            s.append(L(en, "Ligne de commande (androidboot) :\n", "Command line (androidboot):\n"));
            int n = 0;
            for (Map.Entry<String, String> e : r.boot.entrySet()) {
                if (n++ >= 14) break;
                s.append("  ").append(e.getKey().substring(12)).append("=").append(e.getValue()).append("\n");
            }
        }
        if (r.models.isEmpty()) {
            s.append(L(en, "Modèle : aucun identifiant trouvé dans ce fichier\n", "Model: no identifier found in this file\n"));
        } else {
            s.append(L(en, "Modèle probable : ", "Likely model: "));
            for (int i = 0; i < r.models.size(); i++) {
                if (i > 0) s.append(", ");
                s.append(r.models.get(i)).append(" (").append(r.sources.get(i)).append(")");
            }
            s.append("\n");
        }
        return s.toString();
    }
}
