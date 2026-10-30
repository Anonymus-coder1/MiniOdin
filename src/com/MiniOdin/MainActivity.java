package com.MiniOdin;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Base {
    static String[] names = new String[5];
    static Uri[] uris = new Uri[5];
    static final int REQ_BOOT = 10;
    Button[] btn = new Button[5];

    public void onCreate(Bundle s) {
        super.onCreate(s);
        try { build(); } catch (Throwable t) { showCrash(t); }
    }

    void build() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);

        // Row 1: connect and USB info
        l.addView(Ui.row(this,
            Ui.button(this, Lang.t("connect"), new View.OnClickListener() {
                public void onClick(View v) { Odin.connect(MainActivity.this, cb); }
            }),
            Ui.button(this, Lang.t("usbinfo"), new View.OnClickListener() {
                public void onClick(View v) { Odin.usbInfo(MainActivity.this, cb); }
            })));

        // Row 2: boot analysis and free flash
        l.addView(Ui.row(this,
            Ui.button(this, Lang.t("analyze"), new View.OnClickListener() {
                public void onClick(View v) {
                    Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    it.setType("*/*");
                    startActivityForResult(it, REQ_BOOT);
                }
            }),
            Ui.button(this, Lang.t("free"), new View.OnClickListener() {
                public void onClick(View v) { startActivity(new Intent(MainActivity.this, FreeFlashActivity.class)); }
            })));

        // The 5 file slots
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            btn[i] = Ui.button(this, Odin.SLOTS[i] + " : " + (names[i] == null ? Lang.t("empty") : names[i]), new View.OnClickListener() {
                public void onClick(View v) {
                    Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    it.setType("*/*");
                    startActivityForResult(it, idx);
                }
            });
            btn[i].setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) { clear(idx); return true; }
            });
            l.addView(btn[i]);
        }

        // START, then remove files
        l.addView(Ui.button(this, Lang.t("start"), new View.OnClickListener() {
            public void onClick(View v) { askStart(); }
        }));
        l.addView(Ui.button(this, Lang.t("clearfiles"), new View.OnClickListener() {
            public void onClick(View v) { for (int i = 0; i < 5; i++) clear(i); }
        }));

        addTail(l);
        setPager(l);
        if (Odin.J.length() == 0) cb.say("Mini Odin build " + Odin.BUILD);
        // Already in Download Mode: auto-connect (PIT is read automatically)
        if (!Odin.connected() && Usb.state((android.hardware.usb.UsbManager) getSystemService(USB_SERVICE)) == Usb.DOWNLOAD)
            Odin.connect(this, cb);
    }

    void clear(int i) {
        names[i] = null;
        uris[i] = null;
        btn[i].setText(Odin.SLOTS[i] + " : " + Lang.t("empty"));
    }

    void askStart() {
        String what = "";
        for (int i = 0; i < 5; i++) if (names[i] != null) what += Odin.SLOTS[i] + "=" + names[i] + " ";
        if (what.length() == 0) { cb.say(Lang.t("nofile")); return; }
        final String w = what + Opts.summary(this);
        guard(names[4] != null || Opts.on(this, "erase"), new Runnable() {
            public void run() {
                final Uri[] us = uris.clone();
                final String[] ns = names.clone();
                Odin.start(w, cb, new Odin.Step() {
                    public List<Odin.Job> run() {
                        List<Odin.Job> all = new ArrayList<Odin.Job>();
                        for (int i = 0; i < 5; i++) {
                            if (us[i] == null || ns[i] == null) continue;
                            try {
                                if (ns[i].contains(".tar")) {
                                    List<Odin.Job> j = Fw.prepare(MainActivity.this, us[i], ns[i], cb);
                                    if (j == null) return null;
                                    all.addAll(j);
                                } else {
                                    String p = i == 4 ? "USERDATA" : Odin.detect(ns[i]);
                                    if (p == null) { cb.say(Lang.x("Fichier non reconnu : ", "Unrecognised file: ") + ns[i]); continue; }
                                    File f = Fw.copy(MainActivity.this, us[i], ns[i], cb);
                                    all.add(new Odin.Job(ns[i], f, p.toLowerCase(), false));
                                }
                            } catch (Throwable e) {
                                cb.say(Lang.x("Preparation impossible : ", "Cannot prepare: ") + e);
                                return null;
                            }
                        }
                        return all;
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
            if (n == null) n = Lang.x("fichier", "file");
            if (req == REQ_BOOT) { analyze(u, n); return; }
            if (req < 0 || req > 4) return;
            names[req] = n;
            uris[req] = u;
            btn[req].setText(Odin.SLOTS[req] + " : " + n);
            cb.say(Fw.info(n));
            if (n.contains(".tar")) Fw.scan(this, u, cb);
        } catch (Throwable e) {
            cb.say("" + e);
        }
    }

    // ---------- Boot analysis ----------
    void analyze(final Uri u, final String n) {
        cb.say(Lang.t("b_busy") + " " + n);
        new Thread(new Runnable() {
            public void run() {
                final StringBuilder rep = new StringBuilder();
                String model = null;
                try {
                    List<File> fs = Fw.bootFiles(MainActivity.this, u, n);
                    if (fs.isEmpty()) rep.append(Lang.t("b_none"));
                    for (File f : fs) {
                        BootInfo.Result r = BootInfo.analyze(f, f.getName());
                        rep.append(BootInfo.report(r, Lang.en)).append("\n\n");
                        if (model == null && r.model() != null) model = r.model();
                        f.delete();
                    }
                } catch (Throwable e) {
                    rep.append("" + e);
                }
                final String fm = model;
                runOnUiThread(new Runnable() { public void run() { showAnalysis(rep.toString().trim(), fm); } });
            }
        }).start();
    }

    void showAnalysis(String report, final String model) {
        cb.say(report);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);

        // Small phone picture window above the result
        final ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setBackgroundColor(0x22888888);
        box.addView(img, new LinearLayout.LayoutParams(-1, Ui.dp(this, 160)));
        final TextView st = new TextView(this);
        st.setGravity(android.view.Gravity.CENTER);
        box.addView(st);

        TextView t = new TextView(this);
        t.setText(report);
        t.setTextIsSelectable(true);
        t.setTextSize(12);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        box.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        if (model == null) {
            st.setText(Lang.t("b_noimg"));
        } else {
            st.setText(Lang.t("b_find") + " " + model);
            PhoneImg.load(this, model, new PhoneImg.Done() {
                public void got(Bitmap b, String src) {
                    if (b == null) { st.setText(Lang.t("b_noimg")); return; }
                    img.setImageBitmap(b);
                    st.setText(model + "  (" + src + ")");
                }
            });
        }

        AlertDialog.Builder d = new AlertDialog.Builder(this).setTitle(Lang.t("b_title")).setView(box)
            .setPositiveButton(Lang.t("b_close"), null)
            .setNegativeButton(Lang.t("b_key"), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface x, int w) { keyDialog(); }
            });
        if (model != null) d.setNeutralButton(Lang.t("b_gimg"), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface x, int w) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?tbm=isch&q="
                        + Uri.encode(PhoneImg.query(model)))));
                } catch (Throwable e) { cb.say("" + e); }
            }
        });
        d.show();
    }

    // Optional Google Images API key and engine id
    void keyDialog() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        TextView m = new TextView(this);
        m.setText(Lang.t("b_key_t"));
        b.addView(m);
        final EditText k = new EditText(this);
        k.setHint("API key");
        k.setSingleLine(true);
        k.setText(getSharedPreferences("p", 0).getString("gkey", ""));
        b.addView(k);
        final EditText c = new EditText(this);
        c.setHint("cx");
        c.setSingleLine(true);
        c.setText(getSharedPreferences("p", 0).getString("gcx", ""));
        b.addView(c);
        new AlertDialog.Builder(this).setTitle(Lang.t("b_key")).setView(b)
            .setNegativeButton(Lang.t("cancel"), null)
            .setPositiveButton(Lang.t("b_save"), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface x, int w) {
                    getSharedPreferences("p", 0).edit().putString("gkey", k.getText().toString().trim())
                        .putString("gcx", c.getText().toString().trim()).commit();
                }
            }).show();
    }
}
