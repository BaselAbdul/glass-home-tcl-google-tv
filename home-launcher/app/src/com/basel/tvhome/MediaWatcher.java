/*
 * Glass Home for TCL Google TV
 * Copyright (c) 2026 Basel Majid
 * Licensed under the MIT License (see LICENSE).
 */
package com.basel.tvhome;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the last title actually watched in each app (title, subtitle, artwork, position,
 * media id) from the app's media session. Trailers/autoplay previews are ignored (needs 15 s of
 * real playback). Data never leaves the TV; it lives in this app's private storage.
 */
public class MediaWatcher extends NotificationListenerService
        implements MediaSessionManager.OnActiveSessionsChangedListener {

    static final String PREFS = "home";
    static final String SEP = "\u001f";
    /** Live "open the player" intents, valid while the app's session exists. */
    static final Map<String, PendingIntent> ACTIVITY = new ConcurrentHashMap<>();

    private MediaSessionManager msm;
    private final Map<MediaSession.Token, MediaController> ctrls = new HashMap<>();
    private final Map<MediaSession.Token, MediaController.Callback> cbs = new HashMap<>();
    private final Map<String, String> lastTitle = new HashMap<>();
    private final Map<String, Long> lastWrite = new HashMap<>();
    private final Map<String, Long> playingSince = new HashMap<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            for (MediaController mc : new ArrayList<>(ctrls.values())) record(mc, false);
            h.postDelayed(this, 20000);
        }
    };

    @Override public void onListenerConnected() {
        try {
            msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(this, MediaWatcher.class);
            msm.addOnActiveSessionsChangedListener(this, cn);
            onActiveSessionsChanged(msm.getActiveSessions(cn));
            h.removeCallbacks(tick);
            h.postDelayed(tick, 20000);
            Log.i(Glass.TAG, "MEDIAWATCHER connected");
        } catch (Throwable t) {
            Log.w(Glass.TAG, "MEDIAWATCHER error " + t);
        }
    }

    @Override public void onListenerDisconnected() {
        h.removeCallbacks(tick);
        if (msm != null) msm.removeOnActiveSessionsChangedListener(this);
        for (Map.Entry<MediaSession.Token, MediaController> e : ctrls.entrySet()) {
            MediaController.Callback cb = cbs.get(e.getKey());
            if (cb != null) e.getValue().unregisterCallback(cb);
        }
        ctrls.clear();
        cbs.clear();
    }

    @Override public void onActiveSessionsChanged(List<MediaController> list) {
        if (list == null) return;
        for (final MediaController mc : list) {
            final MediaSession.Token tok = mc.getSessionToken();
            if (ctrls.containsKey(tok)) { record(mc, false); continue; }
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata m) { record(mc, false); }
                @Override public void onPlaybackStateChanged(PlaybackState s) { record(mc, false); }
                @Override public void onSessionDestroyed() {
                    record(mc, true);
                    ACTIVITY.remove(mc.getPackageName());
                    ctrls.remove(tok);
                    cbs.remove(tok);
                }
            };
            mc.registerCallback(cb);
            ctrls.put(tok, mc);
            cbs.put(tok, cb);
            record(mc, false);
        }
    }

    private void record(MediaController mc, boolean force) {
        try {
            final String pkg = mc.getPackageName();
            if (pkg == null || pkg.equals(getPackageName())) return;
            PendingIntent sa = mc.getSessionActivity();
            if (sa != null) ACTIVITY.put(pkg, sa);
            MediaMetadata m = mc.getMetadata();
            if (m == null) return;
            CharSequence t = m.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (t == null || t.length() == 0) t = m.getText(MediaMetadata.METADATA_KEY_TITLE);
            if (t == null || t.length() == 0) return;
            final String title = t.toString().trim();
            CharSequence sub = m.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            if (sub == null || sub.length() == 0) sub = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
            if (sub == null || sub.length() == 0) sub = m.getText(MediaMetadata.METADATA_KEY_ALBUM);
            String subtitle = sub == null ? "" : sub.toString().trim();
            if (subtitle.equalsIgnoreCase(title)) subtitle = "";
            String mediaId = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);

            PlaybackState ps = mc.getPlaybackState();
            int st = ps == null ? PlaybackState.STATE_NONE : ps.getState();
            long pos = 0;
            if (ps != null) {
                pos = ps.getPosition();
                if (st == PlaybackState.STATE_PLAYING && ps.getLastPositionUpdateTime() > 0)
                    pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
            }
            long now = System.currentTimeMillis();
            String key = pkg + SEP + title;
            if (st == PlaybackState.STATE_PLAYING) {
                if (!playingSince.containsKey(key)) playingSince.put(key, now);
            }
            Long since = playingSince.get(key);
            boolean watched = pos >= 15000 || (since != null && now - since >= 30000);
            boolean alreadyKnown = title.equals(lastTitle.get(pkg));
            if (!watched && !alreadyKnown) return;           // ignore trailers / previews
            if (st == PlaybackState.STATE_NONE && !alreadyKnown) return;

            Long lw = lastWrite.get(pkg);
            if (!force && alreadyKnown && lw != null && now - lw < 15000) return;
            boolean newTitle = !alreadyKnown;
            lastTitle.put(pkg, title);
            lastWrite.put(pkg, now);

            String artUri = m.getString(MediaMetadata.METADATA_KEY_ART_URI);
            if (artUri == null) artUri = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (artUri == null) artUri = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (art != null) {
                final File f = new File(getFilesDir(), "np_" + pkg + ".jpg");
                if (newTitle || !f.exists()) {
                    final Bitmap a = art;
                    Images.POOL.execute(new Runnable() {
                        @Override public void run() {
                            try {
                                float s = Math.min(1f, 640f / a.getWidth());
                                Bitmap sc = s < 1f ? Bitmap.createScaledBitmap(a, Math.round(a.getWidth() * s),
                                        Math.round(a.getHeight() * s), true) : a;
                                FileOutputStream o = new FileOutputStream(f);
                                sc.compress(Bitmap.CompressFormat.JPEG, 90, o);
                                o.close();
                            } catch (Throwable ignored) { }
                        }
                    });
                    artUri = "file://" + f.getAbsolutePath() + "?t=" + now;
                } else {
                    String[] pv = split(sp.getString("np:" + pkg, null));
                    artUri = pv != null && pv.length >= 6 && pv[0].equals(title) && pv[5].length() > 0
                            ? pv[5] : "file://" + f.getAbsolutePath() + "?t=" + now;
                }
            }
            String rec = title + SEP + subtitle + SEP + now + SEP + Math.max(0, pos) + SEP + Math.max(0, dur)
                    + SEP + (artUri == null ? "" : artUri) + SEP + (mediaId == null ? "" : mediaId);
            sp.edit().putString("np:" + pkg, rec).apply();
            if (newTitle || force) {
                Log.i(Glass.TAG, "MEDIAWATCHER record pkg=" + pkg + " pos=" + pos / 1000 + "s dur=" + dur / 1000
                        + "s art=" + (artUri != null) + " id=" + (mediaId != null) + " sub=" + (subtitle.length() > 0));
            }
        } catch (Throwable t) {
            Log.w(Glass.TAG, "MEDIAWATCHER record " + t);
        }
    }

    static String[] split(String rec) { return rec == null ? null : rec.split(SEP, -1); }
}
