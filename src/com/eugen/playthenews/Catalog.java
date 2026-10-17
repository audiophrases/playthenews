package com.eugen.playthenews;

import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything the Google app's NewsMediaBrowserService offers right now: its categories (General,
 * World, Sports...) and each category's sources with today's episode. Media ids look like
 * {@code NEWS||<category>||<episode mp3 url>}, so the episode can be played directly.
 */
final class Catalog {

    private static final String TAG = "PlayTheNews";
    /** Google's own briefing plays the first ten General items. */
    private static final int DEFAULT_SIZE = 10;
    private static final String GENERAL_ID = "0";

    static final class Category {
        final String id;
        final String name;

        Category(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    static final class Item {
        final String categoryId;
        final String categoryName;
        final String title;
        final String subtitle;
        final String mediaId;
        final String iconUri;

        Item(String categoryId, String categoryName, MediaDescription d) {
            this.categoryId = categoryId;
            this.categoryName = categoryName;
            this.title = d.getTitle().toString();
            this.subtitle = d.getSubtitle() != null ? d.getSubtitle().toString() : null;
            this.mediaId = d.getMediaId();
            this.iconUri = d.getIconUri() != null ? d.getIconUri().toString() : null;
        }

        String audioUrl() {
            String[] parts = mediaId.split("\\|\\|", 3);
            return parts.length == 3 ? parts[2] : mediaId;
        }

        Source toSource() {
            return new Source(Source.GOOGLE, title, categoryName, categoryId);
        }
    }

    interface Callback {
        void onLoaded(Catalog catalog);
    }

    final List<Category> categories;
    final List<Item> items;
    final long loadedAt = System.currentTimeMillis();

    private Catalog(List<Category> categories, List<Item> items) {
        this.categories = categories;
        this.items = items;
    }

    /** Prefers the item in the given category, then the same source listed anywhere else. */
    Item find(String categoryId, String title) {
        Item fallback = null;
        for (Item i : items) {
            if (!i.title.equals(title)) continue;
            if (i.categoryId.equals(categoryId)) return i;
            if (fallback == null) fallback = i;
        }
        return fallback;
    }

    List<Item> inCategory(String categoryId) {
        List<Item> out = new ArrayList<>();
        for (Item i : items) if (i.categoryId.equals(categoryId)) out.add(i);
        return out;
    }

    /** The same ten sources "Hey Google, play the news" plays. */
    List<Source> defaultLineup() {
        List<Item> general = inCategory(GENERAL_ID);
        if (general.isEmpty() && !categories.isEmpty()) general = inCategory(categories.get(0).id);
        List<Source> out = new ArrayList<>();
        for (Item i : general.subList(0, Math.min(DEFAULT_SIZE, general.size()))) out.add(i.toSource());
        return out;
    }

    /** Browses the root and every category. Calls back on the browser's thread, exactly once. */
    static void load(final MediaBrowser browser, final Callback cb) {
        browser.subscribe(browser.getRoot(), new MediaBrowser.SubscriptionCallback() {
            @Override
            public void onChildrenLoaded(String pid, List<MediaBrowser.MediaItem> children) {
                browser.unsubscribe(pid);
                final List<Category> cats = new ArrayList<>();
                for (MediaBrowser.MediaItem c : children) {
                    CharSequence t = c.getDescription().getTitle();
                    if (c.isBrowsable() && t != null) cats.add(new Category(c.getMediaId(), t.toString()));
                }
                if (cats.isEmpty()) {
                    cb.onLoaded(new Catalog(cats, Collections.<Item>emptyList()));
                    return;
                }
                final List<List<Item>> perCategory = new ArrayList<>();
                for (int i = 0; i < cats.size(); i++) perCategory.add(null);
                final int[] pending = {cats.size()};

                for (int i = 0; i < cats.size(); i++) {
                    final int slot = i;
                    final Category cat = cats.get(i);
                    browser.subscribe(cat.id, new MediaBrowser.SubscriptionCallback() {
                        @Override
                        public void onChildrenLoaded(String id, List<MediaBrowser.MediaItem> list) {
                            browser.unsubscribe(id);
                            List<Item> items = new ArrayList<>();
                            for (MediaBrowser.MediaItem m : list) {
                                if (m.isPlayable() && m.getDescription().getTitle() != null) {
                                    items.add(new Item(cat.id, cat.name, m.getDescription()));
                                }
                            }
                            done(items);
                        }

                        @Override
                        public void onError(String id) {
                            Log.w(TAG, "could not load category " + cat.name);
                            done(Collections.<Item>emptyList());
                        }

                        private void done(List<Item> items) {
                            if (perCategory.get(slot) != null) return;
                            perCategory.set(slot, items);
                            if (--pending[0] > 0) return;
                            List<Item> all = new ArrayList<>();
                            for (List<Item> l : perCategory) all.addAll(l);
                            Log.i(TAG, "catalog: " + cats.size() + " categories, " + all.size() + " items");
                            cb.onLoaded(new Catalog(cats, all));
                        }
                    });
                }
            }

            @Override
            public void onError(String pid) {
                Log.w(TAG, "could not load news categories");
                cb.onLoaded(new Catalog(Collections.<Category>emptyList(), Collections.<Item>emptyList()));
            }
        });
    }
}
