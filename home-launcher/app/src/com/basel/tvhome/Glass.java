/*
 * Glass Home for TCL Google TV
 * Copyright (c) 2026 Basel Majid
 * Licensed under the MIT License (see LICENSE).
 */
package com.basel.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.widget.FrameLayout;

/** Liquid-glass surfaces: GPU refraction shader (AGSL) + light rims for tiles. */
final class Glass {
    private Glass() { }

    static final String TAG = "TvHome";

    /** Frosted copy of the whole-screen backdrop that SCREEN-mode glass refracts. */
    static Bitmap screenFrost;
    /** Half-resolution copy of the visible backdrop (sharper source for the glass clock). */
    static Bitmap screenSharp;
    static int screenW = 1, screenH = 1;
    static Boolean shaderSupported = null;

    // Refraction: samples a pre-blurred source, bends it near the rim (lens), adds slight
    // chromatic dispersion, a frosted top sheen and a specular rim lit from the top-left.
    static final String AGSL =
            "uniform shader frost;\n" +
            "uniform float2 size;\n" +
            "uniform float2 origin;\n" +
            "uniform float2 srcScale;\n" +
            "uniform float radius;\n" +
            "uniform float strength;\n" +
            "uniform float tint;\n" +
            "uniform float dark;\n" +
            "uniform float rimW;\n" +
            "float sdRound(float2 p, float2 h, float r) {\n" +
            "  float2 q = abs(p) - (h - r);\n" +
            "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n" +
            "}\n" +
            "float4 main(float2 p) {\n" +
            "  float2 c = size * 0.5;\n" +
            "  float d = sdRound(p - c, c, radius);\n" +
            "  float a = clamp(0.5 - d, 0.0, 1.0);\n" +
            "  if (a <= 0.0) { return float4(0.0); }\n" +
            "  float band = max(radius, 12.0) * 1.25;\n" +
            "  float e = clamp(1.0 + d / band, 0.0, 1.0);\n" +
            "  float e3 = e * e * e;\n" +
            "  float2 n = normalize(p - c + float2(0.001, 0.001));\n" +
            "  float2 disp = -n * e3 * strength;\n" +
            "  float2 sp = (origin + p + disp) * srcScale;\n" +
            "  float2 ca = n * e3 * strength * 0.22 * srcScale;\n" +
            "  float3 col = float3(frost.eval(sp + ca).r, frost.eval(sp).g, frost.eval(sp - ca).b);\n" +
            "  col = col * (1.0 - dark);\n" +
            "  float sheen = 1.0 - smoothstep(0.0, size.y * 0.65, p.y);\n" +
            "  col = mix(col, float3(1.0), tint * (0.45 + 0.55 * sheen));\n" +
            "  float rim = 1.0 - smoothstep(0.0, rimW, -d);\n" +
            "  float2 L = normalize(float2(-0.55, -0.85));\n" +
            "  float lit = clamp(dot(n, L), 0.0, 1.0);\n" +
            "  float back = clamp(dot(n, -L), 0.0, 1.0);\n" +
            "  col = col + rim * (0.08 + 0.62 * lit + 0.20 * back);\n" +
            "  float rr = 1.0 - smoothstep(0.0, rimW, abs(d + rimW * 1.6));\n" +
            "  float rb = 1.0 - smoothstep(0.0, rimW, abs(d + rimW * 3.0));\n" +
            "  col.r = col.r + rr * (0.03 + 0.14 * lit);\n" +
            "  col.b = col.b + rb * (0.04 + 0.10 * back + 0.08 * lit);\n" +
            "  col = col + e3 * 0.05;\n" +
            "  return float4(col * a, a);\n" +
            "}\n";

    static RuntimeShader newShader() {
        if (Boolean.FALSE.equals(shaderSupported)) return null;
        try {
            RuntimeShader s = new RuntimeShader(AGSL);
            if (shaderSupported == null) Log.i(TAG, "GLASS_SHADER_OK");
            shaderSupported = true;
            return s;
        } catch (Throwable t) {
            Log.w(TAG, "GLASS_SHADER_FALLBACK " + t);
            shaderSupported = false;
            return null;
        }
    }

    /** A glass panel. SCREEN refracts the global backdrop; LOCAL refracts its parent's image. */
    static final class GlassView extends FrameLayout {
        static final int SCREEN = 0, LOCAL = 1;
        final int mode;
        float radius, strength, tint = 0.10f, dark = 0.10f, rimW;
        Bitmap localSrc;
        private final RuntimeShader rs;
        private BitmapShader bs;
        private Bitmap bsFor;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix m = new Matrix();
        private final RectF rf = new RectF();
        private final int[] loc = new int[2];

        GlassView(Context c, int mode, float radiusPx, float strengthPx, float dp) {
            super(c);
            this.mode = mode;
            this.radius = radiusPx;
            this.strength = strengthPx;
            this.rimW = 1.4f * dp;
            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);
            rs = newShader();
            rimP.setStyle(Paint.Style.STROKE);
            rimP.setStrokeWidth(Math.max(1f, 1.1f * dp));
        }

        void setLocalSource(Bitmap b) { localSrc = b; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            Bitmap b = mode == SCREEN ? screenFrost : localSrc;
            float ox, oy, sx, sy;
            if (mode == SCREEN) {
                getLocationInWindow(loc);
                ox = loc[0]; oy = loc[1];
                if (b != null) { sx = b.getWidth() / (float) screenW; sy = b.getHeight() / (float) screenH; }
                else { sx = sy = 1f; }
            } else {
                ox = getLeft(); oy = getTop();
                android.view.View p = (android.view.View) getParent();
                float pw = p != null ? p.getWidth() : w, ph = p != null ? p.getHeight() : h;
                if (b != null) { sx = b.getWidth() / pw; sy = b.getHeight() / ph; } else { sx = sy = 1f; }
            }
            rf.set(0, 0, w, h);
            if (b != null && rs != null) {
                if (bsFor != b) {
                    bs = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    bs.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                    rs.setInputShader("frost", bs);
                    bsFor = b;
                }
                rs.setFloatUniform("size", w, h);
                rs.setFloatUniform("origin", ox, oy);
                rs.setFloatUniform("srcScale", sx, sy);
                rs.setFloatUniform("radius", radius);
                rs.setFloatUniform("strength", strength);
                rs.setFloatUniform("tint", tint);
                rs.setFloatUniform("dark", dark);
                rs.setFloatUniform("rimW", rimW);
                paint.setShader(rs);
                c.drawRect(rf, paint);
                return;
            }
            // Fallback (no shader or no source yet): frosted bitmap + tint + lit rim.
            if (b != null) {
                if (bsFor != b) {
                    bs = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    bsFor = b;
                }
                m.setScale(1f / sx, 1f / sy);
                m.postTranslate(-ox, -oy);
                bs.setLocalMatrix(m);
                paint.setShader(bs);
                c.drawRoundRect(rf, radius, radius, paint);
            }
            fill.setColor(b != null ? 0x1FFFFFFF : 0x33FFFFFF);
            c.drawRoundRect(rf, radius, radius, fill);
            rimP.setShader(new LinearGradient(0, 0, w, h, 0xB0FFFFFF, 0x26FFFFFF, Shader.TileMode.CLAMP));
            float i = rimP.getStrokeWidth() / 2f;
            rf.inset(i, i);
            c.drawRoundRect(rf, radius - i, radius - i, rimP);
        }
    }

    /** Glass rim + sheen overlay for opaque tiles (app banners, artwork). Stateful: brightens on focus. */
    static final class Rim extends Drawable {
        private final float r, dp;
        private boolean focused;
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rf = new RectF();

        Rim(float radiusPx, float dp) {
            r = radiusPx;
            this.dp = dp;
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1.2f * dp);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2.4f * dp);
            ring.setColor(0xF5FFFFFF);
        }

        @Override protected void onBoundsChange(Rect b) {
            float w = b.width(), h = b.height();
            rf.set(b);
            clip.reset();
            clip.addRoundRect(rf, r, r, Path.Direction.CW);
            sheen.setShader(new LinearGradient(0, b.top, 0, b.top + h * 0.55f,
                    0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            glint.setShader(new RadialGradient(b.left + w * 0.18f, b.top + h * 0.05f, Math.max(w, h) * 0.55f,
                    0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            edge.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                    0xA6FFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas c) {
            c.save();
            c.clipPath(clip);
            c.drawRect(rf, sheen);
            if (focused) c.drawRect(rf, glint);
            c.restore();
            Paint p = focused ? ring : edge;
            float i = p.getStrokeWidth() / 2f;
            RectF in = new RectF(rf);
            in.inset(i, i);
            c.drawRoundRect(in, r - i, r - i, p);
        }

        @Override public boolean isStateful() { return true; }

        @Override protected boolean onStateChange(int[] state) {
            boolean f = false;
            for (int s : state) if (s == android.R.attr.state_focused) f = true;
            if (f != focused) { focused = f; invalidateSelf(); return true; }
            return false;
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
