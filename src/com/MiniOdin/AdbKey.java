package com.MiniOdin;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;

// Clé RSA au format ADB ("androidpubkey") et signature du jeton d'authentification. Aucune dépendance Android.
public class AdbKey {
    // Préfixe DER d'un condensat SHA-1 : le jeton ADB (20 octets) est déjà un condensat
    static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    // Entier -> tableau little-endian de longueur fixe
    static byte[] le(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int src = be.length - 1 - i;
            out[i] = src >= 0 ? be[src] : 0;
        }
        return out;
    }

    // struct { u32 nbMots; u32 n0inv; u8 modulus[]; u8 rr[]; u32 exposant; } en little-endian
    static byte[] encodePublic(RSAPublicKey k) {
        BigInteger n = k.getModulus();
        int bytes = (n.bitLength() + 7) / 8;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger inv = n.mod(r32).modInverse(r32);
        long n0inv = r32.subtract(inv).longValue() & 0xFFFFFFFFL; // -(n^-1) mod 2^32
        BigInteger rr = BigInteger.ONE.shiftLeft(bytes * 16).mod(n); // (2^(8*bytes))^2 mod n
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + bytes + bytes + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(bytes / 4);
        b.putInt((int) n0inv);
        b.put(le(n, bytes));
        b.put(le(rr, bytes));
        b.putInt(k.getPublicExponent().intValue());
        return b.array();
    }

    static byte[] sign(PrivateKey pk, byte[] token) throws GeneralSecurityException {
        byte[] data = new byte[SHA1_PREFIX.length + token.length];
        System.arraycopy(SHA1_PREFIX, 0, data, 0, SHA1_PREFIX.length);
        System.arraycopy(token, 0, data, SHA1_PREFIX.length, token.length);
        try {
            Signature s = Signature.getInstance("NONEwithRSA");
            s.initSign(pk);
            s.update(data);
            return s.sign();
        } catch (GeneralSecurityException e) {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, pk);
            return c.doFinal(data);
        }
    }
}
