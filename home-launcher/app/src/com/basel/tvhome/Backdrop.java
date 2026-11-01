/*
 * Glass Home for TCL Google TV
 * Copyright (c) 2026 Basel Majid
 * Licensed under the MIT License (see LICENSE).
 */
package com.basel.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Dark matte backdrop (soft top light + fine grain), with an optional, heavily dimmed wallpaper
 * that cross-fades in/out. Static between changes; also feeds the glass its refraction source.
 */
final class Backdrop extends View {

    interface FrostListener { void onFrost(); }

    private Bitmap matte, wall, matteFrost, wallFrost;
    private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint wp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final RectF dst = new RectF();
    private FrostListener listener;
    private boolean wallOn, loading;
    private float wallAlpha, calm;
    private final Paint calmP = new Paint();
    private android.animation.ValueAnimator fade;

    Backdrop(Context c) { super(c); }

    void setFrostListener(FrostListener l) { listener = l; }

    boolean wallpaperOn() { return wallOn; }

    /** Extra dimming used by Focus View (0..1). */
    void setCalm(float v) { calm = v; invalidate(); }

    void setWallpaper(boolean on, boolean animate) {
        wallOn = on;
        if (on && wall == null) { loadWallpaper(); return; }
        fadeTo(on ? 1f : 0f, animate);
    }

    private void loadWallpaper() {
        final int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || loading) return;
        loading = true;
        final Context ctx = getContext().getApplicationContext();
        Images.POOL.execute(new Runnable() {
            @Override public void run() {
                Bitmap comp = null, fr = null;
                try {
                    java.io.InputStream in = ctx.getAssets().open("wallpaper.jpg");
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 2;
                    Bitmap src = android.graphics.BitmapFactory.decodeStream(in, null, o);
                    in.close();
                    if (src != null) {
                        comp = composeWallpaper(src, Math.max(1, w / 2), Math.max(1, h / 2));
                        src.recycle();
                        fr = Bitmap.createScaledBitmap(comp, 480, Math.max(1, Math.round(480f * h / w)), true);
                    }
                } catch (Throwable t) {
                    android.util.Log.w(Glass.TAG, "WALLPAPER " + t);
                }
                final Bitmap fc = comp, ff = fr;
                Images.MAIN.post(new Runnable() {
                    @Override public void run() {
                        loading = false;
                        if (fc == null) return;
                        wall = fc;
                        wallFrost = ff;
                        fadeTo(wallOn ? 1f : 0f, true);
                    }
                });
            }
        });
    }

    private void fadeTo(final float target, boolean animate) {
        if (fade != null) fade.cancel();
        if (!animate) { wallAlpha = target; applyFrost(); invalidate(); return; }
        fade = android.animation.ValueAnimator.ofFloat(wallAlpha, target);
        fade.setDuration(650);
        fade.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        fade.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            boolean swapped;
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                wallAlpha = (float) a.getAnimatedValue();
                if (!swapped && Math.abs(wallAlpha - target) < 0.5f) { swapped = true; applyFrost(); }
                invalidate();
            }
        });
        fade.start();
    }

    private void applyFrost() {
        Glass.screenSharp = wallAlpha >= 0.5f && wall != null ? wall : matte;
        Bitmap f = wallAlpha >= 0.5f && wallFrost != null ? wallFrost : matteFrost;
        if (f != null && f != Glass.screenFrost) {
            Glass.screenFrost = f;
            if (listener != null) listener.onFrost();
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w == 0 || h == 0) return;
        Glass.screenW = w;
        Glass.screenH = h;
        matte = buildMatte(Math.max(1, w / 2), Math.max(1, h / 2));
        matteFrost = Bitmap.createScaledBitmap(matte, 480, Math.max(1, Math.round(480f * h / w)), true);
        applyFrost();
        if (wallOn && wall == null) loadWallpaper();
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if (matte == null) { c.drawColor(0xFF070708); return; }
        dst.set(0, 0, getWidth(), getHeight());
        if (wall == null || wallAlpha < 1f) c.drawBitmap(matte, null, dst, bp);
        if (wall != null && wallAlpha > 0f) {
            wp.setAlpha(Math.round(wallAlpha * 255));
            c.drawBitmap(wall, null, dst, wp);
        }
        if (calm > 0f) {
            calmP.setColor(Math.round(calm * 0x59) << 24);
            c.drawRect(dst, calmP);
        }
    }

    /** Wallpaper, dimmed to ~36%, slightly desaturated, with reading scrims, vignette and grain. */
    static Bitmap composeWallpaper(Bitmap src, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF060607);
        Rect s = new Rect();
        cropRect(src, w, h, s);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(0.82f);
        android.graphics.ColorMatrix dim = new android.graphics.ColorMatrix();
        dim.setScale(0.36f, 0.36f, 0.38f, 1f);
        cm.postConcat(dim);
        p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        c.drawBitmap(src, s, new RectF(0, 0, w, h), p);
        Paint g = new Paint(Paint.DITHER_FLAG);
        g.setShader(new LinearGradient(0, h * 0.45f, 0, h, 0x00000000, 0x8C000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, g);
        g.setShader(new LinearGradient(0, 0, w * 0.45f, 0, 0x59000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, g);
        g.setShader(new RadialGradient(w * 0.5f, h * 0.45f, w * 0.72f, 0x00000000, 0x66000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, g);
        addGrain(b, 2);
        return b;
    }

    static void addGrain(Bitmap b, int amp) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        java.util.Random r = new java.util.Random(11);
        for (int i = 0; i < px.length; i++) {
            int n = r.nextInt(2 * amp + 1) - amp, q = px[i];
            int R = Math.max(0, Math.min(255, ((q >> 16) & 255) + n));
            int G = Math.max(0, Math.min(255, ((q >> 8) & 255) + n));
            int B = Math.max(0, Math.min(255, (q & 255) + n));
            px[i] = 0xFF000000 | (R << 16) | (G << 8) | B;
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    static Bitmap buildMatte(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, new int[]{0xFF202226, 0xFF111215, 0xFF08080A},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w * 0.16f, -h * 0.22f, w * 0.95f, 0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w * 1.02f, h * 0.30f, w * 0.55f, 0x0DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w * 0.5f, h * 0.45f, w * 0.78f, 0x00000000, 0x59000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        // fine monochrome grain -> matte finish, no banding
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < px.length; i++) {
            int n = r.nextInt(5) - 2, q = px[i];
            int R = Math.max(0, Math.min(255, ((q >> 16) & 255) + n));
            int G = Math.max(0, Math.min(255, ((q >> 8) & 255) + n));
            int B = Math.max(0, Math.min(255, (q & 255) + n));
            px[i] = 0xFF000000 | (R << 16) | (G << 8) | B;
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    static void cropRect(Bitmap b, float w, float h, Rect out) {
        float bw = b.getWidth(), bh = b.getHeight();
        float s = Math.max(w / bw, h / bh);
        float cw = w / s, ch = h / s;
        int l = Math.round((bw - cw) / 2f), t = Math.round((bh - ch) / 2f);
        out.set(l, t, Math.round(l + cw), Math.round(t + ch));
    }

    /** Small, blurred copy of an artwork (for LOCAL glass plates). */
    static Bitmap frostOf(Bitmap src, int fw, int fh, int radius) {
        Bitmap f = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(f);
        Rect s = new Rect();
        cropRect(src, fw, fh, s);
        c.drawBitmap(src, s, new RectF(0, 0, fw, fh), new Paint(Paint.FILTER_BITMAP_FLAG));
        blur(f, radius, 3);
        return f;
    }

    /** In-place separable box blur (opaque), repeated passes approximate a Gaussian. */
    static void blur(Bitmap b, int r, int passes) {
        int w = b.getWidth(), h = b.getHeight();
        int[] a = new int[w * h], t = new int[w * h];
        b.getPixels(a, 0, w, 0, 0, w, h);
        for (int i = 0; i < passes; i++) {
            blurH(a, t, w, h, r);
            blurV(t, a, w, h, r);
        }
        b.setPixels(a, 0, w, 0, 0, w, h);
    }

    private static void blurH(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w, rs = 0, gs = 0, bs = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[row + Math.min(w - 1, Math.max(0, i))];
                rs += (c >> 16) & 255; gs += (c >> 8) & 255; bs += c & 255;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = 0xFF000000 | ((rs / div) << 16) | ((gs / div) << 8) | (bs / div);
                int ad = in[row + Math.min(w - 1, x + r + 1)];
                int rm = in[row + Math.max(0, x - r)];
                rs += ((ad >> 16) & 255) - ((rm >> 16) & 255);
                gs += ((ad >> 8) & 255) - ((rm >> 8) & 255);
                bs += (ad & 255) - (rm & 255);
            }
        }
    }

    private static void blurV(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int rs = 0, gs = 0, bs = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[Math.min(h - 1, Math.max(0, i)) * w + x];
                rs += (c >> 16) & 255; gs += (c >> 8) & 255; bs += c & 255;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = 0xFF000000 | ((rs / div) << 16) | ((gs / div) << 8) | (bs / div);
                int ad = in[Math.min(h - 1, y + r + 1) * w + x];
                int rm = in[Math.max(0, y - r) * w + x];
                rs += ((ad >> 16) & 255) - ((rm >> 16) & 255);
                gs += ((ad >> 8) & 255) - ((rm >> 8) & 255);
                bs += (ad & 255) - (rm & 255);
            }
        }
    }
}
