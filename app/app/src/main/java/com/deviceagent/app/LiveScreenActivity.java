package com.deviceagent.app;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.deviceagent.tools.Json;
import java.util.List;
import java.util.Map;

/**
 * Full-width edge-to-edge live viewer and instant takeover screen for a backstage virtual display.
 * Touching the screen or tapping Take over immediately locks out the agent until the user taps Hand back.
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

    private static final long FRAME_POLL_MS = 110L;
    private static final long SESSION_POLL_MS = 2200L;

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

    private boolean active, frameInFlight, sessionInFlight, typeVisible;
    private long lastFrameSeq = -1L;
    private int sourceWidth = 1080, sourceHeight = 2400;

    private TextView titleView, statusPill, emptyNote, confirmTitleView, confirmSummaryView;
    private View statusDot;
    private LinearLayout confirmBanner, confirmQuickActions, typeBar, takeoverToolsRow, bottomActionRow;
    private EditText typeInput;
    private LiveCanvasView canvasView;

    private final Runnable frameLoop = new Runnable() {
        @Override public void run() {
            if (!active) return;
            fetchFrame();
            ui.postDelayed(this, FRAME_POLL_MS);
        }
    };

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
        syncChrome();
        if (autoTakeover && !sessionId.isEmpty()) {
            engageTakeover(true, false);
        }
    }

    private void buildUi() {
        getWindow().setStatusBarColor(k.canvas);
        getWindow().setNavigationBarColor(k.elevated);

        LinearLayout root = k.column();
        root.setBackgroundColor(k.canvas);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            boolean imeUp = insets.isVisible(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, imeUp ? ime.bottom : bars.bottom);
            return insets;
        });

        // ---- Top bar ----
        LinearLayout top = k.row();
        top.setBackgroundColor(k.elevated);
        top.setPadding(k.dp(8), k.dp(8), k.dp(10), k.dp(8));

        Button backBtn = k.buttonView("←", UiKit.SECONDARY);
        backBtn.setContentDescription(AppCopy.BACK);
        backBtn.setTextSize(17);
        backBtn.setPadding(k.dp(12), 0, k.dp(12), 0);
        backBtn.setMinHeight(k.dp(38)); backBtn.setMinimumHeight(k.dp(38));
        backBtn.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, k.dp(38));
        bp.setMarginEnd(k.dp(10));
        top.addView(backBtn, bp);

        LinearLayout titleCol = k.column();
        titleView = k.text(titleCol, AppCopy.LABEL_PRIVATE_SCREEN, 15, k.ink, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);

        LinearLayout statusRow = k.row();
        statusRow.setPadding(0, k.dp(2), 0, 0);
        statusDot = k.dot(statusRow, k.green, 7);
        statusPill = k.text(null, AppCopy.LABEL_AGENT_ACTIVE, 12, k.green, true);
        statusRow.addView(statusPill, new LinearLayout.LayoutParams(-2, -2));
        titleCol.addView(statusRow);
        top.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1));

        Button stopBtn = k.buttonView(AppCopy.ACTION_STOP, UiKit.DANGER);
        stopBtn.setTextSize(13);
        stopBtn.setMinHeight(k.dp(36)); stopBtn.setMinimumHeight(k.dp(36));
        stopBtn.setPadding(k.dp(12), 0, k.dp(12), 0);
        stopBtn.setOnClickListener(v -> stopSession());
        top.addView(stopBtn, new LinearLayout.LayoutParams(-2, k.dp(36)));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        View topLine = new View(this);
        topLine.setBackgroundColor(k.stroke);
        root.addView(topLine, new LinearLayout.LayoutParams(-1, Math.max(1, k.dp(1))));

        // ---- Pending confirmation / handoff banner ----
        confirmBanner = k.column();
        confirmBanner.setPadding(k.dp(14), k.dp(10), k.dp(14), k.dp(10));
        confirmBanner.setBackgroundColor(k.card);
        confirmTitleView = k.text(confirmBanner, "", 15, k.ink, true);
        confirmSummaryView = k.text(confirmBanner, "", 13, k.muted, false);
        confirmSummaryView.setPadding(0, k.dp(2), 0, 0);
        confirmQuickActions = k.row();
        confirmQuickActions.setPadding(0, k.dp(10), 0, 0);
        confirmBanner.addView(confirmQuickActions, new LinearLayout.LayoutParams(-1, -2));
        confirmBanner.setVisibility(View.GONE);
        root.addView(confirmBanner, new LinearLayout.LayoutParams(-1, -2));

        // ---- Full-width live screen stage ----
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        canvasView = new LiveCanvasView(this);
        stage.addView(canvasView, new FrameLayout.LayoutParams(-1, -1));

        emptyNote = k.text(null, AppCopy.PREVIEW_UNAVAILABLE, 14, k.muted, false);
        emptyNote.setGravity(Gravity.CENTER);
        emptyNote.setPadding(k.dp(24), k.dp(24), k.dp(24), k.dp(24));
        stage.addView(emptyNote, new FrameLayout.LayoutParams(-1, -1));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- Collapsible keyboard / typing bar ----
        typeBar = k.row();
        typeBar.setBackgroundColor(k.elevated);
        typeBar.setPadding(k.dp(10), k.dp(8), k.dp(10), k.dp(8));
        typeBar.setVisibility(View.GONE);

        typeInput = new EditText(this);
        typeInput.setHint(AppCopy.TYPE_HINT);
        typeInput.setHintTextColor(k.dim);
        typeInput.setTextColor(k.ink);
        typeInput.setTextSize(15);
        typeInput.setSingleLine(true);
        typeInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        typeInput.setPadding(k.dp(12), k.dp(9), k.dp(12), k.dp(9));
        typeInput.setBackground(k.shape(k.raised, 10, k.strokeStrong));
        typeInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                sendTypedText();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
        ip.setMarginEnd(k.dp(6));
        typeBar.addView(typeInput, ip);

        Button sendBtn = smallBtn(AppCopy.ACTION_SEND, UiKit.PRIMARY, this::sendTypedText);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, k.dp(40));
        sp.setMarginEnd(k.dp(6));
        typeBar.addView(sendBtn, sp);

        Button enterBtn = smallBtn(AppCopy.ACTION_ENTER, UiKit.SECONDARY, () -> sendKey("ENTER"));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, k.dp(40));
        ep.setMarginEnd(k.dp(6));
        typeBar.addView(enterBtn, ep);

        Button delBtn = smallBtn(AppCopy.ACTION_DELETE_KEY, UiKit.SECONDARY, () -> sendKey("DEL"));
        typeBar.addView(delBtn, new LinearLayout.LayoutParams(-2, k.dp(40)));
        root.addView(typeBar, new LinearLayout.LayoutParams(-1, -2));

        // ---- Bottom controls ----
        View botLine = new View(this);
        botLine.setBackgroundColor(k.stroke);
        root.addView(botLine, new LinearLayout.LayoutParams(-1, Math.max(1, k.dp(1))));

        LinearLayout footer = k.column();
        footer.setBackgroundColor(k.elevated);
        footer.setPadding(k.dp(12), k.dp(8), k.dp(12), k.dp(10));

        takeoverToolsRow = k.row();
        takeoverToolsRow.setPadding(0, 0, 0, k.dp(8));
        takeoverToolsRow.setVisibility(View.GONE);
        k.rowButton(takeoverToolsRow, AppCopy.BACK, UiKit.SECONDARY, () -> sendKey("BACK"));
        k.rowButton(takeoverToolsRow, AppCopy.ACTION_TYPE, UiKit.SECONDARY, this::toggleTypeBar);
        k.rowButton(takeoverToolsRow, AppCopy.ACTION_MAIN_SCREEN, UiKit.SECONDARY, this::moveToMainScreen);
        footer.addView(takeoverToolsRow, new LinearLayout.LayoutParams(-1, -2));

        bottomActionRow = k.row();
        footer.addView(bottomActionRow, new LinearLayout.LayoutParams(-1, -2));
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        root.requestApplyInsets();
    }

    private Button smallBtn(String label, int kind, Runnable action) {
        Button b = k.buttonView(label, kind);
        b.setTextSize(13);
        b.setMinHeight(k.dp(40)); b.setMinimumHeight(k.dp(40));
        b.setPadding(k.dp(12), 0, k.dp(12), 0);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void syncChrome() {
        String label = !appLabel.isEmpty() ? appLabel : (!packageName.isEmpty() ? resolveLabel(packageName) : AppCopy.LABEL_PRIVATE_SCREEN);
        titleView.setText(label);

        if (userInControl) {
            k.setDot(statusDot, k.amber);
            statusPill.setText(AppCopy.LABEL_YOU_IN_CONTROL);
            statusPill.setTextColor(k.amber);
        } else {
            k.setDot(statusDot, k.green);
            statusPill.setText(AppCopy.LABEL_AGENT_ACTIVE);
            statusPill.setTextColor(k.green);
        }
        canvasView.setBorderColor(userInControl ? k.amber : 0);

        boolean hasConfirm = confirmId != null && !confirmId.isEmpty();
        confirmBanner.setVisibility(hasConfirm ? View.VISIBLE : View.GONE);
        if (hasConfirm) {
            confirmBanner.setBackgroundColor(userInControl ? k.amberSoft : k.card);
            confirmTitleView.setText(confirmTitle.isEmpty() ? AppCopy.SECTION_CONFIRMATIONS : confirmTitle);
            confirmSummaryView.setText(confirmSummary);
            confirmSummaryView.setVisibility(confirmSummary.isEmpty() ? View.GONE : View.VISIBLE);
            confirmQuickActions.removeAllViews();
            if (!userInControl && !"handoff".equals(confirmKind)) {
                confirmQuickActions.setVisibility(View.VISIBLE);
                k.rowButton(confirmQuickActions, AppCopy.ACTION_APPROVE, UiKit.PRIMARY, () -> decideConfirm("approved"));
                k.rowButton(confirmQuickActions, AppCopy.ACTION_TAKE_OVER, UiKit.SECONDARY, () -> engageTakeover(true, true));
                k.rowButton(confirmQuickActions, AppCopy.CANCEL, UiKit.SECONDARY, () -> decideConfirm("cancelled"));
            } else {
                confirmQuickActions.setVisibility(View.GONE);
            }
        }

        takeoverToolsRow.setVisibility(userInControl ? View.VISIBLE : View.GONE);
        typeBar.setVisibility(userInControl && typeVisible ? View.VISIBLE : View.GONE);

        bottomActionRow.removeAllViews();
        if (userInControl) {
            if (hasConfirm) {
                k.rowButton(bottomActionRow, AppCopy.ACTION_DONE_HAND_BACK, UiKit.PRIMARY, () -> decideConfirm("completed"));
                k.rowButton(bottomActionRow, AppCopy.CANCEL, UiKit.SECONDARY, () -> decideConfirm("cancelled"));
            } else {
                k.rowButton(bottomActionRow, AppCopy.ACTION_HAND_BACK, UiKit.PRIMARY, () -> engageTakeover(false, true));
            }
        } else {
            k.rowButton(bottomActionRow, AppCopy.ACTION_TAKE_OVER, UiKit.PRIMARY, () -> engageTakeover(true, true));
            k.rowButton(bottomActionRow, AppCopy.ACTION_MAIN_SCREEN, UiKit.SECONDARY, this::moveToMainScreen);
        }
    }

    // ---- Takeover, confirmation & input ----

    private void engageTakeover(boolean takeOver, boolean showToast) {
        if (sessionId.isEmpty()) return;
        userInControl = takeOver;
        if (!takeOver) hideTypeBar();
        syncChrome();
        Map<String, Object> payload = Json.mapOf("sessionId", sessionId, "active", takeOver);
        if (takeOver && !confirmId.isEmpty()) payload.put("confirmId", confirmId);
        daemon.postOrdered("/session/takeover", payload, (reply, err) -> {
            if (err != null || !Boolean.TRUE.equals(reply.get("ok"))) {
                if (showToast) toast(AppCopy.TAKEOVER_FAILED);
                return;
            }
            if (showToast) toast(takeOver ? AppCopy.TAKEOVER_ON : AppCopy.TAKEOVER_OFF);
        });
    }

    private void ensureTakeoverBeforeInput() {
        if (!userInControl && !sessionId.isEmpty()) {
            engageTakeover(true, false);
        }
    }

    private void sendInput(Map<String, Object> body) {
        if (displayId <= 0 && sessionId.isEmpty()) return;
        ensureTakeoverBeforeInput();
        Map<String, Object> req = new java.util.LinkedHashMap<>(body);
        if (!sessionId.isEmpty()) req.put("sessionId", sessionId);
        if (displayId > 0) req.put("displayId", displayId);
        daemon.postOrdered("/session/input", req, (reply, err) -> {
            if (active) ui.postDelayed(this::fetchFrame, 60L);
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
        typeVisible = !typeVisible;
        syncChrome();
        if (typeVisible) {
            typeInput.requestFocus();
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(typeInput, InputMethodManager.SHOW_IMPLICIT);
        } else {
            hideKeyboard();
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
            hideTypeBar();
        }
        syncChrome();
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

    // ---- Fast live preview stream & session sync ----

    private void fetchFrame() {
        if (!active || displayId <= 0 || frameInFlight) return;
        frameInFlight = true;
        StringBuilder path = new StringBuilder("/preview?displayId=").append(displayId).append("&scale=0.65&quality=72");
        if (lastFrameSeq > 0) path.append("&sinceFrame=").append(lastFrameSeq);
        daemon.bytes(path.toString(), (shot, err) -> {
            frameInFlight = false;
            if (!active || shot == null) return;
            if (shot.sourceWidth > 0 && shot.sourceHeight > 0) {
                sourceWidth = shot.sourceWidth;
                sourceHeight = shot.sourceHeight;
            }
            if (shot.status == 304) return;
            if (shot.status == 403) {
                canvasView.setFrame(null);
                emptyNote.setText(AppCopy.PREVIEW_HIDDEN_BLOCKED);
                emptyNote.setVisibility(View.VISIBLE);
                return;
            }
            if (shot.status == 200 && shot.bitmap != null) {
                if (shot.frameSeq > 0) lastFrameSeq = shot.frameSeq;
                canvasView.setFrame(shot.bitmap);
                emptyNote.setVisibility(View.GONE);
            }
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
            userInControl = Boolean.TRUE.equals(match.get("userInControl"));
            syncChrome();
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
                syncChrome();
            } else if (!confirmId.isEmpty()) {
                confirmId = "";
                confirmTitle = "";
                confirmSummary = "";
                syncChrome();
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

    // ---- Interactive full-width canvas ----

    private final class LiveCanvasView extends View {
        private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint touchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF dest = new RectF();
        private final float touchSlop;
        private Bitmap frame;
        private int borderColor;
        private float downX, downY, touchX, touchY;
        private long downAt, showTouchUntil;
        private boolean tracking;

        LiveCanvasView(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(k.dp(2.5f));
            touchPaint.setStyle(Paint.Style.STROKE);
            touchPaint.setStrokeWidth(k.dp(2.5f));
            touchPaint.setColor(k.amber);
        }

        void setFrame(Bitmap next) {
            frame = next;
            invalidate();
        }

        void setBorderColor(int color) {
            if (borderColor != color) { borderColor = color; invalidate(); }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int vw = getWidth(), vh = getHeight();
            if (vw <= 0 || vh <= 0) return;
            Bitmap bmp = frame;
            float bw = bmp != null ? bmp.getWidth() : sourceWidth;
            float bh = bmp != null ? bmp.getHeight() : sourceHeight;
            if (bw <= 0 || bh <= 0) return;
            // Maximize full horizontal width first; shrink only if taller than viewport.
            float scale = Math.min(vw / bw, vh / bh);
            float dw = bw * scale, dh = bh * scale;
            float left = (vw - dw) * 0.5f, top = (vh - dh) * 0.5f;
            dest.set(left, top, left + dw, top + dh);
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, dest, bmpPaint);
            }
            if (borderColor != 0) {
                borderPaint.setColor(borderColor);
                float inset = borderPaint.getStrokeWidth() * 0.5f;
                canvas.drawRect(dest.left + inset, dest.top + inset, dest.right - inset, dest.bottom - inset, borderPaint);
            }
            if (tracking || SystemClock.uptimeMillis() < showTouchUntil) {
                canvas.drawCircle(touchX, touchY, k.dp(18), touchPaint);
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

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (dest.isEmpty()) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    if (!dest.contains(ev.getX(), ev.getY())) return false;
                    tracking = true;
                    downX = touchX = ev.getX();
                    downY = touchY = ev.getY();
                    downAt = SystemClock.uptimeMillis();
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
                    showTouchUntil = SystemClock.uptimeMillis() + 180L;
                    invalidate();
                    postDelayed(this::invalidate, 200L);
                    float dist = (float) Math.hypot(upX - downX, upY - downY);
                    long elapsed = Math.max(40L, SystemClock.uptimeMillis() - downAt);
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
