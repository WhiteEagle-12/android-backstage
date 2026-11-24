package com.deviceagent.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.deviceagent.tools.Json;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Timeline of agent actions, the Privacy Fence and items shared to Backstage. */
final class ActivityTab extends TabPage {
    private static final int TIMELINE = 0, BLOCKED = 1, SHARED = 2;
    private int segment = TIMELINE;
    private List<Map<String, Object>> timeline = new ArrayList<>(), inbox = new ArrayList<>();
    private List<String> blocked = new ArrayList<>();
    private final boolean[] loaded = new boolean[3], loading = new boolean[3];
    private final boolean[] failed = new boolean[3];
    private final long[] loadedAt = new long[3];
    private final TextView[] segments = new TextView[3];

    ActivityTab(AgentActivity activity, UiKit kit) { super(activity, kit, AppCopy.TAB_ACTIVITY); }

    @Override void onShow() { loadedAt[segment] = 0; }

    @Override void tick() {
        render();
        if (a.active && SystemClock.elapsedRealtime() - loadedAt[segment] > 5000) load(segment);
    }

    @Override void render() {
        begin();
        put("segments", "segments", this::segmentBar);
        for (int i = 0; i < 3; i++) styleSegment(i);
        String state = loaded[segment] + "|" + failed[segment] + "|" + a.daemonUp;
        if (segment == TIMELINE) {
            StringBuilder sig = new StringBuilder("t|" + state + "|" + a.accountVersion);
            for (Map<String, Object> r : timeline) sig.append('|').append(DaemonClient.str(r, "id"));
            put("timeline", sig.toString(), this::timelineSection);
        } else if (segment == BLOCKED) {
            put("blocked", "b|" + state + "|" + blocked, this::blockedSection);
        } else {
            StringBuilder sig = new StringBuilder("s|" + state);
            for (Map<String, Object> r : inbox) sig.append('|').append(DaemonClient.str(r, "itemId"));
            put("shared", sig.toString(), this::sharedSection);
        }
        end();
    }

    // ---- Segmented switcher ----

    private void segmentBar(LinearLayout box) {
        LinearLayout track = k.row(); track.setPadding(k.dp(4), k.dp(4), k.dp(4), k.dp(4));
        track.setBackground(k.shape(k.card, 12, k.stroke));
        String[] labels = {AppCopy.SEGMENT_TIMELINE, AppCopy.SEGMENT_BLOCKED, AppCopy.SEGMENT_SHARED};
        for (int i = 0; i < 3; i++) {
            final int index = i;
            TextView t = k.text(null, labels[i], 14, k.ink, true);
            t.setGravity(Gravity.CENTER); t.setMinHeight(k.dp(40)); t.setSingleLine(true);
            t.setClickable(true); t.setFocusable(true);
            t.setOnClickListener(v -> select(index));
            segments[i] = t;
            track.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = k.dp(8);
        box.addView(track, lp);
    }

    private void styleSegment(int i) {
        TextView t = segments[i]; if (t == null) return;
        boolean on = i == segment;
        t.setTextColor(on ? k.ink : k.muted);
        t.setBackground(on ? k.shape(k.raised, 9, k.strokeStrong) : null);
        t.setSelected(on);
    }

    private void select(int index) {
        if (index == segment) return;
        segment = index; render();
        if (SystemClock.elapsedRealtime() - loadedAt[index] > 1500) load(index);
    }

    private void load(int which) {
        if (loading[which] || !a.daemonUp) return;
        loading[which] = true;
        String path = which == TIMELINE ? "/timeline" : which == BLOCKED ? "/blocked-apps" : "/inbox";
        a.daemon.get(path, (reply, error) -> {
            loading[which] = false; loadedAt[which] = SystemClock.elapsedRealtime();
            failed[which] = error != null;
            if (error == null) {
                if (which == TIMELINE) timeline = DaemonClient.rows(reply, "entries");
                else if (which == BLOCKED) blocked = DaemonClient.strings(reply, "packages");
                else inbox = DaemonClient.rows(reply, "items");
                loaded[which] = true;
            }
            if (a.isTabVisible(this)) render();
        });
    }

    /** True when the list cannot load because Backstage is not running. */
    private boolean unavailable(LinearLayout box, int which) {
        if (a.daemonUp && !(failed[which] && !loaded[which])) return false;
        k.empty(box, AppCopy.DAEMON_UNAVAILABLE);
        return true;
    }

    // ---- Timeline ----

    private void timelineSection(LinearLayout box) {
        if (unavailable(box, TIMELINE) || !loaded[TIMELINE]) return;
        if (timeline.isEmpty()) { k.empty(box, AppCopy.EMPTY_TIMELINE); return; }
        LinearLayout head = k.row(); head.setPadding(k.dp(4), k.dp(14), 0, 0);
        View spacer = new View(a); head.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        android.widget.Button clear = k.buttonView(AppCopy.CLEAR, UiKit.QUIET);
        clear.setOnClickListener(v -> clearTimeline());
        head.addView(clear);
        box.addView(head);
        LinearLayout card = k.card(box); card.setPadding(k.dp(6), k.dp(4), k.dp(6), k.dp(4));
        boolean first = true;
        for (Map<String, Object> r : timeline) {
            if (!first) { View line = k.divider(card); ((LinearLayout.LayoutParams) line.getLayoutParams()).setMargins(k.dp(40), 0, k.dp(10), 0); }
            first = false;
            timelineRow(card, r);
        }
    }

    private void timelineRow(LinearLayout card, Map<String, Object> r) {
        AccountClient.Assistant owner = a.assistantById(DaemonClient.str(r, "agentId"));
        LinearLayout row = k.row(); row.setPadding(k.dp(12), k.dp(12), k.dp(12), k.dp(12)); row.setMinimumHeight(k.dp(56));
        String outcome = DaemonClient.str(r, "outcome");
        int color = "succeeded".equals(outcome) ? k.green : "blocked".equals(outcome) || outcome.contains("fail") || outcome.contains("error") ? k.danger : k.amber;
        k.dot(row, color, 8);
        LinearLayout col = k.column();
        String tool = toolLabel(DaemonClient.str(r, "tool"));
        k.text(col, tool, 15, k.ink, false);
        List<String> meta = new ArrayList<>();
        String target = DaemonClient.str(r, "target");
        if (!target.isEmpty()) meta.add(a.appLabel(target));
        if (owner != null) meta.add(owner.name);
        long time = DaemonClient.num(r, "timeMs");
        if (time > 0) meta.add(DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString());
        if (!meta.isEmpty()) { TextView m = k.body(col, String.join("  ·  ", meta)); m.setTextSize(13); m.setPadding(0, k.dp(2), 0, 0); }
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        if (owner != null) {
            ImageView chevron = new ImageView(a); chevron.setImageDrawable(new AppIcons(AppIcons.CHEVRON, k.muted));
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(chevron, new LinearLayout.LayoutParams(k.dp(20), k.dp(20)));
            row.setBackground(k.ripple(null, 14)); row.setClickable(true); row.setFocusable(true);
            row.setOnClickListener(v -> ownerDialog(owner));
        }
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    static String toolLabel(String tool) {
        return AppCopy.toolLabel(tool);
    }

    private void ownerDialog(AccountClient.Assistant owner) {
        AlertDialog[] self = new AlertDialog[1];
        LinearLayout form = k.form();
        k.button(form, owner.paused ? AppCopy.ACTION_RESUME : AppCopy.ACTION_PAUSE, UiKit.SECONDARY, () -> { self[0].dismiss(); a.toggleAssistantPause(owner); });
        k.button(form, AppCopy.ACTION_REMOVE_ACCESS, UiKit.DANGER, () -> { self[0].dismiss(); a.confirmRemoveAssistant(owner); });
        self[0] = new AlertDialog.Builder(a).setTitle(owner.name).setView(form).setNegativeButton(AppCopy.CLOSE, null).show();
    }

    private void clearTimeline() {
        timeline = new ArrayList<>(); render();
        a.daemon.post("/timeline/clear", Map.of(), (r, e) -> { if (e == null) toast(AppCopy.TIMELINE_CLEARED); load(TIMELINE); });
    }

    // ---- Blocked apps ----

    private void blockedSection(LinearLayout box) {
        TextView desc = k.body(box, AppCopy.BLOCKED_DESC); desc.setPadding(k.dp(4), k.dp(14), k.dp(4), 0);
        if (unavailable(box, BLOCKED) || !loaded[BLOCKED]) return;
        if (blocked.isEmpty()) k.empty(box, AppCopy.EMPTY_BLOCKED);
        else {
            LinearLayout card = k.card(box); card.setPadding(k.dp(6), k.dp(4), k.dp(6), k.dp(4));
            boolean first = true;
            for (String pkg : blocked) {
                if (!first) { View line = k.divider(card); ((LinearLayout.LayoutParams) line.getLayoutParams()).setMargins(k.dp(64), 0, k.dp(10), 0); }
                first = false;
                LinearLayout row = k.row(); row.setPadding(k.dp(12), k.dp(10), k.dp(4), k.dp(10));
                row.addView(appIcon(pkg), iconParams());
                LinearLayout col = k.column();
                k.text(col, a.appLabel(pkg), 15, k.ink, false);
                TextView p = k.body(col, pkg); p.setTextSize(12); p.setPadding(0, k.dp(2), 0, 0);
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                android.widget.Button remove = k.buttonView(AppCopy.REMOVE, UiKit.QUIET);
                remove.setContentDescription(AppCopy.REMOVE + " " + a.appLabel(pkg));
                remove.setOnClickListener(v -> { List<String> next = new ArrayList<>(blocked); next.remove(pkg); saveBlocked(next); });
                row.addView(remove);
                card.addView(row);
            }
        }
        k.button(box, AppCopy.ACTION_MANAGE_BLOCKED, UiKit.PRIMARY, this::blockedPicker);
    }

    private ImageView appIcon(String pkg) {
        ImageView icon = new ImageView(a); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        try { icon.setImageDrawable(a.getPackageManager().getApplicationIcon(pkg)); }
        catch (PackageManager.NameNotFoundException e) { icon.setImageDrawable(null); }
        return icon;
    }

    private LinearLayout.LayoutParams iconParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(k.dp(36), k.dp(36)); lp.setMarginEnd(k.dp(14)); return lp;
    }

    private void saveBlocked(List<String> packages) {
        blocked = packages; render();
        a.daemon.post("/blocked-apps", Json.mapOf("packages", packages), (reply, error) -> {
            toast(error == null ? AppCopy.BLOCKED_SAVED : AppCopy.BLOCKED_SAVE_FAILED);
            if (error == null) blocked = DaemonClient.strings(reply, "packages");
            load(BLOCKED);
        });
    }

    private static final class App { String pkg, label; Drawable icon; }

    /** Searchable multi-select of launchable apps; loads labels and icons off the main thread. */
    private void blockedPicker() {
        final Set<String> chosen = new HashSet<>(blocked);
        new Thread(() -> {
            PackageManager pm = a.getPackageManager();
            List<App> apps = new ArrayList<>(); Set<String> seen = new HashSet<>();
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(a.getPackageName()) || !seen.add(pkg)) continue;
                App app = new App(); app.pkg = pkg; app.label = info.loadLabel(pm).toString(); app.icon = info.loadIcon(pm);
                apps.add(app);
            }
            for (String pkg : chosen) if (!seen.contains(pkg)) { App app = new App(); app.pkg = pkg; app.label = a.appLabel(pkg); apps.add(app); }
            apps.sort((x, y) -> x.label.compareToIgnoreCase(y.label));
            a.runOnUiThread(() -> { if (!a.isDestroyed()) showPicker(apps, chosen); });
        }, "backstage-app-list").start();
    }

    private void showPicker(List<App> apps, Set<String> chosen) {
        LinearLayout form = k.form();
        EditText search = new EditText(a);
        search.setHint(AppCopy.SEARCH_APPS_HINT); search.setSingleLine(true); search.setTextSize(16);
        search.setTextColor(k.ink); search.setHintTextColor(k.muted);
        search.setCompoundDrawablePadding(k.dp(10));
        AppIcons glass = new AppIcons(AppIcons.SEARCH, k.muted); glass.setBounds(0, 0, k.dp(20), k.dp(20));
        search.setCompoundDrawablesRelative(glass, null, null, null);
        search.setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12));
        search.setBackground(k.shape(k.canvas, 12, k.stroke));
        search.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        form.addView(search, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout list = k.column(); list.setPadding(0, k.dp(8), 0, 0);
        List<View> rows = new ArrayList<>();
        for (App app : apps) {
            LinearLayout row = k.row(); row.setMinimumHeight(k.dp(52)); row.setTag(app.label.toLowerCase(Locale.ROOT) + " " + app.pkg);
            ImageView icon = new ImageView(a); icon.setImageDrawable(app.icon); icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(k.dp(32), k.dp(32)); ip.setMarginEnd(k.dp(12));
            row.addView(icon, ip);
            TextView label = k.text(null, app.label, 15, k.ink, false);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            CheckBox box = new CheckBox(a); box.setContentDescription(app.label);
            box.setChecked(chosen.contains(app.pkg));
            box.setOnCheckedChangeListener((b, on) -> { if (on) chosen.add(app.pkg); else chosen.remove(app.pkg); });
            row.addView(box);
            row.setOnClickListener(v -> box.toggle());
            list.addView(row); rows.add(row);
        }
        UiKit.watch(search, q -> {
            String query = q.trim().toLowerCase(Locale.ROOT);
            for (View row : rows) row.setVisibility(query.isEmpty() || String.valueOf(row.getTag()).contains(query) ? View.VISIBLE : View.GONE);
        });
        ScrollView scroll = new ScrollView(a); scroll.addView(list);
        form.addView(scroll, new LinearLayout.LayoutParams(-1, Math.round(a.getResources().getDisplayMetrics().heightPixels * 0.5f)));
        new AlertDialog.Builder(a).setTitle(AppCopy.DIALOG_BLOCKED_TITLE).setView(form)
                .setPositiveButton(AppCopy.SAVE, (d, w) -> saveBlocked(new ArrayList<>(chosen)))
                .setNegativeButton(AppCopy.CANCEL, null).show();
    }

    // ---- Shared ----

    private void sharedSection(LinearLayout box) {
        TextView desc = k.body(box, AppCopy.SHARED_DESC); desc.setPadding(k.dp(4), k.dp(14), k.dp(4), 0);
        if (unavailable(box, SHARED) || !loaded[SHARED]) return;
        if (inbox.isEmpty()) { k.empty(box, AppCopy.EMPTY_SHARED); return; }
        for (Map<String, Object> item : inbox) {
            LinearLayout card = k.card(box);
            String subject = DaemonClient.str(item, "subject"), text = DaemonClient.str(item, "text");
            if (!subject.isEmpty()) k.title(card, subject);
            if (!text.isEmpty()) {
                TextView t = k.text(card, text, 14, subject.isEmpty() ? k.ink : k.muted, false);
                t.setMaxLines(4); t.setEllipsize(android.text.TextUtils.TruncateAt.END); t.setTextIsSelectable(true);
                if (!subject.isEmpty()) t.setPadding(0, k.dp(4), 0, 0);
            }
            for (Map<String, Object> file : DaemonClient.rows(item, "files")) {
                LinearLayout row = k.row(); row.setPadding(0, k.dp(8), 0, 0);
                k.pill(row, DaemonClient.str(file, "name"), k.ink, k.dark ? k.stroke : 0xFFEEF1F4);
                card.addView(row);
            }
            List<String> meta = new ArrayList<>();
            String source = DaemonClient.str(item, "sourceApp");
            if (!source.isEmpty()) meta.add(a.appLabel(source));
            long time = DaemonClient.num(item, "receivedAt");
            if (time > 0) meta.add(DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString());
            if (!meta.isEmpty()) { TextView m = k.body(card, String.join("  ·  ", meta)); m.setTextSize(12); m.setPadding(0, k.dp(10), 0, 0); }
        }
    }

    private void toast(String message) { if (a.active) Toast.makeText(a, message, Toast.LENGTH_SHORT).show(); }
}
