package com.eugen.playthenews;

import org.json.JSONException;
import org.json.JSONObject;

/** One news source the player can play: a podcast feed, a RAI program, or a Google news item. */
final class Source {
    static final String RSS = "rss";
    static final String RAI = "rai";
    static final String GOOGLE = "google";

    final String kind;
    final String name;
    /** Language code for directory sources; the Google category name for Google sources. */
    final String group;
    /** Feed URL (rss), program JSON URL (rai), or Google category id (google). */
    final String url;

    Source(String kind, String name, String group, String url) {
        this.kind = kind;
        this.name = name;
        this.group = group;
        this.url = url;
    }

    /** Stable identity, used to spot duplicates and to keep the current item across edits. */
    String id() {
        return GOOGLE.equals(kind) ? "google|" + name : kind + "|" + url;
    }

    /** Second line shown under the name in lists. */
    String label() {
        return GOOGLE.equals(kind) ? "Google · " + group : Directory.languageName(group);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("k", kind).put("n", name).put("g", group).put("u", url);
    }

    static Source fromJson(JSONObject o) throws JSONException {
        return new Source(o.getString("k"), o.getString("n"), o.optString("g"), o.getString("u"));
    }
}
