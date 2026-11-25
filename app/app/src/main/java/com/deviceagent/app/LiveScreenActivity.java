package com.deviceagent.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.deviceagent.tools.Json;
import java.util.List;
import java.util.Map;

/**
 * Edge-to-edge live viewer and smooth takeover controller for a backstage virtual display.
 * Touching the screen or sliding the control switcher immediately hands control between Agent and You.
 */
public final class LiveScreenActivity extends Activity {
    static final String EXTRA_SESSION_ID = "sessionId";
    static final String EXTRA_DISPLAY_ID = "displayId";
    static final String EXTRA_PACKAGE = "packageName";
    static final String EXTRA_APP_LABEL = "appLabel";
    static final String EXTRA_USER_IN_CONTROL = "userInControl";
    static final String EXTRA_CONFIRM_ID = "confirmId";
    static final String EXTRA_CONFIRM_KIND = "confirmKind";
    static final String EXTRA_CONFIRM_TITLE = "confirmTitle";
    static final String EXTRA_CONFIRM_SUMMARY = "confirmSummary";
    static final String EXTRA_AUTO_TAKEOVER = "autoTakeover";

    private static final long FRAME_ACTIVE_MS = 32L;
    private static final long FRAME_IDLE_MS = 120L;
    private static final long FRAME_ERROR_MS = 260L;
    private static final long INPUT_BOOST_MS = 1400L;
    private static final long SESSION_POLL_MS = 2200L;
    private static final long CONTROL_OPTIMISTIC_MS = 2000L;

    static Intent openIntent(
            Context ctx, String sessionId, int displayId, String pkg, String label,
            boolean userInControl, String confirmId, String confirmKind,
            String confirmTitle, String confirmSummary, boolean autoTakeover) {
        Intent i = new Intent(ctx, LiveScreenActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (sessionId != null) i.putExtra(EXTRA_SESSION_ID, sessionId);
        i.putExtra(EXTRA_DISPLAY_ID, displayId);
        if (pkg != null) i.putExtra(EXTRA_PACKAGE, pkg);
        if (label != null) i.putExtra(EXTRA_APP_LABEL, label);
        i.putExtra(EXTRA_USER_IN_CONTROL, userInControl);
        if (confirmId != null && !confirmId.isEmpty()) i.putExtra(EXTRA_CONFIRM_ID, confirmId);
        if (confirmKind != null && !confirmKind.isEmpty()) i.putExtra(EXTRA_CONFIRM_KIND, confirmKind);
        if (confirmTitle != null && !confirmTitle.isEmpty()) i.putExtra(EXTRA_CONFIRM_TITLE, confirmTitle);
        if (confirmSummary != null && !confirmSummary.isEmpty()) i.putExtra(EXTRA_CONFIRM_SUMMARY, confirmSummary);
        i.putExtra(EXTRA_AUTO_TAKEOVER, autoTakeover);
        return i;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private UiKit k;
    private DaemonClient daemon;

    private String sessionId = "";
    private int displayId = -1;
    private String packageName = "";
    private String appLabel = "";
    private boolean userInControl;
    private String confirmId = "";
    private String confirmKind = "confirm";
    private String confirmTitle = "";
    private String confirmSummary = "";
    private String lastConfirmSig = "";

    private boolean active, frameInFlight, sessionInFlight, typeVisible;
    private long lastFrameSeq = -1L;
    private long boostUntil, controlOptimisticUntil;
    private int sourceWidth = 1080, sourceHeight = 2400;

    private TextView titleView, subtitleView, emptyNote, confirmTitleView, confirmSummaryView;
    private LinearLayout confirmBanner, confirmQuickActions, typeBar;
    private FrameLayout keyboardBtn;
    private EditText typeInput;
    private LiveCanvasView canvasView;
    private ControlSwitcherView controlSwitcher;

    private final Runnable frameLoop = this::fetchFrame;

    private final Runnable sessionLoop = new Runnable() {
        @Override public void run() {
            if (!active) return;
            syncSessionState();
            ui.postDelayed(this, SESSION_POLL_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        k = new UiKit(this);
        daemon = new DaemonClient(this);
        buildUi();
        applyIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        lastFrameSeq = -1L;
        ui.removeCallbacks(frameLoop);
        ui.removeCallbacks(sessionLoop);
        ui.post(frameLoop);
        ui.post(sessionLoop);
    }

    @Override protected void onPause() {
        active = false;
        ui.removeCallbacks(frameLoop);
        ui.removeCallbacks(sessionLoop);
        super.onPause();
    }

    @Override protected void onDestroy() {
        active = false;
        ui.removeCallbacksAndMessages(null);
        if (daemon != null) daemon.shutdown();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (typeVisible) {
            hideTypeBar();
            syncChrome(true);
            return;
        }
        super.onBackPressed();
    }

    private void applyIntent(Intent intent) {
        if (intent == null) return;
        String sid = intent.getStringExtra(EXTRA_SESSION_ID);
        int disp = intent.getIntExtra(EXTRA_DISPLAY_ID, -1);
        if (sid != null && !sid.isEmpty()) sessionId = sid;
        if (disp > 0) displayId = disp;
        String pkg = intent.getStringExtra(EXTRA_PACKAGE);
        if (pkg != null) packageName = pkg;
        String lbl = intent.getStringExtra(EXTRA_APP_LABEL);
        if (lbl != null && !lbl.isEmpty()) appLabel = lbl;
        else if (!packageName.isEmpty()) appLabel = resolveLabel(packageName);

        String cid = intent.getStringExtra(EXTRA_CONFIRM_ID);
        if (cid != null && !cid.isEmpty()) {
            confirmId = cid;
            String kind = intent.getStringExtra(EXTRA_CONFIRM_KIND);
            confirmKind = "handoff".equals(kind) ? "handoff" : "confirm";
            confirmTitle = intent.getStringExtra(EXTRA_CONFIRM_TITLE);
            if (confirmTitle == null) confirmTitle = "";
            confirmSummary = intent.getStringExtra(EXTRA_CONFIRM_SUMMARY);
            if (confirmSummary == null) confirmSummary = "";
        }
        boolean initialControl = intent.getBooleanExtra(EXTRA_USER_IN_CONTROL, false);
        boolean autoTakeover = intent.getBooleanExtra(EXTRA_AUTO_TAKEOVER, false);
        if (initialControl) userInControl = true;
        lastFrameSeq = -1L;
        syncChrome(false);
        if (autoTakeover && !sessionId.isEmpty()) {
            engageTakeover(true, false);
        }
    }

    private void buildUi() {
        getWindow().setStatusBarColor(k.canvas);
        getWindow().setNavigationBarColor(k.canvas);

        LinearLayout root = k.column();
        root.setBackgroundColor(k.canvas);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            boolean imeUp = insets.isVisible(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, imeUp ? ime.bottom : bars.bottom);
            return insets;
        });

        // ---- Minimal top bar with crisp custom vector icon buttons ----
        LinearLayout top = k.row();
        top.setBackgroundColor(k.canvas);
        top.setPadding(k.dp(12), k.dp(8), k.dp(12), k.dp(8));

        FrameLayout backBtn = k.iconButton(
                AppIcons.ARROW_LEFT, k.ink, k.raised, k.stroke,
                40, 20, 20, AppCopy.BACK, this::finish);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(k.dp(40), k.dp(40));
        bp.setMarginEnd(k.dp(12));
        top.addView(backBtn, bp);

        LinearLayout titleCol = k.column();
        titleView = k.text(titleCol, AppCopy.LABEL_PRIVATE_SCREEN, 16, k.ink, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setLetterSpacing(-0.015f);

        subtitleView = k.text(titleCol, AppCopy.LABEL_PRIVATE_SCREEN, 12, k.dim, false);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1));

        FrameLayout handoffBtn = k.iconButton(
                AppIcons.HANDOFF, k.ink, k.raised, k.stroke,
                38, 19, 19, AppCopy.ACTION_MAIN_SCREEN, this::moveToMainScreen);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(k.dp(38), k.dp(38));
        hp.setMarginStart(k.dp(8));
        top.addView(handoffBtn, hp);

        FrameLayout stopBtn = k.iconButton(
                AppIcons.STOP, k.danger, k.dangerSoft, Color.parseColor("#4A1E21"),
                38, 17, 19, AppCopy.ACTION_STOP, this::stopSession);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(k.dp(38), k.dp(38));
        sp.setMarginStart(k.dp(8));
        top.addView(stopBtn, sp);

        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // ---- Pending confirmation / handoff floating card ----
        confirmBanner = k.column();
        confirmBanner.setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12));
        confirmBanner.setBackground(k.shape(k.card, 14, k.strokeStrong));
        confirmTitleView = k.text(confirmBanner, "", 15, k.ink, true);
        confirmSummaryView = k.text(confirmBanner, "", 13, k.muted, false);
        confirmSummaryView.setPadding(0, k.dp(3), 0, 0);
        confirmQuickActions = k.row();
        confirmQuickActions.setPadding(0, k.dp(10), 0, 0);
        confirmBanner.addView(confirmQuickActions, new LinearLayout.LayoutParams(-1, -2));
        confirmBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(-1, -2);
        cbp.setMargins(k.dp(12), 0, k.dp(12), k.dp(6));
        root.addView(confirmBanner, cbp);

        // ---- Rounded edge-to-edge live screen stage ----
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(k.canvas);
        canvasView = new LiveCanvasView(this);
        stage.addView(canvasView, new FrameLayout.LayoutParams(-1, -1));

        emptyNote = k.text(null, AppCopy.PREVIEW_UNAVAILABLE, 14, k.muted, false);
        emptyNote.setGravity(Gravity.CENTER);
        emptyNote.setPadding(k.dp(24), k.dp(24), k.dp(24), k.dp(24));
        stage.addView(emptyNote, new FrameLayout.LayoutParams(-1, -1));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- Collapsible typing bar (only shown when keyboard icon is active) ----
        typeBar = k.row();
        typeBar.setBackgroundColor(k.elevated);
        typeBar.setPadding(k.dp(12), k.dp(8), k.dp(12), k.dp(8));
        typeBar.setVisibility(View.GONE);

        typeInput = new EditText(this);
        typeInput.setHint(AppCopy.TYPE_HINT);
        typeInput.setHintTextColor(k.dim);
        typeInput.setTextColor(k.ink);
        typeInput.setTextSize(15);
        typeInput.setSingleLine(true);
        typeInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        typeInput.setPadding(k.dp(14), k.dp(9), k.dp(14), k.dp(9));
        typeInput.setBackground(k.shape(k.raised, 20, k.strokeStrong));
        typeInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                sendTypedText();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, k.dp(40), 1);
        ip.setMarginEnd(k.dp(8));
        typeBar.addView(typeInput, ip);

        FrameLayout sendBtn = k.iconButton(
                AppIcons.SEND, k.greenInk, k.green, 0,
                40, 19, 20, AppCopy.ACTION_SEND, this::sendTypedText);
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(k.dp(40), k.dp(40));
        sbp.setMarginEnd(k.dp(6));
        typeBar.addView(sendBtn, sbp);

        FrameLayout enterBtn = k.iconButton(
                AppIcons.RETURN_KEY, k.ink, k.raised, k.strokeStrong,
                40, 18, 20, AppCopy.ACTION_ENTER, () -> sendKey("ENTER"));
        LinearLayout.LayoutParams ebp = new LinearLayout.LayoutParams(k.dp(40), k.dp(40));
        ebp.setMarginEnd(k.dp(6));
        typeBar.addView(enterBtn, ebp);

        FrameLayout delBtn = k.iconButton(
                AppIcons.BACKSPACE, k.ink, k.raised, k.strokeStrong,
                40, 19, 20, AppCopy.ACTION_DELETE_KEY, () -> sendKey("DEL"));
        typeBar.addView(delBtn, new LinearLayout.LayoutParams(k.dp(40), k.dp(40)));
        root.addView(typeBar, new LinearLayout.LayoutParams(-1, -2));

        // ---- Fixed-height bottom control dock (zero jumps, zero flicker) ----
        LinearLayout dock = k.row();
        dock.setBackgroundColor(k.canvas);
        dock.setPadding(k.dp(12), k.dp(10), k.dp(12), k.dp(12));

        controlSwitcher = new ControlSwitcherView(this);
        LinearLayout.LayoutParams csp = new LinearLayout.LayoutParams(0, k.dp(46), 1);
        csp.setMarginEnd(k.dp(10));
        dock.addView(controlSwitcher, csp);

        FrameLayout navBackBtn = k.iconButton(
                AppIcons.CHEVRON_LEFT, k.ink, k.raised, k.strokeStrong,
                46, 21, 23, AppCopy.DESC_VIRTUAL_BACK, () -> {
                    dock.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    sendKey("BACK");
                });
        LinearLayout.LayoutParams nbp = new LinearLayout.LayoutParams(k.dp(46), k.dp(46));
        nbp.setMarginEnd(k.dp(8));
        dock.addView(navBackBtn, nbp);

        keyboardBtn = k.iconButton(
                AppIcons.KEYBOARD, k.ink, k.raised, k.strokeStrong,
                46, 20, 23, AppCopy.DESC_KEYBOARD, this::toggleTypeBar);
        dock.addView(keyboardBtn, new LinearLayout.LayoutParams(k.dp(46), k.dp(46)));

        root.addView(dock, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        root.requestApplyInsets();
    }

    private void syncChrome(boolean animate) {
        String label = !appLabel.isEmpty() ? appLabel : (!packageName.isEmpty() ? resolveLabel(packageName) : AppCopy.LABEL_PRIVATE_SCREEN);
        titleView.setText(label);

        if (userInControl) {
            subtitleView.setText(AppCopy.LABEL_PAUSED_FOR_YOU);
            subtitleView.setTextColor(k.amber);
        } else {
            subtitleView.setText(AppCopy.LABEL_PRIVATE_SCREEN);
            subtitleView.setTextColor(k.dim);
        }

        controlSwitcher.setUserInControl(userInControl, animate);
        canvasView.setActiveControl(userInControl, animate);

        boolean showType = userInControl && typeVisible;
        typeBar.setVisibility(showType ? View.VISIBLE : View.GONE);
        if (showType) {
            k.styleIconButton(keyboardBtn, k.amber, k.amberSoft, k.amber, 23);
        } else {
            k.styleIconButton(keyboardBtn, k.ink, k.raised, k.strokeStrong, 23);
        }

        boolean hasConfirm = confirmId != null && !confirmId.isEmpty();
        confirmBanner.setVisibility(hasConfirm ? View.VISIBLE : View.GONE);
        if (hasConfirm) {
            String sig = confirmId + "|" + confirmKind + "|" + userInControl + "|" + confirmTitle + "|" + confirmSummary;
            if (!sig.equals(lastConfirmSig)) {
                lastConfirmSig = sig;
                confirmBanner.setBackground(k.shape(userInControl ? k.amberSoft : k.card, 14, userInControl ? k.amber : k.green));
                confirmTitleView.setText(confirmTitle.isEmpty() ? AppCopy.SECTION_CONFIRMATIONS : confirmTitle);
                confirmSummaryView.setText(confirmSummary);
                confirmSummaryView.setVisibility(confirmSummary.isEmpty() ? View.GONE : View.VISIBLE);
                confirmQuickActions.removeAllViews();
                confirmQuickActions.setVisibility(View.VISIBLE);
                if (userInControl) {
                    k.rowButton(confirmQuickActions, AppCopy.ACTION_DONE_HAND_BACK, UiKit.PRIMARY, () -> decideConfirm("completed"));
                    k.rowButton(confirmQuickActions, AppCopy.CANCEL, UiKit.SECONDARY, () -> decideConfirm("cancelled"));
                } else if ("handoff".equals(confirmKind)) {
                    k.rowButton(confirmQuickActions, AppCopy.ACTION_TAKE_CONTROL, UiKit.AMBER, () -> engageTakeover(true, true));
                    k.rowButton(confirmQuickActions, AppCopy.CANCEL, UiKit.SECONDARY, () -> decideConfirm("cancelled"));
                } else {
                    k.rowButton(confirmQuickActions, AppCopy.ACTION_APPROVE, UiKit.PRIMARY, () -> decideConfirm("approved"));
                    k.rowButton(confirmQuickActions, AppCopy.ACTION_TAKE_CONTROL, UiKit.SECONDARY, () -> engageTakeover(true, true));
                    k.rowButton(confirmQuickActions, AppCopy.CANCEL, UiKit.SECONDARY, () -> decideConfirm("cancelled"));
                }
            }
        } else {
            lastConfirmSig = "";
        }
    }

    // ---- Takeover, confirmation & input ----

    private void engageTakeover(boolean takeOver, boolean haptic) {
        if (sessionId.isEmpty()) return;
        boolean changed = userInControl != takeOver;
        userInControl = takeOver;
        controlOptimisticUntil = SystemClock.uptimeMillis() + CONTROL_OPTIMISTIC_MS;
        if (!takeOver) hideTypeBar();
        if (haptic && changed && controlSwitcher != null) {
            controlSwitcher.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        syncChrome(true);
        boostFrames();
        Map<String, Object> payload = Json.mapOf("sessionId", sessionId, "active", takeOver);
        if (takeOver && !confirmId.isEmpty()) payload.put("confirmId", confirmId);
        daemon.postOrdered("/session/takeover", payload, (reply, err) -> {
            if (err != null || !Boolean.TRUE.equals(reply.get("ok"))) {
                toast(AppCopy.TAKEOVER_FAILED);
            }
        });
    }

    private void ensureTakeoverBeforeInput() {
        if (!userInControl && !sessionId.isEmpty()) {
            engageTakeover(true, true);
        }
    }

    private void sendInput(Map<String, Object> body) {
        if (displayId <= 0 && sessionId.isEmpty()) return;
        ensureTakeoverBeforeInput();
        boostFrames();
        Map<String, Object> req = new java.util.LinkedHashMap<>(body);
        if (!sessionId.isEmpty()) req.put("sessionId", sessionId);
        if (displayId > 0) req.put("displayId", displayId);
        daemon.postOrdered("/session/input", req, (reply, err) -> {
            if (active) {
                boostFrames();
                scheduleNextFrame(24L);
            }
        });
    }

    private void sendKey(String key) {
        sendInput(Json.mapOf("action", "key", "key", key));
    }

    private void sendTypedText() {
        String text = typeInput.getText() == null ? "" : typeInput.getText().toString();
        if (text.isEmpty()) return;
        typeInput.setText("");
        sendInput(Json.mapOf("action", "text", "text", text));
    }

    private void toggleTypeBar() {
        if (!typeVisible) {
            ensureTakeoverBeforeInput();
            typeVisible = true;
            syncChrome(true);
            typeInput.requestFocus();
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(typeInput, InputMethodManager.SHOW_IMPLICIT);
        } else {
            hideTypeBar();
            syncChrome(true);
        }
    }

    private void hideTypeBar() {
        typeVisible = false;
        hideKeyboard();
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null && typeInput != null) imm.hideSoftInputFromWindow(typeInput.getWindowToken(), 0);
    }

    private void decideConfirm(String decision) {
        String id = confirmId;
        if (id.isEmpty()) return;
        confirmId = "";
        confirmTitle = "";
        confirmSummary = "";
        cancelConfirmNotification(id);
        if ("completed".equals(decision) || "cancelled".equals(decision) || "approved".equals(decision)) {
            userInControl = false;
            controlOptimisticUntil = SystemClock.uptimeMillis() + CONTROL_OPTIMISTIC_MS;
            hideTypeBar();
        }
        syncChrome(true);
        daemon.postOrdered("/confirmations/decide", Json.mapOf("confirmId", id, "decision", decision), (reply, err) -> {
            if ("completed".equals(decision)) toast(AppCopy.TAKEOVER_OFF);
        });
    }

    private void moveToMainScreen() {
        String cid = confirmId;
        if (!cid.isEmpty()) {
            confirmId = "";
            cancelConfirmNotification(cid);
            daemon.postOrdered("/confirmations/decide", Json.mapOf("confirmId", cid, "decision", "handoff"), (r, e) -> {
                toast(e == null ? AppCopy.HANDOFF_DONE : AppCopy.HANDOFF_FAILED);
                finish();
            });
            return;
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (!sessionId.isEmpty()) body.put("sessionId", sessionId);
        if (displayId > 0) body.put("displayId", displayId);
        daemon.post("/session/handoff", body, (reply, err) -> {
            toast(err == null ? AppCopy.HANDOFF_DONE : AppCopy.HANDOFF_FAILED);
            if (err == null) finish();
        });
    }

    private void stopSession() {
        if (!confirmId.isEmpty()) {
            String cid = confirmId;
            confirmId = "";
            cancelConfirmNotification(cid);
            daemon.postOrdered("/confirmations/decide", Json.mapOf("confirmId", cid, "decision", "cancelled"), (r, e) -> {});
        }
        if (sessionId.isEmpty()) { finish(); return; }
        daemon.post("/session/stop", Json.mapOf("sessionId", sessionId), (r, e) -> {
            toast(AppCopy.SESSION_STOPPED);
            finish();
        });
    }

    private void cancelConfirmNotification(String id) {
        if (id == null || id.isEmpty()) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(id.hashCode());
    }

    // ---- Adaptive high-FPS live preview stream & session sync ----

    private void boostFrames() {
        boostUntil = SystemClock.uptimeMillis() + INPUT_BOOST_MS;
    }

    private void scheduleNextFrame(long delayMs) {
        if (!active) return;
        ui.removeCallbacks(frameLoop);
        ui.postDelayed(frameLoop, delayMs);
    }

    private void fetchFrame() {
        if (!active) return;
        if (displayId <= 0) {
            scheduleNextFrame(FRAME_ERROR_MS);
            return;
        }
        if (frameInFlight) return;
        frameInFlight = true;
        StringBuilder path = new StringBuilder("/preview?displayId=").append(displayId).append("&scale=0.68&quality=76");
        if (lastFrameSeq > 0) path.append("&sinceFrame=").append(lastFrameSeq);
        daemon.bytes(path.toString(), (shot, err) -> {
            frameInFlight = false;
            if (!active) return;
            boolean boosted = SystemClock.uptimeMillis() < boostUntil;
            if (shot == null) {
                scheduleNextFrame(FRAME_ERROR_MS);
                return;
            }
            if (shot.sourceWidth > 0 && shot.sourceHeight > 0) {
                sourceWidth = shot.sourceWidth;
                sourceHeight = shot.sourceHeight;
            }
            if (shot.status == 304) {
                scheduleNextFrame(boosted ? FRAME_ACTIVE_MS : FRAME_IDLE_MS);
                return;
            }
            if (shot.status == 403) {
                canvasView.setFrame(null);
                emptyNote.setText(AppCopy.PREVIEW_HIDDEN_BLOCKED);
                emptyNote.setVisibility(View.VISIBLE);
                scheduleNextFrame(500L);
                return;
            }
            if (shot.status == 200 && shot.bitmap != null) {
                if (shot.frameSeq > 0) lastFrameSeq = shot.frameSeq;
                canvasView.setFrame(shot.bitmap);
                emptyNote.setVisibility(View.GONE);
                scheduleNextFrame(FRAME_ACTIVE_MS);
                return;
            }
            scheduleNextFrame(FRAME_IDLE_MS);
        });
    }

    private void syncSessionState() {
        if (!active || sessionInFlight) return;
        sessionInFlight = true;
        daemon.get("/sessions", (reply, err) -> {
            sessionInFlight = false;
            if (!active || err != null || reply == null) return;
            List<Map<String, Object>> rows = DaemonClient.rows(reply, "sessions");
            Map<String, Object> match = null;
            for (Map<String, Object> s : rows) {
                if (!sessionId.isEmpty() && sessionId.equals(DaemonClient.str(s, "sessionId"))) { match = s; break; }
                if (sessionId.isEmpty() && displayId > 0 && displayId == (int) DaemonClient.num(s, "display")) { match = s; break; }
            }
            if (match == null) {
                toast(AppCopy.SESSION_ENDED);
                finish();
                return;
            }
            sessionId = DaemonClient.str(match, "sessionId");
            int disp = (int) DaemonClient.num(match, "display");
            if (disp > 0) displayId = disp;
            int w = (int) DaemonClient.num(match, "width"), h = (int) DaemonClient.num(match, "height");
            if (w > 0 && h > 0) { sourceWidth = w; sourceHeight = h; }
            String pkg = DaemonClient.str(match, "package");
            if (!pkg.isEmpty() && !pkg.equals(packageName)) {
                packageName = pkg;
                appLabel = resolveLabel(pkg);
            }
            boolean remoteControl = Boolean.TRUE.equals(match.get("userInControl"));
            if (SystemClock.uptimeMillis() >= controlOptimisticUntil) {
                userInControl = remoteControl;
            }
            syncChrome(true);
        });
        daemon.get("/confirmations", (reply, err) -> {
            if (!active || err != null || reply == null) return;
            List<Map<String, Object>> rows = DaemonClient.rows(reply, "confirmations");
            Map<String, Object> found = null;
            for (Map<String, Object> c : rows) {
                String dec = DaemonClient.str(c, "decision");
                if (!dec.isEmpty() && !"pending".equals(dec)) continue;
                if ((!sessionId.isEmpty() && sessionId.equals(DaemonClient.str(c, "sessionId")))
                        || (!confirmId.isEmpty() && confirmId.equals(DaemonClient.str(c, "confirmId")))) {
                    found = c; break;
                }
            }
            if (found != null) {
                confirmId = DaemonClient.str(found, "confirmId");
                confirmKind = "handoff".equals(DaemonClient.str(found, "kind")) ? "handoff" : "confirm";
                confirmTitle = DaemonClient.str(found, "title");
                confirmSummary = DaemonClient.str(found, "summary");
                syncChrome(true);
            } else if (!confirmId.isEmpty()) {
                confirmId = "";
                confirmTitle = "";
                confirmSummary = "";
                syncChrome(true);
            }
        });
    }

    private String resolveLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) { return pkg; }
    }

    private void toast(String message) { if (active) Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private static int blendColor(int from, int to, float t) {
        float c = Math.max(0f, Math.min(1f, t));
        int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * c);
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * c);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * c);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * c);
        return Color.argb(a, r, g, b);
    }

    // ---- Smooth sliding Agent / You segmented control switcher ----

    private final class ControlSwitcherView extends View {
        private final Paint trackFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final RectF thumbRect = new RectF();
        private final AppIcons agentIcon = new AppIcons(AppIcons.AGENT, k.green);
        private final AppIcons userIcon = new AppIcons(AppIcons.USER, k.muted);
        private final float touchSlop;
        private ValueAnimator anim;
        private float thumbPos; // 0f = Agent, 1f = You
        private boolean targetUser, dragging;
        private float downX, startPos;

        ControlSwitcherView(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setClickable(true);
            setFocusable(true);
            trackFill.setStyle(Paint.Style.FILL);
            trackFill.setColor(Color.parseColor("#111111"));
            trackStroke.setStyle(Paint.Style.STROKE);
            trackStroke.setStrokeWidth(Math.max(1f, k.dp(1)));
            trackStroke.setColor(Color.parseColor("#242424"));
            thumbFill.setStyle(Paint.Style.FILL);
            thumbStroke.setStyle(Paint.Style.STROKE);
            thumbStroke.setStrokeWidth(Math.max(1f, k.dp(1)));
            labelPaint.setTextSize(k.dp(14));
            labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            agentIcon.setFilled(true);
            userIcon.setFilled(true);
            setContentDescription(AppCopy.CONTROL_AGENT);
        }

        void setUserInControl(boolean user, boolean animate) {
            targetUser = user;
            setContentDescription(user ? AppCopy.LABEL_IN_CONTROL_SHORT : AppCopy.LABEL_AGENT_ACTIVE);
            float goal = user ? 1f : 0f;
            if (!animate) {
                if (anim != null) anim.cancel();
                thumbPos = goal;
                invalidate();
                return;
            }
            if (Math.abs(thumbPos - goal) < 0.005f) return;
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(thumbPos, goal);
            anim.setDuration(210L);
            anim.setInterpolator(new DecelerateInterpolator(1.6f));
            anim.addUpdateListener(va -> {
                thumbPos = (float) va.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = h * 0.5f;
            float strokeHalf = trackStroke.getStrokeWidth() * 0.5f;
            trackRect.set(strokeHalf, strokeHalf, w - strokeHalf, h - strokeHalf);
            canvas.drawRoundRect(trackRect, r, r, trackFill);
            canvas.drawRoundRect(trackRect, r, r, trackStroke);

            float inset = k.dp(3);
            float halfW = (w - inset * 2f) * 0.5f;
            float thumbLeft = inset + thumbPos * halfW;
            thumbRect.set(thumbLeft, inset, thumbLeft + halfW, h - inset);
            float tr = thumbRect.height() * 0.5f;

            int greenBorder = (k.green & 0x00FFFFFF) | 0x88000000;
            int amberBorder = (k.amber & 0x00FFFFFF) | 0xA8000000;
            thumbFill.setColor(blendColor(k.greenSoft, k.amberSoft, thumbPos));
            thumbStroke.setColor(blendColor(greenBorder, amberBorder, thumbPos));
            canvas.drawRoundRect(thumbRect, tr, tr, thumbFill);
            canvas.drawRoundRect(thumbRect, tr, tr, thumbStroke);

            int agentColor = blendColor(k.green, k.muted, thumbPos);
            int userColor = blendColor(k.muted, k.amber, thumbPos);
            drawSegment(canvas, inset, halfW, h, agentIcon, AppCopy.CONTROL_AGENT, agentColor);
            drawSegment(canvas, inset + halfW, halfW, h, userIcon, AppCopy.CONTROL_YOU, userColor);
        }

        private void drawSegment(Canvas canvas, float segLeft, float segW, float h, AppIcons icon, String text, int color) {
            int iconSize = k.dp(16);
            int gap = k.dp(6);
            labelPaint.setColor(color);
            float textW = labelPaint.measureText(text);
            float totalW = iconSize + gap + textW;
            float startX = segLeft + (segW - totalW) * 0.5f;
            int iconTop = Math.round((h - iconSize) * 0.5f);
            icon.setColor(color);
            icon.setBounds(Math.round(startX), iconTop, Math.round(startX) + iconSize, iconTop + iconSize);
            icon.draw(canvas);
            Paint.FontMetrics fm = labelPaint.getFontMetrics();
            float baseline = h * 0.5f - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(text, startX + iconSize + gap, baseline, labelPaint);
        }

        @Override public boolean performClick() {
            return super.performClick();
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            float w = Math.max(1f, getWidth());
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    if (anim != null) anim.cancel();
                    downX = ev.getX();
                    startPos = thumbPos;
                    dragging = false;
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    float dx = ev.getX() - downX;
                    if (!dragging && Math.abs(dx) > touchSlop * 0.6f) {
                        dragging = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) {
                        float halfW = Math.max(1f, w * 0.5f);
                        thumbPos = Math.max(0f, Math.min(1f, startPos + dx / halfW));
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    getParent().requestDisallowInterceptTouchEvent(false);
                    boolean wantUser;
                    if (dragging) {
                        wantUser = thumbPos >= 0.5f;
                    } else {
                        performClick();
                        wantUser = ev.getX() >= w * 0.5f;
                    }
                    dragging = false;
                    if (wantUser != targetUser) {
                        engageTakeover(wantUser, true);
                    } else {
                        setUserInControl(targetUser, true);
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    getParent().requestDisallowInterceptTouchEvent(false);
                    dragging = false;
                    setUserInControl(targetUser, true);
                    return true;
                }
                default -> { return false; }
            }
        }
    }

    // ---- Smooth rounded interactive live screen canvas ----

    private final class LiveCanvasView extends View {
        private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint touchRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint touchFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF dest = new RectF();
        private final RectF borderRect = new RectF();
        private final Path clipPath = new Path();
        private final float touchSlop, cornerRadius, padX, padY;
        private final int idleBorder = Color.parseColor("#222222");
        private Bitmap frame;
        private ValueAnimator borderAnim;
        private float controlBlend; // 0f = Agent, 1f = You
        private boolean controlTarget;
        private float downX, downY, touchX, touchY;
        private long downAt, releaseAt;
        private boolean tracking;

        LiveCanvasView(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            cornerRadius = k.dp(16);
            padX = k.dp(8);
            padY = k.dp(4);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(k.dp(1.5f));
            touchRingPaint.setStyle(Paint.Style.STROKE);
            touchRingPaint.setStrokeWidth(k.dp(2f));
            touchFillPaint.setStyle(Paint.Style.FILL);
            trailPaint.setStyle(Paint.Style.STROKE);
            trailPaint.setStrokeCap(Paint.Cap.ROUND);
            trailPaint.setStrokeWidth(k.dp(3f));
        }

        void setFrame(Bitmap next) {
            frame = next;
            invalidate();
        }

        void setActiveControl(boolean user, boolean animate) {
            controlTarget = user;
            float goal = user ? 1f : 0f;
            if (!animate) {
                if (borderAnim != null) borderAnim.cancel();
                controlBlend = goal;
                invalidate();
                return;
            }
            if (Math.abs(controlBlend - goal) < 0.005f) return;
            if (borderAnim != null) borderAnim.cancel();
            borderAnim = ValueAnimator.ofFloat(controlBlend, goal);
            borderAnim.setDuration(220L);
            borderAnim.setInterpolator(new DecelerateInterpolator());
            borderAnim.addUpdateListener(va -> {
                controlBlend = (float) va.getAnimatedValue();
                invalidate();
            });
            borderAnim.start();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int vw = getWidth(), vh = getHeight();
            if (vw <= 0 || vh <= 0) return;
            float availW = Math.max(1f, vw - padX * 2f);
            float availH = Math.max(1f, vh - padY * 2f);
            Bitmap bmp = frame;
            float bw = bmp != null ? bmp.getWidth() : sourceWidth;
            float bh = bmp != null ? bmp.getHeight() : sourceHeight;
            if (bw <= 0 || bh <= 0) return;
            float scale = Math.min(availW / bw, availH / bh);
            float dw = bw * scale, dh = bh * scale;
            float left = (vw - dw) * 0.5f, top = (vh - dh) * 0.5f;
            dest.set(left, top, left + dw, top + dh);

            if (bmp != null) {
                clipPath.reset();
                clipPath.addRoundRect(dest, cornerRadius, cornerRadius, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(clipPath);
                canvas.drawBitmap(bmp, null, dest, bmpPaint);
                canvas.restore();
            }

            borderPaint.setColor(blendColor(idleBorder, k.amber, controlBlend));
            float inset = borderPaint.getStrokeWidth() * 0.5f;
            borderRect.set(dest.left + inset, dest.top + inset, dest.right - inset, dest.bottom - inset);
            canvas.drawRoundRect(borderRect, cornerRadius, cornerRadius, borderPaint);

            long now = SystemClock.uptimeMillis();
            if (tracking) {
                float dist = (float) Math.hypot(touchX - downX, touchY - downY);
                if (dist > touchSlop) {
                    trailPaint.setColor((k.amber & 0x00FFFFFF) | 0x55000000);
                    canvas.drawLine(downX, downY, touchX, touchY, trailPaint);
                }
                touchFillPaint.setColor((k.amber & 0x00FFFFFF) | 0x33000000);
                touchRingPaint.setColor((k.amber & 0x00FFFFFF) | 0xCC000000);
                canvas.drawCircle(touchX, touchY, k.dp(15), touchFillPaint);
                canvas.drawCircle(touchX, touchY, k.dp(15), touchRingPaint);
            } else if (releaseAt > 0 && now - releaseAt < 240L) {
                float p = (now - releaseAt) / 240f;
                int alphaRing = Math.round((1f - p) * 204f);
                int alphaFill = Math.round((1f - p) * 52f);
                float radius = k.dp(13) + p * k.dp(14);
                touchFillPaint.setColor((k.amber & 0x00FFFFFF) | (alphaFill << 24));
                touchRingPaint.setColor((k.amber & 0x00FFFFFF) | (alphaRing << 24));
                canvas.drawCircle(touchX, touchY, radius, touchFillPaint);
                canvas.drawCircle(touchX, touchY, radius, touchRingPaint);
                postInvalidateOnAnimation();
            }
        }

        private int mapX(float x) {
            if (dest.width() <= 0) return 0;
            float fx = (x - dest.left) / dest.width();
            return Math.max(0, Math.min(sourceWidth - 1, Math.round(fx * sourceWidth)));
        }

        private int mapY(float y) {
            if (dest.height() <= 0) return 0;
            float fy = (y - dest.top) / dest.height();
            return Math.max(0, Math.min(sourceHeight - 1, Math.round(fy * sourceHeight)));
        }

        @Override public boolean performClick() {
            return super.performClick();
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (dest.isEmpty()) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    if (!dest.contains(ev.getX(), ev.getY())) return false;
                    tracking = true;
                    releaseAt = 0;
                    downX = touchX = ev.getX();
                    downY = touchY = ev.getY();
                    downAt = SystemClock.uptimeMillis();
                    ensureTakeoverBeforeInput();
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return false;
                    touchX = Math.max(dest.left, Math.min(dest.right, ev.getX()));
                    touchY = Math.max(dest.top, Math.min(dest.bottom, ev.getY()));
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    if (!tracking) return false;
                    tracking = false;
                    float upX = Math.max(dest.left, Math.min(dest.right, ev.getX()));
                    float upY = Math.max(dest.top, Math.min(dest.bottom, ev.getY()));
                    touchX = upX; touchY = upY;
                    releaseAt = SystemClock.uptimeMillis();
                    postInvalidateOnAnimation();
                    float dist = (float) Math.hypot(upX - downX, upY - downY);
                    long elapsed = Math.max(40L, releaseAt - downAt);
                    if (dist > touchSlop) {
                        int dur = (int) Math.max(120L, Math.min(600L, elapsed));
                        sendInput(Json.mapOf(
                                "action", "swipe",
                                "x1", mapX(downX), "y1", mapY(downY),
                                "x2", mapX(upX), "y2", mapY(upY),
                                "durationMs", dur));
                    } else if (elapsed >= 450L) {
                        sendInput(Json.mapOf("action", "long_press", "x", mapX(upX), "y", mapY(upY), "durationMs", 650));
                    } else {
                        performClick();
                        sendInput(Json.mapOf("action", "tap", "x", mapX(upX), "y", mapY(upY)));
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    tracking = false;
                    invalidate();
                    return true;
                }
                default -> { return false; }
            }
        }
    }
}
