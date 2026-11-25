package com.deviceagent.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.deviceagent.tools.Json;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Native-speed live viewer and continuous multi-touch controller for a backstage virtual display.
 * Composites the virtual display straight onto a SurfaceView via SurfaceFlinger (zero-copy GPU)
 * and streams raw MotionEvents over a persistent TCP_NODELAY socket into InputManager.
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

    private static final long FALLBACK_FRAME_ACTIVE_MS = 32L;
    private static final long FALLBACK_FRAME_IDLE_MS = 120L;
    private static final long FALLBACK_FRAME_ERROR_MS = 260L;
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
    private final Binder aliveBinder = new Binder();
    private UiKit k;
    private DaemonClient daemon;
    private LiveTouchClient touchClient;

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
    private boolean surfaceReady, surfaceAttached, attachInFlight;
    private String activeSurfaceToken = "";
    private long lastFrameSeq = -1L;
    private long controlOptimisticUntil, lastAttachAttemptAt;
    private int sourceWidth = 1080, sourceHeight = 2400;

    private TextView titleView, subtitleView, emptyNote, confirmTitleView, confirmSummaryView;
    private LinearLayout confirmBanner, confirmQuickActions, typeBar;
    private FrameLayout stage, surfaceFrame, keyboardBtn;
    private SurfaceView surfaceView;
    private ImageView fallbackImage;
    private TouchBorderOverlay touchOverlay;
    private EditText typeInput;
    private ControlSwitcherView controlSwitcher;

    private final Runnable fallbackFrameLoop = this::fetchFallbackFrame;

    private final Runnable sessionLoop = new Runnable() {
        @Override public void run() {
            if (!active) return;
            syncSessionState();
            if (surfaceReady && !surfaceAttached && !attachInFlight && displayId > 0
                    && SystemClock.uptimeMillis() - lastAttachAttemptAt > 2500L) {
                tryAttachLiveSurface();
            }
            ui.postDelayed(this, SESSION_POLL_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        k = new UiKit(this);
        daemon = new DaemonClient(this);
        touchClient = new LiveTouchClient();
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
        ui.removeCallbacks(fallbackFrameLoop);
        ui.removeCallbacks(sessionLoop);
        ui.post(sessionLoop);
        if (displayId > 0) {
            touchClient.start(displayId, sessionId);
            if (surfaceReady && !surfaceAttached) {
                tryAttachLiveSurface();
            } else if (!surfaceAttached) {
                scheduleFallbackFrame(0L);
            }
        }
    }

    @Override protected void onPause() {
        active = false;
        ui.removeCallbacks(fallbackFrameLoop);
        ui.removeCallbacks(sessionLoop);
        detachLiveSurface();
        touchClient.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        active = false;
        ui.removeCallbacksAndMessages(null);
        detachLiveSurface();
        if (touchClient != null) touchClient.stop();
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
        int prevDisplay = displayId;
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
        if (prevDisplay > 0 && prevDisplay != displayId) {
            detachLiveSurface();
        }
        syncChrome(false);
        if (autoTakeover && !sessionId.isEmpty()) {
            engageTakeover(true, false);
        }
        if (active && displayId > 0) {
            touchClient.start(displayId, sessionId);
            if (surfaceReady && !surfaceAttached) tryAttachLiveSurface();
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

        // ---- Native GPU SurfaceView stage (exact aspect ratio, rounded corners, zero-lag touch) ----
        stage = new FrameLayout(this);
        stage.setBackgroundColor(k.canvas);
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> layoutSurfaceFrame());

        final float cornerPx = k.dp(16);
        ViewOutlineProvider roundedProvider = new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerPx);
            }
        };

        surfaceFrame = new FrameLayout(this);
        surfaceFrame.setBackgroundColor(Color.BLACK);
        surfaceFrame.setOutlineProvider(roundedProvider);
        surfaceFrame.setClipToOutline(true);

        surfaceView = new SurfaceView(this);
        surfaceView.setOutlineProvider(roundedProvider);
        surfaceView.setClipToOutline(true);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                if (sourceWidth > 0 && sourceHeight > 0) {
                    holder.setFixedSize(sourceWidth, sourceHeight);
                }
                if (active && displayId > 0) tryAttachLiveSurface();
            }

            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surfaceReady = true;
                if (active && displayId > 0 && !surfaceAttached && !attachInFlight) {
                    tryAttachLiveSurface();
                }
            }

            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                detachLiveSurface();
            }
        });
        surfaceFrame.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));

        fallbackImage = new ImageView(this);
        fallbackImage.setScaleType(ImageView.ScaleType.FIT_XY);
        fallbackImage.setVisibility(View.GONE);
        surfaceFrame.addView(fallbackImage, new FrameLayout.LayoutParams(-1, -1));

        touchOverlay = new TouchBorderOverlay(this);
        surfaceFrame.addView(touchOverlay, new FrameLayout.LayoutParams(-1, -1));

        stage.addView(surfaceFrame, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

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
                40, 18, 20, AppCopy.ACTION_ENTER, () -> sendFastKey(KeyEvent.KEYCODE_ENTER, "ENTER"));
        LinearLayout.LayoutParams ebp = new LinearLayout.LayoutParams(k.dp(40), k.dp(40));
        ebp.setMarginEnd(k.dp(6));
        typeBar.addView(enterBtn, ebp);

        FrameLayout delBtn = k.iconButton(
                AppIcons.BACKSPACE, k.ink, k.raised, k.strokeStrong,
                40, 19, 20, AppCopy.ACTION_DELETE_KEY, () -> sendFastKey(KeyEvent.KEYCODE_DEL, "DEL"));
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
                    sendFastKey(KeyEvent.KEYCODE_BACK, "BACK");
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

    private void layoutSurfaceFrame() {
        if (stage == null || surfaceFrame == null) return;
        int vw = stage.getWidth(), vh = stage.getHeight();
        if (vw <= 0 || vh <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return;
        float availW = Math.max(1f, vw - k.dp(16));
        float availH = Math.max(1f, vh - k.dp(8));
        float scale = Math.min(availW / sourceWidth, availH / sourceHeight);
        int dw = Math.max(1, Math.round(sourceWidth * scale));
        int dh = Math.max(1, Math.round(sourceHeight * scale));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceFrame.getLayoutParams();
        if (lp.width != dw || lp.height != dh) {
            lp.width = dw;
            lp.height = dh;
            lp.gravity = Gravity.CENTER;
            surfaceFrame.setLayoutParams(lp);
        }
    }

    // ---- Zero-copy native SurfaceView attach / detach over Binder ----

    private void tryAttachLiveSurface() {
        if (!active || !surfaceReady || displayId <= 0 || attachInFlight || surfaceAttached) return;
        Surface surface = surfaceView.getHolder().getSurface();
        if (surface == null || !surface.isValid()) return;
        attachInFlight = true;
        lastAttachAttemptAt = SystemClock.uptimeMillis();
        String token = UUID.randomUUID().toString();
        activeSurfaceToken = token;
        LiveSurfaceProvider.register(token, surface, aliveBinder);
        Map<String, Object> body = Json.mapOf(
                "displayId", displayId,
                "authority", getPackageName() + ".livesurface",
                "token", token);
        daemon.post("/session/surface/attach", body, (reply, err) -> {
            attachInFlight = false;
            if (!active || !surfaceReady || !token.equals(activeSurfaceToken)) {
                LiveSurfaceProvider.clear(token);
                if (reply != null && Boolean.TRUE.equals(reply.get("ok"))) {
                    daemon.post("/session/surface/detach", Json.mapOf("displayId", displayId, "token", token), null);
                }
                return;
            }
            if (err == null && reply != null && Boolean.TRUE.equals(reply.get("ok"))) {
                int w = (int) DaemonClient.num(reply, "width");
                int h = (int) DaemonClient.num(reply, "height");
                if (w > 0 && h > 0 && (w != sourceWidth || h != sourceHeight)) {
                    sourceWidth = w;
                    sourceHeight = h;
                    surfaceView.getHolder().setFixedSize(sourceWidth, sourceHeight);
                    layoutSurfaceFrame();
                }
                surfaceAttached = true;
                ui.removeCallbacks(fallbackFrameLoop);
                fallbackImage.setVisibility(View.GONE);
                fallbackImage.setImageDrawable(null);
                emptyNote.setVisibility(View.GONE);
            } else {
                LiveSurfaceProvider.clear(token);
                surfaceAttached = false;
                if (reply != null && "blocked_app".equals(DaemonClient.str(reply, "error"))) {
                    emptyNote.setText(AppCopy.PREVIEW_HIDDEN_BLOCKED);
                    emptyNote.setVisibility(View.VISIBLE);
                } else {
                    // Graceful fallback (e.g., screen recording active) using fast JPEG frames.
                    fallbackImage.setVisibility(View.VISIBLE);
                    scheduleFallbackFrame(0L);
                }
            }
        });
    }

    private void detachLiveSurface() {
        String token = activeSurfaceToken;
        boolean wasAttached = surfaceAttached || attachInFlight;
        activeSurfaceToken = "";
        surfaceAttached = false;
        attachInFlight = false;
        if (!token.isEmpty()) LiveSurfaceProvider.clear(token);
        if (wasAttached && displayId > 0 && daemon != null) {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("displayId", displayId);
            if (!token.isEmpty()) body.put("token", token);
            daemon.post("/session/surface/detach", body, null);
        }
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
        touchOverlay.setActiveControl(userInControl, animate);

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
        Map<String, Object> payload = Json.mapOf("sessionId", sessionId, "active", takeOver);
        if (takeOver && !confirmId.isEmpty()) payload.put("confirmId", confirmId);
        daemon.postOrdered("/session/takeover", payload, (reply, err) -> {
            if (err != null || !Boolean.TRUE.equals(reply.get("ok"))) {
                toast(AppCopy.TAKEOVER_FAILED);
            }
        });
    }

    private void markUserControlFromTouch() {
        if (!userInControl && !sessionId.isEmpty()) {
            userInControl = true;
            controlOptimisticUntil = SystemClock.uptimeMillis() + CONTROL_OPTIMISTIC_MS;
            if (controlSwitcher != null) controlSwitcher.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            syncChrome(true);
            // If confirmId is pending, also mark confirmation taken over via HTTP.
            if (!confirmId.isEmpty()) {
                daemon.postOrdered("/session/takeover", Json.mapOf("sessionId", sessionId, "active", true, "confirmId", confirmId), null);
            }
        }
    }

    private void sendFastKey(int keyCode, String fallbackName) {
        markUserControlFromTouch();
        if (touchClient != null && touchClient.sendKey(keyCode)) return;
        sendHttpInput(Json.mapOf("action", "key", "key", fallbackName));
    }

    private void sendHttpInput(Map<String, Object> body) {
        if (displayId <= 0 && sessionId.isEmpty()) return;
        if (!userInControl && !sessionId.isEmpty()) engageTakeover(true, true);
        Map<String, Object> req = new java.util.LinkedHashMap<>(body);
        if (!sessionId.isEmpty()) req.put("sessionId", sessionId);
        if (displayId > 0) req.put("displayId", displayId);
        daemon.postOrdered("/session/input", req, (reply, err) -> {
            if (active && !surfaceAttached) scheduleFallbackFrame(24L);
        });
    }

    private void sendTypedText() {
        String text = typeInput.getText() == null ? "" : typeInput.getText().toString();
        if (text.isEmpty()) return;
        typeInput.setText("");
        sendHttpInput(Json.mapOf("action", "text", "text", text));
    }

    private void toggleTypeBar() {
        if (!typeVisible) {
            if (!userInControl && !sessionId.isEmpty()) engageTakeover(true, true);
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
        detachLiveSurface();
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
        detachLiveSurface();
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

    // ---- Fast JPEG fallback preview (only runs if native SurfaceView attach is unavailable) ----

    private void scheduleFallbackFrame(long delayMs) {
        if (!active || surfaceAttached) return;
        ui.removeCallbacks(fallbackFrameLoop);
        ui.postDelayed(fallbackFrameLoop, delayMs);
    }

    private void fetchFallbackFrame() {
        if (!active || surfaceAttached) return;
        if (displayId <= 0) {
            scheduleFallbackFrame(FALLBACK_FRAME_ERROR_MS);
            return;
        }
        if (frameInFlight) return;
        frameInFlight = true;
        StringBuilder path = new StringBuilder("/preview?displayId=").append(displayId).append("&scale=0.68&quality=76&format=jpeg");
        if (lastFrameSeq > 0) path.append("&sinceFrame=").append(lastFrameSeq);
        daemon.bytes(path.toString(), (shot, err) -> {
            frameInFlight = false;
            if (!active || surfaceAttached) return;
            if (shot == null) {
                scheduleFallbackFrame(FALLBACK_FRAME_ERROR_MS);
                return;
            }
            if (shot.sourceWidth > 0 && shot.sourceHeight > 0
                    && (shot.sourceWidth != sourceWidth || shot.sourceHeight != sourceHeight)) {
                sourceWidth = shot.sourceWidth;
                sourceHeight = shot.sourceHeight;
                layoutSurfaceFrame();
            }
            if (shot.status == 304) {
                scheduleFallbackFrame(FALLBACK_FRAME_IDLE_MS);
                return;
            }
            if (shot.status == 403) {
                fallbackImage.setImageDrawable(null);
                emptyNote.setText(AppCopy.PREVIEW_HIDDEN_BLOCKED);
                emptyNote.setVisibility(View.VISIBLE);
                scheduleFallbackFrame(500L);
                return;
            }
            if (shot.status == 200 && shot.bitmap != null) {
                if (shot.frameSeq > 0) lastFrameSeq = shot.frameSeq;
                fallbackImage.setVisibility(View.VISIBLE);
                fallbackImage.setImageBitmap(shot.bitmap);
                emptyNote.setVisibility(View.GONE);
                scheduleFallbackFrame(FALLBACK_FRAME_ACTIVE_MS);
                return;
            }
            scheduleFallbackFrame(FALLBACK_FRAME_IDLE_MS);
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
            int prevDisp = displayId;
            sessionId = DaemonClient.str(match, "sessionId");
            int disp = (int) DaemonClient.num(match, "display");
            if (disp > 0) displayId = disp;
            int w = (int) DaemonClient.num(match, "width"), h = (int) DaemonClient.num(match, "height");
            if (w > 0 && h > 0 && (w != sourceWidth || h != sourceHeight)) {
                sourceWidth = w;
                sourceHeight = h;
                if (surfaceReady) surfaceView.getHolder().setFixedSize(sourceWidth, sourceHeight);
                layoutSurfaceFrame();
            }
            String pkg = DaemonClient.str(match, "package");
            if (!pkg.isEmpty() && !pkg.equals(packageName)) {
                packageName = pkg;
                appLabel = resolveLabel(pkg);
            }
            boolean blocked = Boolean.TRUE.equals(match.get("blocked"));
            if (blocked) {
                if (surfaceAttached) detachLiveSurface();
                emptyNote.setText(AppCopy.PREVIEW_HIDDEN_BLOCKED);
                emptyNote.setVisibility(View.VISIBLE);
            } else if (displayId > 0 && (prevDisp != displayId || !touchClient.isRunningFor(displayId))) {
                touchClient.start(displayId, sessionId);
                if (surfaceReady && !surfaceAttached && !attachInFlight) tryAttachLiveSurface();
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

    // ---- Persistent TCP_NODELAY binary touch & key stream client ----

    private final class LiveTouchClient {
        private final LinkedBlockingQueue<Packet> queue = new LinkedBlockingQueue<>(64);
        private volatile boolean running;
        private volatile boolean connected;
        private volatile int targetDisplay = -1;
        private volatile String targetSession = "";
        private volatile Socket activeSocket;
        private Thread worker;

        private final class Packet {
            final byte[] bytes;
            final boolean coalescable;
            Packet(byte[] bytes, boolean coalescable) {
                this.bytes = bytes;
                this.coalescable = coalescable;
            }
        }

        synchronized boolean isRunningFor(int disp) {
            return running && targetDisplay == disp;
        }

        synchronized void start(int disp, String sid) {
            if (disp <= 0) return;
            if (running && targetDisplay == disp && (sid == null || sid.equals(targetSession))) return;
            stop();
            targetDisplay = disp;
            targetSession = sid == null ? "" : sid;
            running = true;
            queue.clear();
            worker = new Thread(this::loop, "backstage-touch-client");
            worker.setDaemon(true);
            worker.start();
        }

        synchronized void stop() {
            running = false;
            connected = false;
            queue.clear();
            Socket s = activeSocket;
            activeSocket = null;
            if (s != null) {
                try { s.close(); } catch (IOException ignored) { }
            }
            if (worker != null) {
                worker.interrupt();
                worker = null;
            }
        }

        void sendMotion(MotionEvent ev, int viewW, int viewH) {
            if (!running || viewW <= 0 || viewH <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return;
            int count = Math.min(10, ev.getPointerCount());
            if (count <= 0) return;
            int action = ev.getAction();
            int masked = ev.getActionMasked();
            int relTime = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, ev.getEventTime() - ev.getDownTime()));
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(10 + count * 12);
                DataOutputStream dos = new DataOutputStream(baos);
                dos.writeByte(1);
                dos.writeInt(action);
                dos.writeInt(relTime);
                dos.writeByte(count);
                float sx = (float) sourceWidth / (float) viewW;
                float sy = (float) sourceHeight / (float) viewH;
                for (int i = 0; i < count; i++) {
                    float mx = Math.max(0f, Math.min(sourceWidth - 1f, ev.getX(i) * sx));
                    float my = Math.max(0f, Math.min(sourceHeight - 1f, ev.getY(i) * sy));
                    dos.writeInt(ev.getPointerId(i));
                    dos.writeFloat(mx);
                    dos.writeFloat(my);
                }
                dos.flush();
                enqueue(new Packet(baos.toByteArray(), masked == MotionEvent.ACTION_MOVE));
            } catch (IOException ignored) {
            }
        }

        boolean sendKey(int keyCode) {
            if (!running || !connected) return false;
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(5);
                DataOutputStream dos = new DataOutputStream(baos);
                dos.writeByte(3);
                dos.writeInt(keyCode);
                dos.flush();
                enqueue(new Packet(baos.toByteArray(), false));
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        private void enqueue(Packet p) {
            if (p.coalescable && queue.size() >= 3) {
                // Keep queue latency under 1 frame by dropping stale intermediate MOVE packets.
                for (Packet existing : queue) {
                    if (existing.coalescable) queue.remove(existing);
                }
            }
            if (!queue.offer(p) && !p.coalescable) {
                queue.poll();
                queue.offer(p);
            }
        }

        private void loop() {
            while (running) {
                Socket sock = null;
                try {
                    AgentConfig c = daemon.currentConfig();
                    String path = "/session/touch-stream?displayId=" + targetDisplay
                            + (targetSession.isEmpty() ? "" : "&sessionId=" + targetSession);
                    String auth = com.deviceagent.crypto.LocalAuth.requestHeader(c.localToken, "POST", path, "");
                    sock = new Socket();
                    sock.setTcpNoDelay(true);
                    sock.connect(new InetSocketAddress("127.0.0.1", c.daemonPort), 2000);
                    sock.setSoTimeout(3000);
                    activeSocket = sock;
                    OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 4096);
                    String req = "POST " + path + " HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "X-Backstage-Auth: " + auth + "\r\n"
                            + "Connection: Upgrade\r\n"
                            + "Content-Length: 0\r\n\r\n";
                    out.write(req.getBytes(StandardCharsets.UTF_8));
                    out.flush();

                    if (!readUpgradeOk(sock.getInputStream())) {
                        closeQuiet(sock);
                        sleepBrief();
                        continue;
                    }
                    sock.setSoTimeout(0);
                    connected = true;
                    while (running) {
                        Packet pkt = queue.poll(15, TimeUnit.SECONDS);
                        if (!running) break;
                        if (pkt == null) {
                            out.write(2); // Keepalive heartbeat
                            out.flush();
                            continue;
                        }
                        out.write(pkt.bytes);
                        // Drain any immediately queued packets into the same TCP write for efficiency.
                        Packet next;
                        while ((next = queue.poll()) != null) {
                            out.write(next.bytes);
                        }
                        out.flush();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    connected = false;
                    closeQuiet(sock);
                    if (running) sleepBrief();
                } finally {
                    connected = false;
                    activeSocket = null;
                    closeQuiet(sock);
                }
            }
        }

        private boolean readUpgradeOk(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                sb.append((char) b);
                int len = sb.length();
                if (len >= 4
                        && sb.charAt(len - 4) == '\r'
                        && sb.charAt(len - 3) == '\n'
                        && sb.charAt(len - 2) == '\r'
                        && sb.charAt(len - 1) == '\n') {
                    return sb.toString().startsWith("HTTP/1.1 101");
                }
                if (len > 4096) return false;
            }
            return false;
        }

        private void sleepBrief() {
            try { Thread.sleep(250L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        private void closeQuiet(Socket s) {
            if (s != null) {
                try { s.close(); } catch (IOException ignored) { }
            }
        }
    }

    // ---- Transparent border + real-time multi-touch capture overlay ----

    private final class TouchBorderOverlay extends View {
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF borderRect = new RectF();
        private final float cornerRadius;
        private final int idleBorder = Color.parseColor("#242424");
        private ValueAnimator borderAnim;
        private float controlBlend;

        TouchBorderOverlay(Context context) {
            super(context);
            cornerRadius = k.dp(16);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(k.dp(1.5f));
        }

        void setActiveControl(boolean user, boolean animate) {
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
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            borderPaint.setColor(blendColor(idleBorder, k.amber, controlBlend));
            float inset = borderPaint.getStrokeWidth() * 0.5f;
            borderRect.set(inset, inset, w - inset, h - inset);
            canvas.drawRoundRect(borderRect, cornerRadius, cornerRadius, borderPaint);
        }

        @Override public boolean performClick() {
            return super.performClick();
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return false;
            int masked = ev.getActionMasked();
            if (masked == MotionEvent.ACTION_DOWN) {
                markUserControlFromTouch();
                getParent().requestDisallowInterceptTouchEvent(true);
            } else if (masked == MotionEvent.ACTION_UP) {
                performClick();
                getParent().requestDisallowInterceptTouchEvent(false);
            } else if (masked == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            touchClient.sendMotion(ev, w, h);
            return true;
        }
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
}
