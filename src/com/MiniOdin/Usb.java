package com.MiniOdin;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

// Phone state detection over OTG + USB permission
public class Usb {
    public static final int NONE = 0, OTHER = 1, NORMAL = 2, ADB = 3, DOWNLOAD = 4;
    static final String ACT = "com.MiniOdin.USB";
    static UsbDevice best;            // device chosen by the last state() call
    static BroadcastReceiver pend;    // waiting for the permission answer

    // Samsung in Download Mode (Odin)
    static boolean isDl(UsbDevice d) {
        return d.getVendorId() == 0x04E8 && (d.getProductId() == 0x685D || d.getProductId() == 0x6601);
    }

    // ADB interface: class 0xFF, subclass 0x42, protocol 1
    static boolean hasAdb(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface it = d.getInterface(i);
            if (it.getInterfaceClass() == 0xFF && it.getInterfaceSubclass() == 0x42 && it.getInterfaceProtocol() == 1) return true;
        }
        return false;
    }

    // Most advanced state among plugged devices; device is in Usb.best
    static int state(UsbManager um) {
        int st = NONE;
        best = null;
        for (UsbDevice d : um.getDeviceList().values()) {
            int s;
            if (isDl(d)) s = DOWNLOAD;
            else if (hasAdb(d)) s = ADB;
            else if (d.getVendorId() == 0x04E8) s = NORMAL;
            else s = OTHER;
            if (s > st) { st = s; best = d; }
        }
        return st;
    }

    // Run ok once permission is granted (now if already), else no
    static void withPerm(final Activity a, final UsbDevice d, final Runnable ok, final Runnable no) {
        final UsbManager um = (UsbManager) a.getSystemService(Context.USB_SERVICE);
        if (um.hasPermission(d)) { ok.run(); return; }
        try { if (pend != null) a.unregisterReceiver(pend); } catch (Exception e) {}
        pend = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                try { a.unregisterReceiver(this); } catch (Exception e) {}
                pend = null;
                if (um.hasPermission(d)) ok.run(); else no.run();
            }
        };
        a.registerReceiver(pend, new IntentFilter(ACT));
        int fl = Build.VERSION.SDK_INT >= 31 ? 33554432 : 0; // FLAG_MUTABLE
        um.requestPermission(d, PendingIntent.getBroadcast(a, 0, new Intent(ACT).setPackage(a.getPackageName()), fl));
    }
}
