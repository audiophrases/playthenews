package com.eugen.playthenews;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds podcasts by name in Apple's public podcast directory, which needs no account or key.
 * Blocking: call off the main thread.
 */
final class PodcastSearch {

    private static final int LIMIT = 30;

    static final class Result {
        final String name;
        final String author;
        final String genre;
        final String feedUrl;
        /** When its newest episode came out (ms since epoch), or 0. */
        final long updatedAt;

        Result(String name, String author, String genre, String feedUrl, long updatedAt) {
            this.name = name;
            this.author = author;
            this.genre = genre;
            this.feedUrl = feedUrl;
            this.updatedAt = updatedAt;
        }
    }

    /** Podcasts matching term, as listed in the store of the phone's country. */
    static List<Result> search(String term) throws IOException {
        String country = Locale.getDefault().getCountry();
        String url = "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=" + LIMIT
                + "&term=" + URLEncoder.encode(term, "UTF-8")
                + (country.matches("[A-Z]{2}") ? "&country=" + country : "");
        try {
            JSONArray results = new JSONObject(new String(Resolver.fetch(url), "UTF-8"))
                    .getJSONArray("results");
            List<Result> out = new ArrayList<>();
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                String feed = r.optString("feedUrl", "");
                if (feed.isEmpty()) continue; // a few listings don't publish their feed
                out.add(new Result(r.optString("collectionName", feed), r.optString("artistName", ""),
                        r.optString("primaryGenreName", ""), feed,
                        Resolver.parseDate(r.optString("releaseDate", null))));
            }
            return out;
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from the podcast directory", e);
        }
    }

    private PodcastSearch() {}
}
