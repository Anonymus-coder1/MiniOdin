package com.MiniOdin;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

public class FreeFlashActivity extends Base {
    static String file, part;
    static Uri fileUri;
    static final String[] PARTS = {"AUTO", "BOOT", "RECOVERY", "SYSTEM", "VENDOR", "PRODUCT",
        "USERDATA", "CACHE", "MODEM", "DTBO", "VBMETA", "BOOTLOADER", "INIT_BOOT", "VENDOR_BOOT", "SUPER"};
    static final String[] SLOTS = {"-", "_a", "_b"};
    TextView info;
    Spinner sp, ss;

    public void onCreate(Bundle s) {
        super.onCreate(s);
        try { build(); } catch (Throwable t) { showCrash(t); }
    }

    void build() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(24, 24, 24, 24);

        Button pick = new Button(this);
        pick.setText(Lang.t("pick"));
        pick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                it.setType("*/*");
                startActivityForResult(it, 1);
            }
        });
        l.addView(pick);

        info = new TextView(this);
        show();
        l.addView(info);

        // Target partition (AUTO = detect from name) + A/B slot
        sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, PARTS));
        l.addView(sp);
        ss = new Spinner(this);
        ss.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, SLOTS));
        l.addView(ss);

        Button go = new Button(this);
        go.setText(Lang.t("flash"));
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askFlash(); }
        });
        l.addView(go);

        Button rm = new Button(this);
        rm.setText(Lang.t("clearfiles"));
        rm.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { file = null; part = null; fileUri = null; show(); }
        });
        l.addView(rm);

        addTail(l);
        setPager(l);
    }

    void show() {
        info.setText(file == null ? Lang.t("none") : file + "\n-> " + (part == null ? Lang.t("unk") : part));
    }

    // Chosen partition, else detected
    String target() {
        String p = PARTS[sp.getSelectedItemPosition()];
        if (p.equals("AUTO")) p = part;
        if (p == null) return null;
        String sl = SLOTS[ss.getSelectedItemPosition()];
        if (sl.equals("-") && Opts.on(this, "slotb")) sl = "_b";
        return p + (sl.equals("-") ? "" : sl);
    }

    void askFlash() {
        final String t = target();
        if (file == null || t == null || fileUri == null) { cb.say(Lang.t("nofile")); return; }
        final String w = t + "=" + file + Opts.summary(this);
        final Uri u = fileUri;
        final String fn = file;
        guard(t.startsWith("USERDATA") || Opts.on(this, "erase"), new Runnable() {
            public void run() {
                Odin.start(w, cb, new Odin.Step() {
                    public List<Odin.Job> run() {
                        try {
                            List<Odin.Job> l = new ArrayList<Odin.Job>();
                            l.add(new Odin.Job(fn, Fw.copy(FreeFlashActivity.this, u, fn, cb), t.toLowerCase(), false));
                            return l;
                        } catch (Throwable e) {
                            cb.say(Lang.x("Preparation impossible : ", "Cannot prepare: ") + e);
                            return null;
                        }
                    }
                });
            }
        });
    }

    protected void onActivityResult(int req, int res, Intent data) {
        if (res != RESULT_OK || data == null) return;
        try {
            Uri u = data.getData();
            String n = Odin.name(this, u);
            file = n == null ? Lang.x("fichier", "file") : n;
            fileUri = u;
            part = Odin.detect(file);
            show();
        } catch (Throwable e) {
            cb.say("" + e);
        }
    }
}
