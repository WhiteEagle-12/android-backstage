package com.deviceagent.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
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
    /** Live full-width previews keyed by session, updated in place. */
    private final Map<String, ImageView> thumbs = new HashMap<>();
    private final Map<String, TextView> thumbNotes = new HashMap<>();
    private final Map<String, Boolean> previewLoading = new HashMap<>();
    private final Map<String, Long> previewSeq = new HashMap<>();
    private String activeDeckSessionId = "";
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
        StringBuilder sig = new StringBuilder("screens|" + sessionsLoaded + "|" + a.daemonUp + "|" + activeDeckSessionId);
        for (Map<String, Object> s : sessions) sig.append('|').append(DaemonClient.str(s, "sessionId")).append(',')
                .append(DaemonClient.str(s, "package")).append(',').append(DaemonClient.str(s, "mode")).append(',')
                .append(DaemonClient.str(s, "recording")).append(',').append(DaemonClient.str(s, "blocked")).append(',')
                .append(DaemonClient.str(s, "userInControl")).append(',').append(DaemonClient.str(s, "display"));
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
            String sid = DaemonClient.str(c, "sessionId");
            String rawKind = DaemonClient.str(c, "kind");
            String kind = "handoff".equals(rawKind) || "launch".equals(rawKind) ? rawKind : "confirm";
            String title = DaemonClient.str(c, "title");
            String summary = DaemonClient.str(c, "summary");
            String pkg = DaemonClient.str(c, "package");
            int display = (int) DaemonClient.num(c, "displayId");
            LinearLayout card = k.card(box);
            card.setBackground(k.shape(k.card, 14, "handoff".equals(kind) ? k.amber : k.green));
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
                image.setBackground(k.shape(k.canvas, 10, k.stroke)); image.setClipToOutline(true);
                image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                if (display > 0 && !sid.isEmpty()) {
                    image.setOnClickListener(v -> openLive(sid, display, pkg, false, id, kind, title, summary, true));
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, k.dp(240)); lp.bottomMargin = k.dp(12);
                card.addView(image, lp);
            }
            k.title(card, title);
            if (!summary.isEmpty()) k.body(card, summary);
            LinearLayout row = k.buttonRow(card);
            if ("launch".equals(kind)) {
                k.rowButton(row, AppCopy.ACTION_ALLOW, UiKit.PRIMARY, () -> decide(id, "approved"));
                k.rowButton(row, AppCopy.ACTION_NOT_NOW, UiKit.SECONDARY, () -> decide(id, "cancelled"));
            } else if ("handoff".equals(kind)) {
                k.rowButton(row, AppCopy.ACTION_TAKE_OVER, UiKit.PRIMARY, () -> {
                    if (display > 0 && !sid.isEmpty()) openLive(sid, display, pkg, false, id, kind, title, summary, true);
                    else decide(id, "handoff");
                });
                k.rowButton(row, AppCopy.CANCEL, UiKit.SECONDARY, () -> decide(id, "cancelled"));
            } else {
                k.rowButton(row, AppCopy.ACTION_APPROVE, UiKit.PRIMARY, () -> decide(id, "approved"));
                k.rowButton(row, AppCopy.ACTION_TAKE_OVER, UiKit.SECONDARY, () -> {
                    if (display > 0 && !sid.isEmpty()) openLive(sid, display, pkg, false, id, kind, title, summary, true);
                    else decide(id, "handoff");
                });
                k.rowButton(row, AppCopy.CANCEL, UiKit.SECONDARY, () -> decide(id, "cancelled"));
            }
        }
    }

    private void decide(String confirmId, String decision) {
        confirmations.removeIf(c -> confirmId.equals(DaemonClient.str(c, "confirmId")));
        confirmShots.remove(confirmId);
        a.cancelConfirmNotification(confirmId);
        render();
        a.daemon.post("/confirmations/decide", Json.mapOf("confirmId", confirmId, "decision", decision), (r, e) -> { });
    }

    // ---- Active screens (full width; stacked haptic card deck when 2+ screens run in parallel) ----

    private int resolveDeckIndex() {
        if (sessions.isEmpty()) { activeDeckSessionId = ""; return 0; }
        for (int i = 0; i < sessions.size(); i++) {
            if (activeDeckSessionId.equals(DaemonClient.str(sessions.get(i), "sessionId"))) return i;
        }
        activeDeckSessionId = DaemonClient.str(sessions.get(0), "sessionId");
        return 0;
    }

    private void flipDeck(View hapticSource, int delta) {
        if (sessions.size() < 2) return;
        int idx = resolveDeckIndex();
        int next = (idx + delta + sessions.size()) % sessions.size();
        activeDeckSessionId = DaemonClient.str(sessions.get(next), "sessionId");
        if (hapticSource != null) {
            hapticSource.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        render();
    }

    private void screensSection(LinearLayout box) {
        thumbs.clear(); thumbNotes.clear(); previewSeq.clear();
        if (sessions.isEmpty()) {
            k.section(box, AppCopy.SECTION_ACTIVE_SCREENS);
            if (sessionsLoaded) k.empty(box, AppCopy.EMPTY_ACTIVE_SCREENS);
            return;
        }
        int total = sessions.size();
        int frontIdx = resolveDeckIndex();

        // Header row: clean section title + vector deck arrows when multiple screens are stacked
        LinearLayout header = k.row();
        header.setPadding(k.dp(4), k.dp(20), k.dp(2), k.dp(4));
        TextView secLabel = k.text(null, AppCopy.SECTION_ACTIVE_SCREENS, 13.5f, k.muted, true);
        secLabel.setAccessibilityHeading(true);
        header.addView(secLabel, new LinearLayout.LayoutParams(0, -2, 1));
        if (total > 1) {
            k.pill(header, (frontIdx + 1) + " / " + total, k.green, k.greenSoft);
            FrameLayout prev = k.iconButton(AppIcons.CHEVRON_LEFT, k.ink, k.raised, k.strokeStrong,
                    32, 16, 16, AppCopy.DESC_PREV_SCREEN, () -> flipDeck(header, -1));
            FrameLayout next = k.iconButton(AppIcons.CHEVRON, k.ink, k.raised, k.strokeStrong,
                    32, 16, 16, AppCopy.DESC_NEXT_SCREEN, () -> flipDeck(header, 1));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(k.dp(32), k.dp(32));
            np.setMarginStart(k.dp(6));
            header.addView(prev, new LinearLayout.LayoutParams(k.dp(32), k.dp(32)));
            header.addView(next, np);
        }
        box.addView(header, new LinearLayout.LayoutParams(-1, -2));

        if (total == 1) {
            LinearLayout single = buildSessionCard(sessions.get(0), true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = k.dp(6);
            box.addView(single, lp);
            lastPreviewAt = 0;
            return;
        }

        // Stacked card deck: up to 3 layered cards (back2, back1, front) with tactile swipe + haptics.
        FrameLayout deck = new FrameLayout(a);
        deck.setClipChildren(false);
        deck.setClipToPadding(false);
        int visibleStack = Math.min(3, total);
        int bottomPad = k.dp(12 * (visibleStack - 1) + 4);
        deck.setPadding(0, k.dp(6), 0, bottomPad);

        View[] layers = new View[visibleStack];
        for (int depth = visibleStack - 1; depth >= 0; depth--) {
            int sIdx = (frontIdx + depth) % total;
            boolean isFront = depth == 0;
            LinearLayout card = buildSessionCard(sessions.get(sIdx), isFront);
            float scale = 1f - depth * 0.045f;
            float offsetY = k.dp(14 * depth);
            card.setScaleX(scale);
            card.setScaleY(scale);
            card.setTranslationY(offsetY);
            card.setAlpha(isFront ? 1f : (depth == 1 ? 0.72f : 0.45f));
            card.setElevation(k.dp(8 - depth * 3));
            if (!isFront) {
                card.setClickable(false);
                card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            layers[depth] = card;
            deck.addView(card, new FrameLayout.LayoutParams(-1, -2));
        }

        View frontCard = layers[0];
        View nextCard = visibleStack > 1 ? layers[1] : null;
        attachDeckSwipe(frontCard, nextCard);

        box.addView(deck, new LinearLayout.LayoutParams(-1, -2));
        lastPreviewAt = 0;
    }

    private void attachDeckSwipe(View front, View nextCard) {
        final float slop = ViewConfiguration.get(a).getScaledTouchSlop();
        final float[] down = new float[2];
        final boolean[] dragging = new boolean[1];
        final boolean[] crossed = new boolean[1];
        front.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    down[0] = ev.getRawX();
                    down[1] = ev.getRawY();
                    dragging[0] = false;
                    crossed[0] = false;
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    float dx = ev.getRawX() - down[0];
                    float dy = ev.getRawY() - down[1];
                    if (!dragging[0] && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                        dragging[0] = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (!dragging[0]) return false;
                    v.setTranslationX(dx);
                    v.setRotation(Math.max(-9f, Math.min(9f, dx / 44f)));
                    float w = Math.max(1f, v.getWidth());
                    float progress = Math.min(1f, Math.abs(dx) / (w * 0.28f));
                    if (nextCard != null) {
                        nextCard.setScaleX(0.955f + 0.045f * progress);
                        nextCard.setScaleY(0.955f + 0.045f * progress);
                        nextCard.setTranslationY(k.dp(14) * (1f - progress));
                        nextCard.setAlpha(0.72f + 0.28f * progress);
                    }
                    boolean nowCrossed = progress >= 1f;
                    if (nowCrossed != crossed[0]) {
                        crossed[0] = nowCrossed;
                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging[0]) return false;
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    float dx = ev.getRawX() - down[0];
                    float threshold = Math.max(k.dp(72), v.getWidth() * 0.26f);
                    if (ev.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(dx) >= threshold) {
                        int dir = dx < 0 ? 1 : -1;
                        v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                        float exitX = (dx < 0 ? -1f : 1f) * (v.getWidth() + k.dp(48));
                        v.animate().translationX(exitX).rotation(dx < 0 ? -12f : 12f).alpha(0f)
                                .setDuration(170).setInterpolator(new DecelerateInterpolator())
                                .withEndAction(() -> flipDeck(null, dir)).start();
                    } else {
                        v.animate().translationX(0f).rotation(0f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
                        if (nextCard != null) {
                            nextCard.animate().scaleX(0.955f).scaleY(0.955f).translationY(k.dp(14)).alpha(0.72f).setDuration(180).start();
                        }
                    }
                    dragging[0] = false;
                    return true;
                }
                default -> { return false; }
            }
        });
    }

    private LinearLayout buildSessionCard(Map<String, Object> s, boolean registerPreview) {
        int screenWidth = a.getResources().getDisplayMetrics().widthPixels;
        String id = DaemonClient.str(s, "sessionId"), pkg = DaemonClient.str(s, "package");
        boolean foreground = "foreground".equals(DaemonClient.str(s, "mode"));
        boolean blocked = Boolean.TRUE.equals(s.get("blocked"));
        boolean inControl = Boolean.TRUE.equals(s.get("userInControl"));
        int display = (int) DaemonClient.num(s, "display");
        String label = pkg.isEmpty() ? (foreground ? AppCopy.LABEL_MAIN_SCREEN : AppCopy.LABEL_PRIVATE_SCREEN) : a.appLabel(pkg);

        LinearLayout card = k.card(null);
        card.setPadding(k.dp(14), k.dp(14), k.dp(14), k.dp(14));
        card.setBackground(k.shape(k.card, 14, inControl ? k.amber : k.strokeStrong));

        // Header row: App name + live state pill + compact vector Stop button
        LinearLayout head = k.row();
        LinearLayout titleCol = k.column();
        k.title(titleCol, label);
        head.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1));
        if (inControl) k.pill(head, AppCopy.CONTROL_YOU, k.amber, k.amberSoft);
        else k.pill(head, foreground ? AppCopy.LABEL_MAIN_SCREEN : AppCopy.CONTROL_AGENT, k.green, k.greenSoft);
        if (Boolean.TRUE.equals(s.get("recording"))) k.pill(head, AppCopy.LABEL_RECORDING, k.danger, k.dangerSoft);
        FrameLayout stopIcon = k.iconButton(
                AppIcons.STOP, k.danger, k.dangerSoft, android.graphics.Color.parseColor("#4A1E21"),
                32, 14, 16, AppCopy.ACTION_STOP, () -> stop(id));
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(k.dp(32), k.dp(32));
        stp.setMarginStart(k.dp(2));
        head.addView(stopIcon, stp);
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // Full-width live preview canvas (tap to open edge-to-edge live viewer)
        if (display > 0) {
            long w = DaemonClient.num(s, "width"), h = DaemonClient.num(s, "height");
            int availW = Math.max(k.dp(240), Math.min(screenWidth, k.dp(640)) - k.dp(56));
            int maxH = Math.round(a.getResources().getDisplayMetrics().heightPixels * 0.52f);
            int th = w > 0 && h > 0 ? (int) Math.min(maxH, availW * h / w) : Math.min(maxH, k.dp(400));
            FrameLayout frame = new FrameLayout(a);
            frame.setBackground(k.shape(k.canvas, 12, inControl ? k.amber : k.stroke));
            frame.setClipToOutline(true);
            ImageView thumb = new ImageView(a);
            thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
            thumb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            frame.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
            TextView note = k.text(null, blocked ? AppCopy.PREVIEW_HIDDEN_BLOCKED : AppCopy.PREVIEW_UNAVAILABLE, 13, k.muted, false);
            note.setGravity(Gravity.CENTER); note.setPadding(k.dp(12), 0, k.dp(12), 0);
            frame.addView(note, new FrameLayout.LayoutParams(-1, -1));
            if (!blocked && registerPreview) {
                frame.setClickable(true); frame.setFocusable(true);
                frame.setOnClickListener(v -> openLive(id, display, pkg, inControl, "", "confirm", "", "", false));
                thumbs.put(id, thumb); thumbNotes.put(id, note);
            }
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, th);
            fp.topMargin = k.dp(12);
            card.addView(frame, fp);
        }

        // Clean 2-action control bar aligned with LiveScreenActivity
        if (!foreground && display > 0) {
            LinearLayout actions = k.buttonRow(card);
            if (inControl) {
                k.rowButton(actions, AppCopy.ACTION_OPEN_SCREEN, UiKit.AMBER, () -> openLive(id, display, pkg, true, "", "confirm", "", "", false));
                k.rowButton(actions, AppCopy.ACTION_HAND_BACK, UiKit.PRIMARY, () -> setTakeover(id, false));
            } else {
                k.rowButton(actions, AppCopy.ACTION_OPEN_SCREEN, UiKit.SECONDARY, () -> openLive(id, display, pkg, false, "", "confirm", "", "", false));
                k.rowButton(actions, AppCopy.ACTION_TAKE_CONTROL, UiKit.PRIMARY, () -> openLive(id, display, pkg, false, "", "confirm", "", "", true));
            }
        }
        return card;
    }

    private void openLive(
            String sessionId, int displayId, String pkg, boolean inControl,
            String confirmId, String confirmKind, String confirmTitle, String confirmSummary, boolean autoTakeover) {
        String label = pkg == null || pkg.isEmpty() ? AppCopy.LABEL_PRIVATE_SCREEN : a.appLabel(pkg);
        a.startActivity(LiveScreenActivity.openIntent(
                a, sessionId, displayId, pkg, label, inControl,
                confirmId, confirmKind, confirmTitle, confirmSummary, autoTakeover));
    }

    private void setTakeover(String id, boolean active) {
        a.daemon.postOrdered("/session/takeover", Json.mapOf("sessionId", id, "active", active), (r, e) -> {
            toast(e == null ? (active ? AppCopy.TAKEOVER_ON : AppCopy.TAKEOVER_OFF) : AppCopy.TAKEOVER_FAILED);
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
        if (now - lastPreviewAt >= 500) { lastPreviewAt = now; loadPreviews(); }
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
            long seq = previewSeq.getOrDefault(id, -1L);
            String url = "/preview?displayId=" + display + "&scale=0.55&quality=75&format=jpeg" + (seq > 0 ? "&sinceFrame=" + seq : "");
            a.daemon.bytes(url, (shot, error) -> {
                previewLoading.remove(id);
                ImageView thumb = thumbs.get(id); TextView note = thumbNotes.get(id);
                if (thumb == null || note == null || shot == null) return;
                if (shot.status == 304) return;
                if (shot.status == 403) { thumb.setImageDrawable(null); note.setText(AppCopy.PREVIEW_HIDDEN_BLOCKED); note.setVisibility(View.VISIBLE); return; }
                Bitmap bitmap = shot.status == 200 ? shot.bitmap : null;
                if (bitmap == null) { if (thumb.getDrawable() == null) { note.setText(AppCopy.PREVIEW_UNAVAILABLE); note.setVisibility(View.VISIBLE); } return; }
                if (shot.frameSeq > 0) previewSeq.put(id, shot.frameSeq);
                thumb.setImageBitmap(bitmap); note.setVisibility(View.GONE);
            });
        }
    }
}
