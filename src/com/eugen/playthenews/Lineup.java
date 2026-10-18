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
 * bulletin first. Both are stored in SharedPreferences.
 */
final class Lineup {

    private static final String TAG = "PlayTheNews";
    private static final String PREFS = "lineup";
    private static final String KEY = "sources";
    private static final String KEY_NEWEST_FIRST = "newest_first";

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
