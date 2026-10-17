package com.eugen.playthenews;

import android.app.Activity;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Player screen for the Google app's news briefing.
 *
 * The Assistant's NewsPlayerActivity is exported=false and finishes immediately unless a
 * NewsPlayerSession already exists, so it cannot be started directly. Instead we go through
 * NewsMediaBrowserService -- the exported MediaBrowserService the Google app publishes for
 * Android Auto. Browsing it and playing an item creates a real news session, which is the same
 * thing "Hey Google, play the news" produces. The session token it hands us is then used to drive
 * the standard transport controls (play/pause, skip, seek, stop) and to show the queue.
 *
 * Opening the app auto-starts the General briefing unless a session is already playing or paused.
 */
public class PlayNewsActivity extends Activity {

    private static final String TAG = "PlayTheNews";

    private static final String GSA_PKG = "com.google.android.googlequicksearchbox";
    private static final String NEWS_SERVICE =
            "com.google.android.libraries.assistant.auto.tng.media.mediabrowser.NewsMediaBrowserService";

    /** Category auto-started on launch, to mirror "play the news" (a general briefing). */
    private static final String PREFERRED_CATEGORY = "General";
    private static final int MAX_DEPTH = 2;
    private static final long REWIND_MS = 15000;
    private static final long FORWARD_MS = 30000;
    private static final long TICK_MS = 500;

    private MediaBrowser browser;
    private MediaController controller;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<MediaBrowser.MediaItem> categories = new ArrayList<>();
    private List<MediaSession.QueueItem> queue = new ArrayList<>();
    private boolean autoStartChecked = false;
    private boolean userSeeking = false;
    private String artKey = null;
    private long shownActiveId = MediaSession.QueueItem.UNKNOWN_ID;

    private LinearLayout categoryRow;
    private ImageView art;
    private TextView title, subtitle, elapsed, duration, status;
    private SeekBar seek;
    private ImageButton playPause;
    private ListView queueView;
    private QueueAdapter queueAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        categoryRow = findViewById(R.id.categories);
        art = findViewById(R.id.art);
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        elapsed = findViewById(R.id.elapsed);
        duration = findViewById(R.id.duration);
        status = findViewById(R.id.status);
        seek = findViewById(R.id.seek);
        playPause = findViewById(R.id.play_pause);

        findViewById(R.id.prev).setOnClickListener(v -> { if (controls()) tc().skipToPrevious(); });
        findViewById(R.id.next).setOnClickListener(v -> { if (controls()) tc().skipToNext(); });
        findViewById(R.id.rewind).setOnClickListener(v -> seekBy(-REWIND_MS));
        findViewById(R.id.forward).setOnClickListener(v -> seekBy(FORWARD_MS));
        findViewById(R.id.stop).setOnClickListener(v -> { if (controls()) tc().stop(); });
        playPause.setOnClickListener(v -> togglePlayPause());

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) elapsed.setText(formatTime(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                if (controls()) tc().seekTo(s.getProgress());
            }
        });

        queueView = findViewById(R.id.queue);
        queueAdapter = new QueueAdapter();
        queueView.setAdapter(queueAdapter);
        queueView.setOnItemClickListener((AdapterView<?> p, View v, int pos, long id) -> {
            if (controls()) tc().skipToQueueItem(queue.get(pos).getQueueId());
        });

        setStatus("Connecting…");
        browser = new MediaBrowser(this, new ComponentName(GSA_PKG, NEWS_SERVICE),
                connectionCallback, null);
        browser.connect();
    }

    // ---- connection & browsing ------------------------------------------------------------

    private final MediaBrowser.ConnectionCallback connectionCallback = new MediaBrowser.ConnectionCallback() {
        @Override
        public void onConnected() {
            String root = browser.getRoot();
            Log.i(TAG, "connected, root=" + root);
            try {
                controller = new MediaController(PlayNewsActivity.this, browser.getSessionToken());
                controller.registerCallback(controllerCallback, handler);
            } catch (Exception e) {
                Log.e(TAG, "could not build MediaController", e);
                setStatus("No media session available");
                return;
            }
            refreshAll();
            loadCategories(root);
        }

        @Override
        public void onConnectionFailed() {
            // onGetRoot() rejected us -- the service is allow-listing its callers.
            setStatus("The Google app refused the connection");
            toast("The Google app refused the connection");
        }

        @Override
        public void onConnectionSuspended() {
            Log.w(TAG, "connection suspended");
            setStatus("Disconnected");
            controller = null;
        }
    };

    private void loadCategories(final String root) {
        browser.subscribe(root, new MediaBrowser.SubscriptionCallback() {
            @Override
            public void onChildrenLoaded(String pid, List<MediaBrowser.MediaItem> children) {
                browser.unsubscribe(pid);
                Log.i(TAG, "root has " + children.size() + " children");
                categories.clear();
                categories.addAll(children);
                buildCategoryButtons();
                maybeAutoStart();
            }

            @Override
            public void onError(String pid) {
                Log.e(TAG, "could not load categories");
                maybeAutoStart();
            }
        });
    }

    /** One tap = news: start the General briefing unless something is already loaded. */
    private void maybeAutoStart() {
        if (autoStartChecked) return;
        autoStartChecked = true;
        if (hasLiveSession()) {
            Log.i(TAG, "session already active, not auto-starting");
            return;
        }
        startPreferred();
    }

    private void startPreferred() {
        MediaBrowser.MediaItem chosen = null;
        for (MediaBrowser.MediaItem item : categories) {
            CharSequence t = item.getDescription().getTitle();
            if (t != null && PREFERRED_CATEGORY.contentEquals(t)) { chosen = item; break; }
        }
        if (chosen == null && !categories.isEmpty()) chosen = categories.get(0);
        if (chosen != null) {
            playCategory(chosen, 0);
        } else if (controls()) {
            Log.i(TAG, "no categories, falling back to playFromSearch");
            tc().playFromSearch("news", null);
        }
    }

    /** Plays a node: directly if playable, otherwise its first playable descendant. */
    private void playCategory(final MediaBrowser.MediaItem item, final int depth) {
        if (!controls()) return;
        if (item.isPlayable()) {
            Log.i(TAG, "playFromMediaId " + item.getMediaId());
            setStatus("Starting " + item.getDescription().getTitle() + "…");
            tc().playFromMediaId(item.getMediaId(), null);
            return;
        }
        setStatus("Loading " + item.getDescription().getTitle() + "…");
        browser.subscribe(item.getMediaId(), new MediaBrowser.SubscriptionCallback() {
            @Override
            public void onChildrenLoaded(String pid, List<MediaBrowser.MediaItem> children) {
                browser.unsubscribe(pid);
                for (MediaBrowser.MediaItem c : children) {
                    if (c.isPlayable()) { playCategory(c, depth + 1); return; }
                }
                if (depth < MAX_DEPTH) {
                    for (MediaBrowser.MediaItem c : children) {
                        if (c.isBrowsable()) { playCategory(c, depth + 1); return; }
                    }
                }
                setStatus("Found nothing to play");
            }

            @Override
            public void onError(String pid) {
                setStatus("Could not load " + item.getDescription().getTitle());
            }
        });
    }

    private void buildCategoryButtons() {
        categoryRow.removeAllViews();
        int padH = dp(14), gap = dp(8);
        for (final MediaBrowser.MediaItem item : categories) {
            if (!item.isBrowsable() && !item.isPlayable()) continue;
            Button b = new Button(this);
            b.setText(item.getDescription().getTitle());
            b.setAllCaps(false);
            b.setTextColor(Color.WHITE);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            b.setBackgroundResource(R.drawable.bg_chip);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setPadding(padH, dp(8), padH, dp(8));
            b.setStateListAnimator(null);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = gap;
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> playCategory(item, 0));
            categoryRow.addView(b);
        }
    }

    // ---- transport ------------------------------------------------------------------------

    private boolean controls() {
        if (controller == null) {
            toast("Not connected to the news player");
            return false;
        }
        return true;
    }

    private MediaController.TransportControls tc() {
        return controller.getTransportControls();
    }

    private void togglePlayPause() {
        if (!controls()) return;
        PlaybackState s = controller.getPlaybackState();
        int st = s == null ? PlaybackState.STATE_NONE : s.getState();
        if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
                || st == PlaybackState.STATE_CONNECTING) {
            tc().pause();
        } else if (hasLiveSession()) {
            tc().play();
        } else {
            startPreferred();
        }
    }

    private void seekBy(long deltaMs) {
        if (!controls()) return;
        long pos = currentPosition(controller.getPlaybackState());
        if (pos < 0) return;
        long target = Math.max(0, pos + deltaMs);
        long dur = currentDuration();
        if (dur > 0) target = Math.min(target, dur);
        tc().seekTo(target);
    }

    /** True when the session holds a loaded briefing (playing, paused, buffering...). */
    private boolean hasLiveSession() {
        if (controller == null) return false;
        PlaybackState s = controller.getPlaybackState();
        if (s == null) return false;
        switch (s.getState()) {
            case PlaybackState.STATE_NONE:
            case PlaybackState.STATE_STOPPED:
            case PlaybackState.STATE_ERROR:
                return false;
            default:
                return true;
        }
    }

    // ---- UI updates -----------------------------------------------------------------------

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) {
            Log.i(TAG, "playback state -> " + (state == null ? "null" : state.getState()));
            updateState(state);
        }
        @Override public void onMetadataChanged(MediaMetadata metadata) { updateMetadata(metadata); }
        @Override public void onQueueChanged(List<MediaSession.QueueItem> q) { updateQueue(q); }
        @Override public void onSessionDestroyed() {
            Log.w(TAG, "session destroyed");
            setStatus("Session ended");
            updateState(null);
        }
    };

    private void refreshAll() {
        updateMetadata(controller.getMetadata());
        updateQueue(controller.getQueue());
        updateState(controller.getPlaybackState());
    }

    private void updateState(PlaybackState s) {
        int st = s == null ? PlaybackState.STATE_NONE : s.getState();
        boolean playing = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
                || st == PlaybackState.STATE_CONNECTING;
        playPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);

        switch (st) {
            case PlaybackState.STATE_PLAYING: setStatus("Playing"); break;
            case PlaybackState.STATE_PAUSED: setStatus("Paused"); break;
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING: setStatus("Buffering…"); break;
            case PlaybackState.STATE_STOPPED: setStatus("Stopped"); break;
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM: setStatus("Skipping…"); break;
            case PlaybackState.STATE_ERROR:
                CharSequence msg = s.getErrorMessage();
                setStatus(msg != null ? "Error: " + msg : "Playback error");
                break;
            default: break; // keep "Connecting…" / "Starting…" text
        }
        queueAdapter.notifyDataSetChanged();
        scrollToActive(false);
        tick.run();
    }

    private void updateMetadata(MediaMetadata m) {
        if (m == null) return;
        MediaDescription d = m.getDescription();
        CharSequence t = firstNonEmpty(m.getText(MediaMetadata.METADATA_KEY_TITLE),
                m.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE), d.getTitle());
        CharSequence sub = firstNonEmpty(m.getText(MediaMetadata.METADATA_KEY_ARTIST),
                m.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                m.getText(MediaMetadata.METADATA_KEY_ALBUM), d.getSubtitle());
        title.setText(t != null ? t : getString(R.string.app_name));
        subtitle.setText(sub != null ? sub : "");

        long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        seek.setMax(dur > 0 ? (int) dur : 0);
        duration.setText(dur > 0 ? formatTime(dur) : "--:--");

        Bitmap bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (bmp == null) bmp = d.getIconBitmap();
        if (bmp != null) {
            artKey = null;
            art.setImageBitmap(bmp);
            return;
        }
        String uri = firstNonEmptyString(m.getString(MediaMetadata.METADATA_KEY_ART_URI),
                m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
                d.getIconUri() != null ? d.getIconUri().toString() : null);
        if (uri == null) {
            artKey = null;
            art.setImageResource(R.mipmap.ic_launcher);
        } else if (!uri.equals(artKey)) {
            artKey = uri;
            loadArt(uri);
        }
    }

    private void loadArt(final String uri) {
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                Uri u = Uri.parse(uri);
                String scheme = u.getScheme();
                try (InputStream in = ("http".equals(scheme) || "https".equals(scheme))
                        ? new URL(uri).openStream()
                        : getContentResolver().openInputStream(u)) {
                    bmp = BitmapFactory.decodeStream(in);
                }
            } catch (Exception e) {
                Log.w(TAG, "could not load art " + uri + ": " + e);
            }
            final Bitmap result = bmp;
            handler.post(() -> {
                if (!uri.equals(artKey) || isDestroyed()) return;
                if (result != null) art.setImageBitmap(result);
                else art.setImageResource(R.mipmap.ic_launcher);
            });
        }, "art-loader").start();
    }

    private void updateQueue(List<MediaSession.QueueItem> q) {
        queue = q != null ? q : new ArrayList<>();
        queueAdapter.notifyDataSetChanged();
        scrollToActive(true);
    }

    /** Keeps the playing item visible when the briefing advances. */
    private void scrollToActive(boolean force) {
        long id = activeQueueId();
        if (!force && id == shownActiveId) return;
        shownActiveId = id;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).getQueueId() == id) {
                final int pos = i;
                queueView.post(() -> queueView.setSelection(pos));
                return;
            }
        }
    }

    /** Advances the seek bar from the last reported position while playing. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            handler.removeCallbacks(this);
            if (controller == null) return;
            PlaybackState s = controller.getPlaybackState();
            long pos = currentPosition(s);
            if (pos >= 0 && !userSeeking) {
                long dur = currentDuration();
                if (dur > 0 && seek.getMax() != dur) seek.setMax((int) dur);
                seek.setProgress((int) (dur > 0 ? Math.min(pos, dur) : pos));
                elapsed.setText(formatTime(pos));
            }
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                handler.postDelayed(this, TICK_MS);
            }
        }
    };

    private long currentPosition(PlaybackState s) {
        if (s == null || s.getPosition() == PlaybackState.PLAYBACK_POSITION_UNKNOWN) return -1;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime())
                    * s.getPlaybackSpeed());
        }
        return Math.max(0, pos);
    }

    private long currentDuration() {
        MediaMetadata m = controller == null ? null : controller.getMetadata();
        return m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    private long activeQueueId() {
        PlaybackState s = controller == null ? null : controller.getPlaybackState();
        return s == null ? MediaSession.QueueItem.UNKNOWN_ID : s.getActiveQueueItemId();
    }

    private class QueueAdapter extends BaseAdapter {
        @Override public int getCount() { return queue.size(); }
        @Override public Object getItem(int i) { return queue.get(i); }
        @Override public long getItemId(int i) { return queue.get(i).getQueueId(); }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            TextView tv = (TextView) convert;
            if (tv == null) {
                tv = new TextView(PlayNewsActivity.this);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                tv.setPadding(dp(4), dp(12), dp(4), dp(12));
                tv.setMaxLines(2);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }
            MediaSession.QueueItem item = queue.get(i);
            MediaDescription d = item.getDescription();
            CharSequence t = d.getTitle();
            CharSequence sub = d.getSubtitle();
            String text = (i + 1) + ".  " + (t != null ? t : "Item " + (i + 1));
            if (sub != null && sub.length() > 0) text += "  ·  " + sub;
            tv.setText(text);
            boolean active = item.getQueueId() == activeQueueId();
            tv.setTextColor(active ? 0xFF8AB4F8 : 0xFFE0E0E6);
            tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            return tv;
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    private void setStatus(CharSequence s) { status.setText(s); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static CharSequence firstNonEmpty(CharSequence... values) {
        for (CharSequence v : values) if (v != null && v.length() > 0) return v;
        return null;
    }

    private static String firstNonEmptyString(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return null;
    }

    private static String formatTime(long ms) {
        long total = ms / 1000;
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (controller != null) refreshAll();
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(tick);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (controller != null) controller.unregisterCallback(controllerCallback);
        if (browser != null && browser.isConnected()) browser.disconnect();
        super.onDestroy();
    }
}
