package com.deviceagent.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.deviceagent.tools.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Connection status, recovery cards that appear only when needed, confirmations and active screens. */
final class LiveTab extends TabPage {
    private List<Map<String, Object>> sessions = new ArrayList<>();
    private List<Map<String, Object>> confirmations = new ArrayList<>();
    private boolean sessionsLoading, confirmLoading, sessionsLoaded;
    private final Map<String, Bitmap> confirmShots = new HashMap<>();
    /** Live thumbnails keyed by session, updated in place. */
    private final Map<String, ImageView> thumbs = new HashMap<>();
    private final Map<String, TextView> thumbNotes = new HashMap<>();
    private final Map<String, Boolean> previewLoading = new HashMap<>();
    private long lastPreviewAt;
    private TextView statusTitle, statusMeta, statusBody;
    private View statusDot;

    LiveTab(AgentActivity activity, UiKit kit) { super(activity, kit, AppCopy.TAB_LIVE); }

    int activeCount() { return sessions.size(); }

    @Override void onHide() { lastPreviewAt = 0; }

    @Override void render() {
        AgentActivity.Status st = a.status();
        begin();
        put("status", st.signature() + "|" + a.config.accessEnabled + "|" + a.daemonUp + "|" + a.checked + "|" + a.thisDeviceName() + "|" + a.wifiName(), this::statusCard);
        updateStatusText(st);
        boolean needsPairing = a.config.hasAssistant() && a.config.accessEnabled && a.checked && !a.config.paired;
        if (needsPairing) put("pair", "pair|" + a.areNotificationsEnabled() + "|" + (a.livePairPort() > 0), box -> {
            LinearLayout card = k.card(box);
            k.title(card, AppCopy.SETUP_ACCESS_TITLE);
            k.body(card, AppCopy.ACCESS_WIFI_BODY);
            a.setup.phoneAccessSteps(card, false);
        });
        if (needsPairing) a.setup.updateHint();
        if (!a.isIgnoringBatteryOptimizations()) put("battery", "battery", box -> {
            LinearLayout card = k.card(box);
            k.title(card, AppCopy.BATTERY_TITLE); k.body(card, AppCopy.BATTERY_BODY);
            k.button(card, AppCopy.BATTERY_ACTION, UiKit.PRIMARY, a::requestBatteryExemption);
        });
        if (SetupRules.isRiskyUsbConfig(a.usbUnlockedFunctions)) put("usb", "usb", box -> {
            LinearLayout card = k.card(box);
            k.title(card, AppCopy.USB_TITLE); k.body(card, AppCopy.USB_BODY);
            k.button(card, AppCopy.USB_ACTION, UiKit.PRIMARY, a::fixUsbConfig);
        });
        Intent autostart = a.config.bootBlocked && !a.config.oemAutostartAcked ? a.resolveOemAutostartIntent() : null;
        if (autostart != null) put("autostart", "autostart", box -> {
            LinearLayout card = k.card(box);
            k.title(card, AppCopy.AUTOSTART_TITLE); k.body(card, AppCopy.AUTOSTART_BODY);
            LinearLayout row = k.buttonRow(card);
            k.rowButton(row, AppCopy.AUTOSTART_ACTION, UiKit.PRIMARY, () -> a.openAutostart(autostart));
            k.rowButton(row, AppCopy.DISMISS, UiKit.SECONDARY, a::dismissAutostart);
        });
        if (!confirmations.isEmpty()) {
            StringBuilder sig = new StringBuilder("confirm");
            for (Map<String, Object> c : confirmations) sig.append('|').append(DaemonClient.str(c, "confirmId"));
            put("confirm", sig.toString(), this::confirmSection);
        }
        StringBuilder sig = new StringBuilder("screens|" + sessionsLoaded + "|" + a.daemonUp);
        for (Map<String, Object> s : sessions) sig.append('|').append(DaemonClient.str(s, "sessionId")).append(',')
                .append(DaemonClient.str(s, "package")).append(',').append(DaemonClient.str(s, "mode")).append(',')
                .append(DaemonClient.str(s, "recording")).append(',').append(DaemonClient.str(s, "blocked")).append(',')
                .append(DaemonClient.str(s, "display"));
        if (a.daemonUp) put("screens", sig.toString(), this::screensSection);
        end();
    }

    @Override void tick() { render(); poll(); }

    // ---- Status hero ----

    private void statusCard(LinearLayout box) {
        AgentActivity.Status st = a.status();
        LinearLayout card = k.card(box); card.setPadding(k.dp(20), k.dp(18), k.dp(20), k.dp(18));
        LinearLayout row = k.row();
        statusDot = k.dot(row, st.color, 12);
        statusTitle = k.text(null, st.title, 22, k.ink, true);
        statusTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        row.addView(statusTitle, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);
        statusMeta = k.body(card, "");
        statusBody = k.text(card, "", 14, k.ink, false); statusBody.setPadding(0, k.dp(10), 0, 0);
        LinearLayout actions = k.buttonRow(card);
        if (a.config.accessEnabled) {
            k.rowButton(actions, AppCopy.ACTION_PAUSE, UiKit.SECONDARY, guard(a::pauseAccess));
        } else {
            k.rowButton(actions, AppCopy.ACTION_RESUME, UiKit.PRIMARY, guard(a::resumeAccess));
            if (a.daemonPresent || a.healthUnknown) k.rowButton(actions, AppCopy.ACTION_PAUSE, UiKit.SECONDARY, guard(a::pauseAccess));
        }
        if (st.reconnect != null && a.config.accessEnabled) k.rowButton(actions, AppCopy.ACTION_RECONNECT, UiKit.SECONDARY, guard(st.reconnect));
        if (st.fix != null && st.fixLabel != null) k.button(card, st.fixLabel, UiKit.PRIMARY, guard(st.fix));
        if (st.alerts) k.button(card, AppCopy.ACTION_ALLOW_NOTIFICATIONS, UiKit.SECONDARY, a::openNotificationSettings);
    }

    private void updateStatusText(AgentActivity.Status st) {
        if (statusTitle == null) return;
        statusTitle.setText(st.title); k.setDot(statusDot, st.color);
        List<String> meta = new ArrayList<>();
        String name = a.thisDeviceName(); if (!name.isEmpty()) meta.add(name);
        String wifi = a.wifiName(); if (!wifi.isEmpty()) meta.add(wifi);
        if (!sessions.isEmpty()) meta.add(AppCopy.activeScreensLabel(sessions.size()));
        statusMeta.setText(String.join("  ·  ", meta));
        statusMeta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
        statusBody.setText(st.body == null ? "" : st.body);
        statusBody.setVisibility(st.body == null || st.body.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---- Needs confirmation ----

    private void confirmSection(LinearLayout box) {
        k.section(box, AppCopy.SECTION_CONFIRMATIONS);
        for (Map<String, Object> c : confirmations) {
            String id = DaemonClient.str(c, "confirmId");
            LinearLayout card = k.card(box);
            card.setBackground(k.shape(k.card, 20, k.green));
            Bitmap shot = confirmShots.get(id);
            if (shot == null && c.get("screenshotBase64") instanceof String) {
                try {
                    byte[] raw = Base64.decode((String) c.get("screenshotBase64"), Base64.DEFAULT);
                    shot = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                    if (shot != null) confirmShots.put(id, shot);
                } catch (IllegalArgumentException ignored) { }
            }
            if (shot != null) {
                ImageView image = new ImageView(a); image.setImageBitmap(shot);
                image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setBackground(k.shape(k.canvas, 14, k.stroke)); image.setClipToOutline(true);
                image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, k.dp(220)); lp.bottomMargin = k.dp(12);
                card.addView(image, lp);
            }
            k.title(card, DaemonClient.str(c, "title"));
            String summary = DaemonClient.str(c, "summary");
            if (!summary.isEmpty()) k.body(card, summary);
            LinearLayout row = k.buttonRow(card);
            k.rowButton(row, AppCopy.ACTION_APPROVE, UiKit.PRIMARY, () -> decide(id, "approved"));
            k.rowButton(row, AppCopy.ACTION_TAKE_OVER, UiKit.SECONDARY, () -> decide(id, "handoff"));
            k.rowButton(row, AppCopy.CANCEL, UiKit.SECONDARY, () -> decide(id, "cancelled"));
        }
    }

    private void decide(String confirmId, String decision) {
        confirmations.removeIf(c -> confirmId.equals(DaemonClient.str(c, "confirmId")));
        confirmShots.remove(confirmId);
        a.cancelConfirmNotification(confirmId);
        render();
        a.daemon.post("/confirmations/decide", Json.mapOf("confirmId", confirmId, "decision", decision), (r, e) -> { });
    }

    // ---- Active screens ----

    private void screensSection(LinearLayout box) {
        thumbs.clear(); thumbNotes.clear();
        k.section(box, AppCopy.SECTION_ACTIVE_SCREENS);
        if (sessions.isEmpty()) { if (sessionsLoaded) k.empty(box, AppCopy.EMPTY_ACTIVE_SCREENS); return; }
        for (Map<String, Object> s : sessions) {
            String id = DaemonClient.str(s, "sessionId"), pkg = DaemonClient.str(s, "package");
            boolean foreground = "foreground".equals(DaemonClient.str(s, "mode"));
            boolean blocked = Boolean.TRUE.equals(s.get("blocked"));
            int display = (int) DaemonClient.num(s, "display");
            LinearLayout card = k.card(box);
            LinearLayout row = k.row(); row.setGravity(Gravity.TOP);
            if (display > 0) {
                long w = DaemonClient.num(s, "width"), h = DaemonClient.num(s, "height");
                int tw = k.dp(92), th = w > 0 && h > 0 ? (int) Math.min(k.dp(200), tw * h / w) : k.dp(180);
                FrameLayout frame = new FrameLayout(a);
                frame.setBackground(k.shape(k.canvas, 12, k.stroke)); frame.setClipToOutline(true);
                ImageView thumb = new ImageView(a); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                frame.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
                TextView note = k.text(null, blocked ? AppCopy.PREVIEW_HIDDEN_BLOCKED : AppCopy.PREVIEW_UNAVAILABLE, 11, k.muted, false);
                note.setGravity(Gravity.CENTER); note.setPadding(k.dp(6), 0, k.dp(6), 0);
                frame.addView(note, new FrameLayout.LayoutParams(-1, -1));
                LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(tw, th); fp.setMarginEnd(k.dp(14));
                row.addView(frame, fp);
                if (!blocked) { thumbs.put(id, thumb); thumbNotes.put(id, note); }
            }
            LinearLayout info = k.column();
            k.title(info, pkg.isEmpty() ? (foreground ? AppCopy.LABEL_MAIN_SCREEN : AppCopy.LABEL_PRIVATE_SCREEN) : a.appLabel(pkg));
            if (!pkg.isEmpty()) { TextView p = k.body(info, pkg); p.setTextSize(12); p.setPadding(0, k.dp(2), 0, 0); }
            LinearLayout badges = k.row(); badges.setPadding(0, k.dp(8), 0, 0);
            k.pill(badges, foreground ? AppCopy.LABEL_MAIN_SCREEN : AppCopy.LABEL_PRIVATE_SCREEN, k.green, k.greenSoft);
            if (Boolean.TRUE.equals(s.get("recording"))) k.pill(badges, AppCopy.LABEL_RECORDING, k.danger, k.dangerSoft);
            info.addView(badges);
            LinearLayout actions = k.column(); actions.setPadding(0, k.dp(4), 0, 0);
            if (!foreground) k.button(actions, AppCopy.ACTION_TAKE_OVER, UiKit.PRIMARY, () -> handoff(id, display));
            k.button(actions, AppCopy.ACTION_STOP, UiKit.DANGER, () -> stop(id));
            info.addView(actions);
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(row);
        }
        lastPreviewAt = 0;
    }

    private void handoff(String id, int display) {
        Map<String, Object> body = Json.mapOf("sessionId", id);
        if (display > 0) body.put("displayId", display);
        a.daemon.post("/session/handoff", body, (r, e) -> {
            toast(e == null ? AppCopy.HANDOFF_DONE : AppCopy.HANDOFF_FAILED);
            loadSessions();
        });
    }

    private void stop(String id) {
        sessions.removeIf(s -> id.equals(DaemonClient.str(s, "sessionId")));
        render();
        a.daemon.post("/session/stop", Json.mapOf("sessionId", id), (r, e) -> { if (e == null) toast(AppCopy.SESSION_STOPPED); loadSessions(); });
    }

    private void toast(String message) { if (a.active) Toast.makeText(a, message, Toast.LENGTH_SHORT).show(); }

    // ---- Polling (only while this tab is visible; the activity stops ticking in onPause) ----

    private void poll() {
        if (!a.active || !a.daemonUp) {
            if (!sessions.isEmpty() || !confirmations.isEmpty()) { sessions = new ArrayList<>(); confirmations = new ArrayList<>(); sessionsLoaded = false; }
            return;
        }
        loadSessions();
        if (a.pendingConfirmations > 0 || !confirmations.isEmpty() || a.focusConfirm != null) loadConfirmations();
        long now = SystemClock.elapsedRealtime();
        if (now - lastPreviewAt >= 2000) { lastPreviewAt = now; loadPreviews(); }
    }

    private void loadSessions() {
        if (sessionsLoading) return; sessionsLoading = true;
        a.daemon.get("/sessions", (reply, error) -> {
            sessionsLoading = false;
            if (error != null) return;
            sessions = DaemonClient.rows(reply, "sessions"); sessionsLoaded = true;
            if (a.isTabVisible(this)) render();
        });
    }

    private void loadConfirmations() {
        if (confirmLoading) return; confirmLoading = true;
        a.daemon.get("/confirmations", (reply, error) -> {
            confirmLoading = false;
            if (error != null) return;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> c : DaemonClient.rows(reply, "confirmations"))
                if ("pending".equals(DaemonClient.str(c, "decision")) || DaemonClient.str(c, "decision").isEmpty()) rows.add(c);
            confirmations = rows;
            confirmShots.keySet().removeIf(id -> rows.stream().noneMatch(c -> id.equals(DaemonClient.str(c, "confirmId"))));
            a.focusConfirm = null;
            if (a.isTabVisible(this)) render();
        });
    }

    private void loadPreviews() {
        for (Map<String, Object> s : sessions) {
            String id = DaemonClient.str(s, "sessionId");
            int display = (int) DaemonClient.num(s, "display");
            if (display <= 0 || Boolean.TRUE.equals(s.get("blocked")) || !thumbs.containsKey(id)) continue;
            if (Boolean.TRUE.equals(previewLoading.get(id))) continue;
            previewLoading.put(id, true);
            a.daemon.bytes("/preview?displayId=" + display + "&scale=0.45", (shot, error) -> {
                previewLoading.remove(id);
                ImageView thumb = thumbs.get(id); TextView note = thumbNotes.get(id);
                if (thumb == null || note == null || shot == null) return;
                if (shot.status == 403) { thumb.setImageDrawable(null); note.setText(AppCopy.PREVIEW_HIDDEN_BLOCKED); note.setVisibility(View.VISIBLE); return; }
                Bitmap bitmap = shot.status == 200 && shot.body.length > 0 ? BitmapFactory.decodeByteArray(shot.body, 0, shot.body.length) : null;
                if (bitmap == null) { if (thumb.getDrawable() == null) { note.setText(AppCopy.PREVIEW_UNAVAILABLE); note.setVisibility(View.VISIBLE); } return; }
                thumb.setImageBitmap(bitmap); note.setVisibility(View.GONE);
            });
        }
    }
}
