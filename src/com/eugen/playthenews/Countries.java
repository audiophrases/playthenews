package com.eugen.playthenews;

import android.net.Uri;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Where a source is from, as a flag code: an ISO country ("fr", "gb"), or "es-ct" for every
 * Catalan-language source so those stand apart from Spanish ones. Flags come from flag-icons
 * (MIT); add more with tools/make-flags.ps1.
 */
final class Countries {

    static final String CATALAN = "es-ct";

    private static final Map<String, Integer> FLAGS = new HashMap<>();

    static {
        FLAGS.put("at", R.drawable.flag_at);
        FLAGS.put("au", R.drawable.flag_au);
        FLAGS.put("be", R.drawable.flag_be);
        FLAGS.put("ca", R.drawable.flag_ca);
        FLAGS.put("ch", R.drawable.flag_ch);
        FLAGS.put("de", R.drawable.flag_de);
        FLAGS.put("es", R.drawable.flag_es);
        FLAGS.put(CATALAN, R.drawable.flag_es_ct);
        FLAGS.put("fr", R.drawable.flag_fr);
        FLAGS.put("gb", R.drawable.flag_gb);
        FLAGS.put("ie", R.drawable.flag_ie);
        FLAGS.put("in", R.drawable.flag_in);
        FLAGS.put("it", R.drawable.flag_it);
        FLAGS.put("mx", R.drawable.flag_mx);
        FLAGS.put("nl", R.drawable.flag_nl);
        FLAGS.put("nz", R.drawable.flag_nz);
        FLAGS.put("pt", R.drawable.flag_pt);
        FLAGS.put("qa", R.drawable.flag_qa);
        FLAGS.put("us", R.drawable.flag_us);
    }

    /**
     * Google lists its sources by publisher name only. Matched as whole words, first match wins,
     * so more specific names come before the ones they contain ("abc news australia", "abc news").
     */
    private static final String[][] PUBLISHERS = {
            {"abc news australia", "au"}, {"abc australia", "au"}, {"sbs", "au"},
            {"times of india", "in"}, {"ndtv", "in"}, {"the hindu", "in"},
            {"irish times", "ie"}, {"irish independent", "ie"}, {"rté", "ie"}, {"rte", "ie"},
            {"newstalk", "ie"},
            {"bbc", "gb"}, {"sky news", "gb"}, {"sky sports", "gb"}, {"lbc", "gb"},
            {"global's", "gb"}, {"times radio", "gb"}, {"the times", "gb"}, {"guardian", "gb"},
            {"telegraph", "gb"}, {"financial times", "gb"}, {"economist", "gb"}, {"reuters", "gb"},
            {"itv", "gb"}, {"channel 4", "gb"}, {"talksport", "gb"}, {"independent", "gb"},
            {"npr", "us"}, {"cbs", "us"}, {"abc news", "us"}, {"nbc", "us"}, {"msnbc", "us"},
            {"cnbc", "us"}, {"cnn", "us"}, {"fox", "us"}, {"pbs", "us"}, {"ap", "us"},
            {"associated press", "us"}, {"wall street journal", "us"}, {"wsj", "us"},
            {"new york times", "us"}, {"washington post", "us"}, {"bloomberg", "us"},
            {"espn", "us"}, {"voice of america", "us"}, {"voa", "us"}, {"usa today", "us"},
            {"politico", "us"},
            {"cbc", "ca"}, {"ctv", "ca"}, {"radio canada", "ca"},
            {"al jazeera", "qa"},
            {"dw", "de"}, {"deutsche welle", "de"}, {"tagesschau", "de"}, {"deutschlandfunk", "de"},
            {"france 24", "fr"}, {"franceinfo", "fr"}, {"france info", "fr"}, {"rfi", "fr"},
            {"france inter", "fr"}, {"le monde", "fr"},
            {"3cat", CATALAN}, {"catalunya ràdio", CATALAN}, {"catalunya radio", CATALAN},
            {"rac1", CATALAN}, {"rac 1", CATALAN},
            {"rtve", "es"}, {"rne", "es"}, {"cadena ser", "es"}, {"cope", "es"}, {"onda cero", "es"},
            {"el país", "es"},
            {"rai", "it"}, {"ansa", "it"}, {"sky tg24", "it"},
            {"rtp", "pt"},
            {"srf", "ch"}, {"rts", "ch"}, {"rsi", "ch"}, {"swissinfo", "ch"},
            {"orf", "at"}, {"ö1", "at"},
    };

    /** A language's home country, for feeds that give neither a region nor a country domain. */
    private static final Map<String, String> HOME = new HashMap<>();

    static {
        HOME.put("de", "de");
        HOME.put("fr", "fr");
        HOME.put("it", "it");
        HOME.put("es", "es");
        HOME.put("pt", "pt");
        HOME.put("nl", "nl");
    }

    /** The flag code for a source, or null if unknown. */
    static String of(Source s, Catalog catalog) {
        String name = s.name.toLowerCase(Locale.ROOT);
        if (name.contains("ser tarragona") || name.contains("las noticias de catalunya")
                || s.url.contains("/ser_tarragona/") || s.url.contains("/las_noticias_de_catalunya/")) return CATALAN;
        if (!Source.GOOGLE.equals(s.kind)) {
            String c = Directory.countryOf(s);
            if (c == null) c = s.country;
            // A saved source the directory no longer lists still has its language.
            return c == null && "ca".equals(s.group) ? CATALAN : c;
        }
        String byName = byName(s.name);
        if (byName != null) return byName;
        Catalog.Item item = catalog != null ? catalog.find(s.url, s.name) : null;
        return item != null ? byDomain(item.audioUrl()) : null;
    }

    /**
     * A flag for a feed the directory doesn't list, from the language it declares ("ca", "fr-CA",
     * "en-GB"): Catalan, else the language's region, else the feed's country domain, else the
     * language's home country. Null rather than a guess for, say, plain English on a .com.
     */
    static String guess(String language, String url) {
        String[] lang = language == null ? new String[] {""}
                : language.toLowerCase(Locale.ROOT).replace('_', '-').split("-");
        if (lang[0].equals("ca")) return CATALAN;
        if (lang.length > 1) return FLAGS.containsKey(lang[1]) ? lang[1] : null; // pt-BR isn't Portugal
        String byDomain = byDomain(url);
        return byDomain != null ? byDomain : HOME.get(lang[0]);
    }

    /** The flag drawable for a code, or 0 if there is none. */
    static int flagRes(String code) {
        Integer res = code != null ? FLAGS.get(code) : null;
        return res != null ? res : 0;
    }

    private static String byName(String name) {
        String words = " " + name.toLowerCase(Locale.ROOT).replace('’', '\'')
                .replaceAll("[^\\p{L}\\p{N}']+", " ") + " ";
        for (String[] p : PUBLISHERS) {
            if (words.contains(" " + p[0] + " ")) return p[1];
        }
        return null;
    }

    /** From the episode host's country domain: radiofrance.fr is French, a .cat site Catalan. */
    private static String byDomain(String url) {
        String host = Uri.parse(url).getHost();
        if (host == null) return null;
        String tld = host.substring(host.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (tld.equals("cat")) return CATALAN;
        if (tld.equals("uk")) return "gb";
        return FLAGS.containsKey(tld) ? tld : null;
    }

    private Countries() {}
}
