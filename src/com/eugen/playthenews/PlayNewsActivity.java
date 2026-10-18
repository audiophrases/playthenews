package com.eugen.playthenews;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ImageSpan;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Player screen. Opening it plays getString(R.string.my_list_short) unless something is already loaded; the controls,
 * queue and category buttons all drive {@link PlayerService} through its MediaSession.
 */
public class PlayNewsActivity extends AppActivity {

    private static final long REWIND_MS = 15000;
    private static final long FORWARD_MS = 30000;
    private static final long TICK_MS = 500;
    private static final String STATE_AUTOSTARTED = "autostarted";

    private PlayerService service;
    private MediaController controller;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private List<MediaSession.QueueItem> queue = new ArrayList<>();
    private boolean autoStartChecked = false;
    private boolean userSeeking = false;
    private long shownActiveId = MediaSession.QueueItem.UNKNOWN_ID;

    private LinearLayout categoryRow;
    private ImageView art;
    private TextView title, subtitle, elapsed, duration, status, queueLabel;
    private SeekBar seek;
    private ImageButton playPause;
    private ListView queueView;
    private QueueAdapter queueAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        autoStartChecked = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_AUTOSTARTED);

        findViewById(R.id.language).setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.language)
                .setSingleChoiceItems(new String[]{"Català", "English"}, "ca".equals(AppLanguage.code()) ? 0 : 1, (dialog, which) -> {
                    String code = which == 0 ? "ca" : "en";
                    dialog.dismiss();
                    if (!code.equals(AppLanguage.code())) {
                        getSharedPreferences("appearance", 0).edit().putString("language", code).apply();
                        recreate();
                    }
                }).setNegativeButton(R.string.cancel, null).show());
        Button collapse = findViewById(R.id.collapse_player);
        Button theme = findViewById(R.id.theme_toggle);
        theme.setText(day(this) ? getString(R.string.night_mode) : getString(R.string.day_mode));
        theme.setOnClickListener(v -> {
            getSharedPreferences("appearance", 0).edit().putBoolean("day", !day(this)).apply();
            recreate();
        });
        collapse.setOnClickListener(v -> {
            boolean expanded = !getSharedPreferences("appearance", 0).getBoolean("expanded", false);
            getSharedPreferences("appearance", 0).edit().putBoolean("expanded", expanded).apply();
            updatePlayerSize();
        });
        updatePlayerSize();
        categoryRow = findViewById(R.id.categories);
        art = findViewById(R.id.art);
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        elapsed = findViewById(R.id.elapsed);
        duration = findViewById(R.id.duration);
        status = findViewById(R.id.status);
        queueLabel = findViewById(R.id.queue_label);
        seek = findViewById(R.id.seek);
        playPause = findViewById(R.id.play_pause);

        findViewById(R.id.prev).setOnClickListener(v -> { if (controls()) tc().skipToPrevious(); });
        findViewById(R.id.next).setOnClickListener(v -> { if (controls()) tc().skipToNext(); });
        findViewById(R.id.rewind).setOnClickListener(v -> seekBy(-REWIND_MS));
        findViewById(R.id.forward).setOnClickListener(v -> seekBy(FORWARD_MS));
        findViewById(R.id.stop).setOnClickListener(v -> { if (controls()) tc().stop(); });
        findViewById(R.id.edit_list).setOnClickListener(
                v -> startActivity(new Intent(this, EditListActivity.class)));
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

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        setStatus(getString(R.string.connecting));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(STATE_AUTOSTARTED, autoStartChecked);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, PlayerService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(tick);
        disconnect();
        unbindService(connection);
        super.onStop();
    }

    // ---- service connection ---------------------------------------------------------------

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlayerService.LocalBinder) binder).get();
            controller = new MediaController(PlayNewsActivity.this, service.getSessionToken());
            controller.registerCallback(controllerCallback, handler);
            service.addListener(catalogListener);
            service.refreshLanguage();
            buildCategoryButtons();
            refreshAll();
            maybeAutoStart();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            disconnect();
        }
    };

    private void disconnect() {
        if (controller != null) controller.unregisterCallback(controllerCallback);
        if (service != null) service.removeListener(catalogListener);
        controller = null;
        service = null;
    }

    private final PlayerService.Listener catalogListener = this::buildCategoryButtons;

    /** One tap = news: play My list unless something is already loaded. */
    private void maybeAutoStart() {
        if (autoStartChecked) return;
        autoStartChecked = true;
        if (!service.isActive()) {
            setStatus(getString(R.string.starting_list));
            service.playMyList(0);
        }
    }

    private void buildCategoryButtons() {
        categoryRow.removeAllViews();
        Catalog catalog = service != null ? service.getCatalog() : null;
        findViewById(R.id.category_scroll).setVisibility(
                catalog != null && !catalog.categories.isEmpty() ? View.VISIBLE : View.GONE);
        if (catalog == null) return;
        for (final Catalog.Category c : catalog.categories) {
            addChip(Labels.category(c.name), v -> { if (service != null) service.playCategory(c); });
        }
    }

    private void addChip(String text, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(color(R.color.primary));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setBackgroundResource(R.drawable.bg_chip);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        b.setOnClickListener(click);
        categoryRow.addView(b);
    }

    // ---- transport ------------------------------------------------------------------------

    private boolean controls() {
        return controller != null;
    }

    private MediaController.TransportControls tc() {
        return controller.getTransportControls();
    }

    private void togglePlayPause() {
        if (!controls()) return;
        PlaybackState s = controller.getPlaybackState();
        int st = s == null ? PlaybackState.STATE_NONE : s.getState();
        if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) {
            tc().pause();
        } else {
            tc().play();
        }
    }

    private void seekBy(long deltaMs) {
        if (!controls()) return;
        long pos = currentPosition(controller.getPlaybackState());
        if (pos < 0) return;
        tc().seekTo(Math.max(0, pos + deltaMs));
    }

    // ---- UI updates -----------------------------------------------------------------------

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { updateState(state); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { updateMetadata(metadata); }
        @Override public void onQueueChanged(List<MediaSession.QueueItem> q) { updateQueue(q); }
        @Override public void onQueueTitleChanged(CharSequence t) { updateQueueTitle(t); }
    };

    private void refreshAll() {
        updateMetadata(controller.getMetadata());
        updateQueue(controller.getQueue());
        updateQueueTitle(controller.getQueueTitle());
        updateState(controller.getPlaybackState());
    }

    private void updateState(PlaybackState s) {
        int st = s == null ? PlaybackState.STATE_NONE : s.getState();
        boolean playing = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
                || st == PlaybackState.STATE_CONNECTING;
        playPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);

        switch (st) {
            case PlaybackState.STATE_PLAYING: setStatus(getString(R.string.playing)); break;
            case PlaybackState.STATE_PAUSED: setStatus(getString(R.string.paused)); break;
            case PlaybackState.STATE_BUFFERING: setStatus(getString(R.string.loading)); break;
            case PlaybackState.STATE_CONNECTING: setStatus(getString(R.string.checking_news)); break;
            case PlaybackState.STATE_STOPPED: setStatus(""); break;
            case PlaybackState.STATE_ERROR:
                CharSequence msg = s.getErrorMessage();
                setStatus(msg != null ? msg : getString(R.string.playback_error));
                break;
            default: setStatus(""); break;
        }
        queueAdapter.notifyDataSetChanged();
        scrollToActive(false);
        tick.run();
    }

    private void updateMetadata(MediaMetadata m) {
        if (m == null) {
            title.setText(R.string.app_name);
            subtitle.setText("");
            art.setImageResource(R.mipmap.ic_launcher);
            seek.setProgress(0);
            elapsed.setText(formatTime(0));
            duration.setText("--:--");
            return;
        }
        MediaDescription d = m.getDescription();
        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence sub = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        title.setText(withFlag(t != null ? t : d.getTitle(), m.getString(PlayerService.EXTRA_COUNTRY)));
        subtitle.setText(sub != null ? sub : "");

        long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        seek.setMax(dur > 0 ? (int) dur : 0);
        duration.setText(dur > 0 ? formatTime(dur) : "--:--");

        Bitmap bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp != null) art.setImageBitmap(bmp);
        else art.setImageResource(R.mipmap.ic_launcher);
    }

    private void updateQueue(List<MediaSession.QueueItem> q) {
        queue = q != null ? q : new ArrayList<>();
        queueAdapter.notifyDataSetChanged();
        scrollToActive(true);
    }

    private void updateQueueTitle(CharSequence t) {
        queueLabel.setText(t != null ? Labels.category(t.toString()) : getString(R.string.up_next));
    }

    /** Keeps the playing item visible when the queue advances. */
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
                tv.setCompoundDrawablePadding(dp(10));
            }
            MediaSession.QueueItem item = queue.get(i);
            MediaDescription d = item.getDescription();
            Bundle extras = d.getExtras();
            tv.setCompoundDrawablesRelative(
                    flag(extras != null ? extras.getString(PlayerService.EXTRA_COUNTRY) : null, true),
                    null, null, null);
            CharSequence t = d.getTitle();
            CharSequence sub = d.getSubtitle();
            String text = (i + 1) + ".  " + (t != null ? t : getString(R.string.item) + (i + 1));
            if (sub != null && sub.length() > 0) text += "  ·  " + sub;
            tv.setText(text);
            boolean active = item.getQueueId() == activeQueueId();
            tv.setTextColor(active ? color(R.color.accent) : color(R.color.primary));
            tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            return tv;
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    private void updatePlayerSize() {
        boolean expanded = getSharedPreferences("appearance", 0).getBoolean("expanded", false);
        findViewById(R.id.art).setVisibility(expanded ? View.VISIBLE : View.GONE);
        findViewById(R.id.subtitle).setVisibility(expanded ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.title)).setMaxLines(expanded ? 2 : 1);
        findViewById(R.id.player_details).setVisibility(expanded ? View.VISIBLE : View.GONE);
        ((Button) findViewById(R.id.collapse_player)).setText(expanded ? getString(R.string.collapse_player) : getString(R.string.expand_player));
    }

    private void setStatus(CharSequence s) {
        status.setText(s);
        status.setVisibility(s != null && s.length() > 0 ? View.VISIBLE : View.GONE);
    }

    /** A 20×15dp flag; for an unknown country null, or a blank of the same size to keep a column. */
    private Drawable flag(String country, boolean blankIfUnknown) {
        int res = Countries.flagRes(country);
        if (res == 0 && !blankIfUnknown) return null;
        Drawable d = res != 0 ? getDrawable(res) : new ColorDrawable(Color.TRANSPARENT);
        d.setBounds(0, 0, dp(20), dp(15));
        return d;
    }

    /** The now-playing title with its source's flag in front. */
    private CharSequence withFlag(CharSequence text, String country) {
        Drawable d = flag(country, false);
        if (d == null) return text;
        int align = Build.VERSION.SDK_INT >= 29
                ? DynamicDrawableSpan.ALIGN_CENTER : DynamicDrawableSpan.ALIGN_BASELINE;
        SpannableStringBuilder sb = new SpannableStringBuilder("￼  ").append(text);
        sb.setSpan(new ImageSpan(d, align), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String formatTime(long ms) {
        long total = ms / 1000;
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
}
