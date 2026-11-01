/*
 * Glass Home for TCL Google TV
 * Copyright (c) 2026 Basel Majid
 * Licensed under the MIT License (see LICENSE).
 */
package com.basel.tvhome;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 12-hour digital clock made of liquid glass. The digits are a height field (blurred text mask):
 * each pixel refracts the backdrop through the glyph's slope, splits R/G/B (chromatic dispersion),
 * gets a specular highlight from a top-left light, and a frosted body. Redraws only when the minute
 * changes (with a cross-fade, light sweep and dispersion flare) - zero cost while idle.
 */
final class GlassClock extends View {

    private static final String AGSL =
            "uniform shader mask;\n" +
            "uniform shader height;\n" +
            "uniform shader shade;\n" +
            "uniform shader bg;\n" +
            "uniform float2 origin;\n" +
            "uniform float2 bgScale;\n" +
            "uniform float2 size;\n" +
            "uniform float strength;\n" +
            "uniform float chroma;\n" +
            "uniform float flare;\n" +
            "uniform float sweep;\n" +
            "uniform float shadowOff;\n" +
            "float4 main(float2 p) {\n" +
            "  float a  = mask.eval(p).r;\n" +
            "  float aR = mask.eval(p + float2(chroma, 0.0)).r;\n" +
            "  float aB = mask.eval(p - float2(chroma, 0.0)).r;\n" +
            "  float k = 0.6 + 0.3 * flare;\n" +
            "  float fr = max(aR - a, 0.0) * k;\n" +
            "  float fb = max(aB - a, 0.0) * k;\n" +
            "  float alpha = clamp(a + fr + fb, 0.0, 1.0);\n" +
            "  float sh = shade.eval(p - float2(0.0, shadowOff)).r * 0.45 * (1.0 - alpha);\n" +
            "  if (alpha < 0.004) { return float4(0.0, 0.0, 0.0, sh); }\n" +
            "  float hL = height.eval(p - float2(1.5, 0.0)).r;\n" +
            "  float hR = height.eval(p + float2(1.5, 0.0)).r;\n" +
            "  float hU = height.eval(p - float2(0.0, 1.5)).r;\n" +
            "  float hD = height.eval(p + float2(0.0, 1.5)).r;\n" +
            "  float h  = height.eval(p).r;\n" +
            "  float2 n = float2(hR - hL, hD - hU) * 4.0;\n" +
            "  float s = clamp(length(n), 0.0, 1.0);\n" +
            "  float2 nh = n / (s + 0.0001);\n" +
            "  float2 sp = (origin + p - n * strength) * bgScale;\n" +
            "  float2 ca = n * strength * (0.35 + 0.30 * flare) * bgScale;\n" +
            "  float3 col = float3(bg.eval(sp + ca).r, bg.eval(sp).g, bg.eval(sp - ca).b);\n" +
            "  col = col * 1.25 + 0.12 + 0.08 * h;\n" +
            "  float3 N = normalize(float3(-n, 1.0));\n" +
            "  float spec = pow(clamp(dot(N, normalize(float3(-0.55, -0.80, 0.90))), 0.0, 1.0), 30.0);\n" +
            "  float2 ld = float2(-0.566, -0.824);\n" +
            "  float lit = clamp(-dot(nh, ld), 0.0, 1.0) * s;\n" +
            "  float dark = clamp(dot(nh, ld), 0.0, 1.0) * s;\n" +
            "  col = (col + spec + lit * 0.72) * (1.0 - 0.22 * dark);\n" +
            "  float band = exp(-pow((p.x + p.y * 0.4 - sweep) / (size.y * 0.32), 2.0)) * flare;\n" +
            "  col = clamp(col + band * 0.40, 0.0, 1.0);\n" +
            "  float3 rgb = col * a + float3(1.0, 0.30, 0.26) * fr + float3(0.30, 0.55, 1.0) * fb;\n" +
            "  float outA = clamp(alpha + sh, 0.0, 1.0);\n" +
            "  return float4(min(rgb, float3(alpha)), outA);\n" +
            "}\n";

    private final float dp;
    private final Paint digitP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint ampmP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint drawP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fbW = new Paint(Paint.ANTI_ALIAS_FLAG), fbR = new Paint(Paint.ANTI_ALIAS_FLAG), fbB = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int pad;
    private final int[] loc = new int[2];

    private static final class Layer {
        Bitmap mask, height, shade, bgFor;
        RuntimeShader rs;
        String hm, ap;
        float hmX, apX, base, apBase;
        int w, h;
    }
    private Layer cur, prev;
    private float t = 1f, flare = 0f, sweep = -1f;
    private ValueAnimator anim;
    private String shown = "";

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { update(true); }
    };

    GlassClock(Context c, float dp, float textPx) {
        super(c);
        this.dp = dp;
        pad = Math.round(14 * dp);
        digitP.setColor(0xFFFFFFFF);
        digitP.setTextSize(textPx);
        digitP.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        digitP.setLetterSpacing(-0.01f);
        digitP.setFontFeatureSettings("tnum"); // fixed-width digits: no sideways jitter as minutes change
        ampmP.setColor(0xFFFFFFFF);
        ampmP.setTextSize(textPx * 0.17f);
        ampmP.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ampmP.setLetterSpacing(0.12f);
        PorterDuffXfermode add = new PorterDuffXfermode(PorterDuff.Mode.ADD);
        fbR.setXfermode(add); fbB.setXfermode(add);
        update(false);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        getContext().registerReceiver(tick, f, Context.RECEIVER_EXPORTED);
        update(false);
    }

    @Override protected void onDetachedFromWindow() {
        try { getContext().unregisterReceiver(tick); } catch (Exception ignored) { }
        super.onDetachedFromWindow();
    }

    /** Light sweep + dispersion flare (e.g. when Focus View opens). */
    void shimmer() { animateChange(false); }

    void update(boolean animate) {
        Date now = new Date();
        String hm = new SimpleDateFormat("h:mm", Locale.getDefault()).format(now);
        String ap = new SimpleDateFormat("a", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault());
        if ((hm + ap).equals(shown)) return;
        shown = hm + ap;
        Layer l = build(hm, ap);
        boolean resized = cur == null || cur.w != l.w || cur.h != l.h;
        prev = cur;
        cur = l;
        if (resized) requestLayout();
        if (animate && prev != null) animateChange(true); else { t = 1f; prev = null; invalidate(); }
    }

    private void animateChange(final boolean crossfade) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(900);
        anim.setInterpolator(new DecelerateInterpolator(1.3f));
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float v = (float) a.getAnimatedValue();
                t = crossfade ? Math.min(1f, v / 0.5f) : 1f;
                flare = (float) Math.sin(Math.PI * v);
                sweep = -0.3f * getWidth() + 1.6f * getWidth() * v;
                if (v >= 1f) { prev = null; flare = 0f; sweep = -1f; }
                invalidate();
            }
        });
        anim.start();
    }

    private Layer build(String hm, String ap) {
        Layer l = new Layer();
        l.hm = hm; l.ap = ap;
        Rect db = new Rect(), ab = new Rect();
        digitP.getTextBounds("0123456789:", 0, 11, db);
        ampmP.getTextBounds("AMP", 0, 3, ab);
        float hmW = digitP.measureText(hm), gap = 8 * dp;
        // Fixed canvas sized for the WIDEST time ("00:00" with tabular digits) and the wider of AM/PM,
        // so the clock never needs re-measuring and can never be clipped (e.g. 9:59 -> 10:00, 12:42 AM).
        float maxHmW = digitP.measureText("00:00");
        float apMax = Math.max(ampmP.measureText("AM"), ampmP.measureText("PM"));
        float side = gap + apMax;                   // reserved on BOTH sides -> digits sit on the centre line
        l.w = (int) Math.ceil(maxHmW + 2 * side) + 2 * pad;
        l.h = (db.bottom - db.top) + 2 * pad;
        l.hmX = (l.w - hmW) / 2f;                   // digits centred in the fixed canvas
        l.base = pad - db.top;                      // digit cap-top sits exactly at y = pad
        l.apX = l.hmX + hmW + gap;
        l.apBase = pad - ab.top;                    // AM/PM cap-top aligned with the digits' top
        // coverage mask (digits only): opaque black, white glyphs (coverage in .r)
        l.mask = Bitmap.createBitmap(l.w, l.h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(l.mask);
        c.drawColor(0xFF000000);
        c.drawText(hm, l.hmX, l.base, digitP);
        // height field: blurred coverage -> smooth glass bevel
        l.height = l.mask.copy(Bitmap.Config.ARGB_8888, true);
        Backdrop.blur(l.height, Math.max(2, Math.round(1.3f * dp)), 3);
        l.shade = l.mask.copy(Bitmap.Config.ARGB_8888, true);
        Backdrop.blur(l.shade, Math.max(3, Math.round(4f * dp)), 3);
        if (Glass.shaderSupported == null || Glass.shaderSupported) {
            try {
                l.rs = new RuntimeShader(AGSL);
                BitmapShader ms = new BitmapShader(l.mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                ms.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                BitmapShader hs = new BitmapShader(l.height, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                hs.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                l.rs.setInputShader("mask", ms);
                l.rs.setInputShader("height", hs);
                BitmapShader ss = new BitmapShader(l.shade, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                ss.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                l.rs.setInputShader("shade", ss);
            } catch (Throwable e) {
                Log.w(Glass.TAG, "GLASSCLOCK_FALLBACK " + e);
                l.rs = null;
            }
        }
        return l;
    }

    @Override protected void onMeasure(int ws, int hs) {
        setMeasuredDimension(cur != null ? cur.w : 1, cur != null ? cur.h : 1);
    }

    @Override protected void onDraw(Canvas c) {
        getLocationInWindow(loc);
        if (prev != null && t < 1f) drawLayer(c, prev, 1f - t);
        if (cur != null) drawLayer(c, cur, t);
    }

    private void drawLayer(Canvas c, Layer l, float alpha) {
        Bitmap bg = Glass.screenFrost != null ? Glass.screenFrost : Glass.screenSharp; // frosted source = clean glass body
        float dx = (getWidth() - l.w) / 2f;
        c.save();
        c.translate(dx, 0);
        if (l.rs != null && bg != null) {
            if (l.bgFor != bg) {
                BitmapShader bs = new BitmapShader(bg, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                bs.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                l.rs.setInputShader("bg", bs);
                l.bgFor = bg;
            }
            float sx = bg.getWidth() / (float) Glass.screenW, sy = bg.getHeight() / (float) Glass.screenH;
            l.rs.setFloatUniform("origin", loc[0] + dx, loc[1]);
            l.rs.setFloatUniform("bgScale", sx, sy);
            l.rs.setFloatUniform("size", l.w, l.h);
            l.rs.setFloatUniform("strength", 9f * dp);
            l.rs.setFloatUniform("chroma", (1.0f + 1.6f * flare) * dp);
            l.rs.setFloatUniform("shadowOff", 4f * dp);
            l.rs.setFloatUniform("flare", flare);
            l.rs.setFloatUniform("sweep", sweep);
            drawP.setShader(l.rs);
            drawP.setAlpha(Math.round(alpha * 255));
            c.drawRect(0, 0, l.w, l.h, drawP);
            ampmP.setAlpha(Math.round(alpha * 140));
            c.drawText(l.ap, l.apX, l.apBase, ampmP);
            ampmP.setAlpha(255);
        } else {
            // Fallback: frosted white digits with red/blue dispersion.
            float o = (1.0f + 1.4f * flare) * dp;
            int a = Math.round(alpha * 255);
            fbR.setColor(0xFFFF4D4D); fbR.setAlpha(a * 90 / 255);
            fbB.setColor(0xFF4DA6FF); fbB.setAlpha(a * 90 / 255);
            fbW.setColor(0xFFFFFFFF); fbW.setAlpha(a * 225 / 255);
            for (Paint p : new Paint[]{fbR, fbB, fbW}) {
                p.setTypeface(digitP.getTypeface());
                p.setTextSize(digitP.getTextSize());
                p.setLetterSpacing(digitP.getLetterSpacing());
                p.setFontFeatureSettings("tnum");
            }
            c.drawText(l.hm, l.hmX - o, l.base, fbR);
            c.drawText(l.hm, l.hmX + o, l.base, fbB);
            c.drawText(l.hm, l.hmX, l.base, fbW);
            ampmP.setAlpha(Math.round(alpha * 140));
            c.drawText(l.ap, l.apX, l.apBase, ampmP);
            ampmP.setAlpha(255);
        }
        c.restore();
    }
}
