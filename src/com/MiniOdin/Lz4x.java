package com.MiniOdin;

import java.io.InputStream;

// Décompression LZ4 (format "frame" des .lz4 Samsung) avec la lib lz4-java.
// Chargée par réflexion : l'app compile et tourne même si le .jar n'est pas encore là.
public class Lz4x {
    public static boolean available() {
        try { Class.forName("net.jpountz.lz4.LZ4FrameInputStream"); return true; } catch (Throwable t) { return false; }
    }

    public static InputStream wrap(InputStream in) throws Exception {
        try {
            return (InputStream) Class.forName("net.jpountz.lz4.LZ4FrameInputStream")
                .getConstructor(InputStream.class).newInstance(in);
        } catch (ClassNotFoundException e) {
            throw new Exception(Lang.x("lib lz4-java absente : mets son .jar dans libs/ puis recompile", "lz4-java lib missing: put its .jar in libs/ then rebuild"));
        }
    }
}
