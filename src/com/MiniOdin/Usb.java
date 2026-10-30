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

// Détection de l'état du téléphone branché en OTG + demande de permission USB
public class Usb {
    public static final int NONE = 0, OTHER = 1, NORMAL = 2, ADB = 3, DOWNLOAD = 4;
    static final String ACT = "com.MiniOdin.USB";
    static UsbDevice best;            // appareil retenu par le dernier appel à state()
    static BroadcastReceiver pend;    // attente de la réponse à la demande de permission

    // Samsung en Download Mode (Odin)
    static boolean isDl(UsbDevice d) {
        return d.getVendorId() == 0x04E8 && (d.getProductId() == 0x685D || d.getProductId() == 0x6601);
    }

    // Interface ADB : classe 0xFF, sous-classe 0x42, protocole 1
    static boolean hasAdb(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface it = d.getInterface(i);
            if (it.getInterfaceClass() == 0xFF && it.getInterfaceSubclass() == 0x42 && it.getInterfaceProtocol() == 1) return true;
        }
        return false;
    }

    // Renvoie l'état le plus "avancé" parmi les appareils branchés ; l'appareil correspondant est dans Usb.best
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

    // Exécute ok quand la permission est accordée (tout de suite si elle l'est déjà), sinon no
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
