package com.eugen.playthenews;

import android.util.Xml;

import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Turns a {@link Source} into its latest playable episode. Blocking: call off the main thread. */
final class Resolver {

    /** Some CDNs (RAI's Akamai) reject anything that does not look like a browser. */
    static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36";

    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_REDIRECTS = 6;

    static final class Episode {
        final String title;
        final String audioUrl;
        final String artUrl;
        final long durationMs;

        Episode(String title, String audioUrl, String artUrl, long durationMs) {
            this.title = title;
            this.audioUrl = audioUrl;
            this.artUrl = artUrl;
            this.durationMs = durationMs;
        }
    }

    static Episode resolve(Source s, Catalog catalog) throws IOException {
        switch (s.kind) {
            case Source.RSS: return parseRss(fetch(s.url));
            case Source.RAI: return resolveRai(s.url);
            case Source.GOOGLE: return resolveGoogle(s, catalog);
            default: throw new IOException("Unknown source type " + s.kind);
        }
    }

    // ---- Google ---------------------------------------------------------------------------

    private static Episode resolveGoogle(Source s, Catalog catalog) throws IOException {
        if (catalog == null) throw new IOException("Google news catalog not loaded");
        Catalog.Item item = catalog.find(s.url, s.name);
        if (item == null) throw new IOException(s.name + " is not in today's Google news");
        return new Episode(item.subtitle, item.audioUrl(), item.iconUri, 0);
    }

    // ---- RSS ------------------------------------------------------------------------------

    /** Reads the channel image and the first item's title, enclosure, duration and image. */
    static Episode parseRss(byte[] xml) throws IOException {
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new ByteArrayInputStream(xml), null);

            boolean inItem = false;
            String channelArt = null, title = null, audio = null, itemArt = null, duration = null;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = p.getName();
                    if ("item".equals(tag) || "entry".equals(tag)) {
                        inItem = true;
                    } else if ("itunes:image".equals(tag)) {
                        String href = p.getAttributeValue(null, "href");
                        if (inItem) itemArt = href; else if (channelArt == null) channelArt = href;
                    } else if (!inItem && "url".equals(tag) && channelArt == null) {
                        channelArt = p.nextText().trim();
                    } else if (inItem && "title".equals(tag) && title == null) {
                        title = p.nextText().trim();
                    } else if (inItem && "itunes:duration".equals(tag)) {
                        duration = p.nextText().trim();
                    } else if (inItem && ("enclosure".equals(tag) || "media:content".equals(tag))
                            && audio == null) {
                        String type = p.getAttributeValue(null, "type");
                        if (type == null || type.startsWith("audio") || type.contains("mpeg")) {
                            audio = p.getAttributeValue(null, "url");
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && inItem
                        && ("item".equals(p.getName()) || "entry".equals(p.getName()))) {
                    if (audio != null) break;
                    // An item without audio (a text post); keep looking.
                    inItem = false;
                    title = null;
                    itemArt = null;
                    duration = null;
                }
            }
            if (audio == null) throw new IOException("Feed has no audio episode");
            return new Episode(title, audio, itemArt != null ? itemArt : channelArt,
                    parseDuration(duration));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not read feed: " + e.getMessage(), e);
        }
    }

    // ---- RAI ------------------------------------------------------------------------------

    /** RaiPlay Sound program JSON: newest card's audio is a "relinker" that redirects to HLS. */
    private static Episode resolveRai(String programJson) throws IOException {
        try {
            JSONObject card = new JSONObject(new String(fetch(programJson), "UTF-8"))
                    .getJSONObject("block").getJSONArray("cards").getJSONObject(0);
            JSONObject audio = card.getJSONObject("audio");
            String poster = audio.optString("poster", null);
            if (poster != null && poster.startsWith("/")) poster = "https://www.raiplaysound.it" + poster;
            return new Episode(card.optString("title", audio.optString("title")),
                    finalUrl(audio.getString("url")), poster,
                    parseDuration(audio.optString("duration", null)));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not read RAI program: " + e.getMessage(), e);
        }
    }

    // ---- HTTP -----------------------------------------------------------------------------

    static byte[] fetch(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** Follows redirects (including http -> https, which HttpURLConnection won't) to the media URL. */
    private static String finalUrl(String url) throws IOException {
        HttpURLConnection c = open(url);
        String result = c.getURL().toString();
        c.disconnect();
        return result;
    }

    private static HttpURLConnection open(String url) throws IOException {
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", USER_AGENT);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null) throw new IOException("Redirect without location");
                url = new URL(new URL(url), location).toString();
                continue;
            }
            if (code >= 400) {
                c.disconnect();
                throw new IOException("HTTP " + code + " for " + url);
            }
            return c;
        }
        throw new IOException("Too many redirects");
    }

    /** "1:02:03", "12:34", or plain seconds. */
    private static long parseDuration(String s) {
        if (s == null || s.isEmpty()) return 0;
        long total = 0;
        try {
            for (String part : s.split(":")) total = total * 60 + (long) Double.parseDouble(part);
        } catch (NumberFormatException e) {
            return 0;
        }
        return total * 1000;
    }

    private Resolver() {}
}
