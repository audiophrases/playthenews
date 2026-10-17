package com.eugen.playthenews;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Edits "My list": add sources (Google's or the built-in directory), remove, reorder. */
public class EditListActivity extends Activity {

    private PlayerService service;
    private final List<Source> entries = new ArrayList<>();
    private EntryAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);

        entries.addAll(Lineup.load(this));
        adapter = new EntryAdapter();
        ((ListView) findViewById(R.id.list)).setAdapter(adapter);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.add).setOnClickListener(v -> chooseGroup());
        findViewById(R.id.reset).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Reset your list?")
                .setMessage("Go back to Google's default: the first ten General sources.")
                .setPositiveButton("Reset", (d, w) -> resetToDefault())
                .setNegativeButton(android.R.string.cancel, null)
                .show());
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
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final PlayerService.Listener catalogListener = this::seedIfNeeded;

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
        adapter.notifyDataSetChanged();
        if (service != null) service.onLineupEdited();
    }

    private void move(int i, int delta) {
        int j = i + delta;
        if (j < 0 || j >= entries.size()) return;
        Collections.swap(entries, i, j);
        save();
    }

    private void remove(int i) {
        entries.remove(i);
        save();
    }

    private void resetToDefault() {
        Catalog c = catalog();
        if (c == null) {
            toast("Google news is still loading, try again in a moment");
            return;
        }
        entries.clear();
        entries.addAll(c.defaultLineup());
        save();
    }

    // ---- adding ---------------------------------------------------------------------------

    private static final String GOOGLE_GROUP = "Google News (all categories)";

    private void chooseGroup() {
        final List<String> labels = new ArrayList<>();
        final List<String> codes = new ArrayList<>();
        labels.add(GOOGLE_GROUP);
        codes.add(null);
        for (String[] l : Directory.LANGUAGES) {
            labels.add(l[1] + "  (" + Directory.forLanguage(l[0]).size() + ")");
            codes.add(l[0]);
        }
        new AlertDialog.Builder(this)
                .setTitle("Add from")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String code = codes.get(which);
                    if (code == null) chooseFromGoogle();
                    else chooseSources(Directory.languageName(code), Directory.forLanguage(code), false);
                })
                .show();
    }

    private void chooseFromGoogle() {
        Catalog c = catalog();
        if (c == null) {
            toast("Google news is still loading, try again in a moment");
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
            toast("All " + groupTitle + " sources are already in your list");
            return;
        }
        String[] labels = new String[available.size()];
        for (int i = 0; i < labels.length; i++) {
            Source s = available.get(i);
            labels[i] = showCategory ? s.name + "  ·  " + s.group : s.name;
        }
        final boolean[] checked = new boolean[labels.length];
        new AlertDialog.Builder(this)
                .setTitle(groupTitle)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Add", (d, w) -> {
                    int added = 0;
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) { entries.add(available.get(i)); added++; }
                    }
                    if (added > 0) {
                        save();
                        ((ListView) findViewById(R.id.list)).smoothScrollToPosition(entries.size() - 1);
                        toast("Added " + added + (added == 1 ? " source" : " sources"));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- rows -----------------------------------------------------------------------------

    private class EntryAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int i) { return entries.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(final int i, View convert, ViewGroup parent) {
            Row row = convert != null ? (Row) convert.getTag() : new Row();
            Source s = entries.get(i);
            row.title.setText((i + 1) + ".  " + s.name);
            row.subtitle.setText(s.label());
            row.up.setEnabled(i > 0);
            row.up.setAlpha(i > 0 ? 1f : 0.3f);
            row.down.setEnabled(i < entries.size() - 1);
            row.down.setAlpha(i < entries.size() - 1 ? 1f : 0.3f);
            row.up.setOnClickListener(v -> move(i, -1));
            row.down.setOnClickListener(v -> move(i, 1));
            row.remove.setOnClickListener(v -> remove(i));
            return row.root;
        }
    }

    private class Row {
        final LinearLayout root;
        final TextView title, subtitle;
        final ImageButton up, down, remove;

        Row() {
            root = new LinearLayout(EditListActivity.this);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(8), dp(6), 0, dp(6));

            LinearLayout text = new LinearLayout(EditListActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(EditListActivity.this);
            title.setTextColor(0xFFE0E0E6);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            subtitle = new TextView(EditListActivity.this);
            subtitle.setTextColor(0xFF8A8A92);
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
