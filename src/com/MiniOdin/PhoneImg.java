package com.MiniOdin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONObject;

// Phone picture: Google (Programmable Search, your key) else Wikipedia
public class PhoneImg {
    public interface Done { void got(Bitmap b, String source); }

    static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "MiniOdin/1.0");
        InputStream in = c.getInputStream();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toString("UTF-8");
    }

    static Bitmap bitmap(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "MiniOdin/1.0");
        InputStream in = c.getInputStream();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            o.write(b, 0, n);
            if (o.size() > 6 * 1024 * 1024) break;
        }
        in.close();
        byte[] d = o.toByteArray();
        BitmapFactory.Options op = new BitmapFactory.Options();
        op.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(d, 0, d.length, op);
        int s = 1;
        while (op.outHeight / s > 800) s *= 2;
        op = new BitmapFactory.Options();
        op.inSampleSize = s;
        return BitmapFactory.decodeByteArray(d, 0, d.length, op);
    }

    public static String query(String model) {
        String m = model.startsWith("SM-") || !model.matches("[A-Z][0-9]{3}.*") ? model : "SM-" + model;
        return "Samsung Galaxy " + m;
    }

    public static void load(final Context c, final String model, final Done done) {
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                Bitmap bm = null;
                String src = "";
                try {
                    String key = c.getSharedPreferences("p", 0).getString("gkey", "");
                    String cx = c.getSharedPreferences("p", 0).getString("gcx", "");
                    if (key.length() > 0 && cx.length() > 0) {
                        String u = "https://www.googleapis.com/customsearch/v1?key=" + URLEncoder.encode(key, "UTF-8")
                            + "&cx=" + URLEncoder.encode(cx, "UTF-8") + "&searchType=image&num=5&imgType=photo&q="
                            + URLEncoder.encode(query(model), "UTF-8");
                        JSONObject j = new JSONObject(get(u));
                        JSONArray it = j.optJSONArray("items");
                        for (int i = 0; it != null && i < it.length() && bm == null; i++) {
                            try { bm = bitmap(it.getJSONObject(i).getString("link")); src = "Google"; } catch (Exception e) { /* next image */ }
                        }
                    }
                } catch (Exception e) { /* fall back to Wikipedia */ }
                if (bm == null) {
                    try {
                        String u = "https://en.wikipedia.org/w/api.php?action=query&generator=search&gsrlimit=1&prop=pageimages"
                            + "&piprop=thumbnail&pithumbsize=500&format=json&gsrsearch=" + URLEncoder.encode(query(model), "UTF-8");
                        JSONObject pages = new JSONObject(get(u)).getJSONObject("query").getJSONObject("pages");
                        String k = pages.keys().next();
                        String t = pages.getJSONObject(k).getJSONObject("thumbnail").getString("source");
                        bm = bitmap(t);
                        src = "Wikipedia";
                    } catch (Exception e) { /* no image */ }
                }
                final Bitmap fb = bm;
                final String fs = src;
                h.post(new Runnable() { public void run() { done.got(fb, fs); } });
            }
        }).start();
    }
}
