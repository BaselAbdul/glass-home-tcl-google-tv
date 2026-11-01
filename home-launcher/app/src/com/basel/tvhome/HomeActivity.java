/*
 * Glass Home for TCL Google TV
 * Copyright (c) 2026 Basel Majid
 * Licensed under the MIT License (see LICENSE).
 */
package com.basel.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal Apple TV-inspired home screen with a liquid-glass visual system.
 * Plain framework views + one AGSL shader. No libraries, no ads, no tracking.
 */
public class HomeActivity extends Activity {

    private static final String[] DEFAULT_FAVS = {
            "app:com.apple.atve.androidtv.appletv",
            "app:com.google.android.youtube.tv",
            "app:com.netflix.ninja",
            "app:com.wbd.stream"
    };
    private static final String[] RESUME_APPS = {
            "com.apple.atve.androidtv.appletv", "com.google.android.youtube.tv",
            "com.netflix.ninja", "com.wbd.stream"
    };
    private static final Set<String> NEVER_SHOW = new HashSet<>(Arrays.asList(
            "com.basel.tvhome", "com.google.android.apps.tv.launcherx",
            "com.google.android.tungsten.setupwraith", "com.android.tv.settings"));

    private static final int TEXT = 0xFFF5F5F7;
    private static final int TEXT_DIM = 0xCCD2D6DE;
    private static final Interpolator EASE = new DecelerateInterpolator(1.6f);
    private static final Interpolator LIQUID = new OvershootInterpolator(1.5f);

    static final class Item {
        String key, label, sub = "", pkg, inputId;
        Drawable banner, icon;
        Intent launch;
        boolean isInput;
    }

    private final Map<String, Item> items = new LinkedHashMap<>();
    private PackageManager pm;
    private TvInputManager tim;
    private SharedPreferences prefs;
    private Backdrop backdrop;
    private Glass.GlassView clockGlass, shelf;
    private GlassTile wallSwitch, focusSwitch, resumePill;
    private float wallT, focusT;
    // Focus View
    private boolean focusMode;
    private LinearLayout headerLeft, bigClockBox;
    private TextView focusName;
    private GlassClock glassClock;
    private Continue.Entry latest;
    private Object focusReturnTag;
    private static final Interpolator GLIDE = new DecelerateInterpolator(2.2f);
    private LinearLayout favRow, cwRow, appRow;
    private HorizontalScrollView favScroll, cwScroll, appScroll;
    private TextView title, subtitle, cwLabel;
    private String signature = "", cwSignature = null;
    private static final Map<String, Bitmap> GLYPHS = new java.util.HashMap<>();
    private static final String[] DEFAULT_HIDDEN = {"app:com.tcl.gallery", "app:com.tcl.ui_mediaCenter"};
    private static final Set<String> DEFAULT_HIDDEN_LABELS = new HashSet<>(Arrays.asList(
            "Safety Guard", "T-Solo", "TCL Channel", "TCL Home", "Gallery", "Media Player"));

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(false); loadContinue(); }
    };

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        pm = getPackageManager();
        tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        if (!prefs.getBoolean("v3init", false)) {
            Set<String> h = new HashSet<>(prefs.getStringSet("hidden", Collections.<String>emptySet()));
            h.addAll(Arrays.asList(DEFAULT_HIDDEN));
            prefs.edit().putStringSet("hidden", h).putBoolean("v3init", true).apply();
        }
        setContentView(buildUi());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED);
        refresh(true);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh(false);
        loadContinue();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (applyStateExtras(intent)) return;
        if (!focusMode) favScroll.smoothScrollTo(0, 0);
        cwScroll.smoothScrollTo(0, 0);
        appScroll.smoothScrollTo(0, 0);
        focusFirst();
    }

    /**
     * Lets a script set a known starting state (used by the recording script):
     *   am start -n com.basel.tvhome/.HomeActivity --ez focusView false --ez wallpaper true
     */
    private boolean applyStateExtras(Intent i) {
        if (i == null || (!i.hasExtra("focusView") && !i.hasExtra("wallpaper"))) return false;
        if (i.hasExtra("wallpaper")) {
            boolean w = i.getBooleanExtra("wallpaper", true);
            if (w != backdrop.wallpaperOn()) {
                prefs.edit().putBoolean("wallpaper", w).apply();
                backdrop.setWallpaper(w, false);
                wallT = w ? 1f : 0f;
                wallSwitch.invalidate();
            }
        }
        if (i.hasExtra("focusView")) {
            boolean f = i.getBooleanExtra("focusView", false);
            if (f != focusMode) setFocusMode(f, false);
        }
        favScroll.scrollTo(0, 0);
        cwScroll.scrollTo(0, 0);
        appScroll.scrollTo(0, 0);
        focusFirst();
        return true;
    }

    @Override public void onBackPressed() { if (focusMode) setFocusMode(false, true); }

    @Override protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    // ================================================================ layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        backdrop = new Backdrop(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);

        // ---- header: focused title (left) + glass clock capsule (right)
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(this);
        style(title, 30, TEXT, "sans-serif-light");
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        subtitle = new TextView(this);
        style(subtitle, 12, TEXT_DIM, "sans-serif-medium");
        subtitle.setLetterSpacing(0.06f);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        tcol.addView(title);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = px(4);
        tcol.addView(subtitle, sl);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, -2, Gravity.START | Gravity.CENTER_VERTICAL);
        tl.leftMargin = px(48);
        tl.rightMargin = px(300);
        header.addView(tcol, tl);
        headerLeft = tcol;

        clockGlass = new Glass.GlassView(this, Glass.GlassView.SCREEN, dp(24), dp(10), dp(1));
        clockGlass.tint = 0.05f;
        clockGlass.dark = 0f;
        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.HORIZONTAL);
        clock.setGravity(Gravity.CENTER_VERTICAL);
        clock.setPadding(px(22), 0, px(22), 0);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("h:mm");
        style(time, 21, TEXT, "sans-serif-medium");
        View sep = new View(this);
        sep.setBackgroundColor(0x40FFFFFF);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEE d MMM");
        date.setFormat24Hour("EEE d MMM");
        style(date, 13, TEXT_DIM, "sans-serif-medium");
        clock.addView(time);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(px(1), px(18));
        sp.leftMargin = px(14);
        sp.rightMargin = px(14);
        clock.addView(sep, sp);
        clock.addView(date);
        clockGlass.addView(clock, new FrameLayout.LayoutParams(-2, -1));
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        right.setClipToPadding(false);
        focusSwitch = buildFocusSwitch();
        LinearLayout.LayoutParams fsl = new LinearLayout.LayoutParams(px(48), px(48));
        fsl.rightMargin = px(12);
        right.addView(focusSwitch, fsl);
        wallSwitch = buildWallSwitch();
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(px(48), px(48));
        wl.rightMargin = px(12);
        right.addView(wallSwitch, wl);
        right.addView(clockGlass, new LinearLayout.LayoutParams(-2, px(48)));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, px(48), Gravity.END | Gravity.CENTER_VERTICAL);
        cl.rightMargin = px(48);
        header.addView(right, cl);

        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, px(70));
        hl.topMargin = px(24);
        col.addView(header, hl);

        // ---- favourites
        favScroll = row(48, 14);
        favRow = (LinearLayout) favScroll.getChildAt(0);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = px(4);
        col.addView(favScroll, fl);

        // ---- continue watching
        cwLabel = sectionLabel("Continue Watching");
        LinearLayout.LayoutParams cwl = new LinearLayout.LayoutParams(-2, -2);
        cwl.leftMargin = px(50);
        cwl.topMargin = px(4);
        col.addView(cwLabel, cwl);
        cwScroll = row(48, 12);
        cwRow = (LinearLayout) cwScroll.getChildAt(0);
        col.addView(cwScroll, new LinearLayout.LayoutParams(-1, -2));

        // ---- apps & inputs, on a liquid-glass shelf
        shelf = new Glass.GlassView(this, Glass.GlassView.SCREEN, dp(26), dp(16), dp(1));
        shelf.tint = 0.035f;
        shelf.dark = 0f;
        final float shelfR = dp(26);
        shelf.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), shelfR); }
        });
        shelf.setClipToOutline(true);
        LinearLayout shelfCol = new LinearLayout(this);
        shelfCol.setOrientation(LinearLayout.VERTICAL);
        shelfCol.setClipChildren(false);
        shelfCol.setClipToPadding(false);
        TextView appsLabel = sectionLabel("Apps");
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
        al.leftMargin = px(20);
        al.topMargin = px(14);
        shelfCol.addView(appsLabel, al);
        appScroll = row(16, 9);
        appRow = (LinearLayout) appScroll.getChildAt(0);
        shelfCol.addView(appScroll, new LinearLayout.LayoutParams(-1, -2));
        shelf.addView(shelfCol, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams shl = new LinearLayout.LayoutParams(-1, -2);
        shl.leftMargin = px(34);
        shl.rightMargin = px(34);
        shl.topMargin = px(6);
        col.addView(shelf, shl);

        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // ---- Focus View overlay: large calm clock, focused name, single Resume pill
        bigClockBox = new LinearLayout(this);
        bigClockBox.setOrientation(LinearLayout.VERTICAL);
        bigClockBox.setGravity(Gravity.CENTER_HORIZONTAL);
        glassClock = new GlassClock(this, dp(1), TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 100,
                getResources().getDisplayMetrics()));
        TextClock bd = new TextClock(this);
        bd.setFormat12Hour("EEEE  ·  d MMMM");
        bd.setFormat24Hour("EEEE  ·  d MMMM");
        style(bd, 15, 0x99FFFFFF, "sans-serif-medium");
        bd.setLetterSpacing(0.04f);
        bd.setGravity(Gravity.CENTER);
        bigClockBox.addView(glassClock);
        LinearLayout.LayoutParams bdl = new LinearLayout.LayoutParams(-2, -2);
        bdl.topMargin = px(4);   // 18dp of air below the visible digits (14dp is glass padding)
        bigClockBox.addView(bd, bdl);
        FrameLayout.LayoutParams bcl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        bcl.topMargin = px(86);  // visible digits start ~100dp from the top
        root.addView(bigClockBox, bcl);

        focusName = new TextView(this);
        style(focusName, 15, 0xD9FFFFFF, "sans-serif-medium");
        focusName.setLetterSpacing(0.04f);
        focusName.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams fnl = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        fnl.topMargin = px(368);
        root.addView(focusName, fnl);

        resumePill = buildResumePill();
        FrameLayout.LayoutParams rpl = new FrameLayout.LayoutParams(px(300), px(44), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        rpl.topMargin = px(404);
        root.addView(resumePill, rpl);
        for (View v : new View[]{bigClockBox, focusName, resumePill}) { v.setAlpha(0f); v.setVisibility(View.INVISIBLE); }

        backdrop.setFrostListener(new Backdrop.FrostListener() {
            @Override public void onFrost() { clockGlass.invalidate(); shelf.invalidate(); if (glassClock != null) glassClock.invalidate(); }
        });
        boolean on = prefs.getBoolean("wallpaper", true);
        wallT = on ? 1f : 0f;
        backdrop.setWallpaper(on, false);
        if (prefs.getBoolean("focusView", false)) {
            root.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    if (favScroll.getWidth() == 0 || favRow.getChildCount() == 0) return;
                    favScroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    setFocusMode(true, false);
                }
            });
        }
        return root;
    }

    /** Focus View switch: a ring (lens); a centre dot appears when Focus View is on. */
    private GlassTile buildFocusSwitch() {
        final GlassTile t = new GlassTile(this, dp(1), dp(24));
        t.scaleTo = 1.12f;
        t.setTag("focus-switch");
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(1.5f));
        final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(0xFFFFFFFF);
        t.painter = new GlassTile.Painter() {
            @Override public void paint(Canvas c, float w, float h, float focus) {
                float cx = w / 2f, cy = h / 2f;
                ring.setColor(0xFFFFFFFF);
                ring.setAlpha(Math.round(150 + 60 * focus + 45 * focusT));
                c.drawCircle(cx, cy, dp(8.5f), ring);
                if (focusT > 0.01f) {
                    dot.setAlpha(Math.round(250 * focusT));
                    c.drawCircle(cx, cy, dp(3.6f) * focusT, dot);
                }
            }
        };
        t.onFocus = new Runnable() { @Override public void run() {
            showTitle("Focus View", focusMode ? "ON  ·  PRESS OK OR BACK TO EXIT" : "OFF  ·  PRESS OK FOR A CALMER HOME");
        } };
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setFocusMode(!focusMode, true); }
        });
        return t;
    }

    /** One quiet glass pill: resume the most recent title. */
    private GlassTile buildResumePill() {
        final GlassTile t = new GlassTile(this, dp(1), dp(22));
        t.scaleTo = 1.06f;
        t.setTag("resume-pill");
        final android.text.TextPaint tp = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xF2FFFFFF);
        tp.setTextSize(dp(13));
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        final android.text.TextPaint sp = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        sp.setColor(0x8CFFFFFF);
        sp.setTextSize(dp(10));
        sp.setLetterSpacing(0.08f);
        sp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        final Paint tri = new Paint(Paint.ANTI_ALIAS_FLAG);
        tri.setColor(0xF2FFFFFF);
        final android.graphics.Path path = new android.graphics.Path();
        t.painter = new GlassTile.Painter() {
            @Override public void paint(Canvas c, float w, float h, float focus) {
                if (latest == null) return;
                float x = dp(20), cy = h / 2f;
                path.reset();
                path.moveTo(x, cy - dp(5.5f));
                path.lineTo(x + dp(9), cy);
                path.lineTo(x, cy + dp(5.5f));
                path.close();
                c.drawPath(path, tri);
                x += dp(19);
                String app = appLabel(latest.pkg).toUpperCase();
                float appW = sp.measureText(app);
                String title = TextUtils.ellipsize(latest.title, tp, w - x - appW - dp(36), TextUtils.TruncateAt.END).toString();
                c.drawText(title, x, cy + tp.getTextSize() * 0.36f, tp);
                c.drawText(app, w - dp(20) - appW, cy + sp.getTextSize() * 0.36f, sp);
            }
        };
        t.onFocus = new Runnable() { @Override public void run() {
            if (latest != null) focusName.setText("Resume  ·  " + latest.title);
        } };
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (latest != null) resume(latest); }
        });
        return t;
    }

    private void sizeResumePill() {
        if (latest == null) return;
        android.text.TextPaint tp = new android.text.TextPaint();
        tp.setTextSize(dp(13));
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        android.text.TextPaint sp = new android.text.TextPaint();
        sp.setTextSize(dp(10));
        sp.setLetterSpacing(0.08f);
        float w = dp(20 + 19) + tp.measureText(latest.title) + dp(36) + sp.measureText(appLabel(latest.pkg).toUpperCase()) + dp(20);
        int width = Math.round(Math.max(dp(240), Math.min(dp(460), w)));
        android.view.ViewGroup.LayoutParams lp = resumePill.getLayoutParams();
        if (lp.width != width) { lp.width = width; resumePill.setLayoutParams(lp); }
        resumePill.invalidate();
    }

    private static boolean isNight() {
        int hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return hr >= 23 || hr < 6;
    }

    /**
     * Focus View: one calm pane. Rows fade away, favourites glide to the optical centre,
     * a large clock rises in, and a single Resume pill appears. GPU-only animations.
     */
    private void setFocusMode(final boolean on, boolean animate) {
        focusMode = on;
        prefs.edit().putBoolean("focusView", on).apply();
        if (on) {
            View cur = getCurrentFocus();
            focusReturnTag = cur != null ? cur.getTag() : null;
        }
        final long d = animate ? 1 : 0;
        // icon
        android.animation.ValueAnimator ia = android.animation.ValueAnimator.ofFloat(focusT, on ? 1f : 0f);
        ia.setDuration(260 * d);
        ia.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator v) { focusT = (float) v.getAnimatedValue(); focusSwitch.invalidate(); }
        });
        ia.start();
        // chrome that leaves / returns
        View[] chrome = {headerLeft, clockGlass, cwLabel, cwScroll, shelf};
        for (int i = 0; i < chrome.length; i++) {
            final View v = chrome[i];
            if (on) {
                v.animate().alpha(0f).translationY(dp(10)).setStartDelay(i * 25L * d).setDuration(200 * d)
                        .setInterpolator(EASE).withEndAction(new Runnable() { @Override public void run() { if (focusMode) v.setVisibility(View.INVISIBLE); } }).start();
            } else {
                v.setVisibility(View.VISIBLE);
                v.animate().alpha(1f).translationY(0f).setStartDelay((120 + i * 30L) * d).setDuration(300 * d)
                        .setInterpolator(GLIDE).withEndAction(null).start();
            }
        }
        positionFavs(on, d);
        // overlay
        final boolean pill = on && latest != null;
        sizeResumePill();
        View[] over = {bigClockBox, focusName, resumePill};
        for (int i = 0; i < over.length; i++) {
            final View v = over[i];
            boolean show = on && (v != resumePill || pill);
            float target = v == bigClockBox && isNight() ? 0.78f : 1f;
            if (show) {
                v.setVisibility(View.VISIBLE);
                v.setTranslationY(dp(10));
                v.animate().alpha(target).translationY(0f).setStartDelay((200 + i * 70L) * d).setDuration(420 * d)
                        .setInterpolator(GLIDE).withEndAction(null).start();
            } else {
                v.animate().alpha(0f).setStartDelay(0).setDuration(160 * d).setInterpolator(EASE)
                        .withEndAction(new Runnable() { @Override public void run() { if (v.getAlpha() == 0f) v.setVisibility(View.INVISIBLE); } }).start();
            }
        }
        // calmer backdrop; gentle night dimming of the whole pane
        final float calmTo = on ? 1f : 0f;
        android.animation.ValueAnimator ca = android.animation.ValueAnimator.ofFloat(on ? 0f : 1f, calmTo);
        ca.setDuration(500 * d);
        ca.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator v) { backdrop.setCalm((float) v.getAnimatedValue()); }
        });
        ca.start();
        // focus: default item on enter, restore the previous one on exit
        favRow.postDelayed(new Runnable() {
            @Override public void run() {
                View target = null;
                if (!focusMode && focusReturnTag != null) target = getWindow().getDecorView().findViewWithTag(focusReturnTag);
                if (target == null || target.getVisibility() != View.VISIBLE || "focus-switch".equals(focusReturnTag))
                    target = favRow.getChildCount() > 0 ? favRow.getChildAt(0) : focusSwitch;
                if (focusMode && getCurrentFocus() == focusSwitch) return; // keep switch focused after toggling on
                target.requestFocus();
            }
        }, animate ? 260 : 0);
        if (on) {
            focusName.setText("");
            glassClock.update(false);
            if (animate) glassClock.postDelayed(new Runnable() { @Override public void run() { if (focusMode) glassClock.shimmer(); } }, 520);
            else glassClock.invalidate();
        }
    }

    /** Favourites glide to the optical centre and grow, never leaving the 60dp TV safe area. */
    private void positionFavs(boolean on, long d) {
        float tx = 0f, ty = 0f, sc = 1f;
        if (on) {
            int content = favRow.getWidth() - favRow.getPaddingLeft() - favRow.getPaddingRight() - px(18);
            int avail = favScroll.getWidth() - favRow.getPaddingLeft() - favRow.getPaddingRight();
            if (content > 0 && content < avail) tx = (avail - content) / 2f;
            float centre = favScroll.getTop() + favScroll.getHeight() / 2f;
            ty = dp(300) - centre;
            float safe = favScroll.getWidth() - 2f * dp(60);
            sc = content > 0 ? Math.min(1.12f, safe / (content * 1.08f)) : 1f;
            sc = Math.max(1f, sc);
        }
        favScroll.setPivotX(favScroll.getWidth() / 2f);
        favScroll.setPivotY(favScroll.getHeight() / 2f);
        favScroll.animate().translationX(tx).translationY(ty).scaleX(sc).scaleY(sc)
                .setStartDelay((on ? 70 : 0) * d).setDuration((on ? 480 : 380) * d).setInterpolator(GLIDE).start();
    }

    /** Minimal glass switch: three hanging light streaks (echoing the wallpaper); filled = on. */
    private GlassTile buildWallSwitch() {
        final GlassTile t = new GlassTile(this, dp(1), dp(24));
        t.scaleTo = 1.12f;
        t.setTag("wallpaper-switch");
        final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint lineP = new Paint(Paint.ANTI_ALIAS_FLAG);
        lineP.setStyle(Paint.Style.STROKE);
        lineP.setStrokeWidth(dp(1.3f));
        final RectF bar = new RectF();
        final float[] hs = {18f, 12f, 15f};
        t.painter = new GlassTile.Painter() {
            @Override public void paint(Canvas c, float w, float h, float focus) {
                float bw = dp(3.4f), gap = dp(3.2f), total = 3 * bw + 2 * gap;
                float x = (w - total) / 2f, top = h / 2f - dp(9f);
                for (int i = 0; i < 3; i++) {
                    float bh = dp(hs[i]);
                    bar.set(x, top, x + bw, top + bh);
                    fillP.setShader(new android.graphics.LinearGradient(0, top, 0, top + bh,
                            0xF5FFFFFF, 0x59FFFFFF, android.graphics.Shader.TileMode.CLAMP));
                    fillP.setAlpha(Math.round(255 * wallT));
                    c.drawRoundRect(bar, bw / 2f, bw / 2f, fillP);
                    lineP.setColor(0xFFFFFFFF);
                    lineP.setAlpha(Math.round((110 + 60 * focus) * (1f - wallT)));
                    c.drawRoundRect(bar, bw / 2f, bw / 2f, lineP);
                    x += bw + gap;
                }
            }
        };
        t.onFocus = new Runnable() { @Override public void run() { showWallTitle(); } };
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleWallpaper(); }
        });
        return t;
    }

    private void showWallTitle() {
        boolean on = backdrop.wallpaperOn();
        showTitle("Wallpaper", on ? "ON  ·  PRESS OK TO TURN OFF" : "OFF  ·  PRESS OK TO TURN ON");
    }

    private void toggleWallpaper() {
        final boolean on = !backdrop.wallpaperOn();
        prefs.edit().putBoolean("wallpaper", on).apply();
        backdrop.setWallpaper(on, true);
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(wallT, on ? 1f : 0f);
        a.setDuration(260);
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator v) {
                wallT = (float) v.getAnimatedValue();
                wallSwitch.invalidate();
            }
        });
        a.start();
        showWallTitle();
    }

    private TextView sectionLabel(String s) {
        TextView t = new TextView(this);
        style(t, 13, TEXT_DIM, "sans-serif-medium");
        t.setLetterSpacing(0.05f);
        t.setText(s);
        return t;
    }

    private HorizontalScrollView row(int padH, int padV) {
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setSmoothScrollingEnabled(true);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        r.setPadding(px(padH), px(padV), px(padH), px(padV));
        sv.addView(r, new FrameLayout.LayoutParams(-2, -2));
        return sv;
    }

    private Bitmap glyphFor(String key, Drawable banner, Drawable icon, boolean big) {
        String k = key + (big ? "#b" : "#s");
        if (GLYPHS.containsKey(k)) return GLYPHS.get(k);
        Bitmap g = null;
        if (big && banner != null) g = GlassTile.clearGlyph(banner, 320, 180);
        if (g == null && icon != null) g = GlassTile.clearGlyph(icon, 128, 128);
        GLYPHS.put(k, g);
        return g;
    }

    private View tile(final Item it, int wDp, int hDp, final boolean big) {
        final GlassTile t = new GlassTile(this, dp(1), dp(big ? 18 : 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(wDp), px(hDp));
        lp.rightMargin = px(big ? 18 : 12);
        t.setLayoutParams(lp);
        t.setTag(it.key);
        t.scaleTo = big ? 1.08f : 1.10f;
        if (it.isInput) {
            t.big = it.label;
            t.small = it.sub;
        } else {
            t.glyphBoxH = big ? 38f : 22f;
            t.glyphBoxW = big ? 0.62f : 0.5f;
            t.glyph = glyphFor(it.key, it.banner, it.icon, big);
            if (t.glyph == null) t.colorIcon = it.icon;
            if (!big) t.label = it.label;
        }
        t.onFocus = new Runnable() { @Override public void run() { showTitle(it.label, it.sub); } };
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { launch(it); } });
        t.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { menu(it); return true; }
        });
        return t;
    }

    private View cwCard(final Continue.Entry e) {
        final GlassTile card = new GlassTile(this, dp(1), dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(208), px(117));
        lp.rightMargin = px(20);
        card.setLayoutParams(lp);
        card.setTag("cw:" + e.id);
        card.scaleTo = 1.07f;
        final String appLabel = appLabel(e.pkg);
        final boolean hasSub = e.sub != null && e.sub.length() > 0;
        final int plateH = 44;
        card.artBottomInset = dp(plateH + 8);

        // ---- bottom glass plate: title, detail line, hairline progress
        final Glass.GlassView plate = new Glass.GlassView(this, Glass.GlassView.LOCAL, dp(12), dp(9), dp(1));
        plate.tint = 0.03f;
        plate.dark = 0.48f;
        LinearLayout pc = new LinearLayout(this);
        pc.setOrientation(LinearLayout.VERTICAL);
        pc.setGravity(Gravity.CENTER_VERTICAL);
        pc.setPadding(px(11), 0, px(11), 0);
        TextView tt = new TextView(this);
        style(tt, 12.5f, TEXT, "sans-serif-medium");
        tt.setSingleLine(true);
        tt.setEllipsize(TextUtils.TruncateAt.END);
        tt.setText(e.title);
        pc.addView(tt);
        String detail = hasSub ? e.sub : "";
        if (e.remainingMs > 0) {
            long min = Math.max(1, e.remainingMs / 60000);
            String left = min >= 60 ? (min / 60) + "h " + (min % 60) + "m left" : min + "m left";
            detail = detail.length() > 0 ? detail + "  ·  " + left : left;
        }
        if (detail.length() > 0) {
            TextView dt = new TextView(this);
            style(dt, 10, 0x99FFFFFF, "sans-serif-medium");
            dt.setSingleLine(true);
            dt.setEllipsize(TextUtils.TruncateAt.END);
            dt.setText(detail);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
            dl.topMargin = px(2);
            pc.addView(dt, dl);
        }
        if (e.progress >= 0f) {
            final float prog = e.progress;
            View bar = new View(this) {
                final Paint tr = new Paint(Paint.ANTI_ALIAS_FLAG), fi = new Paint(Paint.ANTI_ALIAS_FLAG);
                final RectF r1 = new RectF(), r2 = new RectF();
                { tr.setColor(0x33FFFFFF); fi.setColor(0xF2FFFFFF); }
                @Override protected void onDraw(Canvas c) {
                    float h = getHeight(), w = getWidth();
                    r1.set(0, 0, w, h);
                    r2.set(0, 0, Math.max(h, w * prog), h);
                    c.drawRoundRect(r1, h / 2, h / 2, tr);
                    c.drawRoundRect(r2, h / 2, h / 2, fi);
                }
            };
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, px(2.5f));
            bl.topMargin = px(5);
            pc.addView(bar, bl);
        }
        plate.addView(pc, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, px(plateH), Gravity.BOTTOM);
        pl.setMargins(px(6), 0, px(6), px(6));
        card.addView(plate, pl);

        // ---- platform tag: clear-glass chip with the app's white wordmark
        final Glass.GlassView chip = new Glass.GlassView(this, Glass.GlassView.LOCAL, dp(9), dp(5), dp(1));
        chip.tint = 0.05f;
        chip.dark = 0.5f;
        Item app = items.get("app:" + e.pkg);
        Bitmap mark = glyphFor("app:" + e.pkg, app != null ? app.banner : null, app != null ? app.icon : null, true);
        if (mark != null) {
            ImageView mv = new ImageView(this);
            mv.setImageBitmap(mark);
            mv.setAlpha(0.95f);
            mv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int mh = px(9);
            int mw = Math.min(px(64), Math.round(mh * mark.getWidth() / (float) mark.getHeight()));
            chip.addView(mv, new FrameLayout.LayoutParams(mw, mh, Gravity.CENTER));
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(mw + px(18), px(19), Gravity.TOP | Gravity.START);
            cl.setMargins(px(8), px(8), 0, 0);
            card.addView(chip, cl);
        } else {
            TextView ct = new TextView(this);
            style(ct, 9, TEXT, "sans-serif-medium");
            ct.setLetterSpacing(0.08f);
            ct.setText(appLabel.toUpperCase());
            ct.setGravity(Gravity.CENTER);
            ct.setPadding(px(9), 0, px(9), 0);
            chip.addView(ct, new FrameLayout.LayoutParams(-2, -1));
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, px(19), Gravity.TOP | Gravity.START);
            cl.setMargins(px(8), px(8), 0, 0);
            card.addView(chip, cl);
        }

        if (e.art != null) {
            Images.load(this, e.art, 480, new Images.Callback() {
                @Override public void done(Bitmap b) {
                    if (b == null) { useIcon(e, card, plate); chip.setVisibility(View.GONE); return; }
                    card.setArt(b);
                    Bitmap fr = Backdrop.frostOf(b, 96, 54, 3);
                    plate.setLocalSource(fr);
                    chip.setLocalSource(fr);
                }
            });
        } else {
            useIcon(e, card, plate);
            chip.setVisibility(View.GONE); // wordmark already fills the card
        }

        final String sub = buildSub(e, appLabel);
        card.onFocus = new Runnable() { @Override public void run() { showTitle(e.title, sub); } };
        card.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { resume(e); } });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                new AlertDialog.Builder(HomeActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(e.title)
                        .setItems(new String[]{"Remove from Continue Watching"}, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                Set<String> h = new HashSet<>(prefs.getStringSet("cwHidden", Collections.<String>emptySet()));
                                h.add(e.id + "|" + e.title);
                                prefs.edit().putStringSet("cwHidden", h).apply();
                                cwSignature = null;
                                loadContinue();
                            }
                        }).show();
                return true;
            }
        });
        return card;
    }

    /** No artwork: clear glass card with the app's white wordmark. */
    private void useIcon(Continue.Entry e, GlassTile card, Glass.GlassView plate) {
        try {
            Item it = items.get("app:" + e.pkg);
            Drawable banner = it != null ? it.banner : pm.getApplicationBanner(e.pkg);
            Drawable icon = it != null ? it.icon : pm.getApplicationIcon(e.pkg);
            card.glyphBoxH = 26f;
            card.glyphBoxW = 0.56f;
            card.glyphLift = 22f;
            card.glyph = glyphFor("app:" + e.pkg, banner, icon, true);
            if (card.glyph == null) card.colorIcon = icon;
            card.invalidate();
            Bitmap b = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888);
            b.eraseColor(0xFF15161A);
            plate.setLocalSource(b);
        } catch (Exception ignored) { }
    }

    private String buildSub(Continue.Entry e, String appLabel) {
        StringBuilder sb = new StringBuilder(appLabel.toUpperCase());
        if (e.sub != null && e.sub.length() > 0) sb.append("  ·  ").append(e.sub);
        if (e.remainingMs > 0) {
            long min = Math.max(1, e.remainingMs / 60000);
            sb.append("  ·  ").append(min >= 60 ? (min / 60) + " H " + (min % 60) + " MIN LEFT" : min + " MIN LEFT");
        }
        return sb.toString();
    }

    private void showTitle(String t, String s) {
        title.setText(t);
        subtitle.setText(s);
        if (focusMode && focusName != null) focusName.setText(t);
    }

    // ================================================================ data

    private void refresh(boolean initialFocus) {
        collect();
        if (!prefs.getBoolean("v4init", false)) {
            Set<String> h = new HashSet<>(prefs.getStringSet("hidden", Collections.<String>emptySet()));
            for (Item it : items.values()) if (DEFAULT_HIDDEN_LABELS.contains(it.label)) h.add(it.key);
            prefs.edit().putStringSet("hidden", h).putBoolean("v4init", true).apply();
        }
        List<String> favs = favs();
        Set<String> hidden = prefs.getStringSet("hidden", Collections.<String>emptySet());
        StringBuilder sig = new StringBuilder(items.keySet() + "|" + favs + "|" + hidden);
        for (Item it : items.values()) sig.append(it.label);
        if (sig.toString().equals(signature) && !initialFocus) return;
        signature = sig.toString();
        render(favs, hidden);
        if (initialFocus) focusFirst();
    }

    private void loadContinue() {
        final Set<String> pk = new LinkedHashSet<>(Arrays.asList(RESUME_APPS));
        for (String k : favs()) if (k.startsWith("app:")) pk.add(k.substring(4));
        final Context ctx = getApplicationContext();
        Images.POOL.execute(new Runnable() {
            @Override public void run() {
                final List<Continue.Entry> list = Continue.query(ctx, pk, 14);
                Images.MAIN.post(new Runnable() { @Override public void run() { renderContinue(list); } });
            }
        });
    }

    private void renderContinue(List<Continue.Entry> list) {
        Set<String> hidden = prefs.getStringSet("cwHidden", Collections.<String>emptySet());
        List<Continue.Entry> show = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        for (Continue.Entry e : list) {
            if (hidden.contains(e.id) || hidden.contains(e.id + "|" + e.title)
                    || pm.getLeanbackLaunchIntentForPackage(e.pkg) == null && e.intent == null) continue;
            show.add(e);
            sig.append(e.id).append(e.title).append(e.art).append(Math.round(e.progress * 100)).append(';');
        }
        latest = show.isEmpty() ? null : show.get(0);
        if (resumePill != null) {
            sizeResumePill();
            if (focusMode) {
                boolean want = latest != null;
                if (want && resumePill.getVisibility() != View.VISIBLE) {
                    resumePill.setVisibility(View.VISIBLE);
                    resumePill.animate().alpha(1f).setDuration(300).start();
                } else if (!want) { resumePill.setAlpha(0f); resumePill.setVisibility(View.INVISIBLE); }
            }
        }
        if (sig.toString().equals(cwSignature)) return;
        cwSignature = sig.toString();

        View cur = getCurrentFocus();
        Object fk = cur != null ? cur.getTag() : null;
        cwRow.removeAllViews();
        if (show.isEmpty()) {
            GlassTile none = new GlassTile(this, dp(1), dp(16));
            none.big = "Nothing to resume yet";
            none.small = "PLAY SOMETHING IN APPLE TV, YOUTUBE, NETFLIX OR HBO MAX";
            none.setFocusable(false);
            none.setAlpha(0.75f);
            none.setLayoutParams(new LinearLayout.LayoutParams(px(440), px(117)));
            cwRow.addView(none);
        } else {
            for (Continue.Entry e : show) cwRow.addView(cwCard(e));
        }
        if (fk != null) {
            View v = getWindow().getDecorView().findViewWithTag(fk);
            if (v != null) v.requestFocus();
        }
    }

    private void collect() {
        items.clear();
        Item s = new Item();
        s.key = "settings";
        s.label = "Settings";
        s.sub = "";
        s.launch = new Intent(Settings.ACTION_SETTINGS);
        ResolveInfo sr = pm.resolveActivity(s.launch, 0);
        if (sr != null) s.icon = sr.loadIcon(pm);
        items.put(s.key, s);

        if (tim != null) {
            List<TvInputInfo> inputs = tim.getTvInputList();
            Map<String, String> cecName = new LinkedHashMap<>();
            for (TvInputInfo t : inputs) {
                if (t.getType() == TvInputInfo.TYPE_HDMI && t.getParentId() != null) {
                    CharSequence l = t.loadLabel(this);
                    if (l != null && l.length() > 0) cecName.put(t.getParentId(), l.toString());
                }
            }
            for (TvInputInfo t : inputs) {
                if (t.getType() != TvInputInfo.TYPE_HDMI || t.getParentId() != null) continue;
                String id = t.getId();
                String name = cecName.get(id);
                if (name != null) prefs.edit().putString("label:" + id, name).apply();
                else name = prefs.getString("label:" + id, null);
                int st = tim.getInputState(id);
                if (name == null && st != TvInputManager.INPUT_STATE_CONNECTED) continue;
                CharSequence port = t.loadCustomLabel(this);
                if (port == null || port.length() == 0) port = t.loadLabel(this);
                Item it = new Item();
                it.key = "input:" + id;
                it.isInput = true;
                it.inputId = id;
                it.label = name != null ? name : String.valueOf(port);
                it.sub = String.valueOf(port).toUpperCase();
                it.icon = t.loadIcon(this);
                items.put(it.key, it);
            }
        }

        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<Item> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String p = ri.activityInfo.packageName;
            if (NEVER_SHOW.contains(p) || !seen.add(p)) continue;
            Item it = new Item();
            it.key = "app:" + p;
            it.pkg = p;
            it.label = String.valueOf(ri.loadLabel(pm));
            it.sub = "";
            it.banner = ri.activityInfo.loadBanner(pm);
            it.icon = ri.loadIcon(pm);
            it.launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(p, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            apps.add(it);
        }
        Collections.sort(apps, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return a.label.compareToIgnoreCase(b.label); }
        });
        for (Item it : apps) items.put(it.key, it);
    }

    private String appLabel(String pkg) {
        Item it = items.get("app:" + pkg);
        if (it != null) return it.label;
        try { return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); }
        catch (Exception e) { return pkg; }
    }

    private List<String> favs() {
        String s = prefs.getString("favs", null);
        if (s == null) {
            List<String> d = new ArrayList<>(Arrays.asList(DEFAULT_FAVS));
            for (Item it : items.values())
                if (it.isInput && it.label.toLowerCase().contains("playstation")) d.add(it.key);
            saveFavs(d);
            return d;
        }
        return new ArrayList<>(Arrays.asList(TextUtils.split(s, ",")));
    }

    private void saveFavs(List<String> f) { prefs.edit().putString("favs", TextUtils.join(",", f)).apply(); }

    private void render(List<String> favs, Set<String> hidden) {
        View cur = getCurrentFocus();
        Object focusedKey = cur != null ? cur.getTag() : null;
        favRow.removeAllViews();
        appRow.removeAllViews();
        for (String k : favs) {
            Item it = items.get(k);
            if (it != null) favRow.addView(tile(it, 150, 92, true));
        }
        for (Item it : items.values()) {
            if (favs.contains(it.key) || hidden.contains(it.key)) continue;
            appRow.addView(tile(it, 96, 62, false));
        }
        if (focusMode) favRow.post(new Runnable() { @Override public void run() { if (focusMode) positionFavs(true, 0); } });
        if (focusedKey != null) {
            View v = getWindow().getDecorView().findViewWithTag(focusedKey);
            if (v != null) { v.requestFocus(); return; }
        }
        if (cur != null) focusFirst();
    }

    private void focusFirst() {
        favRow.post(new Runnable() {
            @Override public void run() {
                View v = favRow.getChildCount() > 0 ? favRow.getChildAt(0)
                        : (appRow.getChildCount() > 0 ? appRow.getChildAt(0) : null);
                if (v != null) v.requestFocus();
            }
        });
    }

    // ================================================================ actions

    private void launch(Item it) {
        try {
            Intent i = it.isInput
                    ? new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(it.inputId))
                    : new Intent(it.launch);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open " + it.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void resume(Continue.Entry e) {
        // 1) app still open: jump straight back into its player
        android.app.PendingIntent pi = MediaWatcher.ACTIVITY.get(e.pkg);
        if (pi != null && e.id.startsWith("np:")) {
            try {
                android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                pi.send(this, 0, null, null, null, null, o.toBundle());
                return;
            } catch (Exception ignored) { }
        }
        // 2) YouTube: open the exact video at the saved position
        if ("com.google.android.youtube.tv".equals(e.pkg) && e.mediaId != null && e.mediaId.matches("[A-Za-z0-9_-]{11}")) {
            try {
                String url = "https://www.youtube.com/watch?v=" + e.mediaId
                        + (e.posMs > 30000 ? "&t=" + (e.posMs / 1000) : "");
                Intent yt = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(e.pkg);
                yt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(yt);
                return;
            } catch (Exception ignored) { }
        }
        // 3) Watch Next deep link
        if (e.intent != null) {
            try {
                Intent i = new Intent(e.intent);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Exception ignored) { }
        }
        // 4) open the app
        Intent l = pm.getLeanbackLaunchIntentForPackage(e.pkg);
        if (l == null) l = pm.getLaunchIntentForPackage(e.pkg);
        if (l != null) {
            try { startActivity(l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; } catch (Exception ignored) { }
        }
        Toast.makeText(this, "Can't open this title", Toast.LENGTH_SHORT).show();
    }

    private void menu(final Item it) {
        final List<String> favs = favs();
        final boolean fav = favs.contains(it.key);
        final List<String> opts = new ArrayList<>();
        if (fav) {
            opts.add("Move left");
            opts.add("Move right");
            opts.add("Remove from favourites");
        } else {
            opts.add("Add to favourites");
            if (!"settings".equals(it.key)) opts.add("Hide");
        }
        if (it.pkg != null) opts.add("App info");
        if ("settings".equals(it.key)) opts.add("Show hidden apps");

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(it.label)
                .setItems(opts.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String o = opts.get(which);
                        int idx = favs.indexOf(it.key);
                        Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden", Collections.<String>emptySet()));
                        switch (o) {
                            case "Move left":
                                if (idx > 0) { Collections.swap(favs, idx, idx - 1); saveFavs(favs); }
                                break;
                            case "Move right":
                                if (idx >= 0 && idx < favs.size() - 1) { Collections.swap(favs, idx, idx + 1); saveFavs(favs); }
                                break;
                            case "Remove from favourites":
                                favs.remove(it.key); saveFavs(favs);
                                break;
                            case "Add to favourites":
                                favs.add(it.key); saveFavs(favs);
                                break;
                            case "Hide":
                                hidden.add(it.key); prefs.edit().putStringSet("hidden", hidden).apply();
                                break;
                            case "Show hidden apps":
                                prefs.edit().remove("hidden").apply();
                                break;
                            case "App info":
                                try {
                                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:" + it.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                                } catch (Exception ignored) { }
                                return;
                            default:
                                return;
                        }
                        signature = "";
                        refresh(false);
                        View v = getWindow().getDecorView().findViewWithTag(it.key);
                        if (v != null) v.requestFocus(); else focusFirst();
                    }
                })
                .show();
    }

    // ================================================================ helpers

    private void style(TextView t, float sp, int color, String family) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        t.setShadowLayer(dp(6), 0, dp(1), 0x66000000);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private int px(float v) { return Math.round(dp(v)); }
}
