package com.MiniOdin;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

// Petit client ADB par USB (le téléphone qui fait tourner l'app est l'hôte) : sert à envoyer "reboot:download"
public class Adb {
    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f, CLSE = 0x45534c43;

    UsbDeviceConnection c;
    UsbEndpoint in, out;
    Odin.Cb cb;

    static class Msg { int cmd, a0, a1; byte[] data; }

    // Clé de l'app, créée une fois puis gardée (l'option "toujours autoriser" du téléphone cible la retient)
    static synchronized KeyPair key(Context ctx) throws GeneralSecurityException {
        SharedPreferences p = ctx.getSharedPreferences("adb", 0);
        String pv = p.getString("priv", null), pb = p.getString("pub", null);
        if (pv != null && pb != null) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PrivateKey a = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(pv, Base64.NO_WRAP)));
                PublicKey b = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pb, Base64.NO_WRAP)));
                return new KeyPair(b, a);
            } catch (Exception e) { /* clé illisible : on en refait une */ }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        p.edit().putString("priv", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
            .putString("pub", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP)).commit();
        return kp;
    }

    // Ouvre l'interface ADB de l'appareil et lance le service demandé (ex : "reboot:download")
    public static boolean reboot(UsbManager um, UsbDevice d, String service, Context ctx, Odin.Cb cb) {
        UsbInterface itf = null;
        UsbEndpoint ei = null, eo = null;
        for (int i = 0; i < d.getInterfaceCount() && itf == null; i++) {
            UsbInterface it = d.getInterface(i);
            if (it.getInterfaceClass() != 0xFF || it.getInterfaceSubclass() != 0x42 || it.getInterfaceProtocol() != 1) continue;
            for (int k = 0; k < it.getEndpointCount(); k++) {
                UsbEndpoint e = it.getEndpoint(k);
                if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (e.getDirection() == UsbConstants.USB_DIR_IN) ei = e; else eo = e;
            }
            if (ei != null && eo != null) itf = it;
        }
        if (itf == null) { cb.say(Lang.x("Interface ADB introuvable sur cet appareil", "ADB interface not found on this device")); return false; }
        UsbDeviceConnection c = um.openDevice(d);
        if (c == null) { cb.say(Lang.x("Ouverture USB impossible (permission ?)", "Cannot open USB device (permission?)")); return false; }
        try {
            if (!c.claimInterface(itf, true)) { cb.say(Lang.x("Interface ADB occupée par un autre programme", "ADB interface busy")); return false; }
            Adb a = new Adb();
            a.c = c;
            a.in = ei;
            a.out = eo;
            a.cb = cb;
            return a.run(ctx, service);
        } catch (Throwable t) {
            cb.say("ADB : " + t);
            return false;
        } finally {
            try { c.releaseInterface(itf); } catch (Throwable t) {}
            c.close();
        }
    }

    boolean run(Context ctx, String service) throws Exception {
        KeyPair kp = key(ctx);
        byte[] hello = "host::\0".getBytes();
        if (!send(CNXN, 0x01000000, 4096, hello)) { cb.say(Lang.x("Envoi USB impossible", "USB send failed")); return false; }
        boolean connected = false, signed = false, sentKey = false;
        int silent = 0;
        long end = System.currentTimeMillis() + 70000;
        while (System.currentTimeMillis() < end && !connected) {
            Msg m = recv(3000);
            if (m == null) {
                silent++;
                if (silent == 2) send(CNXN, 0x01000000, 4096, hello); // le téléphone n'a peut-être pas vu le premier
                continue;
            }
            if (m.cmd == CNXN) {
                connected = true;
            } else if (m.cmd == AUTH && m.a0 == 1) {
                if (!signed) {
                    send(AUTH, 2, 0, AdbKey.sign(kp.getPrivate(), m.data));
                    signed = true;
                    cb.say(Lang.x("Authentification ADB : signature envoyée", "ADB authentication: signature sent"));
                } else if (!sentKey) {
                    String pub = Base64.encodeToString(AdbKey.encodePublic((RSAPublicKey) kp.getPublic()), Base64.NO_WRAP) + " MiniOdin@android\0";
                    send(AUTH, 3, 0, pub.getBytes());
                    sentKey = true;
                    cb.say(Lang.x("Autorise le débogage USB sur le téléphone cible (coche \"Toujours autoriser\")", "Allow USB debugging on the target phone (tick \"Always allow\")"));
                }
                // clé déjà envoyée : on attend la réponse de l'utilisateur sur le téléphone cible
            }
        }
        if (!connected) {
            cb.say(Lang.x("Pas de connexion ADB : autorisation refusée ou expirée", "No ADB connection: authorization refused or timed out"));
            return false;
        }
        cb.say(Lang.x("ADB connecté, envoi de la commande : ", "ADB connected, sending command: ") + service);
        if (!send(OPEN, 1, 0, (service + "\0").getBytes())) { cb.say(Lang.x("Envoi USB impossible", "USB send failed")); return false; }
        for (int i = 0; i < 4; i++) {
            Msg m = recv(2000);
            if (m == null) continue;
            if (m.cmd == OKAY) return true;
            if (m.cmd == CLSE) { cb.say(Lang.x("Commande refusée par le téléphone", "Command refused by the phone")); return false; }
        }
        // pas de réponse : le téléphone a sans doute déjà redémarré
        cb.say(Lang.x("Pas de confirmation reçue (le téléphone redémarre peut-être déjà)", "No confirmation received (the phone may already be rebooting)"));
        return true;
    }

    // En-tête de 24 octets : commande, arg0, arg1, taille, somme des octets, commande inversée
    boolean send(int cmd, int a0, int a1, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (int i = 0; i < data.length; i++) sum += data[i] & 0xFF;
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(sum).putInt(cmd ^ 0xFFFFFFFF);
        if (c.bulkTransfer(out, b.array(), 24, 5000) != 24) return false;
        if (data.length > 0 && c.bulkTransfer(out, data, data.length, 5000) != data.length) return false;
        return true;
    }

    Msg recv(int timeout) {
        byte[] h = new byte[24];
        int n = c.bulkTransfer(in, h, 24, timeout);
        if (n != 24) return null;
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt(0);
        m.a0 = b.getInt(4);
        m.a1 = b.getInt(8);
        int len = b.getInt(12);
        m.data = new byte[0];
        if (len > 0 && len <= 1048576) {
            byte[] d = new byte[len];
            int got = 0;
            while (got < len) {
                int r = c.bulkTransfer(in, d, got, len - got, 5000);
                if (r <= 0) break;
                got += r;
            }
            if (got < len) return null;
            m.data = d;
        }
        return m;
    }
}
