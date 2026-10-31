package com.eugen.playthenews;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The user's own ordered list of sources ("My list") and how it plays: in that order, or newest
 * bulletin first, optionally only bulletins of some length. All are stored in SharedPreferences.
 */
final class Lineup {

    private static final String TAG = "PlayTheNews";
    private static final String PREFS = "lineup";
    private static final String KEY = "sources";
    private static final String KEY_NEWEST_FIRST = "newest_first";
    private static final String KEY_LENGTH_FILTER = "length_filter";

    static boolean exists(Context c) {
        ensurePreloaded(c);
        return prefs(c).contains(KEY);
    }

    static boolean newestFirst(Context c) {
        ensurePreloaded(c);
        return prefs(c).getBoolean(KEY_NEWEST_FIRST, true);
    }

    static void setNewestFirst(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_NEWEST_FIRST, on).apply();
    }

    /**
     * 0 plays bulletins of any length; n > 0 only those shorter than n minutes; n < 0 skips
     * those shorter than -n minutes.
     */
    static int lengthFilter(Context c) {
        return prefs(c).getInt(KEY_LENGTH_FILTER, 0);
    }

    static void setLengthFilter(Context c, int filter) {
        prefs(c).edit().putInt(KEY_LENGTH_FILTER, filter).apply();
    }

    /** Whether a bulletin this long passes the filter. An unknown length (0) always does. */
    static boolean passes(int filter, long durationMs) {
        if (filter == 0 || durationMs <= 0) return true;
        boolean shorter = durationMs < Math.abs(filter) * 60_000L;
        return filter > 0 ? shorter : !shorter;
    }

    /**
     * The sources in play order (saved or newest first), without those whose latest bulletin the
     * length filter rejects. Sources not in latest are kept: their length is not known yet.
     */
    static List<Source> arrange(Context c, List<Source> sources, Map<String, Resolver.Episode> latest) {
        List<Source> out = newestFirst(c) ? byNewest(sources, latest) : new ArrayList<>(sources);
        int filter = lengthFilter(c);
        for (int i = out.size() - 1; i >= 0; i--) {
            Resolver.Episode e = latest.get(out.get(i).id());
            if (e != null && !passes(filter, e.durationMs)) out.remove(i);
        }
        return out;
    }

    /**
     * The sources ordered by their latest episode, newest first. Sources with no known date go
     * last, in their saved order (the sort is stable).
     */
    static List<Source> byNewest(List<Source> sources, Map<String, Resolver.Episode> latest) {
        List<Source> out = new ArrayList<>(sources);
        Collections.sort(out, (a, b) -> Long.compare(publishedAt(b, latest), publishedAt(a, latest)));
        return out;
    }

    private static long publishedAt(Source s, Map<String, Resolver.Episode> latest) {
        Resolver.Episode e = latest.get(s.id());
        return e != null ? e.publishedAt : 0;
    }

    static List<Source> load(Context c) {
        ensurePreloaded(c);
        List<Source> out = new ArrayList<>();
        String json = prefs(c).getString(KEY, null);
        if (json == null) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) out.add(Source.fromJson(a.getJSONObject(i)));
        } catch (JSONException e) {
            Log.e(TAG, "corrupt lineup, ignoring", e);
        }
        return out;
    }

    static void save(Context c, List<Source> sources) {
        JSONArray a = new JSONArray();
        try {
            for (Source s : sources) a.put(s.toJson());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        prefs(c).edit().putString(KEY, a.toString()).apply();
    }

    private static void ensurePreloaded(Context c) {
        if (prefs(c).contains(KEY)) return;
        try (java.io.InputStream in = c.getResources().openRawResource(R.raw.preloaded_sources);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            String json = out.toString("UTF-8");
            new JSONArray(json); // Validate before saving; preserve an intentionally empty saved list.
            prefs(c).edit().putString(KEY, json).putBoolean(KEY_NEWEST_FIRST, true).apply();
        } catch (Exception e) { Log.e(TAG, "Could not preload sources", e); }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Lineup() {}
}
