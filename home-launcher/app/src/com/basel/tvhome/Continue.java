/*
 * Glass Home for TCL Google TV
 * Copyright (c) 2026 Basel Majid
 * Licensed under the MIT License (see LICENSE).
 */
package com.basel.tvhome;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.provider.BaseColumns;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Continue Watching": reads the system Watch Next list that streaming apps publish
 * (needs READ_EPG_DATA, granted over ADB). Apps that publish nothing fall back to a
 * "Resume in <app>" card based on when they were last used (needs usage access).
 */
final class Continue {
    private Continue() { }

    static final class Entry {
        String id, pkg, title, sub = "", art, mediaId;
        Intent intent;
        long when;
        float progress = -1f;
        long remainingMs = -1, posMs = 0;
        boolean appOnly;
    }

    static List<Entry> query(Context ctx, Collection<String> fallbackPkgs, int limit) {
        List<Entry> rows = new ArrayList<>();
        Set<String> withRows = new HashSet<>();
        Set<String> seen = new HashSet<>();
        String[] proj = {
                BaseColumns._ID,
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_BROWSABLE
        };
        Cursor c = null;
        int raw = 0;
        try {
            c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, proj, null, null, null);
            while (c != null && c.moveToNext()) {
                raw++;
                if (!c.isNull(12) && c.getInt(12) == 0) continue;
                Entry e = new Entry();
                e.id = "wn:" + c.getLong(0);
                e.pkg = c.getString(1);
                e.title = c.getString(2);
                if (e.title == null || e.title.length() == 0) continue;
                String ep = c.getString(3), sn = c.getString(4), en = c.getString(5);
                StringBuilder sb = new StringBuilder();
                if (sn != null && sn.length() > 0) sb.append("S").append(sn);
                if (en != null && en.length() > 0) { if (sb.length() > 0) sb.append(" · "); sb.append("E").append(en); }
                if (ep != null && ep.length() > 0) { if (sb.length() > 0) sb.append(" · "); sb.append(ep); }
                e.sub = sb.toString();
                String thumb = c.getString(7), poster = c.getString(6);
                e.art = thumb != null && thumb.length() > 0 ? thumb : poster;
                String iu = c.getString(8);
                if (iu != null) {
                    try { e.intent = Intent.parseUri(iu, Intent.URI_INTENT_SCHEME); } catch (Exception ignored) { }
                }
                e.when = c.isNull(9) ? 0 : c.getLong(9);
                long pos = c.isNull(10) ? 0 : c.getLong(10), dur = c.isNull(11) ? 0 : c.getLong(11);
                if (dur > 0 && pos > 0) {
                    e.progress = Math.min(1f, pos / (float) dur);
                    e.remainingMs = Math.max(0, dur - pos);
                }
                if (!seen.add(e.pkg + "|" + e.title + "|" + e.sub)) continue;
                rows.add(e);
                withRows.add(e.pkg);
            }
        } catch (Throwable t) {
            Log.w(Glass.TAG, "WATCHNEXT_ERROR " + t);
        } finally {
            if (c != null) c.close();
        }

        // Last-played records captured from media sessions (see MediaWatcher)
        int np = 0;
        try {
            Map<String, ?> all = ctx.getSharedPreferences(MediaWatcher.PREFS, Context.MODE_PRIVATE).getAll();
            for (Map.Entry<String, ?> en : all.entrySet()) {
                if (!en.getKey().startsWith("np:") || !(en.getValue() instanceof String)) continue;
                String pkg = en.getKey().substring(3);
                if (withRows.contains(pkg)) continue;
                String[] v = ((String) en.getValue()).split("\u001f", -1);
                if (v.length < 6 || v[0].length() == 0) continue;
                Entry e = new Entry();
                e.id = "np:" + pkg;
                e.pkg = pkg;
                e.title = v[0];
                e.sub = v[1];
                try { e.when = Long.parseLong(v[2]); } catch (Exception x) { e.when = 0; }
                long pos = 0, dur = 0;
                try { pos = Long.parseLong(v[3]); dur = Long.parseLong(v[4]); } catch (Exception ignored) { }
                if (dur > 0 && pos > 0) { e.progress = Math.min(1f, pos / (float) dur); e.remainingMs = Math.max(0, dur - pos); }
                e.posMs = pos;
                e.art = v[5].length() > 0 ? v[5] : null;
                if (v.length >= 7 && v[6].length() > 0) e.mediaId = v[6];
                rows.add(e);
                withRows.add(pkg);
                np++;
            }
        } catch (Throwable t) {
            Log.w(Glass.TAG, "NP_ERROR " + t);
        }

        int usage = 0;
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            Map<String, UsageStats> m = usm.queryAndAggregateUsageStats(now - 21L * 24 * 3600 * 1000, now);
            Log.i(Glass.TAG, "USAGE entries=" + m.size());
            for (String p : fallbackPkgs) {
                if (true) break; // generic "Resume in <app>" cards disabled by design
                if (withRows.contains(p)) continue;
                UsageStats u = m.get(p);
                if (u == null || u.getLastTimeUsed() <= 0 || u.getTotalTimeInForeground() < 20_000) continue;
                Entry e = new Entry();
                e.id = "app:" + p;
                e.pkg = p;
                e.appOnly = true;
                e.when = u.getLastTimeUsed();
                rows.add(e);
                usage++;
            }
        } catch (Throwable t) {
            Log.w(Glass.TAG, "USAGE_ERROR " + t);
        }

        Collections.sort(rows, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return Long.compare(b.when, a.when); }
        });
        if (rows.size() > limit) rows = new ArrayList<>(rows.subList(0, limit));
        Log.i(Glass.TAG, "WATCHNEXT rows=" + raw + " mediaSession=" + np + " shown=" + rows.size() + " usageFallback=" + usage);
        return rows;
    }
}
