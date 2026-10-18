package com.eugen.playthenews;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Edits "La meva llista": add sources (Google's, the built-in directory's, or any podcast by search or
 * feed address), remove, reorder, and choose whether it plays in that order or newest first.
 */
public class EditListActivity extends AppActivity {

    private PlayerService service;
    /** The list in the user's own order, as saved. */
    private final List<Source> entries = new ArrayList<>();
    /** What the screen shows, in play order: entries itself, or entries sorted newest first. */
    private List<Source> rows = entries;
    /** Latest episode per source id, for sorting and showing ages in newest-first mode. */
    private Map<String, Resolver.Episode> latest = new HashMap<>();
    private boolean newestFirst;
    private boolean checking;
    private int latestRequest;
    private int searchRequest;
    /** Feed checks and podcast searches. */
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private EntryAdapter adapter;
    private TextView hint, orderMine, orderNewest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);

        entries.addAll(Lineup.load(this));
        adapter = new EntryAdapter();
        ((ListView) findViewById(R.id.list)).setAdapter(adapter);

        newestFirst = Lineup.newestFirst(this);
        hint = findViewById(R.id.hint);
        orderMine = findViewById(R.id.order_mine);
        orderNewest = findViewById(R.id.order_newest);
        orderMine.setOnClickListener(v -> setNewestFirst(false));
        orderNewest.setOnClickListener(v -> setNewestFirst(true));
        showOrder();

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.add).setOnClickListener(v -> chooseGroup());
        findViewById(R.id.reset).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Vols restablir la llista?")
                .setMessage("Torna a la llista predeterminada de Google: les primeres deu fonts generals.")
                .setPositiveButton("Restableix", (d, w) -> resetToDefault())
                .setNegativeButton(R.string.cancel, null)
                .show());
    }

    @Override
    protected void onDestroy() {
        background.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, PlayerService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (service != null) service.removeListener(catalogListener);
        service = null;
        unbindService(connection);
        super.onStop();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlayerService.LocalBinder) binder).get();
            service.addListener(catalogListener);
            seedIfNeeded();
            refreshLatest();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final PlayerService.Listener catalogListener = () -> {
        seedIfNeeded();
        adapter.notifyDataSetChanged(); // Google flags can depend on the catalog
    };

    /** Before the first edit there is no saved list yet: start from Google's default. */
    private void seedIfNeeded() {
        Catalog c = catalog();
        if (c == null || Lineup.exists(this)) return;
        entries.clear();
        entries.addAll(c.defaultLineup());
        save();
    }

    private Catalog catalog() {
        return service != null ? service.getCatalog() : null;
    }

    // ---- editing --------------------------------------------------------------------------

    private void save() {
        Lineup.save(this, entries);
        refreshRows();
        if (service != null) service.onLineupEdited();
        refreshLatest();
    }

    private void move(int i, int delta) {
        int j = i + delta;
        if (j < 0 || j >= entries.size()) return;
        Collections.swap(entries, i, j);
        save();
    }

    private void remove(Source s) {
        entries.remove(s);
        save();
    }

    // ---- play order -----------------------------------------------------------------------

    private void setNewestFirst(boolean on) {
        if (on == newestFirst) return;
        newestFirst = on;
        Lineup.setNewestFirst(this, on);
        showOrder();
        refreshRows();
        if (service != null) service.onLineupEdited();
        refreshLatest();
    }

    private void showOrder() {
        orderMine.setSelected(!newestFirst);
        orderNewest.setSelected(newestFirst);
        hint.setText(newestFirst ? R.string.edit_hint_newest : R.string.edit_hint);
    }

    private void refreshRows() {
        rows = newestFirst ? Lineup.byNewest(entries, latest) : entries;
        adapter.notifyDataSetChanged();
    }

    /** Newest first needs every source's latest bulletin; the service resolves (and caches) them. */
    private void refreshLatest() {
        if (service == null || !newestFirst || entries.isEmpty()) return;
        final int request = ++latestRequest;
        checking = true;
        refreshRows();
        service.fetchLatest(new ArrayList<>(entries), found -> {
            if (request != latestRequest) return;
            latest = found;
            checking = false;
            refreshRows();
            // The playing queue sorted with what was known then; bring it in line with the screen.
            if (service != null) service.onLineupEdited();
        });
    }

    private String subtitle(Source s) {
        if (!newestFirst) return s.label();
        Resolver.Episode e = latest.get(s.id());
        if (e != null && e.publishedAt > 0) return s.label() + "  ·  " + age(e.publishedAt);
        return checking ? s.label() + "  ·  s’està comprovant…" : s.label();
    }

    /** "ara mateix", "25 min ago", "3 h ago", "2 days ago". */
    private static String age(long time) {
        long min = (System.currentTimeMillis() - time) / 60000;
        if (min < 1) return "ara mateix";
        if (min < 60) return min + " min enrere";
        if (min < 24 * 60) return (min / 60) + " h enrere";
        long days = min / (24 * 60);
        return days == 1 ? "fa 1 dia" : days + " dies enrere";
    }

    private void resetToDefault() {
        Catalog c = catalog();
        if (c == null) {
            toast("Les notícies de Google encara s’estan carregant. Torna-ho a provar d’aquí a un moment.");
            return;
        }
        entries.clear();
        entries.addAll(c.defaultLineup());
        save();
    }

    // ---- adding ---------------------------------------------------------------------------

    private static final String GOOGLE_GROUP = "Google News (totes les categories)";

    private void chooseGroup() {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        labels.add(GOOGLE_GROUP);
        actions.add(this::chooseFromGoogle);
        for (String[] l : Directory.LANGUAGES) {
            final String code = l[0];
            labels.add(l[1] + "  (" + Directory.forLanguage(code).size() + ")");
            actions.add(() -> chooseSources(Directory.languageName(code), Directory.forLanguage(code), false));
        }
        labels.add("Cerca pòdcasts…");
        actions.add(this::searchPodcasts);
        labels.add("Adreça del canal del pòdcast…");
        actions.add(this::addByAddress);
        new AlertDialog.Builder(this)
                .setTitle("Afegeix des de")
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void chooseFromGoogle() {
        Catalog c = catalog();
        if (c == null) {
            toast("Les notícies de Google encara s’estan carregant. Torna-ho a provar d’aquí a un moment.");
            return;
        }
        // The same source often appears in several categories; list it once.
        Map<String, Source> unique = new LinkedHashMap<>();
        for (Catalog.Item i : c.items) {
            if (!unique.containsKey(i.title)) unique.put(i.title, i.toSource());
        }
        chooseSources("Google News", new ArrayList<>(unique.values()), true);
    }

    private void chooseSources(String groupTitle, List<Source> candidates, boolean showCategory) {
        Set<String> have = new HashSet<>();
        for (Source s : entries) have.add(s.id());
        final List<Source> available = new ArrayList<>();
        for (Source s : candidates) if (!have.contains(s.id())) available.add(s);
        if (available.isEmpty()) {
            toast("Totes les fonts de " + groupTitle + " ja són a la teva llista");
            return;
        }
        String[] labels = new String[available.size()];
        for (int i = 0; i < labels.length; i++) {
            Source s = available.get(i);
            labels[i] = showCategory ? s.name + "  ·  " + Labels.category(s.group) : s.name;
        }
        final boolean[] checked = new boolean[labels.length];
        new AlertDialog.Builder(this)
                .setTitle(groupTitle)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Afegeix", (d, w) -> {
                    int added = 0;
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) { entries.add(available.get(i)); added++; }
                    }
                    if (added > 0) {
                        save();
                        ((ListView) findViewById(R.id.list)).smoothScrollToPosition(entries.size() - 1);
                        toast("S’ha afegit: " + added + (added == 1 ? " font" : " fonts"));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---- adding any podcast ---------------------------------------------------------------

    private void addByAddress() {
        final EditText input = dialogInput("https://example.com/podcast.xml",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_DONE);
        final TextView status = dialogText("Enganxa l’adreça del canal RSS del pòdcast. "
                + "Es reproduirà l’episodi més recent.");
        final AlertDialog dialog = showInputDialog("Canal del pòdcast", "Afegeix", input, status, null);
        final Button add = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        add.setOnClickListener(v -> {
            String url = input.getText().toString().trim();
            if (url.isEmpty()) return;
            if (!url.matches("(?i)https?://.*")) url = "https://" + url;
            add.setEnabled(false);
            addFeed(url, null, dialog, status, () -> add.setEnabled(true));
        });
        input.setOnEditorActionListener((v, action, e) -> add.performClick());
    }

    private void searchPodcasts() {
        final EditText input = dialogInput("Nom del pòdcast", InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_ACTION_SEARCH);
        final TextView status = dialogText("Cerca al directori de pòdcasts d’Apple.");
        final List<PodcastSearch.Result> found = new ArrayList<>();
        final ResultAdapter results = new ResultAdapter(found);
        final ListView list = new ListView(this);
        list.setAdapter(results);
        final AlertDialog dialog = showInputDialog("Cerca pòdcasts", "Cerca", input, status, list);
        final boolean[] busy = {false};

        final Runnable search = () -> {
            final String term = input.getText().toString().trim();
            if (term.isEmpty() || busy[0]) return;
            final int request = ++searchRequest;
            status.setText("S’està cercant…");
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(input.getWindowToken(), 0);
            background.execute(() -> {
                try {
                    final List<PodcastSearch.Result> r = PodcastSearch.search(term);
                    onUi(() -> {
                        if (request != searchRequest) return;
                        found.clear();
                        found.addAll(r);
                        results.notifyDataSetChanged();
                        list.setSelection(0);
                        status.setText(r.isEmpty() ? "No s’ha trobat cap pòdcast." : "Toca un pòdcast per afegir-lo.");
                    });
                } catch (IOException e) {
                    onUi(() -> { if (request == searchRequest) status.setText("La cerca ha fallat. Comprova la connexió i torna-ho a provar."); });
                }
            });
        };
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> search.run());
        input.setOnEditorActionListener((v, action, e) -> {
            search.run();
            return true;
        });
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (busy[0]) return;
            PodcastSearch.Result r = found.get(pos);
            busy[0] = true;
            addFeed(r.feedUrl, r.name, dialog, status, () -> busy[0] = false);
        });
    }

    /**
     * Checks that url is a podcast feed with audio and adds it. A feed the directory lists is
     * added as the directory has it; any other gets its language and flag from the feed itself.
     */
    private void addFeed(final String url, final String name, final AlertDialog dialog,
            final TextView status, final Runnable onFailure) {
        final Source listed = Directory.byUrl(url);
        String id = listed != null ? listed.id() : Source.RSS + "|" + url;
        for (Source s : entries) {
            if (s.id().equals(id)) {
                status.setText(s.name + " ja és a la teva llista.");
                onFailure.run();
                return;
            }
        }
        status.setText(name != null ? "S’està comprovant: " + name + "…" : "S’està comprovant…");
        background.execute(() -> {
            try {
                final Source added;
                if (listed != null) {
                    added = listed;
                } else {
                    Resolver.Feed feed = Resolver.readFeed(url);
                    String title = name != null ? name
                            : feed.title != null && !feed.title.isEmpty() ? feed.title : Uri.parse(url).getHost();
                    String lang = feed.language == null ? ""
                            : feed.language.toLowerCase(Locale.ROOT).split("[-_]")[0];
                    added = new Source(Source.RSS, title, lang, url, Countries.guess(feed.language, url));
                }
                onUi(() -> {
                    dialog.dismiss();
                    entries.add(added);
                    save();
                    ((ListView) findViewById(R.id.list)).smoothScrollToPosition(entries.size() - 1);
                    toast("S’ha afegit: " + added.name);
                });
            } catch (Exception e) {
                onUi(() -> {
                    status.setText("No s’ha pogut llegir el canal. Comprova l’adreça i la connexió.");
                    onFailure.run();
                });
            }
        });
    }

    /** Runs on the main thread, unless the screen has gone in the meantime. */
    private void onUi(Runnable r) {
        runOnUiThread(() -> { if (!isDestroyed()) r.run(); });
    }

    private EditText dialogInput(String hint, int inputType, int imeAction) {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setHint(hint);
        input.setInputType(inputType);
        input.setImeOptions(imeAction);
        return input;
    }

    private TextView dialogText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color(R.color.secondary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(dp(4), dp(8), dp(4), dp(4));
        return t;
    }

    /**
     * A dialog with an input, a status line and optionally a list. Its positive button is wired
     * by the caller, so a failed check can keep the dialog open.
     */
    private AlertDialog showInputDialog(String title, String action, EditText input, TextView status,
            View below) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        box.addView(status);
        if (below != null) {
            box.addView(below, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)));
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(action, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
        input.requestFocus();
        return dialog;
    }

    /** Search results: name, then author, genre and how recent the newest episode is. */
    private class ResultAdapter extends BaseAdapter {
        private final List<PodcastSearch.Result> results;

        ResultAdapter(List<PodcastSearch.Result> results) {
            this.results = results;
        }

        @Override public int getCount() { return results.size(); }
        @Override public Object getItem(int i) { return results.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                row = new LinearLayout(EditListActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(4), dp(10), dp(4), dp(10));
                TextView name = new TextView(EditListActivity.this);
                name.setTextColor(color(R.color.primary));
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                name.setMaxLines(2);
                name.setEllipsize(TextUtils.TruncateAt.END);
                TextView detail = new TextView(EditListActivity.this);
                detail.setTextColor(color(R.color.secondary));
                detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                detail.setMaxLines(1);
                detail.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(name);
                row.addView(detail);
            }
            PodcastSearch.Result r = results.get(i);
            List<String> parts = new ArrayList<>();
            if (!r.author.isEmpty()) parts.add(r.author);
            if (!r.genre.isEmpty()) parts.add(r.genre);
            if (r.updatedAt > 0) parts.add("actualitzat " + age(r.updatedAt));
            ((TextView) row.getChildAt(0)).setText(r.name);
            ((TextView) row.getChildAt(1)).setText(TextUtils.join("  ·  ", parts));
            return row;
        }
    }

    // ---- rows -----------------------------------------------------------------------------

    private class EntryAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(final int i, View convert, ViewGroup parent) {
            Row row = convert != null ? (Row) convert.getTag() : new Row();
            final Source s = rows.get(i);
            int flag = Countries.flagRes(Countries.of(s, catalog()));
            // Invisible rather than gone, so names stay in one column.
            row.flag.setVisibility(flag != 0 ? View.VISIBLE : View.INVISIBLE);
            if (flag != 0) row.flag.setImageResource(flag);
            row.title.setText((i + 1) + ".  " + s.name);
            row.subtitle.setText(subtitle(s));
            // Newest first sets the order itself; moving only applies to the user's own order.
            int arrows = newestFirst ? View.GONE : View.VISIBLE;
            row.up.setVisibility(arrows);
            row.down.setVisibility(arrows);
            row.up.setEnabled(i > 0);
            row.up.setAlpha(i > 0 ? 1f : 0.3f);
            row.down.setEnabled(i < entries.size() - 1);
            row.down.setAlpha(i < entries.size() - 1 ? 1f : 0.3f);
            row.up.setOnClickListener(v -> move(i, -1));
            row.down.setOnClickListener(v -> move(i, 1));
            row.remove.setOnClickListener(v -> remove(s));
            return row.root;
        }
    }

    private class Row {
        final LinearLayout root;
        final ImageView flag;
        final TextView title, subtitle;
        final ImageButton up, down, remove;

        Row() {
            root = new LinearLayout(EditListActivity.this);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(8), dp(6), 0, dp(6));

            flag = new ImageView(EditListActivity.this);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(20), dp(15));
            flp.rightMargin = dp(12);
            root.addView(flag, flp);

            LinearLayout text = new LinearLayout(EditListActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(EditListActivity.this);
            title.setTextColor(color(R.color.primary));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            subtitle = new TextView(EditListActivity.this);
            subtitle.setTextColor(color(R.color.secondary));
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            text.addView(title);
            text.addView(subtitle);
            root.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            up = button(R.drawable.ic_up, R.string.move_up);
            down = button(R.drawable.ic_down, R.string.move_down);
            remove = button(R.drawable.ic_close, R.string.remove);
            root.setTag(this);
        }

        private ImageButton button(int icon, int label) {
            ImageButton b = new ImageButton(EditListActivity.this);
            b.setImageResource(icon);
            b.setContentDescription(getString(label));
            b.setPadding(dp(12), dp(12), dp(12), dp(12));
            TypedValue bg = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, bg, true);
            b.setBackgroundResource(bg.resourceId);
            root.addView(b, new LinearLayout.LayoutParams(dp(46), dp(46)));
            return b;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
