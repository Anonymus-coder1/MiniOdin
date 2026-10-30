package com.MiniOdin;

import java.io.InputStream;

// LZ4 frame decompression (Samsung .lz4) with lz4-java.
// Loaded by reflection: the app builds even without the .jar.
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
