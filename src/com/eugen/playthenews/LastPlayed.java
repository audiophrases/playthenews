package com.eugen.playthenews;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What was playing when the app last ran: the queue, its item, that item's bulletin and how far
 * into it, so a restart picks up at the same point. Stored in SharedPreferences.
 */
final class LastPlayed {

    private static final String TAG = "PlayTheNews";
    private static final String PREFS = "last_played";
    private static final String KEY = "state";

    final List<Source> queue;
    final String title;
    final int index;
    /** The bulletin that was playing, or null if it hadn't been resolved yet. */
    final Resolver.Episode episode;
    final long positionMs;

    private LastPlayed(List<Source> queue, String title, int index, Resolver.Episode episode,
            long positionMs) {
        this.queue = queue;
        this.title = title;
        this.index = index;
        this.episode = episode;
        this.positionMs = positionMs;
    }

    static void save(Context c, List<Source> queue, String title, int index,
            Resolver.Episode episode, long positionMs) {
        try {
            JSONArray q = new JSONArray();
            for (Source s : queue) q.put(s.toJson());
            JSONObject o = new JSONObject().put("t", title).put("i", index).put("q", q)
                    .put("p", positionMs);
            if (episode != null) {
                o.put("e", new JSONObject().put("t", episode.title).put("a", episode.audioUrl)
                        .put("r", episode.artUrl).put("d", episode.durationMs)
                        .put("at", episode.publishedAt));
            }
            prefs(c).edit().putString(KEY, o.toString()).apply();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Null if nothing was saved, or it can't be read. */
    static LastPlayed load(Context c) {
        String json = prefs(c).getString(KEY, null);
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            List<Source> queue = new ArrayList<>();
            JSONArray q = o.getJSONArray("q");
            for (int i = 0; i < q.length(); i++) queue.add(Source.fromJson(q.getJSONObject(i)));
            int index = o.getInt("i");
            if (index < 0 || index >= queue.size()) return null;
            JSONObject e = o.optJSONObject("e");
            Resolver.Episode episode = e == null ? null : new Resolver.Episode(
                    e.has("t") ? e.getString("t") : null,
                    e.getString("a"), e.has("r") ? e.getString("r") : null,
                    e.optLong("d"), e.optLong("at"));
            return new LastPlayed(queue, o.getString("t"), index, episode, o.optLong("p"));
        } catch (JSONException e) {
            Log.e(TAG, "corrupt last played state, ignoring", e);
            return null;
        }
    }

    static void clear(Context c) {
        prefs(c).edit().remove(KEY).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
