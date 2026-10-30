package com.MiniOdin;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.hardware.usb.*;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

// Odin USB bulk protocol: connect, handshake, auto PIT, write session
public class Odin {
    // A file to write, matched in the PIT by file or partition name
    public static class Job {
        public String label;   // display name
        public File file;      // file in cache
        public String key;     // name to look up in the PIT
        public boolean byFile; // true = flash file name, false = partition name
        public Job(String label, File file, String key, boolean byFile) {
            this.label = label; this.file = file; this.key = key; this.byFile = byFile;
        }
    }

    // Prepares files (MD5, extract); returns the jobs, or null to abort
    public interface Step { List<Job> run(); }
    public interface Cb { void say(String m); void progress(int p); }

    static UsbDeviceConnection conn;
    static UsbEndpoint in, out;
    static Cb log;                 // last log (low-level messages)
    static int ctlIf = 0;          // CDC control interface (DTR/RTS)
    static final String BUILD = "r19";
    static String path;
    static Activity act;
    static int gen;                // current connection attempt id
    static byte[] pitData;         // PIT read on this connection
    static boolean loke;           // handshake already done
    static boolean sess;           // session already open
    static boolean busy;           // USB operation in progress
    static final Object LOCK = new Object();
    static StringBuilder J = new StringBuilder(); // log shared between screens

    static final int PART = 131072;      // bytes per part
    static final int PER_SEQ = 240;      // parts per sequence
    static final int CHUNK = 16384;      // max bulkTransfer size

    public static final String[] SLOTS = {"BL", "AP", "CP", "CSC", "USERDATA"};
    static final String[][] MAP = {
        // Most specific names first (init_boot must never match boot)
        {"init_boot", "INIT_BOOT"}, {"vendor_boot", "VENDOR_BOOT"}, {"system_ext", "SYSTEM_EXT"}, {"super", "SUPER"},
        {"userdata", "USERDATA"}, {"recovery", "RECOVERY"}, {"vbmeta", "VBMETA"},
        {"dtbo", "DTBO"}, {"vendor", "VENDOR"}, {"product", "PRODUCT"},
        {"system", "SYSTEM"}, {"cache", "CACHE"}, {"modem", "MODEM"},
        {"radio", "MODEM"}, {"sboot", "BOOTLOADER"}, {"bootloader", "BOOTLOADER"},
        {"boot", "BOOT"}
    };

    // Never written: phone identity (IMEI...), MTK preloader, partition table
    static final String[] PROTECTED = {"preloader", "pgpt", "sgpt", "pit", "proinfo", "nvram", "nvdata", "nvcfg",
        "protect1", "protect2", "persist", "efuse", "seccfg", "frp"};

    public static String detect(String file) {
        String n = file.toLowerCase();
        if (!(n.endsWith(".img") || n.endsWith(".bin"))) return null;
        for (String[] m : MAP) if (n.contains(m[0])) return m[1];
        return null;
    }

    public static String name(Context c, Uri u) {
        String n = u.getLastPathSegment();
        Cursor q = c.getContentResolver().query(u, null, null, null, null);
        if (q != null) {
            if (q.moveToFirst()) {
                int i = q.getColumnIndex("_display_name");
                if (i >= 0) n = q.getString(i);
            }
            q.close();
        }
        return n;
    }

    static boolean isDl(UsbDevice d) { return d.getProductId() == 0x685D || d.getProductId() == 0x6601; }

    public static boolean connected() { return conn != null && in != null && out != null; }

    public static void connect(Activity a, Cb cb) { gen++; connect2(a, cb, 0); }

    static void dead() {
        try { if (conn != null) conn.close(); } catch (Exception e) {}
        conn = null; in = null; out = null; loke = false; sess = false; pitData = null;
    }

    static void connect2(final Activity a, final Cb cb, final int n) {
        log = cb;
        act = a;
        final UsbManager um = (UsbManager) a.getSystemService(Context.USB_SERVICE);
        UsbDevice d = null;
        for (UsbDevice x : um.getDeviceList().values()) {
            if (x.getVendorId() != 0x04E8) continue;
            if (d == null || isDl(x)) d = x; // prefer the Download Mode device
        }
        if (d == null) { cb.say(Lang.t("nousb")); return; }
        path = d.getDeviceName();
        if (n == 0) cb.say(Lang.x("Pilote Odin : ", "Odin driver: ") + path + String.format("  (VID %04X / PID %04X)", d.getVendorId(), d.getProductId()));
        if (n == 0 && !isDl(d)) cb.say(Lang.x("PID inhabituel : le telephone est-il bien en Download Mode ?", "Unusual PID: is the phone really in Download Mode?"));
        final UsbDevice dev = d;
        if (!um.hasPermission(d)) cb.say(Lang.t("perm"));
        Usb.withPerm(a, dev, new Runnable() {
            public void run() { open(um, dev, cb, n); }
        }, new Runnable() {
            public void run() { cb.say(Lang.x("Permission USB refusee", "USB permission denied")); }
        });
    }

    static void open(UsbManager um, UsbDevice d, final Cb cb, final int n) {
        loke = false;
        sess = false;
        pitData = null;
        if (conn != null) { try { conn.close(); } catch (Exception e) {} conn = null; in = null; out = null; }
        UsbDeviceConnection c = um.openDevice(d);
        if (c == null) { cb.say(Lang.x("openDevice a echoue (cable / permission ?)", "openDevice failed (cable / permission?)")); return; }
        ctlIf = 0;
        for (int i = 0; i < d.getInterfaceCount(); i++)
            if (d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_COMM) ctlIf = d.getInterface(i).getId();
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface it = d.getInterface(i);
            UsbEndpoint ei = null, eo = null;
            for (int k = 0; k < it.getEndpointCount(); k++) {
                UsbEndpoint e = it.getEndpoint(k);
                if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (e.getDirection() == UsbConstants.USB_DIR_IN) ei = e; else eo = e;
                }
            }
            if (ei == null || eo == null) continue;
            boolean ok = c.claimInterface(it, true);
            cb.say("interface " + it.getId() + " claim=" + ok + " | IN 0x" + Integer.toHexString(ei.getAddress())
                + " | OUT 0x" + Integer.toHexString(eo.getAddress()) + " | max " + eo.getMaxPacketSize());
            if (ok) { conn = c; in = ei; out = eo; break; }
        }
        if (out == null) c.close();
        if (out == null && n < 8) {
            // the phone may still be enumerating: retry every 1.5 s
            cb.say(Lang.t("wait") + " (" + (n + 1) + "/8)");
            final int g = gen;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() { if (g == gen && act != null) connect2(act, cb, n + 1); }
            }, 1500);
            return;
        }
        cb.say(Lang.t(out == null ? "noif" : "open"));
        if (out == null) { describe(d, cb); cb.say(Lang.t("nobulk")); return; }
        autoPit(cb);
    }

    // Describe all interfaces of a USB device
    static void describe(UsbDevice d, Cb cb) {
        cb.say(Lang.x("   interfaces vues : ", "   interfaces seen: ") + d.getInterfaceCount());
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface it = d.getInterface(i);
            StringBuilder s = new StringBuilder("   if " + it.getId() + Lang.x(" classe ", " class ") + it.getInterfaceClass() + " :");
            for (int k = 0; k < it.getEndpointCount(); k++) {
                UsbEndpoint e = it.getEndpoint(k);
                s.append(" type").append(e.getType()).append(e.getDirection() == UsbConstants.USB_DIR_IN ? "-IN" : "-OUT");
            }
            cb.say(s.toString());
        }
    }

    // List everything seen on USB (for diagnostics)
    public static void usbInfo(Activity a, Cb cb) {
        log = cb;
        UsbManager um = (UsbManager) a.getSystemService(Context.USB_SERVICE);
        if (um.getDeviceList().isEmpty()) { cb.say(Lang.x("Aucun appareil USB visible (cable OTG ? OTG active ?)", "No USB device visible (OTG cable? OTG enabled?)")); return; }
        for (UsbDevice d : um.getDeviceList().values()) {
            cb.say(String.format("%s  VID %04X PID %04X  %s", d.getDeviceName(), d.getVendorId(), d.getProductId(),
                um.hasPermission(d) ? "(permission OK)" : Lang.x("(sans permission)", "(no permission)")));
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface it = d.getInterface(i);
                StringBuilder s = new StringBuilder("   if " + it.getId() + Lang.x(" classe ", " class ") + it.getInterfaceClass() + " :");
                for (int k = 0; k < it.getEndpointCount(); k++) {
                    UsbEndpoint e = it.getEndpoint(k);
                    s.append(" ").append(e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK ? "BULK"
                        : e.getType() == UsbConstants.USB_ENDPOINT_XFER_INT ? "INT" : "T" + e.getType())
                        .append(e.getDirection() == UsbConstants.USB_DIR_IN ? "-IN" : "-OUT")
                        .append("(0x").append(Integer.toHexString(e.getAddress())).append(")");
                }
                cb.say(s.toString());
            }
        }
    }

    // Handshake: send "ODIN", expect "LOKE"
    public static String handshake() {
        if (conn == null) return null;
        // after "LOKE", replaying "ODIN" gets no answer
        if (loke) return "LOKE";
        byte[] junk = new byte[64];
        int j = conn.bulkTransfer(in, junk, junk.length, 200); // drain pending data
        if (j > 0 && log != null) log.say(Lang.x("   (donnees en attente : ", "   (pending data: ") + hex(java.util.Arrays.copyOf(junk, j)) + ")");
        // DTR + RTS = 1, like a serial port
        conn.controlTransfer(0x21, 0x22, 0x03, ctlIf, null, 0, 500);
        byte[] m = "ODIN".getBytes();
        for (int t = 1; t <= 3; t++) {
            int w = conn.bulkTransfer(out, m, m.length, 2000);
            if (w < 0) {
                if (log != null) log.say(Lang.t("lost"));
                dead();
                return null;
            }
            byte[] r = new byte[64];
            int n = conn.bulkTransfer(in, r, r.length, 2000);
            if (n > 0) {
                String s = new String(r, 0, n);
                if ("LOKE".equals(s)) loke = true;
                return s;
            }
        }
        if (log != null) log.say(Lang.x("Pas de reponse : debranche/rebranche le cable puis reconnecte", "No answer: unplug/replug the cable then reconnect"));
        return null;
    }

    // ---------- Control packets ----------
    static byte[] pkt(int type, int req, int... d) {
        ByteBuffer b = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(type).putInt(req);
        for (int x : d) b.putInt(x);
        return b.array();
    }

    static byte[] recv(int n) {
        byte[] r = new byte[n];
        int k = conn.bulkTransfer(in, r, n, 5000);
        return k < 0 ? null : java.util.Arrays.copyOf(r, k);
    }

    static String hex(byte[] r) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(16, r.length); i++) s.append(String.format("%02X ", r[i]));
        return s.toString().trim();
    }

    // Send a control packet, return {responseType, value} or null
    static int[] ask(byte[] p) {
        if (conn == null || conn.bulkTransfer(out, p, p.length, 5000) < 0) return null;
        byte[] r = recv(1024);
        if (r == null || r.length < 8) return null;
        ByteBuffer b = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN);
        return new int[]{b.getInt(0), b.getInt(4)};
    }

    // Open the Odin session if not already open
    static boolean beginSession(Cb cb) {
        if (sess) return true;
        int[] r = null;
        for (int v : new int[]{4, 0}) {
            r = ask(pkt(0x64, 0, v));
            if (r != null && r[0] == 0x64) break;
        }
        if (r == null || r[0] != 0x64) { cb.say(Lang.x("Ouverture de session refusee", "Session refused")); return false; }
        sess = true;
        return true;
    }

    // ---------- Auto PIT read (read-only) ----------
    public static void autoPit(final Cb cb) {
        log = cb;
        if (!connected()) return;
        new Thread(new Runnable() {
            public void run() {
                synchronized (LOCK) {
                    try {
                        if (!"LOKE".equals(handshake())) { cb.say(Lang.t("noloke")); return; }
                        if (pitData == null) {
                            if (!beginSession(cb)) return;
                            int[] r = ask(pkt(0x65, 1)); // ask PIT size
                            if (r == null || r[0] != 0x65 || r[1] <= 0 || r[1] > 1048576) { cb.say(Lang.x("PIT illisible", "PIT unreadable")); return; }
                            int size = r[1], n = (size + 499) / 500;
                            byte[] pit = new byte[n * 500];
                            for (int i = 0; i < n; i++) {
                                byte[] q = pkt(0x65, 2, i);
                                if (conn.bulkTransfer(out, q, q.length, 5000) < 0) { cb.say(Lang.x("PIT : echec partie ", "PIT: failed part ") + i); return; }
                                byte[] d = recv(500);
                                if (d == null) { cb.say(Lang.x("PIT : pas de reponse partie ", "PIT: no answer for part ") + i); return; }
                                System.arraycopy(d, 0, pit, i * 500, Math.min(500, d.length));
                                cb.progress((i + 1) * 100 / n);
                            }
                            ask(pkt(0x65, 3)); // end of PIT transfer
                            // keep the session OPEN: closing it here made the phone answer an error
                            pitData = pit;
                        }
                        ByteBuffer b = ByteBuffer.wrap(pitData).order(ByteOrder.LITTLE_ENDIAN);
                        if (b.getInt(0) != 0x12349876) { cb.say("PIT magic " + Integer.toHexString(b.getInt(0))); pitData = null; return; }
                        cb.say(Lang.x("PIT lue automatiquement : ", "PIT read automatically: ") + b.getInt(4) + " partitions");
                        cb.progress(0);
                    } catch (Throwable e) { cb.say("PIT: " + e); }
                }
            }
        }).start();
    }

    static String str(byte[] p, int o) {
        int e = o;
        while (e < o + 32 && p[e] != 0) e++;
        return new String(p, o, e - o);
    }

    // {id, type} of a PIT partition, or null
    public static int[] pitFind(String name, boolean byFile) {
        if (pitData == null) return null;
        ByteBuffer b = ByteBuffer.wrap(pitData).order(ByteOrder.LITTLE_ENDIAN);
        int cnt = b.getInt(4);
        for (int i = 0; i < cnt; i++) {
            int o = 28 + i * 132;
            if (o + 132 > pitData.length) break;
            if (str(pitData, o + (byFile ? 68 : 36)).equalsIgnoreCase(name)) return new int[]{b.getInt(o + 8), b.getInt(o + 4)};
        }
        return null;
    }

    public static int pitId(String name, boolean byFile) {
        int[] r = pitFind(name, byFile);
        return r == null ? -1 : r[0];
    }

    static String pitName(int id) {
        if (pitData == null) return "?";
        ByteBuffer b = ByteBuffer.wrap(pitData).order(ByteOrder.LITTLE_ENDIAN);
        int cnt = b.getInt(4);
        for (int i = 0; i < cnt; i++) {
            int o = 28 + i * 132;
            if (o + 132 > pitData.length) break;
            if (b.getInt(o + 8) == id) return str(pitData, o + 36);
        }
        return "?";
    }

    static boolean isProtected(String partName) {
        String n = partName.toLowerCase();
        for (String p : PROTECTED) if (n.equals(p)) return true;
        return false;
    }

    // ---------- Write session ----------
    public static void start(final String what, final Cb cb, final Step next) {
        log = cb;
        if (!connected()) { cb.say(Lang.t("conn1")); return; }
        new Thread(new Runnable() {
            public void run() {
                synchronized (LOCK) {
                    if (busy) return;
                    busy = true;
                    try {
                        if (!"LOKE".equals(handshake())) { cb.say(Lang.t("noloke")); return; }
                        cb.say(what);
                        List<Job> jobs = next == null ? null : next.run(); // MD5, extraction
                        if (jobs == null) return;
                        if (jobs.isEmpty()) { cb.say(Lang.t("nofile")); return; }
                        if (pitData == null) { cb.say(Lang.x("PIT pas encore lue : reconnecte le telephone", "PIT not read yet: reconnect the phone")); return; }
                        flashAll(jobs, cb);
                    } catch (Throwable e) {
                        cb.say(Lang.x("Erreur pendant l'ecriture : ", "Error while writing: ") + e);
                    } finally {
                        busy = false;
                    }
                }
            }
        }).start();
    }

    static boolean send(byte[] b, int off, int len) {
        while (len > 0) {
            int n = Math.min(CHUNK, len);
            int w = conn.bulkTransfer(out, b, off, n, 10000);
            if (w <= 0) return false;
            off += w;
            len -= w;
        }
        return true;
    }

    // Ack after a part: first int is -1 on error
    static boolean ack(Cb cb) {
        byte[] r = recv(64);
        if (r == null || r.length < 4) { cb.say(Lang.x("Pas d'accuse de reception du telephone", "No acknowledgement from the phone")); return false; }
        if (ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == -1) { cb.say(Lang.x("Le telephone signale une erreur : ", "The phone reports an error: ") + hex(r)); return false; }
        return true;
    }

    static boolean flashAll(List<Job> jobs, Cb cb) throws Exception {
        Context c = App.ctx;
        // Resolve in the PIT + protected partitions check
        List<Job> ok = new ArrayList<Job>();
        List<int[]> ids = new ArrayList<int[]>();
        long total = 0;
        boolean slotB = Opts.on(c, "slotb");
        for (Job j : jobs) {
            int[] f = null;
            if (!j.byFile && slotB && !j.key.endsWith("_b")) f = pitFind(j.key + "_b", false);
            if (f == null) f = pitFind(j.key, j.byFile);
            if (f == null && !j.byFile) f = pitFind(j.key.replaceAll("_[ab]$", ""), false);
            if (f == null) { cb.say(Lang.x("Ignore (absent de la PIT) : ", "Skipped (not in the PIT): ") + j.label); continue; }
            String pn = pitName(f[0]);
            if (isProtected(pn)) { cb.say(Lang.x("Ignore (partition protegee '", "Skipped (protected partition '") + pn + "') : " + j.label); continue; }
            ok.add(j);
            ids.add(f);
            total += j.file.length();
            cb.say(Lang.x("Cible : ", "Target: ") + j.label + " -> " + pn + " (PIT #" + f[0] + ")");
        }
        if (ok.isEmpty()) { cb.say(Lang.x("Rien a ecrire", "Nothing to write")); return false; }
        if (Opts.on(c, "repart") || Opts.on(c, "tflash"))
            cb.say(Lang.x("Options Repartitionner / TFlash non gerees : ignorees", "Repartition / TFlash options not supported: ignored"));
        if (!beginSession(cb)) return false;
        int[] r = ask(pkt(0x64, 2, (int) total, (int) (total >>> 32)));
        if (r == null || r[0] != 0x64) { cb.say(Lang.x("Le telephone refuse la taille totale", "The phone refuses the total size")); return false; }
        long done = 0;
        byte[] buf = new byte[PART];
        for (int k = 0; k < ok.size(); k++) {
            Job j = ok.get(k);
            int[] f = ids.get(k);
            long size = j.file.length();
            cb.say(Lang.x("Ecriture de ", "Writing ") + j.label + " (" + (size >> 10) + " KB)");
            r = ask(pkt(0x66, 0));
            if (r == null || r[0] != 0x66) { cb.say(Lang.x("Debut de transfert refuse", "Transfer start refused")); return false; }
            FileInputStream fi = new FileInputStream(j.file);
            try {
                long left = size;
                while (left > 0) {
                    long seqReal = Math.min(left, (long) PART * PER_SEQ);
                    int parts = (int) ((seqReal + PART - 1) / PART);
                    boolean last = left - seqReal == 0;
                    r = ask(pkt(0x66, 2, parts * PART));
                    if (r == null || r[0] != 0x66) { cb.say(Lang.x("Sequence refusee", "Sequence refused")); return false; }
                    for (int p = 0; p < parts; p++) {
                        int want = (int) Math.min(PART, seqReal - (long) p * PART);
                        int got = 0;
                        while (got < want) {
                            int n = fi.read(buf, got, want - got);
                            if (n <= 0) break;
                            got += n;
                        }
                        if (got != want) { cb.say(Lang.x("Lecture du fichier incomplete", "Incomplete file read")); return false; }
                        if (want < PART) java.util.Arrays.fill(buf, want, PART, (byte) 0);
                        if (!send(buf, 0, PART)) { cb.say(Lang.x("Ecriture USB echouee", "USB write failed")); return false; }
                        if (!ack(cb)) return false;
                        done += want;
                        cb.progress((int) (done * 100 / total));
                    }
                    r = ask(pkt(0x66, 3, 0, (int) seqReal, 0, f[1], f[0], last ? 1 : 0));
                    if (r == null || r[0] != 0x66) { cb.say(Lang.x("Fin de sequence refusee", "End of sequence refused")); return false; }
                    left -= seqReal;
                }
            } finally {
                fi.close();
            }
            cb.say(Lang.x("OK : ", "Done: ") + j.label);
        }
        // End session; reboot is optional
        boolean reboot = Opts.on(c, "reboot");
        ask(pkt(0x67, 0));
        sess = false;
        if (reboot) {
            ask(pkt(0x67, 1));
            cb.say(Lang.x("Ecriture terminee, le telephone redemarre", "Write finished, the phone is rebooting"));
            dead();
        } else {
            loke = false;
            cb.say(Lang.x("Ecriture terminee. Debranche/rebranche le cable pour continuer", "Write finished. Unplug/replug the cable to continue"));
        }
        cb.progress(100);
        return true;
    }
}
