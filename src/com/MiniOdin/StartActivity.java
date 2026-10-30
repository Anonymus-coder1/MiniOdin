package com.MiniOdin;

import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

// Ecran de demarrage : detecte l'etat du telephone et propose de le redemarrer en Download Mode via ADB
public class StartActivity extends Activity {
    TextView status, note;
    Button reboot, go;
    UsbManager um;
    Handler h = new Handler();
    boolean running, sent, opened;
    int last = -1;

    Odin.Cb cb = new Odin.Cb() {
        public void say(String m) { Odin.J.append(m + "\n"); }
        public void progress(int p) {}
    };

    Runnable poll = new Runnable() {
        public void run() {
            if (!running) return;
            refresh();
            h.postDelayed(this, 1500);
        }
    };

    public void onCreate(Bundle s) {
        Ui.prep(this);
        super.onCreate(s);
        try { build(); } catch (Throwable t) {
            TextView v = new TextView(this);
            v.setText("" + t);
            setContentView(v);
        }
    }

    void build() {
        um = (UsbManager) getSystemService(USB_SERVICE);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(Ui.dp(this, 24), Ui.dp(this, 32), Ui.dp(this, 24), Ui.dp(this, 24));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        l.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 120)));

        TextView title = new TextView(this);
        title.setText("Mini Odin");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        l.addView(title);

        status = new TextView(this);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 8));
        l.addView(status);

        note = new TextView(this);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, 0, 0, Ui.dp(this, 16));
        l.addView(note);

        reboot = Ui.button(this, Lang.t("s_reboot"), new View.OnClickListener() {
            public void onClick(View v) { rebootDownload(); }
        });
        l.addView(reboot);
        go = Ui.button(this, Lang.t("s_go"), new View.OnClickListener() {
            public void onClick(View v) { open(); }
        });
        l.addView(go);
        l.addView(Ui.row(this,
            Ui.button(this, Lang.t("lang"), new View.OnClickListener() {
                public void onClick(View v) { Lang.toggle(StartActivity.this); recreate(); }
            }),
            Ui.button(this, Lang.t(Ui.light(this) ? "themeD" : "themeL"), new View.OnClickListener() {
                public void onClick(View v) {
                    getSharedPreferences("p", 0).edit().putBoolean("light", !Ui.light(StartActivity.this)).commit();
                    recreate();
                }
            })));
        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        setContentView(sv);
        refresh();
    }

    void open() {
        startActivity(new Intent(this, MainActivity.class));
    }

    protected void onResume() {
        super.onResume();
        running = true;
        h.post(poll);
    }

    protected void onPause() {
        super.onPause();
        running = false;
    }

    // Met a jour le texte et les boutons selon ce qui est branche
    void refresh() {
        if (um == null) return;
        int st = Usb.state(um);
        if (st != last) {
            last = st;
            String[] k = {"s_none", "s_other", "s_normal", "s_adb", "s_dl"};
            status.setText(Lang.t(k[st]));
            reboot.setEnabled(st == Usb.ADB);
            if (st != Usb.ADB) note.setText("");
        }
        if (st == Usb.DOWNLOAD && sent && !opened) { opened = true; open(); }
    }

    // Envoie "reboot:download" en ADB (le telephone peut demander d'autoriser le debogage USB)
    void rebootDownload() {
        final UsbDevice d = Usb.best;
        if (d == null || last != Usb.ADB) return;
        reboot.setEnabled(false);
        note.setText(Lang.t("s_auth"));
        Usb.withPerm(this, d, new Runnable() {
            public void run() {
                new Thread(new Runnable() {
                    public void run() {
                        final boolean ok = Adb.reboot(um, d, "reboot:download", getApplicationContext(), cb);
                        runOnUiThread(new Runnable() {
                            public void run() {
                                note.setText(Lang.t(ok ? "s_sent" : "s_fail"));
                                sent = ok;
                                last = -1;
                                refresh();
                            }
                        });
                    }
                }).start();
            }
        }, new Runnable() {
            public void run() {
                note.setText(Lang.x("Permission USB refusee", "USB permission denied"));
                last = -1;
                refresh();
            }
        });
    }
}
