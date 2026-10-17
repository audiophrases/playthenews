package com.eugen.playthenews;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** The user's own ordered list of sources ("My list"), stored in SharedPreferences. */
final class Lineup {

    private static final String TAG = "PlayTheNews";
    private static final String PREFS = "lineup";
    private static final String KEY = "sources";

    static boolean exists(Context c) {
        return prefs(c).contains(KEY);
    }

    static List<Source> load(Context c) {
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

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Lineup() {}
}
