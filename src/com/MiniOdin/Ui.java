package com.MiniOdin;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

// Small shared helpers: theme, sizes, buttons
public class Ui {
    static boolean light(Context c) { return c.getSharedPreferences("p", 0).getBoolean("light", false); }

    // Call first in onCreate, before super.onCreate
    static void prep(Activity a) {
        Lang.load(a);
        a.setTheme(light(a) ? android.R.style.Theme_DeviceDefault_Light : android.R.style.Theme_DeviceDefault);
    }

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static Button button(Context c, String text, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    // Horizontal row, equal widths
    static LinearLayout row(Context c, View... views) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return r;
    }
}
