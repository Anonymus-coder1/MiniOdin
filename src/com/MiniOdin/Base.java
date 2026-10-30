package com.MiniOdin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

// Base screen: 2 sliding pages (flash + log / options)
public class Base extends Activity {
    TextView log, left, right;
    ProgressBar bar;
    View[] pages = new View[2];

    Odin.Cb cb = new Odin.Cb() {
        public void say(String m) {
            final String t = new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + m + "\n";
            Odin.J.append(t);
            runOnUiThread(new Runnable() { public void run() { log.append(t); } });
        }
        public void progress(final int p) {
            runOnUiThread(new Runnable() { public void run() { bar.setProgress(p); } });
        }
    };

    boolean light() { return Ui.light(this); }

    void setLight(boolean v) {
        getSharedPreferences("p", 0).edit().putBoolean("light", v).commit();
        recreate();
    }

    public void onCreate(Bundle s) {
        Ui.prep(this); // language + theme, before super.onCreate
        super.onCreate(s);
        String c = getSharedPreferences("p", 0).getString("crash", null);
        if (c != null) {
            Odin.J.append(Lang.x("Crash precedent :\n", "Previous crash:\n") + c + "\n");
            getSharedPreferences("p", 0).edit().remove("crash").commit();
        }
    }

    void addTail(LinearLayout l) {
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        l.addView(bar);
        log = new TextView(this);
        log.setText(Odin.J.toString());
        l.addView(log);
    }

    int cur = 0;
    GestureDetector gd;

    // Page 0 = flash + log, page 1 = options
    void setPager(LinearLayout flash) {
        flash.setPadding(96, 24, 96, 24);
        ScrollView s0 = new ScrollView(this);
        s0.addView(flash);
        ScrollView s1 = new ScrollView(this);
        s1.addView(optionsPage());
        pages[0] = s0;
        pages[1] = s1;
        FrameLayout f = new FrameLayout(this);
        f.addView(s0);
        f.addView(s1);
        right = arrow(f, ">", Gravity.RIGHT | Gravity.CENTER_VERTICAL, 1);
        left = arrow(f, "<", Gravity.LEFT | Gravity.CENTER_VERTICAL, 0);
        pages[1].setTranslationX(getResources().getDisplayMetrics().widthPixels);
        left.setAlpha(0f);
        left.setEnabled(false);
        // swipe to change page
        gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || b == null) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.abs(dx) > 200 && Math.abs(dx) > Math.abs(dy) * 2) go(dx < 0 ? 1 : 0);
                return false;
            }
        });
        setContentView(f);
    }

    public boolean dispatchTouchEvent(MotionEvent e) {
        if (gd != null) gd.onTouchEvent(e);
        return super.dispatchTouchEvent(e);
    }

    TextView arrow(FrameLayout f, String t, int g, final int target) {
        TextView a = new TextView(this);
        a.setText(t);
        a.setTextSize(32);
        a.setPadding(14, 60, 14, 60);
        a.setBackgroundColor(0x33888888);
        a.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { go(target); } });
        f.addView(a, new FrameLayout.LayoutParams(-2, -2, g));
        return a;
    }

    // Slide to page p (0 or 1)
    void go(int p) {
        if (p == cur || pages[0] == null) return;
        cur = p;
        int w = getResources().getDisplayMetrics().widthPixels;
        pages[0].animate().translationX(p == 0 ? 0 : -w).setDuration(280).setInterpolator(new DecelerateInterpolator());
        pages[1].animate().translationX(p == 0 ? w : 0).setDuration(280).setInterpolator(new DecelerateInterpolator());
        left.animate().alpha(p == 1 ? 1f : 0f).setDuration(280);
        right.animate().alpha(p == 0 ? 1f : 0f).setDuration(280);
        left.setEnabled(p == 1);
        right.setEnabled(p == 0);
    }

    LinearLayout optionsPage() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(96, 24, 96, 24);
        TextView h = new TextView(this);
        h.setText(Lang.t("opts"));
        h.setTextSize(22);
        l.addView(h);
        for (int i = 0; i < Opts.K.length; i++) {
            final int idx = i;
            final CheckBox c = new CheckBox(this);
            c.setText(Opts.L[i][Lang.en ? 1 : 0]);
            c.setChecked(Opts.on(this, Opts.K[i]));
            c.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(final CompoundButton v, boolean on) {
                    if (!on) { Opts.set(Base.this, Opts.K[idx], false); return; }
                    // warn on every activation
                    new AlertDialog.Builder(Base.this).setTitle(Lang.t("warn").replace("USERDATA", "").trim())
                        .setMessage(Lang.t("w_" + Opts.K[idx]))
                        .setCancelable(false)
                        .setNegativeButton(Lang.t("cancel"), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int x) { v.setChecked(false); }
                        })
                        .setPositiveButton(Lang.t("sure"), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int x) { Opts.set(Base.this, Opts.K[idx], true); }
                        }).show();
                }
            });
            l.addView(c);
        }
        // light/dark theme (no warning needed)
        CheckBox th = new CheckBox(this);
        th.setText(Lang.t("themeL"));
        th.setChecked(light());
        th.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) { setLight(on); }
        });
        l.addView(th);
        return l;
    }

    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, Lang.t("lang"));
        m.add(0, 4, 0, Lang.t(light() ? "themeD" : "themeL"));
        m.add(0, 2, 0, Lang.t("share"));
        m.add(0, 5, 0, Lang.t("sharetxt"));
        m.add(0, 3, 0, Lang.t("clearlog"));
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem i) {
        if (i.getItemId() == 1) { Lang.toggle(this); recreate(); }
        else if (i.getItemId() == 4) setLight(!light());
        else if (i.getItemId() == 3) { Odin.J.setLength(0); log.setText(""); bar.setProgress(0); }
        else if (i.getItemId() == 5) shareLog("txt");
        else shareLog("log");
        return true;
    }

    // On build failure, show the error instead of closing
    void showCrash(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        TextView v = new TextView(this);
        v.setText("" + w);
        v.setTextSize(11);
        v.setTextIsSelectable(true);
        v.setPadding(24, 24, 24, 24);
        ScrollView sv = new ScrollView(this);
        sv.addView(v);
        setContentView(sv);
    }

    // Write the log to a real file (.log/.txt) and share it
    void shareLog(String ext) {
        try {
            File[] old = getCacheDir().listFiles();
            if (old != null) for (File x : old) if (x.getName().startsWith("minodin-")) x.delete();
            String name = "minodin-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + "." + ext;
            FileOutputStream fo = new FileOutputStream(new File(getCacheDir(), name));
            fo.write(Odin.J.toString().getBytes("UTF-8"));
            fo.close();
            Uri u = Uri.parse("content://com.MiniOdin.files/" + name);
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/plain");
            s.putExtra(Intent.EXTRA_STREAM, u);
            s.setClipData(ClipData.newRawUri(name, u));
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, name));
        } catch (Throwable e) {
            cb.say("" + e);
        }
    }

    // Confirm before risky actions
    void guard(boolean wipe, final Runnable go) {
        String msg = (wipe ? Lang.t("wipe") + "\n\n" : "") + (Opts.on(this, "repart") ? Lang.t("repart") : "");
        if (msg.length() == 0) { go.run(); return; }
        new AlertDialog.Builder(this).setTitle(Lang.t("warn")).setMessage(msg.trim())
            .setNegativeButton(Lang.t("cancel"), null)
            .setPositiveButton(Lang.t("yes"), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int x) { go.run(); }
            }).show();
    }
}
