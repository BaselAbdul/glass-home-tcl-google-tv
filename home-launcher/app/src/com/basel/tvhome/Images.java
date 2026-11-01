/*
 * Glass Home for TCL Google TV
 * Copyright (c) 2026 Basel Majid
 * Licensed under the MIT License (see LICENSE).
 */
package com.basel.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny async image loader with down-sampling and an in-memory LRU cache. */
final class Images {
    private Images() { }

    interface Callback { void done(Bitmap b); }

    static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
    };

    static void load(final Context ctx, final String uri, final int maxW, final Callback cb) {
        if (uri == null || uri.length() == 0) { cb.done(null); return; }
        final String key = uri + "#" + maxW;
        Bitmap hit = CACHE.get(key);
        if (hit != null) { cb.done(hit); return; }
        final Context app = ctx.getApplicationContext();
        POOL.execute(new Runnable() {
            @Override public void run() {
                Bitmap b = null;
                try {
                    byte[] data = read(app, uri);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    int s = 1;
                    while (o.outWidth / (s * 2) >= maxW) s *= 2;
                    BitmapFactory.Options d = new BitmapFactory.Options();
                    d.inSampleSize = s;
                    d.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    b = BitmapFactory.decodeByteArray(data, 0, data.length, d);
                } catch (Throwable ignored) { }
                if (b != null) CACHE.put(key, b);
                final Bitmap fb = b;
                MAIN.post(new Runnable() { @Override public void run() { cb.done(fb); } });
            }
        });
    }

    private static byte[] read(Context ctx, String uri) throws IOException {
        InputStream in;
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            HttpURLConnection con = (HttpURLConnection) new URL(uri).openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(8000);
            con.setInstanceFollowRedirects(true);
            in = con.getInputStream();
        } else {
            in = ctx.getContentResolver().openInputStream(Uri.parse(uri));
        }
        if (in == null) throw new IOException("no stream");
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > MAX_BYTES) throw new IOException("too large");
            }
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }
}
