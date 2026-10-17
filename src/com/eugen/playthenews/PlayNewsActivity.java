package com.eugen.playthenews;

import android.app.Activity;
import android.content.ComponentName;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.List;

/**
 * Touch launcher for the Google app's news briefing.
 *
 * The Assistant's NewsPlayerActivity is exported=false and finishes immediately unless a
 * NewsPlayerSession already exists, so it cannot be started directly. Instead we go through
 * NewsMediaBrowserService -- the exported MediaBrowserService the Google app publishes for
 * Android Auto. Browsing it and playing an item creates a real news session, which is the same
 * thing "Hey Google, play the news" produces. This needs no root and no assistant role.
 */
public class PlayNewsActivity extends Activity {

    private static final String TAG = "PlayTheNews";

    private static final String GSA_PKG = "com.google.android.googlequicksearchbox";
    private static final String NEWS_SERVICE =
            "com.google.android.libraries.assistant.auto.tng.media.mediabrowser.NewsMediaBrowserService";

    /** How deep to walk browsable nodes looking for something playable. */
    private static final int MAX_DEPTH = 3;
    private static final long GIVE_UP_MS = 20000;

    /** Category to prefer at the top level, to mirror "play the news" (a general briefing). */
    private static final String PREFERRED_CATEGORY = "General";

    private MediaBrowser browser;
    private MediaController controller;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean done = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        browser = new MediaBrowser(this,
                new ComponentName(GSA_PKG, NEWS_SERVICE),
                connectionCallback,
                null);

        Log.i(TAG, "connecting to " + NEWS_SERVICE);
        browser.connect();

        handler.postDelayed(giveUp, GIVE_UP_MS);
    }

    private final Runnable giveUp = new Runnable() {
        @Override public void run() {
            fail("Timed out waiting for the news to start");
        }
    };

    private final MediaBrowser.ConnectionCallback connectionCallback = new MediaBrowser.ConnectionCallback() {
        @Override
        public void onConnected() {
            String root = browser.getRoot();
            Log.i(TAG, "connected, root=" + root);
            try {
                controller = new MediaController(PlayNewsActivity.this, browser.getSessionToken());
                controller.registerCallback(controllerCallback);
            } catch (Exception e) {
                Log.e(TAG, "could not build MediaController", e);
            }
            subscribe(root, 0);
        }

        @Override
        public void onConnectionFailed() {
            // onGetRoot() rejected us -- the service is allow-listing its callers.
            fail("The Google app refused the connection");
        }

        @Override
        public void onConnectionSuspended() {
            Log.w(TAG, "connection suspended");
        }
    };

    /**
     * Descends a single deterministic path: root -> preferred category -> first playable item.
     * Playing the first item of a category starts that briefing; the session queues the rest of
     * the category and auto-advances through the news sources.
     */
    private void subscribe(final String parentId, final int depth) {
        if (done) return;
        Log.i(TAG, "subscribe depth=" + depth + " id=" + parentId);

        browser.subscribe(parentId, new MediaBrowser.SubscriptionCallback() {
            @Override
            public void onChildrenLoaded(String pid, List<MediaBrowser.MediaItem> children) {
                if (done) return;
                Log.i(TAG, "loaded " + children.size() + " children of " + pid);

                // Prefer a directly playable child.
                for (MediaBrowser.MediaItem item : children) {
                    if (item.isPlayable()) {
                        Log.i(TAG, "  playable: " + item.getMediaId());
                        play(item);
                        return;
                    }
                }

                if (depth >= MAX_DEPTH) {
                    if (depth == 0) fallback();
                    return;
                }

                // No playable child here: pick exactly one browsable node to descend into.
                MediaBrowser.MediaItem chosen = null;
                if (depth == 0) {
                    for (MediaBrowser.MediaItem item : children) {
                        CharSequence title = item.getDescription().getTitle();
                        if (item.isBrowsable() && title != null
                                && PREFERRED_CATEGORY.contentEquals(title)) {
                            chosen = item;
                            break;
                        }
                    }
                }
                if (chosen == null) {
                    for (MediaBrowser.MediaItem item : children) {
                        if (item.isBrowsable()) { chosen = item; break; }
                    }
                }

                if (chosen != null) {
                    Log.i(TAG, "  descending into " + chosen.getDescription().getTitle()
                            + " (" + chosen.getMediaId() + ")");
                    subscribe(chosen.getMediaId(), depth + 1);
                } else if (depth == 0) {
                    fallback();
                }
            }

            @Override
            public void onError(String pid) {
                Log.e(TAG, "subscribe error for " + pid);
                if (depth == 0) fallback();
            }
        });
    }

    private void play(MediaBrowser.MediaItem item) {
        if (controller == null) {
            fail("No media session available");
            return;
        }
        Log.i(TAG, "playFromMediaId " + item.getMediaId());
        controller.getTransportControls().playFromMediaId(item.getMediaId(), null);
        succeed();
    }

    /** Nothing playable surfaced -- ask the session directly. */
    private void fallback() {
        if (done || controller == null) {
            fail("Found nothing to play");
            return;
        }
        Log.i(TAG, "falling back to playFromSearch/play");
        controller.getTransportControls().playFromSearch("news", null);
        controller.getTransportControls().play();
        succeed();
    }

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state != null) Log.i(TAG, "playback state -> " + state.getState());
        }
    };

    private void succeed() {
        if (done) return;
        done = true;
        handler.removeCallbacks(giveUp);
        // Give the session a moment to spin up before we tear the browser down.
        handler.postDelayed(new Runnable() {
            @Override public void run() { finish(); }
        }, 2500);
    }

    private void fail(String message) {
        if (done) return;
        done = true;
        handler.removeCallbacks(giveUp);
        Log.e(TAG, "failed: " + message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (controller != null) controller.unregisterCallback(controllerCallback);
        if (browser != null && browser.isConnected()) browser.disconnect();
        super.onDestroy();
    }
}
