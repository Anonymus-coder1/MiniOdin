package com.MiniOdin;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

// Light Samsung firmware reader (.tar / .tar.md5), no external lib
public class Fw {
    // Type / CSC / version from the file NAME
    public static String info(String f) {
        String[] t = f.split("_");
        String type = t[0].equals("HOME") ? "HOME_CSC" : t[0];
        String code = null, ver = null;
        for (int i = 1; i < t.length; i++) {
            if (t[i].equals("OMC") || t[i].equals("CSC")) continue;
            if (type.endsWith("CSC") && code == null && t[i].length() == 3) { code = t[i]; continue; }
            if (ver == null && t[i].length() >= 8) ver = t[i];
        }
        return "" + type + (code != null ? " | CSC=" + code : "") + (ver != null ? " | ver=" + ver : "");
    }


    // Tar entry size: octal, or base-256 for huge files
    static long size(byte[] h) {
        if ((h[124] & 0x80) != 0) {
            long v = h[124] & 0x7F;
            for (int i = 125; i < 136; i++) v = (v << 8) | (h[i] & 0xFF);
            return v;
        }
        try { return Long.parseLong(new String(h, 124, 12).trim(), 8); } catch (Exception e) { return 0; }
    }

    // Reads exactly n bytes of a stream
    static class Lim extends InputStream {
        DataInputStream d;
        long left;
        Lim(DataInputStream d, long n) { this.d = d; left = n; }
        public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) < 0 ? -1 : (b[0] & 255);
        }
        public int read(byte[] b, int o, int l) throws IOException {
            if (left <= 0) return -1;
            int n = d.read(b, o, (int) Math.min(l, left));
            if (n > 0) left -= n;
            return n;
        }
        void drain() throws IOException {
            while (left > 0) {
                long k = d.skip(left);
                if (k <= 0) { if (d.read() < 0) break; k = 1; }
                left -= k;
            }
        }
    }

    // Checks the MD5 appended to .tar.md5
    static boolean md5check(Context c, Uri u, Odin.Cb cb) throws Exception {
        ParcelFileDescriptor p = c.getContentResolver().openFileDescriptor(u, "r");
        try {
            long tot = p.getStatSize();
            FileInputStream fi = new FileInputStream(p.getFileDescriptor());
            int tail = (int) Math.min(tot, 512);
            fi.getChannel().position(tot - tail);
            byte[] t = new byte[tail];
            new DataInputStream(fi).readFully(t);
            String ts = new String(t, "ISO-8859-1");
            String last = "";
            for (String s : ts.split("\n")) if (s.trim().length() > 0) last = s.trim();
            String want = last.length() >= 32 ? last.substring(0, 32).toLowerCase() : "";
            if (!want.matches("[0-9a-f]{32}")) { cb.say(Lang.x("Aucun MD5 trouvé en fin de fichier", "No MD5 found at the end of the file")); return true; }
            long len = tot - tail + ts.lastIndexOf(last); // data ends before the MD5 line
            cb.say(Lang.x("Vérification MD5 (", "Checking MD5 (") + (len >> 20) + " MB)...");
            fi.getChannel().position(0);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[1 << 16];
            long done = 0;
            while (done < len) {
                int r = fi.read(buf, 0, (int) Math.min(buf.length, len - done));
                if (r <= 0) break;
                md.update(buf, 0, r);
                done += r;
                cb.progress((int) (done * 100 / len));
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            boolean ok = sb.toString().equals(want);
            cb.say(ok ? "MD5 OK" : Lang.x("MD5 différent (attendu ", "MD5 mismatch (expected ") + want + Lang.x(", obtenu ", ", got ") + sb + Lang.x(") : firmware corrompu", "): corrupted firmware"));
            return ok;
        } finally {
            p.close();
        }
    }

    // MD5, extract to cache (.lz4 decompressed), match PIT ids
    public static List<Odin.Job> prepare(Context c, Uri u, String name, Odin.Cb cb) {
        List<Odin.Job> jobs = new ArrayList<Odin.Job>();
        try {
            if (name.endsWith(".md5")) {
                if (Opts.on(c, "nomd5")) cb.say(Lang.x("MD5 ignoré (option activée)", "MD5 skipped (option enabled)"));
                else if (!md5check(c, u, cb)) return null;
            }
            ParcelFileDescriptor p = c.getContentResolver().openFileDescriptor(u, "r");
            long tot = p.getStatSize();
            p.close();
            DataInputStream d = new DataInputStream(new BufferedInputStream(c.getContentResolver().openInputStream(u)));
            byte[] h = new byte[512];
            long pos = 0;
            while (true) {
                try { d.readFully(h); } catch (EOFException e) { break; }
                pos += 512;
                if (h[0] == 0) break;
                String n = new String(h, 0, 100).trim();
                long sz = size(h);
                long padded = (sz + 511) / 512 * 512;
                boolean lz = n.endsWith(".lz4");
                String out = lz ? n.substring(0, n.length() - 4) : n;
                if (sz == 0 || h[156] == '5') { continue; }
                if (sz > 2147483648L) {
                    cb.say("  " + out + Lang.x(" : trop gros pour la préparation (", " : too big to prepare (") + (sz >> 20) + " MB)");
                    long sk = padded;
                    while (sk > 0) { long k = d.skip(sk); if (k <= 0) break; sk -= k; }
                    pos += padded;
                    continue;
                }
                File f = new File(c.getCacheDir(), out.replace('/', '_'));
                Lim lim = new Lim(d, sz);
                InputStream src = lz ? Lz4x.wrap(lim) : lim;
                FileOutputStream fo = new FileOutputStream(f);
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = src.read(buf)) > 0) {
                    fo.write(buf, 0, r);
                    if (tot > 0) cb.progress((int) ((pos + sz - lim.left) * 100 / tot));
                }
                fo.close();
                lim.drain();
                long pad = padded - sz;
                while (pad > 0) { long k = d.skip(pad); if (k <= 0) break; pad -= k; }
                pos += padded;
                int id = Odin.pitId(out, true);
                if (id >= 0) jobs.add(new Odin.Job(out, f, out, true));
                cb.say("  " + out + (lz ? Lang.x(" (décompressé)", " (decompressed)") : "") + " -> " + (f.length() >> 10) + " KB"
                    + (id >= 0 ? "  |  PIT #" + id : Lang.x("  |  absent de la PIT", "  |  not in the PIT")));
            }
            cb.progress(100);
            return jobs;
        } catch (Throwable e) {
            cb.say("Erreur : " + e);
            return null;
        }
    }

    // Copy a picked file to cache so it can be re-read while flashing
    public static File copy(Context c, Uri u, String name, Odin.Cb cb) throws Exception {
        File f = new File(c.getCacheDir(), "job_" + name.replace('/', '_'));
        InputStream in = c.getContentResolver().openInputStream(u);
        FileOutputStream fo = new FileOutputStream(f);
        byte[] b = new byte[1 << 16];
        int n;
        while ((n = in.read(b)) > 0) fo.write(b, 0, n);
        fo.close();
        in.close();
        return f;
    }

    // Get boot images from a file (.img, .lz4 or a .tar)
    public static List<File> bootFiles(Context c, Uri u, String name) throws Exception {
        List<File> r = new ArrayList<File>();
        String low = name.toLowerCase();
        if (!low.contains(".tar")) {
            File f = new File(c.getCacheDir(), "scan_" + name.replace('/', '_'));
            InputStream in = c.getContentResolver().openInputStream(u);
            if (low.endsWith(".lz4")) in = Lz4x.wrap(in);
            FileOutputStream fo = new FileOutputStream(f);
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) fo.write(b, 0, n);
            fo.close();
            in.close();
            r.add(f);
            return r;
        }
        DataInputStream d = new DataInputStream(new BufferedInputStream(c.getContentResolver().openInputStream(u)));
        byte[] h = new byte[512];
        while (r.size() < 3) {
            try { d.readFully(h); } catch (EOFException e) { break; }
            if (h[0] == 0) break;
            String n = new String(h, 0, 100).trim();
            long sz = size(h);
            long padded = (sz + 511) / 512 * 512;
            String base = n.endsWith(".lz4") ? n.substring(0, n.length() - 4) : n;
            if (sz > 0 && h[156] != '5' && base.endsWith("boot.img")) {
                File f = new File(c.getCacheDir(), "scan_" + base.replace('/', '_'));
                Lim lim = new Lim(d, sz);
                InputStream src = n.endsWith(".lz4") ? Lz4x.wrap(lim) : lim;
                FileOutputStream fo = new FileOutputStream(f);
                byte[] b = new byte[1 << 16];
                int k;
                while ((k = src.read(b)) > 0) fo.write(b, 0, k);
                fo.close();
                lim.drain();
                long pad = padded - sz;
                while (pad > 0) { long q = d.skip(pad); if (q <= 0) break; pad -= q; }
                r.add(f);
            } else {
                long sk = padded;
                while (sk > 0) { long q = d.skip(sk); if (q <= 0) { if (d.read() < 0) break; q = 1; } sk -= q; }
            }
        }
        d.close();
        return r;
    }

    // List tar contents by reading 512-byte headers only
    public static void scan(final Context c, final Uri u, final Odin.Cb cb) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    ParcelFileDescriptor p = c.getContentResolver().openFileDescriptor(u, "r");
                    long tot = p.getStatSize();
                    p.close();
                    DataInputStream d = new DataInputStream(new BufferedInputStream(c.getContentResolver().openInputStream(u)));
                    byte[] h = new byte[512];
                    long pos = 0;
                    while (true) {
                        d.readFully(h);
                        pos += 512;
                        if (h[0] == 0) break;
                        String n = new String(h, 0, 100).trim();
                        long sz = 0;
                        sz = size(h);
                        cb.say("  - " + n + " (" + (sz >> 20) + " MB)");
                        long sk = (sz + 511) / 512 * 512;
                        while (sk > 0) { long k = d.skip(sk); if (k <= 0) break; sk -= k; pos += k; }
                        if (tot > 0) cb.progress((int) (pos * 100 / tot));
                    }
                    cb.progress(100);
                } catch (EOFException e) {
                    cb.progress(100);
                } catch (Throwable e) {
                    cb.say("" + e);
                }
            }
        }).start();
    }
}
