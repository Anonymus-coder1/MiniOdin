package com.MiniOdin;

import android.app.Application;
import android.content.Context;
import java.io.PrintWriter;
import java.io.StringWriter;

// Keeps the last crash to show it in the log on next launch
public class App extends Application {
    static Context ctx; // global context for flash threads

    public void onCreate() {
        super.onCreate();
        ctx = getApplicationContext();
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter w = new StringWriter();
                    e.printStackTrace(new PrintWriter(w));
                    getSharedPreferences("p", 0).edit().putString("crash", w.toString()).commit();
                } catch (Throwable x) {}
                if (old != null) old.uncaughtException(t, e);
            }
        });
    }
}
