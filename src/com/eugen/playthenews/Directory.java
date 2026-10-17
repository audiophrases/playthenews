package com.eugen.playthenews;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in news bulletins beyond what the Google app offers. Each is a public podcast feed (or,
 * for RAI, RaiPlay Sound's program JSON) whose newest episode is the latest bulletin.
 * Entries are (language, flag code as in {@link Countries}, name, url).
 */
final class Directory {

    static final String[][] LANGUAGES = {
            {"de", "Deutsch"}, {"fr", "Français"}, {"es", "Español"}, {"ca", "Català"},
            {"it", "Italiano"}, {"en", "English"}, {"pt", "Português"},
    };

    /** Flag code by source id. Filled while SOURCES is built, so it must be declared first. */
    private static final Map<String, String> COUNTRIES = new HashMap<>();

    static final List<Source> SOURCES = Arrays.asList(
            rss("de", "de", "tagesschau in 100 Sekunden", "https://www.tagesschau.de/multimedia/sendung/tagesschau_in_100_sekunden/podcast-ts100-audio-100~podcast.xml"),
            rss("de", "de", "Deutschlandfunk – Die Nachrichten", "https://www.deutschlandfunk.de/nachrichten-108.xml"),
            rss("de", "de", "NDR Info – Nachrichten", "https://www.ndr.de/nachrichten/info/podcast4370.xml"),
            rss("de", "de", "MDR – Hier & Jetzt", "https://www.mdr.de/nachrichten/podcast/beste/audiogalerie-mdr-102.xml"),
            rss("de", "de", "tagesschau – 20 Uhr", "https://www.tagesschau.de/multimedia/sendung/tagesschau_20_uhr/podcast-ts2000-audio-100~podcast.xml"),
            rss("de", "de", "ZDF heute journal", "https://www.zdf.de/rss/podcast/audio/zdf/nachrichten/heute-journal"),
            rss("de", "de", "BR24 Thema des Tages", "https://feeds.br.de/br24-thema-des-tages/feed.xml"),
            rss("de", "at", "Ö1 Journale", "https://podcast.orf.at/podcast/oe1/oe1_journale/oe1_journale.xml"),
            rss("de", "ch", "SRF HeuteMorgen", "https://www.srf.ch/feed/podcast/sd/179ababb-4b36-40b2-951d-92d5c207fe9f.xml"),
            rss("de", "ch", "SRF Echo der Zeit", "https://www.srf.ch/feed/podcast/sd/28549e81-c453-4671-92ad-cb28796d06a8.xml"),
            rss("de", "ch", "SRF News Plus", "https://www.srf.ch/feed/podcast/sd/cef54868-809e-4c26-80c3-0e6475cd6479.xml"),
            rss("de", "de", "DW – Langsam gesprochene Nachrichten", "https://rss.dw.com/xml/DKpodcast_lgn_de"),

            rss("fr", "fr", "France Inter – Journal de 8h", "https://radiofrance-podcast.net/podcast09/rss_12495.xml"),
            rss("fr", "fr", "France Inter – Journal de 19h", "https://radiofrance-podcast.net/podcast09/rss_11736.xml"),
            rss("fr", "fr", "France Culture – Journal de 7h", "https://radiofrance-podcast.net/podcast09/rss_10055.xml"),
            rss("fr", "fr", "France Culture – Journal de 12h30", "https://radiofrance-podcast.net/podcast09/rss_10059.xml"),
            rss("fr", "fr", "RFI – Journal monde", "https://apis.rfi.fr/products/get_product/fov-rfi-fr-get-journaux-podcast-v2-monde?token_application=975d23b8-7a07-11e8-9f62-005056a90194"),
            rss("fr", "fr", "RFI – Journal en français facile", "https://apis.fle.rfi.fr/products/get_product/fle_getpodcast_by_nid_author_rfi?token_application=applepodcast_fle&program.entrepriseId=WBMZ39-FLE-FR-20220627"),
            rss("fr", "fr", "Europe 1 – Le journal", "https://feeds.audiomeans.fr/feed/44f6a116-20a6-44c4-98c4-b3e1bdd46dce.xml"),
            rss("fr", "fr", "RTL – Le journal", "https://feeds.audiomeans.fr/feed/81fdd41c-51e9-4839-b867-7ad5bfe61dea.xml"),
            rss("fr", "ch", "RTS – Le Journal horaire", "https://www.rts.ch/la-1ere/programmes/le-journal-horaire/podcast/?flux=rss"),
            rss("fr", "ca", "Radio-Canada – Le Radiojournal", "https://feeds.feedburner.com/LeRadiojournal"),
            rss("fr", "fr", "Le Monde – L'Heure du Monde", "https://feeds.acast.com/public/shows/68db9d2509b1c365e44318ca"),

            rss("es", "es", "RNE – Boletines", "https://secure-embed.rtve.es/rneapple/boletines.rss"),
            rss("es", "es", "RNE – 24 horas", "https://api.rtve.es/api/adapter/programas/1871/audios.rss"),
            rss("es", "es", "Onda Cero – Las noticias", "https://www.ondacero.es/rss/podcast/triton/mount/ATRESMEDIA_LAS_NOTICIAS_EN_ONDA_CERO_AOD_P/origin/fastly"),
            rss("es", "es", "Cadena SER – Las noticias de Catalunya", "https://fapi-top.prisasd.com/podcast/playser/las_noticias_de_catalunya/itunestfp/podcast.xml"),
            rss("es", "fr", "RFI Español – Noticieros", "https://apis.rfi.fr/products/get_product/fov-rfi-es-get-journaux-podcast-v2?token_application=975d23b8-7a07-11e8-9f62-005056a90194"),
            rss("es", "mx", "Heraldo de México – Noticias", "https://feeds.acast.com/public/shows/6168800db567570014734b8c"),

            rss("ca", "es-ct", "3CatInfo – El radar", "https://dinamics.3cat.cat/public/podcast/catradio/xml/5/6/podprograma2165.xml"),
            rss("ca", "es-ct", "Catalunya Ràdio – Catalunya migdia", "https://dinamics.3cat.cat/public/podcast/catradio/xml/9/3/podprograma1739.xml"),
            rss("ca", "es-ct", "Catalunya Ràdio – Catalunya nit", "https://dinamics.3cat.cat/public/podcast/catradio/xml/2/8/podprograma1682.xml"),
            rss("ca", "es-ct", "À Punt – Les notícies del matí", "https://www.apuntmedia.es/podcast-mp3/les-noticies-del-mati-11200.xml"),
            rss("ca", "es-ct", "À Punt – Les notícies del migdia", "https://www.apuntmedia.es/podcast-mp3/les-noticies-del-migdia-11300.xml"),
            rss("ca", "es-ct", "RAC1 – El davantal", "https://api.audioteca.rac1.cat/rss/el-mon/el-davantal"),

            rai("it", "it", "Rai GR1", "https://www.raiplaysound.it/programmi/gr1.json"),
            rai("it", "it", "Rai GR2", "https://www.raiplaysound.it/programmi/gr2.json"),
            rai("it", "it", "Rai GR3", "https://www.raiplaysound.it/programmi/gr3.json"),
            rss("it", "ch", "RSI – Radiogiornale", "https://www.rsi.ch/rete-uno/programmi/informazione/radiogiornale/?f=podcast-xml"),
            rss("it", "it", "Il Sole 24 Ore – Effetto giorno", "https://backr24.ilsole24ore.com/podcast/effettogiorno.xml"),
            rss("it", "it", "Radio Radicale – Notiziario del mattino", "https://www.radioradicale.it/rubriche/64/notiziario-del-mattino/podcast"),

            rss("en", "gb", "BBC – Global News Podcast", "https://podcasts.files.bbci.co.uk/p02nq0gn.rss"),
            rss("en", "gb", "BBC – Newshour", "https://podcasts.files.bbci.co.uk/p002vsnk.rss"),
            rss("en", "gb", "BBC – Six O'Clock News", "https://podcasts.files.bbci.co.uk/b006qjxt.rss"),
            rss("en", "gb", "BBC – The World Tonight", "https://podcasts.files.bbci.co.uk/b006qtl3.rss"),
            rss("en", "us", "NPR News Now", "https://feeds.npr.org/500005/podcast.xml"),
            rss("en", "us", "NPR – Up First", "https://feeds.npr.org/510318/podcast.xml"),
            rss("en", "gb", "Times Radio – News Briefing", "https://feeds.acast.com/public/shows/c5cdab6e-7ef4-4562-b081-71f44a5b9533"),
            rss("en", "us", "AP Newswatch", "https://audioboom.com/channels/5023446.rss"),
            rss("en", "au", "ABC Australia – Top Stories", "https://www.abc.net.au/feeds/101858056/podcast.xml"),
            rss("en", "gb", "The Guardian – Today in Focus", "https://www.theguardian.com/news/series/todayinfocus/podcast.xml"),
            rss("en", "us", "Bloomberg News Now", "https://www.omnycontent.com/d/playlist/e73c998e-6e60-432f-8610-ae210140c5b1/d9566f78-0464-4367-9dcc-b05700aeec6f/7f880b3c-7f67-4b4b-b520-b05700af9172/podcast.rss"),

            rss("pt", "pt", "RTP Antena 1 – Noticiário", "https://www.omnycontent.com/d/playlist/f175b1e3-ecc7-4dc5-8307-b3aa008ffd8a/d1fe3b63-b8c1-4962-abdc-b40300f6c3e4/5dceb6f0-be7b-409b-b512-b40300f6c3ec/podcast.rss")
    );

    static List<Source> forLanguage(String code) {
        List<Source> out = new ArrayList<>();
        for (Source s : SOURCES) if (s.group.equals(code)) out.add(s);
        return out;
    }

    static String languageName(String code) {
        for (String[] l : LANGUAGES) if (l[0].equals(code)) return l[1];
        return code;
    }

    /** The flag code of a directory source, or null if the directory doesn't list it. */
    static String countryOf(Source s) {
        return COUNTRIES.get(s.id());
    }

    private static Source rss(String lang, String country, String name, String url) {
        return add(new Source(Source.RSS, name, lang, url), country);
    }

    private static Source rai(String lang, String country, String name, String url) {
        return add(new Source(Source.RAI, name, lang, url), country);
    }

    private static Source add(Source s, String country) {
        COUNTRIES.put(s.id(), country);
        return s;
    }

    private Directory() {}
}
