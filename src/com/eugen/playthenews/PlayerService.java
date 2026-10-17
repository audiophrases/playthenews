package com.eugen.playthenews;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Plays a queue of news sources with our own MediaPlayer and publishes a MediaSession, so the
 * player screen, the notification, the lock screen and headset buttons all control the same thing.
 *
 * The Google app is only used as a catalog: its NewsMediaBrowserService (reachable thanks to the
 * caller allow-list overlay) lists today's episodes, which are plain podcast URLs we play ourselves.
 */
public class PlayerService extends Service {

    private static final String TAG = "PlayTheNews";

    static final String ACTION_TOGGLE = "com.eugen.playthenews.TOGGLE";
    static final String ACTION_NEXT = "com.eugen.playthenews.NEXT";
    static final String ACTION_PREV = "com.eugen.playthenews.PREV";
    static final String ACTION_STOP = "com.eugen.playthenews.STOP";

    static final String MY_LIST = "My list";

    /** Flag code ({@link Countries}) in queue item extras and in the metadata. */
    static final String EXTRA_COUNTRY = "com.eugen.playthenews.COUNTRY";

    private static final String GSA_PKG = "com.google.android.googlequicksearchbox";
    private static final String NEWS_SERVICE =
            "com.google.android.libraries.assistant.auto.tng.media.mediabrowser.NewsMediaBrowserService";

    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 1;
    private static final long RESTART_THRESHOLD_MS = 3000;
    private static final long SEEK_BACK_MS = 15000;
    private static final long SEEK_FORWARD_MS = 30000;
    private static final long EPISODE_CACHE_MS = 10 * 60 * 1000;
    private static final long CATALOG_MAX_AGE_MS = 20 * 60 * 1000;
    private static final long CATALOG_WAIT_S = 15;
    private static final int ART_SIZE_PX = 512;
    private static final int LATEST_THREADS = 8;
    /** Newest-first waits at most this long for every source; slower ones are sorted last. */
    private static final long LATEST_WAIT_MS = 12000;

    private static final long ACTIONS = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
            | PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM | PlaybackState.ACTION_SEEK_TO
            | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;

    /** Notified on the main thread when the Google catalog (categories) changes. */
    interface Listener {
        void onCatalogChanged();
    }

    /** Gets each source's latest episode, by source id, on the main thread. */
    interface LatestCallback {
        void onLatest(Map<String, Resolver.Episode> latest);
    }

    final class LocalBinder extends Binder {
        PlayerService get() { return PlayerService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Resolves many sources at once, for sorting by date. */
    private final ExecutorService fetchers = Executors.newFixedThreadPool(LATEST_THREADS);
    private final List<Listener> listeners = new ArrayList<>();

    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;

    private MediaBrowser browser;
    private volatile Catalog catalog;
    private volatile CountDownLatch catalogReady = new CountDownLatch(1);
    private boolean catalogLoading;
    private boolean pendingMyList;

    private final Map<String, Resolver.Episode> episodeCache = new HashMap<>();
    private final Map<String, Long> episodeCacheTime = new HashMap<>();

    private List<Source> queue = new ArrayList<>();
    private String queueTitle = MY_LIST;
    private int index = -1;
    private int state = PlaybackState.STATE_NONE;
    private CharSequence errorMessage;

    private MediaPlayer player;
    private boolean prepared;
    private boolean playWhenReady;
    private Resolver.Episode episode;
    private Bitmap art;
    /** Bumped on every item change so stale async results are dropped. */
    private int playToken;
    private int consecutiveFailures;

    private boolean started;
    private boolean foreground;
    private boolean noisyRegistered;
    private boolean resumeOnFocusGain;

    // ---- lifecycle ------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);

        session = new MediaSession(this, "PlayTheNews");
        session.setCallback(sessionCallback, handler);
        session.setSessionActivity(PendingIntent.getActivity(this, 0,
                new Intent(this, PlayNewsActivity.class), immutable(PendingIntent.FLAG_UPDATE_CURRENT)));
        publishState();
        session.setActive(true);

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Playback",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        loadCatalog();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_TOGGLE.equals(action)) {
            if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) pause();
            else play();
        } else if (ACTION_NEXT.equals(action)) {
            skip(1);
        } else if (ACTION_PREV.equals(action)) {
            previous();
        } else if (ACTION_STOP.equals(action)) {
            stop();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        abandonFocus();
        setNoisyReceiver(false);
        session.release();
        if (browser != null && browser.isConnected()) browser.disconnect();
        worker.shutdownNow();
        fetchers.shutdownNow();
        super.onDestroy();
    }

    // ---- public API for the activities (same process) -------------------------------------

    MediaSession.Token getSessionToken() {
        return session.getSessionToken();
    }

    Catalog getCatalog() {
        return catalog;
    }

    void addListener(Listener l) { listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    /** True while something is loaded (playing, paused or loading). */
    boolean isActive() {
        return index >= 0 && state != PlaybackState.STATE_STOPPED && state != PlaybackState.STATE_NONE;
    }

    void playMyList(int start) {
        if (!Lineup.exists(this)) {
            // First run: start from the same ten sources as Google's own briefing.
            if (catalog == null) {
                pendingMyList = true;
                setState(PlaybackState.STATE_CONNECTING);
                return;
            }
            Lineup.save(this, catalog.defaultLineup());
        }
        final List<Source> list = Lineup.load(this);
        if (list.isEmpty()) {
            toast("Your list is empty. Tap Edit to add sources.");
            return;
        }
        if (!Lineup.newestFirst(this)) {
            playQueue(list, MY_LIST, start);
            return;
        }
        // The order depends on every source's latest bulletin, so check them all first.
        final int token = ++playToken;
        releasePlayer();
        index = -1;
        episode = null;
        art = null;
        publishMetadata();
        setState(PlaybackState.STATE_CONNECTING);
        fetchLatest(list, latest -> {
            if (token == playToken) playQueue(Lineup.byNewest(list, latest), MY_LIST, start);
        });
    }

    /**
     * Resolves the latest episode of every source in parallel (cached ones are instant) and calls
     * back once on the main thread, when all are done or after {@link #LATEST_WAIT_MS}. Sources
     * that failed or were too slow are missing from the result.
     */
    void fetchLatest(List<Source> sources, final LatestCallback cb) {
        final Map<String, Resolver.Episode> found = new HashMap<>();
        final int[] pending = {sources.size()};
        final Runnable deliver = new Runnable() {
            private boolean delivered;

            @Override public void run() {
                if (delivered) return;
                delivered = true;
                handler.removeCallbacks(this);
                cb.onLatest(new HashMap<>(found));
            }
        };
        if (sources.isEmpty()) {
            handler.post(deliver);
            return;
        }
        handler.postDelayed(deliver, LATEST_WAIT_MS);
        for (final Source src : sources) {
            fetchers.execute(() -> {
                Resolver.Episode ep = null;
                try {
                    ep = resolveCached(src);
                } catch (Exception e) {
                    Log.i(TAG, "could not check " + src.name + ": " + e);
                }
                final Resolver.Episode result = ep;
                handler.post(() -> {
                    if (result != null) found.put(src.id(), result);
                    if (--pending[0] == 0) deliver.run();
                });
            });
        }
    }

    void playCategory(Catalog.Category category) {
        Catalog c = catalog;
        if (c == null) return;
        List<Source> list = new ArrayList<>();
        for (Catalog.Item i : c.inCategory(category.id)) list.add(i.toSource());
        playQueue(list, category.name, 0);
    }

    /**
     * Called after My list or its play order was edited: keeps the current item playing within
     * the new order. Newest first sorts by the episodes resolved so far.
     */
    void onLineupEdited() {
        if (!MY_LIST.equals(queueTitle) || index < 0) return;
        String currentId = queue.get(index).id();
        List<Source> list = Lineup.load(this);
        if (Lineup.newestFirst(this)) list = Lineup.byNewest(list, cachedEpisodes());
        int newIndex = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(currentId)) { newIndex = i; break; }
        }
        if (newIndex < 0) {
            // The playing source was removed: keep playing it, continue with what took its place.
            list.add(Math.min(index, list.size()), queue.get(index));
            newIndex = Math.min(index, list.size() - 1);
        }
        queue = list;
        index = newIndex;
        publishQueue();
        publishState();
    }

    // ---- queue & transport ----------------------------------------------------------------

    private void playQueue(List<Source> list, String title, int start) {
        refreshCatalogIfStale();
        queue = new ArrayList<>(list);
        queueTitle = title;
        consecutiveFailures = 0;
        publishQueue();
        playIndex(Math.max(0, Math.min(start, queue.size() - 1)));
    }

    private void playIndex(int i) {
        if (i < 0 || i >= queue.size()) {
            finishQueue();
            return;
        }
        index = i;
        final int token = ++playToken;
        final Source src = queue.get(i);
        releasePlayer();
        episode = null;
        art = null;
        playWhenReady = true;
        errorMessage = null;
        publishMetadata();
        setState(PlaybackState.STATE_BUFFERING);
        if (!requestFocus()) Log.w(TAG, "audio focus not granted");
        goForeground();

        worker.execute(() -> {
            try {
                final Resolver.Episode ep = resolveCached(src);
                handler.post(() -> { if (token == playToken) startEpisode(src, ep, token); });
            } catch (Exception e) {
                Log.w(TAG, "could not resolve " + src.name + ": " + e);
                handler.post(() -> { if (token == playToken) onItemFailed(src, e); });
            }
        });
    }

    private void startEpisode(final Source src, Resolver.Episode ep, final int token) {
        episode = ep;
        publishMetadata();
        loadArt(ep.artUrl, token);
        Log.i(TAG, "playing " + src.name + " -> " + ep.audioUrl);

        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        player.setOnPreparedListener(mp -> {
            if (token != playToken) return;
            prepared = true;
            consecutiveFailures = 0;
            publishMetadata();
            if (playWhenReady) {
                mp.start();
                setState(PlaybackState.STATE_PLAYING);
            } else {
                setState(PlaybackState.STATE_PAUSED);
            }
            prefetch(index + 1);
        });
        player.setOnCompletionListener(mp -> { if (token == playToken) skip(1); });
        player.setOnErrorListener((mp, what, extra) -> {
            if (token == playToken) {
                onItemFailed(src, new Exception("player error " + what + "/" + extra));
            }
            return true;
        });
        player.setOnSeekCompleteListener(mp -> { if (token == playToken) publishState(); });
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", Resolver.USER_AGENT);
            player.setDataSource(this, Uri.parse(ep.audioUrl), headers);
            player.prepareAsync();
        } catch (Exception e) {
            onItemFailed(src, e);
        }
    }

    private void onItemFailed(Source src, Exception e) {
        Log.w(TAG, "skipping " + src.name + ": " + e);
        episodeCache.remove(src.id());
        releasePlayer();
        if (++consecutiveFailures >= Math.max(3, queue.size())) {
            errorMessage = "Nothing could be played. Check the network connection.";
            index = -1;
            setState(PlaybackState.STATE_ERROR);
            stopForegroundCompat(false);
            return;
        }
        toast("Couldn't play " + src.name + ", skipping");
        handler.postDelayed(() -> skip(1), 300);
    }

    void play() {
        if (index < 0) {
            playMyList(0);
        } else if (prepared && player != null) {
            playWhenReady = true;
            requestFocus();
            player.start();
            setState(PlaybackState.STATE_PLAYING);
            goForeground();
        } else if (player == null && episode == null && state != PlaybackState.STATE_BUFFERING) {
            playIndex(index);
        } else {
            playWhenReady = true;
            setState(PlaybackState.STATE_BUFFERING);
        }
    }

    void pause() {
        playWhenReady = false;
        if (prepared && player != null && player.isPlaying()) player.pause();
        if (index >= 0) setState(PlaybackState.STATE_PAUSED);
        stopForegroundCompat(false);
    }

    void stop() {
        playToken++;
        releasePlayer();
        index = -1;
        episode = null;
        art = null;
        abandonFocus();
        setState(PlaybackState.STATE_STOPPED);
        publishMetadata();
        stopForegroundCompat(true);
        // A paused notification is detached from the service, so remove it explicitly.
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        stopSelfIfStarted();
    }

    void skip(int delta) {
        int next = index + delta;
        if (next >= queue.size()) {
            finishQueue();
        } else {
            playIndex(Math.max(0, next));
        }
    }

    void previous() {
        if (position() > RESTART_THRESHOLD_MS || index <= 0) {
            seekTo(0);
        } else {
            playIndex(index - 1);
        }
    }

    void seekTo(long ms) {
        if (!prepared || player == null) return;
        int dur = player.getDuration();
        long target = Math.max(0, dur > 0 ? Math.min(ms, dur) : ms);
        player.seekTo((int) target);
        publishState();
    }

    private void finishQueue() {
        Log.i(TAG, "end of " + queueTitle);
        stop();
    }

    // ---- session --------------------------------------------------------------------------

    private final MediaSession.Callback sessionCallback = new MediaSession.Callback() {
        @Override public void onPlay() { play(); }
        @Override public void onPause() { pause(); }
        @Override public void onStop() { stop(); }
        @Override public void onSkipToNext() { skip(1); }
        @Override public void onSkipToPrevious() { previous(); }
        @Override public void onSeekTo(long pos) { seekTo(pos); }
        @Override public void onFastForward() { seekTo(position() + SEEK_FORWARD_MS); }
        @Override public void onRewind() { seekTo(position() - SEEK_BACK_MS); }
        @Override public void onSkipToQueueItem(long id) {
            if (id >= 0 && id < queue.size()) playIndex((int) id);
        }
    };

    private long position() {
        return prepared && player != null ? player.getCurrentPosition() : 0;
    }

    private void setState(int newState) {
        state = newState;
        publishState();
    }

    private void publishState() {
        PlaybackState.Builder b = new PlaybackState.Builder()
                .setActions(ACTIONS)
                .setActiveQueueItemId(index >= 0 ? index : MediaSession.QueueItem.UNKNOWN_ID)
                .setState(state, position(), state == PlaybackState.STATE_PLAYING ? 1f : 0f);
        if (state == PlaybackState.STATE_ERROR && errorMessage != null) b.setErrorMessage(errorMessage);
        session.setPlaybackState(b.build());
        updateNotification();
        setNoisyReceiver(state == PlaybackState.STATE_PLAYING);
    }

    private void publishMetadata() {
        if (index < 0 || index >= queue.size()) {
            session.setMetadata(null);
            return;
        }
        Source src = queue.get(index);
        long duration = prepared && player != null && player.getDuration() > 0
                ? player.getDuration() : (episode != null ? episode.durationMs : 0);
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, src.name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, episode != null ? episode.title : src.label())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, queueTitle)
                .putString(EXTRA_COUNTRY, Countries.of(src, catalog))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
        if (episode != null && episode.artUrl != null) {
            b.putString(MediaMetadata.METADATA_KEY_ART_URI, episode.artUrl);
        }
        if (art != null) b.putBitmap(MediaMetadata.METADATA_KEY_ART, art);
        session.setMetadata(b.build());
    }

    private void publishQueue() {
        List<MediaSession.QueueItem> items = new ArrayList<>();
        for (int i = 0; i < queue.size(); i++) {
            Source s = queue.get(i);
            Bundle extras = new Bundle();
            extras.putString(EXTRA_COUNTRY, Countries.of(s, catalog));
            items.add(new MediaSession.QueueItem(new MediaDescription.Builder()
                    .setMediaId(s.id()).setTitle(s.name).setSubtitle(s.label())
                    .setExtras(extras).build(), i));
        }
        session.setQueue(items);
        session.setQueueTitle(queueTitle);
    }

    // ---- resolving ------------------------------------------------------------------------

    /** Worker thread. */
    private Resolver.Episode resolveCached(Source src) throws Exception {
        synchronized (episodeCache) {
            Long t = episodeCacheTime.get(src.id());
            Resolver.Episode cached = episodeCache.get(src.id());
            if (cached != null && t != null && System.currentTimeMillis() - t < EPISODE_CACHE_MS) {
                return cached;
            }
        }
        if (Source.GOOGLE.equals(src.kind)) catalogReady.await(CATALOG_WAIT_S, TimeUnit.SECONDS);
        Resolver.Episode ep = Resolver.resolve(src, catalog);
        synchronized (episodeCache) {
            episodeCache.put(src.id(), ep);
            episodeCacheTime.put(src.id(), System.currentTimeMillis());
        }
        return ep;
    }

    private Map<String, Resolver.Episode> cachedEpisodes() {
        synchronized (episodeCache) {
            return new HashMap<>(episodeCache);
        }
    }

    private void prefetch(int i) {
        if (i < 0 || i >= queue.size()) return;
        final Source src = queue.get(i);
        worker.execute(() -> {
            try {
                resolveCached(src);
            } catch (Exception e) {
                Log.i(TAG, "prefetch of " + src.name + " failed: " + e);
            }
        });
    }

    private void loadArt(final String url, final int token) {
        if (url == null) return;
        worker.execute(() -> {
            try {
                byte[] bytes = Resolver.fetch(url);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= ART_SIZE_PX) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                final Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                handler.post(() -> {
                    if (token != playToken || bmp == null) return;
                    art = bmp;
                    publishMetadata();
                    updateNotification();
                });
            } catch (Exception e) {
                Log.i(TAG, "no art from " + url + ": " + e);
            }
        });
    }

    // ---- Google catalog -------------------------------------------------------------------

    private void loadCatalog() {
        if (catalogLoading) return;
        catalogLoading = true;
        if (browser != null && browser.isConnected()) {
            Catalog.load(browser, this::onCatalogLoaded);
            return;
        }
        browser = new MediaBrowser(this, new ComponentName(GSA_PKG, NEWS_SERVICE),
                new MediaBrowser.ConnectionCallback() {
                    @Override public void onConnected() {
                        Catalog.load(browser, PlayerService.this::onCatalogLoaded);
                    }
                    @Override public void onConnectionFailed() {
                        Log.w(TAG, "Google app refused the connection; Google sources unavailable");
                        onCatalogLoaded(null);
                    }
                }, null);
        browser.connect();
    }

    private void onCatalogLoaded(Catalog c) {
        catalogLoading = false;
        if (c != null) {
            catalog = c;
            logCountries(c);
            // Google sources not known by name get their flag from the catalog's episode URLs.
            if (!queue.isEmpty()) publishQueue();
        }
        catalogReady.countDown();
        for (Listener l : new ArrayList<>(listeners)) l.onCatalogChanged();
        if (pendingMyList) {
            pendingMyList = false;
            if (catalog == null) Lineup.save(this, Collections.<Source>emptyList());
            playMyList(0);
        }
    }

    /** One line listing each Google source's flag ("?" if unknown), to extend {@link Countries}. */
    private static void logCountries(Catalog c) {
        StringBuilder sb = new StringBuilder("flags:");
        Set<String> seen = new HashSet<>();
        for (Catalog.Item i : c.items) {
            if (!seen.add(i.title)) continue;
            String code = Countries.of(i.toSource(), c);
            sb.append(' ').append(i.title).append('=').append(code != null ? code : "?").append(';');
        }
        Log.i(TAG, sb.toString());
    }

    /** Google's episode URLs change through the day; refresh before starting a new queue. */
    private void refreshCatalogIfStale() {
        Catalog c = catalog;
        if (c != null && System.currentTimeMillis() - c.loadedAt > CATALOG_MAX_AGE_MS && !catalogLoading) {
            catalogReady = new CountDownLatch(1);
            loadCatalog();
        }
    }

    // ---- player plumbing ------------------------------------------------------------------

    private void releasePlayer() {
        prepared = false;
        if (player != null) {
            player.setOnCompletionListener(null);
            player.setOnErrorListener(null);
            player.release();
            player = null;
        }
    }

    private boolean requestFocus() {
        int result;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setOnAudioFocusChangeListener(focusListener, handler)
                        .build();
            }
            result = audio.requestAudioFocus(focusRequest);
        } else {
            result = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        } else {
            audio.abandonAudioFocus(focusListener);
        }
    }

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocusGain = false;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Speech is hard to follow ducked, so pause for both kinds of transient loss.
                resumeOnFocusGain = state == PlaybackState.STATE_PLAYING;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (resumeOnFocusGain) play();
                resumeOnFocusGain = false;
                break;
        }
    };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { pause(); }
    };

    private void setNoisyReceiver(boolean on) {
        if (on == noisyRegistered) return;
        noisyRegistered = on;
        if (on) {
            registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        } else {
            unregisterReceiver(noisyReceiver);
        }
    }

    // ---- notification / foreground --------------------------------------------------------

    private void goForeground() {
        if (!started) {
            // Keep running after the activity unbinds.
            started = true;
            startService(new Intent(this, PlayerService.class));
        }
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        foreground = true;
    }

    private void stopForegroundCompat(boolean remove) {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(remove ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(remove);
        }
        foreground = false;
        if (!remove) updateNotification();
    }

    private void stopSelfIfStarted() {
        if (started) {
            started = false;
            stopSelf();
        }
    }

    private void updateNotification() {
        if (index < 0) return;
        if (!foreground && state != PlaybackState.STATE_PAUSED && state != PlaybackState.STATE_ERROR) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification());
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Source src = index >= 0 && index < queue.size() ? queue.get(index) : null;
        boolean playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
        b.setSmallIcon(R.drawable.ic_play)
                .setContentTitle(src != null ? src.name : getString(R.string.app_name))
                .setContentText(episode != null ? episode.title : queueTitle)
                .setSubText(queueTitle + (index >= 0 ? " · " + (index + 1) + "/" + queue.size() : ""))
                .setLargeIcon(art)
                .setContentIntent(session.getController().getSessionActivity())
                .setDeleteIntent(serviceIntent(ACTION_STOP, 4))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(playing)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(R.drawable.ic_prev, getString(R.string.previous),
                        serviceIntent(ACTION_PREV, 1)).build())
                .addAction(new Notification.Action.Builder(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                        getString(R.string.play_pause), serviceIntent(ACTION_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(R.drawable.ic_next, getString(R.string.next),
                        serviceIntent(ACTION_NEXT, 3)).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private PendingIntent serviceIntent(String action, int requestCode) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, i, immutable(PendingIntent.FLAG_UPDATE_CURRENT));
    }

    private static int immutable(int flags) {
        return Build.VERSION.SDK_INT >= 23 ? flags | PendingIntent.FLAG_IMMUTABLE : flags;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
