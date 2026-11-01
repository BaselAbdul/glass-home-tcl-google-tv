/*
 * Glass Home for TCL Google TV
 * Copyright (c) 2026 Basel Majid
 * Licensed under the MIT License (see LICENSE).
 */
package com.basel.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

/**
 * Crystal-clear liquid-glass tile. Everything is drawn with cheap 2D primitives (no per-frame
 * shader): clear body, chromatic (red/blue) dispersion on rim and glyph, specular top edge,
 * and a light sweep + dispersion pulse when focus lands. Only the focused tile animates.
 */
final class GlassTile extends FrameLayout {
    private static final Interpolator OVER = new OvershootInterpolator(1.35f);
    private static final Interpolator EASE = new DecelerateInterpolator(1.7f);

    final float dp, radius;
    float scaleTo = 1.08f, glyphBoxH = 36f, glyphBoxW = 0.6f, glyphLift = 0f;
    Bitmap glyph;
    Drawable colorIcon;
    String label, big, small;
    Runnable onFocus;

    /** Optional custom content (e.g. the wallpaper switch icon). */
    interface Painter { void paint(Canvas c, float w, float h, float focus); }
    Painter painter;

    private Bitmap art, artBlur;
    private BitmapShader artSh;
    private boolean artContain;
    float artBottomInset = 0f; // px reserved below a contained (portrait) poster
    private final Paint blurP = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shade = new Paint();
    private final RectF posterR = new RectF();
    private final Matrix artM = new Matrix();
    private float focus, chroma, sweep = -1f;
    private ValueAnimator anim;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint redRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blueRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spec = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint gR = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint gB = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint tBig = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tSmall = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF(), tmp = new RectF();
    private final Rect ir = new Rect();
    private final Path clip = new Path();
    private final Matrix sweepM = new Matrix();
    private LinearGradient sweepSh;

    GlassTile(Context c, float dp, float radiusPx) {
        super(c);
        this.dp = dp;
        this.radius = radiusPx;
        setWillNotDraw(false);
        setFocusable(true);
        setClickable(true);
        setAlpha(0.9f);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
        rim.setStyle(Paint.Style.STROKE);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFFFFF);
        ring.setStrokeWidth(1.6f * dp);
        PorterDuffXfermode add = new PorterDuffXfermode(PorterDuff.Mode.ADD);
        for (Paint p : new Paint[]{redRim, blueRim}) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(0.8f * dp); p.setXfermode(add); }
        redRim.setColor(0xFFFF453A);
        blueRim.setColor(0xFF0A84FF);
        spec.setStyle(Paint.Style.STROKE);
        spec.setStrokeWidth(1.0f * dp);
        spec.setStrokeCap(Paint.Cap.ROUND);
        gR.setColorFilter(new PorterDuffColorFilter(0xFFFF4D4D, PorterDuff.Mode.SRC_IN));
        gR.setXfermode(add);
        gB.setColorFilter(new PorterDuffColorFilter(0xFF4DA6FF, PorterDuff.Mode.SRC_IN));
        gB.setXfermode(add);
        tBig.setColor(0xF2FFFFFF);
        tBig.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tBig.setTextAlign(Paint.Align.CENTER);
        tSmall.setColor(0x99FFFFFF);
        tSmall.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tSmall.setTextAlign(Paint.Align.CENTER);
        tSmall.setLetterSpacing(0.06f);
    }

    void setArt(Bitmap b) {
        art = b;
        artContain = b != null && b.getWidth() < b.getHeight() * 1.3f; // poster / square art
        artBlur = artContain ? Backdrop.frostOf(b, 64, 36, 3) : null;
        artSh = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        updateArtMatrix();
        invalidate();
    }

    private void updateArtMatrix() {
        if (art == null || artSh == null || getWidth() == 0) return;
        if (artContain) {
            float pad = 8f * dp, availH = getHeight() - artBottomInset - 2 * pad;
            float s = availH / art.getHeight();
            float pw = art.getWidth() * s;
            float left = (getWidth() - pw) / 2f;
            posterR.set(left, pad, left + pw, pad + availH);
            artM.setScale(s, s);
            artM.postTranslate(left, pad);
        } else {
            float s = Math.max(getWidth() / (float) art.getWidth(), getHeight() / (float) art.getHeight());
            artM.setScale(s, s);
            artM.postTranslate((getWidth() - art.getWidth() * s) / 2f, (getHeight() - art.getHeight() * s) / 2f);
        }
        artSh.setLocalMatrix(artM);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        rf.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rf, radius, radius, Path.Direction.CW);
        fill.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x1FFFFFFF, 0x0AFFFFFF, 0x05FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        // polished-chrome rim: alternating light/dark bands along the edge
        rim.setShader(new LinearGradient(0, 0, w, h,
                new int[]{0xF2FFFFFF, 0x38FFFFFF, 0xB3FFFFFF, 0x14FFFFFF, 0x8CFFFFFF},
                new float[]{0f, 0.22f, 0.46f, 0.74f, 1f}, Shader.TileMode.CLAMP));
        glint.setShader(new android.graphics.RadialGradient(w * 0.12f, -h * 0.1f, Math.max(w, h) * 0.75f,
                0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        spec.setShader(new LinearGradient(0, 0, w, 0, new int[]{0x00FFFFFF, 0xA6FFFFFF, 0x00FFFFFF},
                null, Shader.TileMode.CLAMP));
        float band = h * 0.9f;
        sweepSh = new LinearGradient(0, 0, band, band * 0.5f, new int[]{0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF},
                null, Shader.TileMode.CLAMP);
        sweepP.setShader(sweepSh);
        tBig.setTextSize(Math.min(15f * dp, h * 0.2f));
        tSmall.setTextSize(Math.min(10f * dp, h * 0.15f));
        updateArtMatrix();
    }

    @Override protected void onFocusChanged(boolean g, int dir, Rect prev) {
        super.onFocusChanged(g, dir, prev);
        animate().scaleX(g ? scaleTo : 1f).scaleY(g ? scaleTo : 1f).alpha(g ? 1f : 0.9f)
                .setDuration(g ? 320 : 180).setInterpolator(g ? OVER : EASE).start();
        animateGlass(g);
        if (g && onFocus != null) onFocus.run();
    }

    private void animateGlass(final boolean g) {
        if (anim != null) anim.cancel();
        final float f0 = focus;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(g ? 700 : 200);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float t = (float) a.getAnimatedValue();
                if (g) {
                    focus = f0 + (1f - f0) * EASE.getInterpolation(Math.min(1f, t / 0.45f));
                    chroma = (float) Math.sin(Math.PI * Math.min(1f, t / 0.6f)) * 1.4f;
                    sweep = -0.45f + 1.95f * t;
                } else {
                    focus = f0 * (1f - EASE.getInterpolation(t));
                    chroma = 0f;
                    sweep = -1f;
                }
                invalidate();
            }
        });
        anim.start();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (artSh != null && artContain) {
            if (artBlur != null) c.drawBitmap(artBlur, null, rf, blurP);
            shade.setColor(0x73000000);
            c.drawRect(rf, shade);
            artP.setShader(artSh);
            c.drawRoundRect(posterR, 6f * dp, 6f * dp, artP);
        } else if (artSh != null) {
            artP.setShader(artSh);
            c.drawRoundRect(rf, radius, radius, artP);
        } else {
            fill.setAlpha((int) (255 * (0.75f + 0.25f * focus)));
            c.drawRoundRect(rf, radius, radius, fill);
        }
        if (painter != null) { painter.paint(c, w, h, focus); return; }
        boolean text = big != null;
        float cx = w / 2f;
        float cy = (label != null ? h * 0.42f : h / 2f) - glyphLift * dp;
        if (!text && glyph != null) {
            float bw = w * glyphBoxW, bh = glyphBoxH * dp;
            float s = Math.min(bw / glyph.getWidth(), bh / glyph.getHeight());
            float gw = glyph.getWidth() * s, gh = glyph.getHeight() * s;
            tmp.set(cx - gw / 2f, cy - gh / 2f, cx + gw / 2f, cy + gh / 2f);
            float o = dp * (0.4f + 0.6f * focus + chroma);
            gR.setAlpha((int) (55 + 125 * focus));
            gB.setAlpha((int) (55 + 125 * focus));
            c.save(); c.translate(-o, 0); c.drawBitmap(glyph, null, tmp, gR); c.restore();
            c.save(); c.translate(o, 0); c.drawBitmap(glyph, null, tmp, gB); c.restore();
            glyphP.setAlpha((int) (215 + 40 * focus));
            c.drawBitmap(glyph, null, tmp, glyphP);
        } else if (!text && colorIcon != null) {
            float gs = glyphBoxH * dp;
            tmp.set(cx - gs / 2f, cy - gs / 2f, cx + gs / 2f, cy + gs / 2f);
            tmp.round(ir);
            colorIcon.setBounds(ir);
            colorIcon.draw(c);
        }
        if (text) {
            float y = small != null ? h * 0.5f : h * 0.5f + tBig.getTextSize() * 0.35f;
            c.drawText(ellipsize(big, tBig, w - 16 * dp), cx, y, tBig);
            if (small != null) c.drawText(ellipsize(small, tSmall, w - 16 * dp), cx, y + tSmall.getTextSize() * 1.9f, tSmall);
        } else if (label != null) {
            c.drawText(ellipsize(label, tSmall, w - 12 * dp), cx, h - 9 * dp, tSmall);
        }
    }

    @Override protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        float w = getWidth(), h = getHeight();
        // soft top-left light inside the glass (always faint, brighter on focus)
        c.save();
        c.clipPath(clip);
        glint.setAlpha((int) (110 + 145 * focus));
        c.drawRect(rf, glint);
        c.restore();
        // light sweep across the glass when focus lands
        if (sweep > -0.45f && sweep < 1.5f && sweepSh != null) {
            c.save();
            c.clipPath(clip);
            sweepM.setTranslate(sweep * (w + h) - h, 0);
            sweepSh.setLocalMatrix(sweepM);
            c.drawRect(rf, sweepP);
            c.restore();
        }
        // chromatic dispersion on the rim (red / blue split), stronger while moving
        float o = dp * (0.35f + 0.7f * focus + chroma * 0.8f);
        float i = 1.2f * dp + o;
        tmp.set(rf);
        tmp.inset(i, i);
        float rr = Math.max(0f, radius - i);
        redRim.setAlpha((int) (22 + 110 * focus));
        blueRim.setAlpha((int) (22 + 110 * focus));
        c.save(); c.translate(-o, -o * 0.5f); c.drawRoundRect(tmp, rr, rr, redRim); c.restore();
        c.save(); c.translate(o, o * 0.5f); c.drawRoundRect(tmp, rr, rr, blueRim); c.restore();
        // lit glass edge + focus ring
        rim.setStrokeWidth(dp * (1.0f + 0.5f * focus));
        c.drawRoundRect(tmp, rr, rr, rim);
        if (focus > 0.01f) {
            ring.setAlpha((int) (215 * focus));
            c.drawRoundRect(tmp, rr, rr, ring);
        }
        // specular highlight along the top edge
        float sy = i + 0.9f * dp;
        c.drawLine(radius, sy, w - radius, sy, spec);
    }

    private static String ellipsize(String s, TextPaint p, float max) {
        return TextUtils.ellipsize(s, p, max, TextUtils.TruncateAt.END).toString();
    }

    /**
     * "Clear" glyph: renders any icon/banner, detects its background (transparent or a solid
     * colour sampled from the border), removes it and returns the logo as a white alpha mask,
     * cropped to its bounds. Returns null when the artwork has no clean background.
     */
    static Bitmap clearGlyph(Drawable d, int w, int h) {
        if (d == null) return null;
        Bitmap src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(src);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int ring = Math.max(2, Math.min(w, h) / 40);
        long sr = 0, sg = 0, sb = 0;
        int opaque = 0, total = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x >= ring && y >= ring && x < w - ring && y < h - ring) continue;
                total++;
                int p = px[y * w + x];
                if ((p >>> 24) > 200) { opaque++; sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255; }
            }
        }
        boolean clearBg = opaque < total * 0.5;
        int br = 0, bgc = 0, bb = 0;
        if (!clearBg) {
            br = (int) (sr / opaque); bgc = (int) (sg / opaque); bb = (int) (sb / opaque);
            double var = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (x >= ring && y >= ring && x < w - ring && y < h - ring) continue;
                    int p = px[y * w + x];
                    if ((p >>> 24) <= 200) continue;
                    int dr = ((p >> 16) & 255) - br, dg = ((p >> 8) & 255) - bgc, db = (p & 255) - bb;
                    var += Math.sqrt(dr * dr + dg * dg + db * db);
                }
            }
            if (var / opaque > 38) return null; // busy/photographic background
        }
        int minX = w, minY = h, maxX = -1, maxY = -1, covered = 0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i], a = p >>> 24, oa;
            if (a == 0) oa = 0;
            else if (clearBg) oa = a;
            else {
                int dr = ((p >> 16) & 255) - br, dg = ((p >> 8) & 255) - bgc, db = (p & 255) - bb;
                double m = (Math.sqrt(dr * dr + dg * dg + db * db) - 22.0) / 70.0;
                m = m < 0 ? 0 : (m > 1 ? 1 : m);
                oa = (int) Math.round(a * m);
            }
            px[i] = (oa << 24) | 0x00FFFFFF;
            if (oa > 110) {
                covered++;
                int x = i % w, y = i / w;
                if (x < minX) minX = x; if (x > maxX) maxX = x;
                if (y < minY) minY = y; if (y > maxY) maxY = y;
            }
        }
        if (covered < w * h * 0.004 || covered > w * h * 0.75 || maxX < 0) return null;
        minX = Math.max(0, minX - 2); minY = Math.max(0, minY - 2);
        maxX = Math.min(w - 1, maxX + 2); maxY = Math.min(h - 1, maxY + 2);
        int cw = maxX - minX + 1, ch = maxY - minY + 1;
        return Bitmap.createBitmap(px, minY * w + minX, w, cw, ch, Bitmap.Config.ARGB_8888);
    }
}
