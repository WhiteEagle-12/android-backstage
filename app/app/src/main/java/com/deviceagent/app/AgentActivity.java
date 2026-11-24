package com.deviceagent.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.deviceagent.adb.AdbKey;
import com.deviceagent.adb.AdbMdns;
import com.deviceagent.adb.AdbPairing;
import com.deviceagent.crypto.LinkPairing;
import com.deviceagent.daemon.DaemonConfig;
import com.deviceagent.rpc.LanAddresses;
import com.deviceagent.rpc.SimpleHttp;
import com.deviceagent.tools.Json;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Setup until this phone is linked and paired; then a persistent shell with four tabs. The shell is
 * built once. Each 1.5 s tick updates the visible tab in place, so nothing flickers or loses scroll.
 */
public final class AgentActivity extends Activity {
    static final String OPEN_PAIRING_INPUT = "com.deviceagent.app.OPEN_PAIRING_INPUT";
    static volatile String lastStatus = "idle";
    static final int TAB_LIVE = 0, TAB_ACTIVITY = 1, TAB_ASSISTANTS = 2, TAB_SETTINGS = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean checking = new AtomicBoolean();
    AgentConfig config;
    Session session;
    UiKit kit;
    DaemonClient daemon;
    SetupFlow setup;
    private AdbMdns.Listener mdnsListener;
    private TabPage[] tabs;
    private FrameLayout pages;
    private LinearLayout navBar, ctaBar;
    private final AppIcons[] navIcons = new AppIcons[4];
    private final View[] navPills = new View[4];
    private final TextView[] navLabels = new TextView[4];
    private TextView notice;
    private boolean shownShell, imeVisible;
    private long noticeAt;

    /** Agent requests waiting for approval, loaded with the account. */
    List<AccountClient.Request> requests = new java.util.ArrayList<>();
    boolean active, daemonUp, daemonPresent, healthUnknown, connected, checked, startIssued;
    private boolean portConflict, invitationVisible;
    private String relayLastError = "";
    String usbUnlockedFunctions = "";
    private String wifi = "";
    int pendingConfirmations;
    /** Set when opened from a confirmation notification; Live fetches confirmations at once. */
    String focusConfirm;
    private Map<String, Object> permits;
    private int autoPairPort;
    /** Approval currently on screen or being decided; suppresses duplicate prompts. */
    private String confirmShownFor = null, confirmErrorFor = null, confirmError = "";
    private AlertDialog currentInvitationDialog;
    private long startTime;
    private String shownKey = "";
    private int seenRevision = -1;
    private AccountClient accountClient;
    volatile AccountClient.Account account;
    volatile String accountError = "";
    /** Bumped whenever the account, its requests or an offered update change. */
    int accountVersion;
    private volatile long accountLoadedAt;
    private final AtomicBoolean accountLoading = new AtomicBoolean();
    volatile AccountClient.Update availableUpdate;
    volatile boolean checkingUpdate;
    volatile String updateStatus = "";

    static final class Session {
        volatile boolean busy;
        volatile String message = "";
        volatile int revision;
        volatile boolean restartRequested;
        String server = "", code = "", clientName = "", page = "main", email = "", emailCode = "", password = "", confirmPassword = "", authMode = "login";
        boolean emailSent, signupSent, resetSent;
        int tab = TAB_LIVE;
        void done(String text) { message = text; busy = false; revision++; }
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!active) return;
            config = AgentConfig.load(AgentActivity.this);
            clearResolvedPairingError();
            if (session.restartRequested) { session.restartRequested = false; startIssued = false; }
            maybeStart();
            boolean shell = shellMode();
            if (shell != shownShell || (!shell && (!shownKey.equals(screenKey()) || seenRevision != session.revision))) renderNow();
            else {
                seenRevision = session.revision;
                if (shell) tabs[session.tab].tick(); else setup.updateHint();
                syncChrome();
            }
            refreshHealth();
            refreshAccount(false);
            resumeInvitation();
            ui.postDelayed(this, 1500);
        }
    };

    // ---- Lifecycle ----

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        config = AgentConfig.load(this);
        Object retained = getLastNonConfigurationInstance();
        session = retained instanceof Session ? (Session) retained : new Session();
        if (session.server.isEmpty()) session.server = config.hasAssistant() ? config.relayUrl : SetupRules.DEFAULT_RELAY;
        if (saved != null && retained == null) {
            session.server = saved.getString("server", session.server);
            session.code = saved.getString("code", "");
            session.clientName = saved.getString("clientName", "");
            session.page = saved.getString("page", "main");
            session.tab = saved.getInt("tab", TAB_LIVE);
        }
        kit = new UiKit(this);
        daemon = new DaemonClient(this);
        StartScripts.write(this, config);
        if (saved == null) importLink(getIntent());
        if (saved == null) focusConfirmFrom(getIntent());
        buildShell();
        renderNow();
        startMdns();
        if (saved == null) openWirelessFromNotification(getIntent());
        if (saved == null) openPairingFromNotification(getIntent());
        if (PhoneSignIn.pending(this) && !session.busy) nativeSignIn(false);
    }

    @Override public Object onRetainNonConfigurationInstance() { return session; }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("clientName", session.clientName); out.putString("server", session.server); out.putString("code", session.code); out.putString("page", session.page);
        out.putInt("tab", session.tab);
        super.onSaveInstanceState(out);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); importLink(intent); focusConfirmFrom(intent);
        renderNow(); openWirelessFromNotification(intent); openPairingFromNotification(intent);
    }
    private void focusConfirmFrom(Intent intent) {
        String id = intent == null ? null : intent.getStringExtra("confirmId");
        if (id == null || id.isBlank()) return;
        focusConfirm = id; session.tab = TAB_LIVE;
        intent.removeExtra("confirmId");
    }
    private void openWirelessFromNotification(Intent intent) {
        if (intent == null || !RecoveryNotifier.OPEN_WIRELESS.equals(intent.getAction())) return;
        intent.setAction(Intent.ACTION_MAIN);
        WirelessDebuggingSettings.open(this);
    }
    private void openPairingFromNotification(Intent intent) {
        if (intent == null || !OPEN_PAIRING_INPUT.equals(intent.getAction())) return;
        int port = intent.getIntExtra(PairingCodeReceiver.EXTRA_PORT, 0);
        intent.setAction(Intent.ACTION_MAIN);
        if (port > 0) {
            AgentConfig.saveAdbState(this, null, port, null);
            config = AgentConfig.load(this);
        }
        manualPairDialog();
    }
    private void clearResolvedPairingError() {
        if (config.paired && !session.busy && session.message.equals(AppCopy.PAIR_REJECTED)) {
            session.message = "";
            session.revision++;
        }
    }
    private void importLink(Intent intent) {
        android.net.Uri uri = intent == null ? null : intent.getData();
        if (uri == null || session.busy) return;
        if (!"androidbackstage".equals(uri.getScheme()) || !"connect".equals(uri.getHost())) return;
        try {
            String server = SetupRules.relayUrl(uri.getQueryParameter("relay"));
            String code = LinkPairing.normalizeCode(uri.getQueryParameter("code"));
            session.server = server; session.code = code; session.page = "connect";
            String label = uri.getQueryParameter("name");
            session.clientName = label == null ? "" : SetupRules.connectionName(label);
            session.message = AppCopy.LINK_REVIEW;
        } catch (RuntimeException e) { session.message = AppCopy.LINK_INCOMPLETE; }
        // A link only fills the form. It never grants access or submits a code.
    }
    @Override protected void onResume() {
        super.onResume(); active = true; config = AgentConfig.load(this); clearResolvedPairingError();
        if (!daemonUp && SetupRules.shouldStart(config.accessEnabled, config.hasAssistant(), config.paired)) {
            startIssued = false;
            AdbDiscovery.refresh(this);
        }
        // Coming back from Android Settings mid-pairing: the code entry should
        // already be waiting. Fire the pairing notification once per fresh port
        // so the user never has to ask for it. Stale ports never re-fire.
        int pairPort = livePairPort();
        if (config.paired) autoPairPort = 0;
        else if (("main".equals(session.page) || "access".equals(session.page))
                && pairPort > 0 && pairPort != autoPairPort
                && areNotificationsEnabled()
                && (Build.VERSION.SDK_INT < 33 || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            autoPairPort = pairPort;
            PairingNotifier.showPairing(this, pairPort);
            toast(AppCopy.PAIRING_SHOWN);
        }
        if (tabs != null && shellMode()) tabs[session.tab].onShow();
        renderNow(); ui.post(refresh);
    }
    @Override protected void onPause() {
        active = false; ui.removeCallbacks(refresh);
        if (tabs != null) tabs[session.tab].onHide();
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (currentInvitationDialog != null) currentInvitationDialog.dismiss();
        if (mdnsListener != null) { AdbDiscovery.release(mdnsListener); mdnsListener = null; }
        ui.removeCallbacks(refresh); daemon.shutdown(); super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (!"main".equals(session.page)) { session.page = "main"; session.message = ""; renderNow(); }
        else if (shellMode() && session.tab != TAB_LIVE) selectTab(TAB_LIVE);
        else super.onBackPressed();
    }

    // ---- Shell ----

    boolean shellMode() { return config.hasAssistant() && "main".equals(session.page) && (config.paired || daemonUp); }

    private String screenKey() {
        return session.page + ":" + config.hasAssistant() + ":" + config.paired + ":" + (config.lastPairPort > 0)
                + ":" + isDevOptionsOn() + ":" + isWirelessOn() + ":" + (daemonUp && !config.paired)
                + ":" + isIgnoringBatteryOptimizations() + ":" + areNotificationsEnabled();
    }

    private void buildShell() {
        UiKit k = kit;
        getWindow().setStatusBarColor(k.canvas);
        getWindow().setNavigationBarColor(k.elevated);
        LinearLayout frame = k.column(); frame.setBackgroundColor(k.canvas);
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            imeVisible = insets.isVisible(WindowInsets.Type.ime());
            // The bottom bar takes the system bar inset in the shell; the setup flow has no bar, so the frame takes it.
            v.setPadding(bars.left, bars.top, bars.right, imeVisible ? ime.bottom : shellMode() ? 0 : bars.bottom);
            navBar.setPadding(0, k.dp(6), 0, k.dp(8) + bars.bottom);
            syncChrome();
            return insets;
        });
        FrameLayout body = new FrameLayout(this);
        pages = new FrameLayout(this);
        body.addView(pages, new FrameLayout.LayoutParams(-1, -1));
        setup = new SetupFlow(this, k);
        pages.addView(setup.scroll, new FrameLayout.LayoutParams(-1, -1));
        tabs = new TabPage[]{new LiveTab(this, k), new ActivityTab(this, k), new AssistantsTab(this, k), new SettingsTab(this, k)};
        for (TabPage t : tabs) { t.scroll.setVisibility(View.GONE); pages.addView(t.scroll, new FrameLayout.LayoutParams(-1, -1)); }

        notice = new TextView(this);
        notice.setTextSize(14); notice.setTextColor(k.ink);
        notice.setBackground(k.shape(k.raised, 12, k.strokeStrong));
        notice.setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(12)); notice.setElevation(k.dp(4));
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        notice.setOnClickListener(v -> { if (!session.busy) { session.message = ""; syncChrome(); } });
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        np.setMargins(k.dp(16), 0, k.dp(16), k.dp(14));
        body.addView(notice, np);
        frame.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        ctaBar = k.column(); ctaBar.setGravity(Gravity.CENTER_HORIZONTAL); ctaBar.setPadding(k.dp(16), k.dp(8), k.dp(16), k.dp(10));
        ctaBar.setBackgroundColor(k.canvas);
        Button cta = k.buttonView(AppCopy.ACTION_CONNECT_AGENT, UiKit.PRIMARY);
        cta.setOnClickListener(v -> { if (!session.busy) chooseAgent(); });
        ctaBar.addView(cta, new LinearLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels - k.dp(32), k.dp(568)), -2));
        frame.addView(ctaBar, new LinearLayout.LayoutParams(-1, -2));

        navBar = k.row(); navBar.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable navBg = k.shape(k.elevated, 0, 0);
        navBar.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{navBg, hairline()}));
        int[] kinds = {AppIcons.LIVE, AppIcons.ACTIVITY, AppIcons.ASSISTANTS, AppIcons.SETTINGS};
        String[] labels = {AppCopy.TAB_LIVE, AppCopy.TAB_ACTIVITY, AppCopy.TAB_ASSISTANTS, AppCopy.TAB_SETTINGS};
        for (int i = 0; i < 4; i++) {
            final int index = i;
            LinearLayout item = k.column(); item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, k.dp(4), 0, k.dp(2)); item.setClickable(true); item.setFocusable(true);
            item.setBackground(k.ripple(null, 16));
            item.setContentDescription(labels[i]);
            item.setOnClickListener(v -> selectTab(index));
            FrameLayout pill = new FrameLayout(this);
            ImageView icon = new ImageView(this); navIcons[i] = new AppIcons(kinds[i], k.muted); icon.setImageDrawable(navIcons[i]);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            pill.addView(icon, new FrameLayout.LayoutParams(k.dp(22), k.dp(22), Gravity.CENTER));
            navPills[i] = pill;
            item.addView(pill, new LinearLayout.LayoutParams(k.dp(60), k.dp(30)));
            TextView label = k.text(null, labels[i], 12, k.muted, true); label.setGravity(Gravity.CENTER);
            label.setPadding(0, k.dp(4), 0, 0); label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            navLabels[i] = label;
            item.addView(label, new LinearLayout.LayoutParams(-2, -2));
            navBar.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        frame.addView(navBar, new LinearLayout.LayoutParams(-1, -2));
        setContentView(frame);
        frame.requestApplyInsets();
    }

    /** A 1 dp top border for the bottom bar. */
    private android.graphics.drawable.Drawable hairline() {
        android.graphics.drawable.GradientDrawable line = new android.graphics.drawable.GradientDrawable();
        line.setColor(Color.TRANSPARENT); line.setStroke(Math.max(1, kit.dp(0.75f)), kit.stroke);
        android.graphics.drawable.InsetDrawable inset = new android.graphics.drawable.InsetDrawable(line, -kit.dp(2), 0, -kit.dp(2), -kit.dp(2));
        return inset;
    }

    /** Rebuilds the visible screen now: the setup flow, or the selected tab in place. */
    void renderNow() {
        if (tabs == null) return;
        boolean shell = shellMode();
        String key = screenKey();
        if (!shell) {
            int keep = shownKey.equals(key) && !shownShell ? setup.scroll.getScrollY() : 0;
            setup.render();
            if (keep > 0) setup.scroll.post(() -> setup.scroll.scrollTo(0, keep));
        }
        shownKey = key; seenRevision = session.revision;
        if (shell != shownShell) { for (TabPage t : tabs) t.invalidate(); getWindow().getDecorView().requestApplyInsets(); }
        shownShell = shell;
        setup.scroll.setVisibility(shell ? View.GONE : View.VISIBLE);
        for (int i = 0; i < 4; i++) tabs[i].scroll.setVisibility(shell && i == session.tab ? View.VISIBLE : View.GONE);
        if (shell) tabs[session.tab].render();
        syncChrome();
    }

    void selectTab(int index) {
        if (index == session.tab) { tabs[index].scroll.smoothScrollTo(0, 0); return; }
        tabs[session.tab].onHide();
        session.tab = index;
        tabs[index].onShow();
        renderNow();
        if (active) tabs[index].tick();
    }

    boolean isTabVisible(TabPage tab) { return active && shownShell && tabs[session.tab] == tab; }

    /** Notice, bottom bar and pinned button follow the current state without rebuilding anything. */
    private void syncChrome() {
        if (navBar == null) return;
        boolean shell = shownShell;
        navBar.setVisibility(shell && !imeVisible ? View.VISIBLE : View.GONE);
        ctaBar.setVisibility(shell && !imeVisible && session.tab == TAB_ASSISTANTS && accountServer() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 4; i++) {
            boolean on = i == session.tab;
            navIcons[i].setColor(on ? kit.green : kit.muted); navIcons[i].setFilled(on);
            navPills[i].setBackground(on ? kit.shape(kit.greenSoft, 99, 0) : null);
            navLabels[i].setTextColor(on ? kit.ink : kit.muted);
            ((View) navLabels[i].getParent()).setSelected(on);
        }
        String message = session.message;
        if (message.isEmpty()) notice.setVisibility(View.GONE);
        else {
            if (!message.contentEquals(notice.getText()) || notice.getVisibility() != View.VISIBLE) noticeAt = System.currentTimeMillis();
            notice.setText(message); notice.setVisibility(View.VISIBLE);
            if (!session.busy && System.currentTimeMillis() - noticeAt > 6000) { session.message = ""; notice.setVisibility(View.GONE); }
        }
    }

    // ---- Connection status for the Live hero ----

    static final class Status {
        String title, body, fixLabel; int color; Runnable reconnect, fix; boolean alerts;
        String signature() { return title + "|" + body + "|" + fixLabel + "|" + (reconnect != null) + "|" + alerts; }
    }

    Status status() {
        Status s = new Status();
        boolean needsPhoneSetup = !config.paired || !daemonUp;
        s.color = kit.amber;
        if (!checked) s.title = AppCopy.STATUS_CONNECTING;
        else if (!config.accessEnabled && healthUnknown) { s.title = AppCopy.STATUS_PAUSED; s.body = AppCopy.PAUSE_UNCONFIRMED; }
        else if (!config.accessEnabled && daemonPresent) { s.title = AppCopy.STATUS_PAUSED; s.body = AppCopy.PAUSE_STILL_ACTIVE; }
        else if (!config.accessEnabled) { s.title = AppCopy.STATUS_PAUSED; s.body = AppCopy.PAUSED_BODY; s.color = kit.muted; }
        else if (portConflict) { s.title = AppCopy.STATUS_OFFLINE; s.body = AppCopy.PORT_CONFLICT; s.color = kit.danger; s.reconnect = this::restartConnection; }
        else if (daemonPresent && !daemonUp) { s.title = AppCopy.STATUS_OFFLINE; s.body = AppCopy.RESTART_FOR_UPDATE; s.color = kit.danger; s.reconnect = this::restartConnection; }
        else if (connected) {
            s.title = AppCopy.STATUS_CONNECTED; s.color = kit.green;
            if (!isWirelessOn()) { s.body = AppCopy.WIRELESS_OFF_CONNECTED; s.fixLabel = AppCopy.ACTION_OPEN_WIRELESS; s.fix = this::openAccessSettingsSmart; }
        } else if (daemonUp) {
            s.reconnect = this::retryRelayNow;
            if (SetupRules.isRelayAuthError(relayLastError)) { s.title = AppCopy.STATUS_NEEDS_ACCESS; s.body = AppCopy.RELAY_REMOVED; s.color = kit.danger; }
            else { s.title = AppCopy.STATUS_RECONNECTING; s.body = AppCopy.RECONNECTING_BODY; }
        } else if ("auth_revoked".equals(AgentService.lastDiagnosis)) {
            s.title = AppCopy.STATUS_NEEDS_ACCESS; s.body = AppCopy.PAIR_REVOKED; s.color = kit.danger;
            s.fixLabel = AppCopy.ACTION_PAIR_AGAIN; s.fix = () -> { session.page = "access"; renderNow(); };
        } else if (!isWirelessOn()) {
            s.title = AppCopy.STATUS_OFFLINE; s.body = AppCopy.WIRELESS_OFF; s.color = kit.danger;
            s.fixLabel = AppCopy.ACTION_OPEN_WIRELESS; s.fix = this::openAccessSettingsSmart;
        } else if (startIssued && System.currentTimeMillis() - startTime < 25000) s.title = AppCopy.STATUS_CONNECTING;
        else {
            s.title = AppCopy.STATUS_NEEDS_ACCESS; s.body = AppCopy.ACCESS_NEEDS_HAND; s.color = kit.danger;
            s.fixLabel = AppCopy.ACTION_OPEN_WIRELESS; s.fix = this::openAccessSettingsSmart; s.reconnect = this::restartConnection;
        }
        s.alerts = checked && config.accessEnabled && (!isWirelessOn() || needsPhoneSetup) && !areNotificationsEnabled();
        return s;
    }

    String thisDeviceName() {
        String name = account != null ? deviceNamed(account.thisDevice) : config.deviceName;
        return name == null || name.isBlank() ? Build.MODEL : name;
    }

    /** The Wi-Fi network name from /health ("\"Home\" aa:bb:…"); never the BSSID. */
    String wifiName() {
        int a = wifi.indexOf('"'), b = wifi.lastIndexOf('"');
        return a >= 0 && b > a ? wifi.substring(a + 1, b) : "";
    }

    String appLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) { return pkg; }
    }

    String locationState() {
        if (!LocationProvider.allowed(this)) return AppCopy.LOCATION_OFF;
        return LocationProvider.granted(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ? AppCopy.LOCATION_PRECISE : AppCopy.LOCATION_APPROX;
    }

    // ---- Account: assistants, devices, requests ----

    boolean accountServer() { return config.hasAssistant() && SetupRules.sameServer(config.relayUrl, SetupRules.DEFAULT_RELAY); }

    private String deviceNamed(String id) {
        if (account != null) for (AccountClient.Device d : account.devices) if (d.id.equals(id)) return d.name;
        return null;
    }
    AccountClient.Assistant assistantById(String id) {
        if (account == null || id == null || id.isEmpty()) return null;
        for (AccountClient.Assistant a : account.assistants) if (a.id.equals(id)) return a;
        return null;
    }
    String assistantState(AccountClient.Assistant a) {
        if (a.paused) return AppCopy.STATUS_PAUSED;
        if (a.screens > 0) return AppCopy.activeScreensLabel(a.screens);
        return String.format(AppCopy.ASSISTANT_CAN_USE_FMT, usesText(a));
    }
    private String usesText(AccountClient.Assistant a) {
        if (account == null) return "";
        if (a.all()) return account.devices.size() > 1 ? AppCopy.USES_ALL_DEVICES : AppCopy.USES_THIS_PHONE;
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (AccountClient.Device d : account.devices) if (a.canUse(d.id)) names.add(d.name);
        return names.isEmpty() ? AppCopy.USES_NO_DEVICE : String.join(", ", names);
    }
    /** The assistant's logo on a light chip, so dark logos stay visible in dark mode. */
    ImageView assistantIcon(UiKit k, String icon) {
        ImageView view = new ImageView(this); view.setImageResource(iconFor(icon)); view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setBackground(k.shape(Color.WHITE, 12, k.stroke)); view.setPadding(k.dp(7), k.dp(7), k.dp(7), k.dp(7));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return view;
    }
    private int iconFor(String icon) {
        @android.annotation.SuppressLint("DiscouragedApi")
        int id = getResources().getIdentifier("assistant_" + (icon == null ? "mcp" : icon.replaceAll("[^a-z]", "")), "drawable", getPackageName());
        return id == 0 ? R.drawable.assistant_mcp : id;
    }

    void decideRequest(AccountClient.Request r, boolean allow) {
        Session state = session; state.busy = true; state.message = allow ? AppCopy.ALLOWING : AppCopy.DENYING; renderNow();
        final Context app = getApplicationContext();
        new Thread(() -> {
            String message = AgentRequests.decide(app, r.id, allow);
            ui.post(() -> { requests.removeIf(x -> x.id.equals(r.id)); accountVersion++; accountLoadedAt = 0; refreshAccount(true); });
            state.done(message);
        }, "backstage-decide").start();
    }

    long installedVersionCode() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); }
        catch (Exception e) { return Long.MAX_VALUE; }
    }

    /** Manual, user-triggered check only (zero background polling). */
    void checkForUpdates() {
        if (checkingUpdate) return;
        checkingUpdate = true;
        updateStatus = AppCopy.UPDATE_CHECKING;
        accountVersion++;
        renderNow();
        final String base = accountServer() ? config.relayUrl : SetupRules.DEFAULT_RELAY;
        new Thread(() -> {
            AccountClient.Update found = null;
            String err = null;
            try { found = new AccountClient(base).latestApp(); }
            catch (Exception e) { err = AppCopy.UPDATE_CHECK_FAILED; }
            final AccountClient.Update result = found;
            final String error = err;
            ui.post(() -> {
                checkingUpdate = false;
                if (error != null) {
                    updateStatus = "";
                    toast(error);
                } else if (result != null && result.versionCode > installedVersionCode()) {
                    availableUpdate = result;
                    updateStatus = String.format(AppCopy.UPDATE_AVAILABLE_FMT, result.versionName);
                    if (active && !isDestroyed()) showUpdateDialog(result);
                } else {
                    availableUpdate = null;
                    updateStatus = AppCopy.UPDATE_UP_TO_DATE;
                    toast(String.format(AppCopy.UPDATE_UP_TO_DATE_FMT, BuildInfo.VERSION));
                }
                accountVersion++;
                renderNow();
            });
        }, "backstage-update-check").start();
    }

    void showUpdateDialog(AccountClient.Update update) {
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(AppCopy.UPDATE_TITLE)
                .setMessage(String.format(AppCopy.UPDATE_BODY_FMT, update.versionName))
                .setNegativeButton(AppCopy.CANCEL, null);
        if (daemonUp) {
            b.setPositiveButton(AppCopy.UPDATE_ACTION, (d, w) -> installUpdate(update));
        } else {
            b.setPositiveButton(AppCopy.UPDATE_OPEN_BROWSER, (d, w) -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(update.url))); }
                catch (Exception e) { toast(AppCopy.UPDATE_FAILED); }
            });
        }
        b.show();
    }

    void installUpdate(AccountClient.Update update) {
        Session state = session; state.busy = true; state.message = AppCopy.UPDATE_IN_PROGRESS; renderNow();
        daemon.post("/update", Json.mapOf("url", update.url, "sha256", update.sha256),
                (r, e) -> { state.done(e == null ? AppCopy.UPDATE_RESTARTING : AppCopy.UPDATE_FAILED); renderNow(); });
    }
    void refreshAccount(boolean force) {
        if (!accountServer()) return;
        if (!force && account != null && System.currentTimeMillis() - accountLoadedAt < 20000) return;
        if (!accountLoading.compareAndSet(false, true)) return;
        if (accountClient == null) accountClient = new AccountClient(config.relayUrl);
        final AccountClient client = accountClient; final List<DaemonConfig.Link> links = new java.util.ArrayList<>(config.links);
        new Thread(() -> {
            AccountClient.Account loaded = null; String error = ""; List<AccountClient.Request> waiting = null;
            try { client.signIn(links); loaded = client.account(); waiting = client.requests(false); }
            catch (Exception e) { error = account == null ? AccountClient.explain(e) : ""; }
            final AccountClient.Account result = loaded; final String message = error; final List<AccountClient.Request> pendingRequests = waiting;
            ui.post(() -> {
                accountLoading.set(false);
                boolean requestsChanged = pendingRequests != null && !sameRequests(requests, pendingRequests);
                boolean newRequest = requestsChanged && requests.isEmpty() && !pendingRequests.isEmpty();
                if (pendingRequests != null) requests = pendingRequests;
                boolean changed = requestsChanged || (result != null && (account == null || !sameAccount(account, result))) || !message.equals(accountError);
                if (result != null) { account = result; accountLoadedAt = System.currentTimeMillis(); accountError = ""; rememberAccountName(); }
                else if (!message.isEmpty()) accountError = message;
                if (changed) accountVersion++;
                if (newRequest && shellMode() && focusConfirm == null) selectTab(TAB_ASSISTANTS);
                else if (changed && active) renderNow();
            });
        }, "backstage-account").start();
    }
    /** The account's name for this device is the only name shown, even before the account loads next time. */
    private void rememberAccountName() {
        String name = account == null ? null : deviceNamed(account.thisDevice);
        if (name == null || name.isBlank() || name.equals(config.deviceName)) return;
        AgentConfig current = AgentConfig.load(this); current.deviceName = name; current.save(this); config = current;
    }
    private static boolean sameRequests(List<AccountClient.Request> a, List<AccountClient.Request> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).id.equals(b.get(i).id)) return false;
        return true;
    }
    private static boolean sameAccount(AccountClient.Account a, AccountClient.Account b) {
        if (a.devices.size() != b.devices.size() || a.assistants.size() != b.assistants.size()) return false;
        for (int i = 0; i < a.devices.size(); i++) { AccountClient.Device x = a.devices.get(i), y = b.devices.get(i); if (!x.id.equals(y.id) || !x.name.equals(y.name) || x.online != y.online || x.needsReconnect != y.needsReconnect || !x.status.equals(y.status) || !x.model.equals(y.model)) return false; }
        for (int i = 0; i < a.assistants.size(); i++) { AccountClient.Assistant x = a.assistants.get(i), y = b.assistants.get(i); if (!x.id.equals(y.id) || !x.name.equals(y.name) || x.paused != y.paused || x.screens != y.screens || !String.valueOf(x.access).equals(String.valueOf(y.access))) return false; }
        return true;
    }
    /** Run one account change off the main thread, then reload. */
    private void accountAction(String working, AccountAction action, String done) {
        Session state = session; state.busy = true; state.message = working; renderNow();
        final AccountClient client = accountClient;
        new Thread(() -> {
            try {
                action.run(client); AccountClient.Account loaded = client.account();
                ui.post(() -> { account = loaded; accountLoadedAt = System.currentTimeMillis(); accountVersion++; renderNow(); });
                state.done(done);
            } catch (Exception e) { state.done(AccountClient.explain(e)); }
        }, "backstage-account-action").start();
    }
    private interface AccountAction { void run(AccountClient client) throws Exception; }

    void toggleAssistantPause(AccountClient.Assistant a) {
        accountAction(a.paused ? AppCopy.RESUMING : AppCopy.PAUSING, c -> c.setPaused(a.id, !a.paused),
                a.paused ? String.format(AppCopy.ASSISTANT_RESUMED_FMT, a.name) : String.format(AppCopy.ASSISTANT_PAUSED_FMT, a.name));
    }
    void confirmRemoveAssistant(AccountClient.Assistant a) {
        new AlertDialog.Builder(this).setTitle(String.format(AppCopy.REMOVE_NAMED_FMT, a.name)).setMessage(String.format(AppCopy.REMOVE_ASSISTANT_BODY_FMT, a.name))
                .setPositiveButton(AppCopy.REMOVE, (x, y) -> accountAction(AppCopy.REMOVING, c -> c.removeAssistant(a.id), String.format(AppCopy.REMOVED_FMT, a.name)))
                .setNegativeButton(AppCopy.CANCEL, null).show();
    }

    /** Email, passkeys and recovery codes: every way to sign in to this account. */
    void signInMethodsDialog() {
        if (accountClient == null) { toast(AppCopy.WAIT_FOR_ACCOUNT); return; }
        final AccountClient client = accountClient; final List<DaemonConfig.Link> links = new java.util.ArrayList<>(config.links);
        new Thread(() -> {
            try { client.signIn(links); AccountClient.Methods m = client.methods(); ui.post(() -> { if (!isDestroyed()) showMethods(m); }); }
            catch (Exception e) { ui.post(() -> toast(AccountClient.explain(e))); }
        }, "backstage-methods").start();
    }
    private void showMethods(AccountClient.Methods m) {
        UiKit k = kit; LinearLayout form = k.form(); AlertDialog[] self = new AlertDialog[1];
        k.title(form, AppCopy.FIELD_EMAIL);
        k.body(form, m.email.isEmpty() ? (m.emailAvailable ? AppCopy.EMAIL_ADD_BODY : AppCopy.EMAIL_UNAVAILABLE) : String.format(AppCopy.EMAIL_CURRENT_FMT, m.email));
        if (m.emailAvailable) k.button(form, m.email.isEmpty() ? AppCopy.ACTION_ADD_EMAIL : AppCopy.ACTION_CHANGE_EMAIL, UiKit.SECONDARY, () -> { self[0].dismiss(); emailDialog(); });
        if (!m.email.isEmpty()) k.button(form, AppCopy.ACTION_REMOVE_EMAIL, UiKit.SECONDARY, () -> { self[0].dismiss(); accountAction(AppCopy.REMOVING, AccountClient::emailRemove, AppCopy.EMAIL_REMOVED); });
        if (!m.email.isEmpty()) {
            k.space(form, 14); k.title(form, AppCopy.PASSWORD_TITLE);
            k.body(form, m.hasPassword ? AppCopy.PASSWORD_SET_BODY : AppCopy.PASSWORD_NONE_BODY);
            k.button(form, m.hasPassword ? AppCopy.ACTION_CHANGE_PASSWORD : AppCopy.ACTION_SET_PASSWORD, UiKit.SECONDARY, () -> { self[0].dismiss(); passwordDialog(m.hasPassword); });
        }
        k.space(form, 14); k.title(form, AppCopy.RECOVERY_CODES_TITLE);
        k.body(form, m.recoveryLeft > 0 ? String.format(java.util.Locale.getDefault(), AppCopy.RECOVERY_LEFT_FMT, m.recoveryLeft) : AppCopy.RECOVERY_NONE);
        k.button(form, m.recoveryLeft > 0 ? AppCopy.ACTION_REPLACE_RECOVERY : AppCopy.ACTION_CREATE_RECOVERY, UiKit.SECONDARY, () -> { self[0].dismiss(); newRecoveryCodes(); });
        k.space(form, 14); k.title(form, AppCopy.PASSKEYS_TITLE);
        if (m.passkeys.isEmpty()) k.body(form, AppCopy.PASSKEYS_NONE);
        for (String[] key : m.passkeys) k.button(form, String.format(AppCopy.REMOVE_PASSKEY_FMT, key[1]), UiKit.SECONDARY, () -> { self[0].dismiss();
            new AlertDialog.Builder(this).setTitle(AppCopy.REMOVE_PASSKEY_TITLE).setPositiveButton(AppCopy.REMOVE, (x, y) -> accountAction(AppCopy.REMOVING, c -> c.removePasskey(key[0]), AppCopy.PASSKEY_REMOVED)).setNegativeButton(AppCopy.CANCEL, null).show(); });
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        self[0] = new AlertDialog.Builder(this).setTitle(AppCopy.ACTION_SIGN_IN_METHODS).setView(scroll).setNegativeButton(AppCopy.CLOSE, null).show();
    }
    private void emailDialog() {
        UiKit k = kit; LinearLayout form = k.form();
        EditText email = k.field(form, AppCopy.FIELD_EMAIL, AppCopy.FIELD_EMAIL_HINT, ""); email.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        int labelIndex = form.getChildCount();
        EditText code = k.field(form, AppCopy.FIELD_EMAIL_CODE, AppCopy.FIELD_EMAIL_CODE_HINT, ""); code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        List<View> codeViews = new java.util.ArrayList<>();
        for (int i = labelIndex; i < form.getChildCount(); i++) codeViews.add(form.getChildAt(i));
        for (View v : codeViews) v.setVisibility(View.GONE);
        TextView status = k.body(form, "");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(AppCopy.EMAIL_DIALOG_TITLE).setView(form).setPositiveButton(AppCopy.ACTION_EMAIL_CODE, null).setNegativeButton(AppCopy.CANCEL, null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String address = email.getText().toString().trim(), entered = code.getText().toString().trim(); final AccountClient client = accountClient;
            v.setEnabled(false);
            new Thread(() -> {
                try {
                    if (code.getVisibility() != View.VISIBLE) { client.emailStart(address); ui.post(() -> { for (View c : codeViews) c.setVisibility(View.VISIBLE); status.setText(AppCopy.EMAIL_SENT); ((Button) v).setText(AppCopy.ACTION_CONFIRM); v.setEnabled(true); }); }
                    else { client.emailVerify(address, entered); ui.post(() -> { dialog.dismiss(); toast(AppCopy.EMAIL_ADDED); }); }
                } catch (Exception e) { ui.post(() -> { status.setText(AccountClient.explain(e)); v.setEnabled(true); }); }
            }, "backstage-email").start();
        }));
        dialog.show();
    }
    private void passwordDialog(boolean hasCurrent) {
        UiKit k = kit; LinearLayout form = k.form();
        EditText current = hasCurrent ? k.field(form, AppCopy.FIELD_CURRENT_PASSWORD, "", "") : null;
        if (current != null) { current.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD); current.setTypeface(android.graphics.Typeface.DEFAULT); }
        EditText next = k.field(form, hasCurrent ? AppCopy.FIELD_NEW_PASSWORD : AppCopy.FIELD_PASSWORD, AppCopy.FIELD_PASSWORD_HINT, "");
        next.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD); next.setTypeface(android.graphics.Typeface.DEFAULT);
        EditText confirm = k.field(form, AppCopy.FIELD_CONFIRM_PASSWORD, AppCopy.FIELD_PASSWORD_HINT, "");
        confirm.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD); confirm.setTypeface(android.graphics.Typeface.DEFAULT);
        TextView status = k.body(form, "");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(hasCurrent ? AppCopy.ACTION_CHANGE_PASSWORD : AppCopy.ACTION_SET_PASSWORD)
                .setView(form).setPositiveButton(AppCopy.SAVE, null).setNegativeButton(AppCopy.CANCEL, null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String cur = current == null ? null : current.getText().toString();
            String pw = next.getText().toString(), cf = confirm.getText().toString();
            if (pw.length() < 8) { status.setText(AppCopy.ERROR_PASSWORD_SHORT); return; }
            if (!pw.equals(cf)) { status.setText(AppCopy.ERROR_PASSWORD_MISMATCH); return; }
            v.setEnabled(false); final AccountClient client = accountClient;
            new Thread(() -> {
                try { client.setPassword(cur, pw); ui.post(() -> { dialog.dismiss(); toast(AppCopy.PASSWORD_SAVED); }); }
                catch (Exception e) { ui.post(() -> { status.setText(AccountClient.explain(e)); v.setEnabled(true); }); }
            }, "backstage-password").start();
        }));
        dialog.show();
    }
    private void newRecoveryCodes() {
        final AccountClient client = accountClient;
        new Thread(() -> {
            try { List<String> codes = client.newRecoveryCodes(); String text = String.join("\n", codes);
                ui.post(() -> new AlertDialog.Builder(this).setTitle(AppCopy.RECOVERY_CODES_TITLE).setMessage(text + "\n\n" + AppCopy.RECOVERY_SAVE_BODY)
                        .setPositiveButton(AppCopy.COPY, (d, w) -> { copy(text, true); toast(AppCopy.RECOVERY_COPIED); }).setNegativeButton(AppCopy.DONE, null).show()); }
            catch (Exception e) { ui.post(() -> toast(AccountClient.explain(e))); }
        }, "backstage-recovery").start();
    }
    private void devicePicker(LinearLayout form, AccountClient.Assistant current, List<android.widget.CheckBox> boxes, android.widget.CheckBox[] future) {
        for (AccountClient.Device d : account.devices) {
            android.widget.CheckBox box = new android.widget.CheckBox(this); box.setText(d.thisDevice ? d.name + " (" + AppCopy.BADGE_THIS_PHONE + ")" : d.name);
            box.setTextColor(kit.ink); box.setTextSize(16);
            box.setChecked(current == null || current.canUse(d.id)); box.setTag(d.id); boxes.add(box); form.addView(box);
        }
        future[0] = new android.widget.CheckBox(this); future[0].setText(AppCopy.CHECK_FUTURE_DEVICES); future[0].setTextColor(kit.ink); future[0].setTextSize(16);
        future[0].setChecked(current == null || current.all()); form.addView(future[0]);
    }
    private boolean allChecked(List<android.widget.CheckBox> boxes, android.widget.CheckBox future) {
        if (!future.isChecked()) return false; for (android.widget.CheckBox b : boxes) if (!b.isChecked()) return false; return true;
    }
    private List<String> checkedIds(List<android.widget.CheckBox> boxes) {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>(); for (android.widget.CheckBox b : boxes) if (b.isChecked()) ids.add(String.valueOf(b.getTag())); return ids;
    }
    void assistantDialog(AccountClient.Assistant a) {
        UiKit k = kit; LinearLayout form = k.form();
        if (!a.origin.isEmpty()) k.body(form, a.origin);
        k.text(form, assistantState(a), 15, a.paused ? k.danger : k.ink, true).setPadding(0, k.dp(6), 0, 0);
        AlertDialog[] self = new AlertDialog[1];
        LinearLayout row = k.buttonRow(form);
        if (a.screens > 0) k.rowButton(row, AppCopy.ACTION_STOP_WORK, UiKit.SECONDARY, () -> { self[0].dismiss(); accountAction(AppCopy.STOPPING, c -> c.stopWork(a.id), String.format(AppCopy.SCREENS_CLOSED_FMT, a.name)); });
        k.rowButton(row, a.paused ? AppCopy.ACTION_RESUME : AppCopy.ACTION_PAUSE, UiKit.SECONDARY, () -> { self[0].dismiss(); toggleAssistantPause(a); });
        k.space(form, 12);
        k.title(form, AppCopy.DIALOG_DEVICES_ACCESS);
        List<android.widget.CheckBox> boxes = new java.util.ArrayList<>(); android.widget.CheckBox[] future = new android.widget.CheckBox[1];
        devicePicker(form, a, boxes, future);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(a.name).setView(scroll)
                .setPositiveButton(AppCopy.SAVE, null).setNegativeButton(AppCopy.CANCEL, null).setNeutralButton(AppCopy.ACTION_REMOVE_ACCESS, null).create();
        self[0] = dialog;
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                boolean all = allChecked(boxes, future[0]); List<String> ids = checkedIds(boxes);
                if (!all && ids.isEmpty()) { toast(AppCopy.ERROR_SELECT_DEVICE); return; }
                dialog.dismiss(); accountAction(AppCopy.SAVING, c -> c.setAccess(a.id, all, ids), String.format(AppCopy.UPDATED_FMT, a.name));
            });
            Button remove = dialog.getButton(AlertDialog.BUTTON_NEUTRAL); remove.setTextColor(kit.danger);
            remove.setOnClickListener(v -> { dialog.dismiss(); confirmRemoveAssistant(a); });
        }); dialog.show();
    }
    void deviceDialog(AccountClient.Device d) {
        UiKit k = kit; LinearLayout form = k.form();
        EditText name = k.field(form, AppCopy.FIELD_DEVICE_NAME, AppCopy.FIELD_DEVICE_NAME_HINT, d.name);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});
        k.body(form, d.needsReconnect ? AppCopy.DEVICE_NAME_BODY + " " + AppCopy.DEVICE_LOST_LINK : AppCopy.DEVICE_NAME_BODY).setPadding(0, k.dp(10), 0, 0);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(d.thisDevice ? AppCopy.BADGE_THIS_PHONE : d.name).setView(form).setPositiveButton(AppCopy.SAVE, null).setNegativeButton(AppCopy.CANCEL, null);
        if (!d.thisDevice) builder.setNeutralButton(AppCopy.DIALOG_REMOVE_DEVICE, null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = name.getText().toString().trim();
                if (value.isEmpty()) { name.setError(AppCopy.ERROR_NAME_REQUIRED); return; }
                dialog.dismiss(); if (!value.equals(d.name)) accountAction(AppCopy.RENAMING, c -> c.rename(d.id, value), String.format(AppCopy.RENAMED_FMT, value));
            });
            if (!d.thisDevice) { Button remove = dialog.getButton(AlertDialog.BUTTON_NEUTRAL); remove.setTextColor(kit.danger);
                remove.setOnClickListener(v -> { dialog.dismiss();
                new AlertDialog.Builder(this).setTitle(String.format(AppCopy.REMOVE_NAMED_FMT, d.name)).setMessage(AppCopy.REMOVE_DEVICE_BODY)
                        .setPositiveButton(AppCopy.REMOVE, (a, b) -> accountAction(AppCopy.REMOVING, c -> c.removeDevice(d.id), String.format(AppCopy.REMOVED_FMT, d.name))).setNegativeButton(AppCopy.CANCEL, null).show(); }); }
        }); dialog.show();
    }
    void addDeviceDialog() {
        UiKit k = kit; LinearLayout form = k.form();
        EditText name = k.field(form, AppCopy.FIELD_DEVICE_NAME, AppCopy.FIELD_NEW_DEVICE_HINT, "");
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});
        k.body(form, AppCopy.ADD_DEVICE_BODY).setPadding(0, k.dp(10), 0, 0);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(AppCopy.ACTION_ADD_DEVICE).setView(form).setPositiveButton(AppCopy.ACTION_GET_CODE, null).setNegativeButton(AppCopy.CANCEL, null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = name.getText().toString().trim(); dialog.dismiss();
            final AccountClient client = accountClient; Session state = session; state.busy = true; state.message = AppCopy.GETTING_CODE; renderNow();
            new Thread(() -> {
                try { Map<String, Object> started = client.addDevice(label); state.done(""); ui.post(() -> { renderNow(); showAddCode(started); }); }
                catch (Exception e) { state.done(AccountClient.explain(e)); }
            }, "backstage-add-device").start();
        })); dialog.show();
    }
    private void showAddCode(Map<String, Object> started) {
        final String ticket = String.valueOf(started.get("ticket")), code = String.valueOf(started.get("code")), label = String.valueOf(started.get("deviceName"));
        final long deadline = android.os.SystemClock.elapsedRealtime() + 600000;
        UiKit k = kit; LinearLayout form = k.form();
        k.body(form, AppCopy.ADD_CODE_BODY);
        TextView big = k.text(form, code, 30, k.ink, true); big.setTypeface(Typeface.MONOSPACE); big.setLetterSpacing(0.15f); big.setGravity(Gravity.CENTER); big.setTextIsSelectable(true);
        big.setPadding(0, k.dp(14), 0, k.dp(14));
        TextView status = k.body(form, String.format(AppCopy.ADD_CODE_WAITING_FMT, label));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(String.format(AppCopy.ADD_NAMED_FMT, label)).setView(form).setNegativeButton(AppCopy.CANCEL, null).create();
        final AccountClient client = accountClient; final boolean[] stopped = {false};
        dialog.setOnDismissListener(d -> { if (!stopped[0]) { stopped[0] = true; new Thread(() -> client.cancelAdd(ticket), "backstage-add-cancel").start(); } });
        Runnable poll = new Runnable() { @Override public void run() {
            if (stopped[0]) return;
            if (android.os.SystemClock.elapsedRealtime() >= deadline) { status.setText(AppCopy.CODE_EXPIRED); stopped[0] = true; return; }
            new Thread(() -> {
                try { String id = client.pollAdd(ticket); ui.post(() -> { if (stopped[0]) return; if (id != null) { stopped[0] = true; dialog.dismiss(); toast(String.format(AppCopy.DEVICE_JOINED_FMT, label)); refreshAccount(true); } else ui.postDelayed(this, 2000); }); }
                catch (java.io.IOException e) { ui.post(() -> {
                    if (stopped[0]) return;
                    if (e instanceof SimpleHttp.HttpException && ((SimpleHttp.HttpException) e).status < 500) {
                        status.setText(AccountClient.explain(e)); stopped[0] = true;
                    } else { status.setText(AppCopy.CONNECTION_INTERRUPTED); ui.postDelayed(this, 2000); }
                }); }
                catch (Exception e) { ui.post(() -> { if (stopped[0]) return; status.setText(AccountClient.explain(e)); stopped[0] = true; }); }
            }, "backstage-add-poll").start();
        } };
        dialog.show(); ui.postDelayed(poll, 1500);
    }

    // ---- Connecting an agent (requests start on the computer; invitation codes remain for hosts that need them) ----

    private android.content.SharedPreferences invitePrefs() { return getSharedPreferences("backstage-invitation", 0); }
    private void clearInvitation() { invitePrefs().edit().clear().commit(); InvitationNotifier.cancel(this); }
    private void chooseAgent() {
        if (!connected) { session.message = AppCopy.CONNECT_PHONE_FIRST; syncChrome(); return; }
        String pending = invitePrefs().getString("code", "");
        if (!pending.isEmpty()) { resumeInvitation(); return; }
        String address = SetupRules.DEFAULT_RELAY + "/work/mcp";
        String one = String.format(AppCopy.AGENT_INSTRUCTIONS_FMT, SetupRules.DEFAULT_RELAY + "/work/connect/instructions");
        UiKit k = kit; LinearLayout form = k.form();
        k.text(form, AppCopy.CONNECT_AGENT_BODY, 15, k.ink, false);
        TextView url = k.text(form, address, 14, k.ink, true); url.setTypeface(Typeface.MONOSPACE); url.setTextIsSelectable(true); url.setPadding(0, k.dp(10), 0, 0);
        k.button(form, AppCopy.ACTION_COPY_ADDRESS, UiKit.PRIMARY, () -> { copy(address, false); toast(AppCopy.ADDRESS_COPIED); });
        k.button(form, AppCopy.ACTION_COPY_INSTRUCTIONS, UiKit.SECONDARY, () -> { copy(one, false); toast(AppCopy.PASTE_INTO_AGENT); });
        k.body(form, AppCopy.CONNECT_AGENT_APPROVE).setPadding(0, k.dp(12), 0, 0);
        new AlertDialog.Builder(this).setTitle(AppCopy.ACTION_CONNECT_AGENT).setView(form)
                .setNeutralButton(AppCopy.ACTION_CONNECTION_CODE, (d, w) -> chooseInvitationAgent()).setNegativeButton(AppCopy.DONE, null).show();
    }
    private void chooseInvitationAgent() {
        new AlertDialog.Builder(this).setTitle(AppCopy.CHOOSE_AGENT_TITLE)
                .setItems(new String[]{AppCopy.AGENT_CHATGPT, AppCopy.AGENT_CURSOR, AppCopy.AGENT_CODEX, AppCopy.AGENT_CLAUDE_CODE, AppCopy.AGENT_OTHER}, (d, w) ->
                        createInvitation(new String[]{"chatgpt", "cursor", "codex", "claude-code", "other"}[w])).setNegativeButton(AppCopy.CANCEL, null).show();
    }
    private void createInvitation(String agent) {
        if (accountClient == null || account == null) { toast(AppCopy.WAIT_FOR_ACCOUNT); refreshAccount(true); return; }
        final AccountClient client = accountClient;
        session.busy = true; session.message = AppCopy.CREATING_REQUEST; renderNow();
        new Thread(() -> {
            try {
                Map<String, Object> invite = client.invitation(); String code = Json.string(invite, "code");
                invitePrefs().edit().putString("code", code).putString("agent", agent)
                        .putLong("expires", System.currentTimeMillis() + 600000).commit();
                session.done(""); ui.post(() -> { if (!isDestroyed()) { renderNow(); resumeInvitation(); } });
            } catch (Exception e) { session.done(AccountClient.explain(e)); }
        }, "backstage-invite").start();
    }
    private void resumeInvitation() {
        if (!active || invitationVisible || session.busy || accountClient == null || account == null) return;
        String code = invitePrefs().getString("code", ""); if (code.isEmpty()) return;
        if (invitePrefs().getLong("expires", 0) <= System.currentTimeMillis()) {
            clearInvitation(); session.message = AppCopy.REQUEST_EXPIRED; syncChrome(); return;
        }
        showInvitation(accountClient, code, invitePrefs().getString("agent", "other"));
    }
    private void cancelInvitation(AccountClient client, String code) {
        clearInvitation(); new Thread(() -> { try { client.cancelInvitation(code); } catch (Exception ignored) { } }, "backstage-cancel-invite").start();
    }
    /** Close the invitation prompt on screen, if any, so two prompts never stack. */
    private void closeInvitationDialog() {
        AlertDialog old = currentInvitationDialog; currentInvitationDialog = null;
        if (old != null && old.isShowing()) old.dismiss();
    }
    private static String agentName(String agent) {
        switch (agent) {
            case "claude-code": return AppCopy.AGENT_CLAUDE_CODE;
            case "chatgpt": return AppCopy.AGENT_CHATGPT;
            case "codex": return AppCopy.AGENT_CODEX;
            case "cursor": return AppCopy.AGENT_CURSOR;
            default: return AppCopy.AGENT_YOURS;
        }
    }
    private void showInvitation(AccountClient client, String code, String agent) {
        if (isDestroyed()) return;
        closeInvitationDialog();
        invitationVisible = true;
        String name = agentName(agent);
        String message = String.format(AppCopy.INVITE_MESSAGE_FMT, name, agent, code);
        UiKit k = kit; LinearLayout form = k.form();
        if (agent.equals("chatgpt")) {
            k.text(form, AppCopy.CHATGPT_STEP_PLUGIN, 15, k.ink, false);
            k.body(form, AppCopy.CHATGPT_DEV_MODE);
            k.button(form, AppCopy.ACTION_OPEN_CHATGPT, UiKit.PRIMARY, () -> openBackstagePage("https://chatgpt.com/plugins"));
            k.button(form, AppCopy.ACTION_COPY_ADDRESS, UiKit.SECONDARY, () -> { copy(SetupRules.DEFAULT_RELAY + "/work/mcp", false); toast(AppCopy.PASTE_ADDRESS_CHATGPT); });
            k.text(form, AppCopy.CHATGPT_STEP_CODE, 15, k.ink, false).setPadding(0, k.dp(14), 0, 0);
            k.button(form, AppCopy.ACTION_COPY_CONNECTION_CODE, UiKit.PRIMARY, () -> { copy(code, true); toast(AppCopy.PASTE_CODE_PAGE); });
        } else {
            k.text(form, String.format(AppCopy.INVITE_PASTE_FMT, name), 15, k.ink, false);
            k.button(form, AppCopy.ACTION_COPY_SETUP_MESSAGE, UiKit.PRIMARY, () -> { copy(message, true); toast(String.format(AppCopy.PASTE_INTO_FMT, name)); });
            k.button(form, AppCopy.ACTION_COPY_CONNECTION_CODE, UiKit.SECONDARY, () -> { copy(code, true); toast(AppCopy.USE_CODE_IF_PAGE); });
            if (agent.equals("codex")) k.body(form, AppCopy.CODEX_LOCAL_ONLY);
        }
        k.body(form, AppCopy.INVITE_LASTS).setPadding(0, k.dp(12), 0, 0);
        TextView status = k.text(form, String.format(AppCopy.WAITING_FOR_FMT, name), 15, k.ink, true); status.setPadding(0, k.dp(10), 0, 0);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(String.format(AppCopy.CONNECT_NAMED_FMT, name)).setView(scroll)
                .setNegativeButton(AppCopy.CANCEL, (d, w) -> cancelInvitation(client, code)).create();
        final boolean[] stopped = {false};
        k.button(form, AppCopy.ACTION_START_AGAIN, UiKit.QUIET, () -> { cancelInvitation(client, code); dialog.dismiss(); createInvitation(agent); });
        dialog.setOnCancelListener(d -> cancelInvitation(client, code));
        // A replaced prompt is dismissed after its successor shows; only the prompt
        // still on screen may mark the invitation as hidden.
        dialog.setOnDismissListener(d -> { stopped[0] = true; if (currentInvitationDialog == dialog || currentInvitationDialog == null) invitationVisible = false; });
        Runnable poll = new Runnable() { public void run() {
            if (stopped[0] || isDestroyed()) return;
            new Thread(() -> {
                try { Map<String, Object> row = client.invitationStatus(code); ui.post(() -> {
                    if (stopped[0] || isDestroyed()) return;
                    String state = Json.string(row, "state");
                    if ("waiting".equals(state)) {
                        String approval = Json.string(row, "approvalCode");
                        // Keep waiting only while the approval prompt is the one on screen.
                        if (approval.equals(confirmShownFor) && currentInvitationDialog != dialog) { ui.postDelayed(this, 2000); return; }
                        if (!approval.equals(confirmShownFor)) InvitationNotifier.showApproval(AgentActivity.this, Json.string(row, "clientName"));
                        confirmInvitation(client, code, row); return;
                    }
                    if ("approved".equals(state)) { clearInvitation(); dialog.dismiss(); toast(AppCopy.ASSISTANT_CONNECTED); refreshAccount(true); return; }
                    if ("cancelled".equals(state) || "denied".equals(state)) {
                        clearInvitation(); status.setText(AppCopy.REQUEST_ENDED); return;
                    }
                    ui.postDelayed(this, 2000);
                }); } catch (Exception e) { ui.post(() -> { if (stopped[0] || isDestroyed()) return;
                    if (e instanceof SimpleHttp.HttpException && ((SimpleHttp.HttpException) e).status != 429 && ((SimpleHttp.HttpException) e).status < 500) {
                        clearInvitation(); status.setText(AppCopy.REQUEST_ENDED); return;
                    }
                    status.setText(AppCopy.CANT_REACH_RETRYING); ui.postDelayed(this, 4000);
                }); }
            }, "backstage-invite-status").start();
        } };
        currentInvitationDialog = dialog; dialog.show(); ui.post(poll);
    }
    private void confirmInvitation(AccountClient client, String invite, Map<String, Object> row) {
        closeInvitationDialog();
        invitationVisible = true;
        String name = Json.string(row, "clientName"), check = Json.string(row, "check"), code = Json.string(row, "approvalCode"), origin = Json.string(row, "clientOrigin");
        confirmShownFor = code;
        UiKit k = kit; LinearLayout form = k.form();
        if (origin != null && !origin.isEmpty()) k.body(form, origin);
        TextView digits = k.text(form, check, 30, k.ink, true); digits.setLetterSpacing(0.18f); digits.setPadding(0, k.dp(10), 0, k.dp(6));
        k.body(form, AppCopy.INVITE_CHECK_DIGITS);
        TextView error = k.text(form, "", 14, k.danger, false); error.setPadding(0, k.dp(8), 0, 0);
        if (code.equals(confirmErrorFor)) { error.setText(confirmError); } else { error.setVisibility(View.GONE); }
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        // Allow/Deny keep the dialog open on failure with the reason shown and a retry,
        // instead of dropping to a message and popping a fresh prompt.
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(String.format(AppCopy.ALLOW_NAMED_FMT, name)).setView(scroll)
                .setPositiveButton(AppCopy.ACTION_ALLOW, null).setNegativeButton(AppCopy.ACTION_DENY, null).create();
        dialog.setOnCancelListener(d -> { confirmShownFor = null; cancelInvitation(client, invite); });
        dialog.setOnDismissListener(d -> { if (currentInvitationDialog == dialog || currentInvitationDialog == null) invitationVisible = false; });
        dialog.setOnShowListener(d -> {
            Button allow = dialog.getButton(AlertDialog.BUTTON_POSITIVE), deny = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            allow.setOnClickListener(v -> decide(dialog, client, code, true, allow, deny, error));
            deny.setOnClickListener(v -> decide(dialog, client, code, false, allow, deny, error));
        });
        currentInvitationDialog = dialog; dialog.show();
    }
    /** Runs Allow/Deny inside the confirm dialog; failures stay visible with a retry. */
    private void decide(AlertDialog dialog, AccountClient client, String code, boolean allowAccess, Button allow, Button deny, TextView error) {
        allow.setEnabled(false); deny.setEnabled(false);
        error.setText(allowAccess ? AppCopy.ALLOWING : AppCopy.DENYING); error.setVisibility(View.VISIBLE);
        new Thread(() -> {
            Exception failure = null;
            try {
                if (allowAccess) client.approve(code, true, null);
                else client.deny(code);
            } catch (Exception e) { failure = e; }
            final Exception result = failure;
            ui.post(() -> {
                if (isDestroyed()) return;
                if (result == null) {
                    confirmShownFor = null; confirmErrorFor = null; confirmError = "";
                    clearInvitation(); dialog.dismiss();
                    session.message = allowAccess ? AppCopy.ACCESS_ALLOWED_RETURN : AppCopy.REQUEST_DENIED;
                    renderNow(); refreshAccount(true);
                } else {
                    confirmErrorFor = code; confirmError = AccountClient.explain(result);
                    error.setText(confirmError); allow.setEnabled(true); deny.setEnabled(true);
                    if (allowAccess) allow.setText(AppCopy.RETRY);
                }
            });
        }, "backstage-decide").start();
    }
    void approveDialog() {
        UiKit k = kit; LinearLayout form = k.form();
        k.body(form, AppCopy.APPROVAL_CODE_BODY);
        EditText code = k.field(form, AppCopy.FIELD_APPROVAL_CODE, AppCopy.FIELD_APPROVAL_CODE_HINT, "");
        code.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setTypeface(Typeface.MONOSPACE); code.setTextSize(20);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(AppCopy.ACTION_APPROVAL_CODE).setView(form).setPositiveButton(AppCopy.ACTION_NEXT, null).setNegativeButton(AppCopy.CANCEL, null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String typed = code.getText().toString().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            if (typed.length() != 8) { code.setError(AppCopy.ERROR_APPROVAL_CODE); return; }
            dialog.dismiss();
            final AccountClient client = accountClient; Session state = session; state.busy = true; state.message = AppCopy.CHECKING_CODE; renderNow();
            new Thread(() -> {
                try { AccountClient.Approval preview = client.preview(typed); state.done(""); ui.post(() -> { renderNow(); approveConfirm(typed, preview); }); }
                catch (Exception e) { state.done(AccountClient.explain(e)); }
            }, "backstage-approve-preview").start();
        })); dialog.show();
    }
    private void approveConfirm(String code, AccountClient.Approval preview) {
        UiKit k = kit; LinearLayout form = k.form();
        LinearLayout row = k.row();
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(k.dp(44), k.dp(44)); ip.setMarginEnd(k.dp(14)); row.addView(assistantIcon(k, preview.icon), ip);
        LinearLayout column = k.column();
        k.text(column, preview.clientName, 18, k.ink, true);
        if (!preview.clientOrigin.isEmpty()) k.body(column, preview.clientOrigin);
        row.addView(column); form.addView(row);
        k.space(form, 12);
        k.title(form, AppCopy.DIALOG_DEVICES_ACCESS);
        List<android.widget.CheckBox> boxes = new java.util.ArrayList<>(); android.widget.CheckBox[] future = new android.widget.CheckBox[1];
        devicePicker(form, null, boxes, future);
        k.body(form, AppCopy.APPROVE_DEVICES_BODY);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(String.format(AppCopy.ALLOW_NAMED_FMT, preview.clientName)).setView(form)
                .setPositiveButton(AppCopy.ACTION_ALLOW, null).setNegativeButton(AppCopy.ACTION_DENY, (a, b) -> accountAction(AppCopy.DENYING, c -> c.deny(code), AppCopy.REQUEST_DENIED)).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            boolean all = allChecked(boxes, future[0]); List<String> ids = checkedIds(boxes);
            if (!all && ids.isEmpty()) { toast(AppCopy.ERROR_SELECT_DEVICE); return; }
            dialog.dismiss(); accountAction(AppCopy.ALLOWING, c -> c.approve(code, all, ids), String.format(AppCopy.CONNECTED_RETURN_FMT, preview.clientName));
        })); dialog.show();
    }
    private void openBackstagePage(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (Exception e) { toast(AppCopy.OPEN_SITE_FALLBACK); }
    }
    void openAccountPage() { openBackstagePage("https://relay.android-backstage.com/work/account"); }

    // ---- Per-connection controls for phones linked to a server other than the account server ----

    /** Changes when a permit, a connection name or a test's minutes left change. */
    String permitsSignature() {
        StringBuilder sig = new StringBuilder(String.valueOf(permits)).append('|').append(session.busy).append('|').append(System.currentTimeMillis() / 60000);
        for (DaemonConfig.Link link : config.links) sig.append('|').append(AgentConfig.connectionName(this, link.agentId));
        return sig.toString();
    }

    void connectionCard(UiKit k, LinearLayout parent, DaemonConfig.Link link) {
        String id = link.agentId;
        LinearLayout box = k.card(parent);
        k.title(box, AgentConfig.connectionName(this, id));
        String label = AppCopy.PERMIT_RESUME, mode = null;
        if (permits != null) {
            Object row = permits.get(id);
            label = AppCopy.PERMIT_FULL; mode = "full";
            if (row instanceof Map) {
                Map<?, ?> permit = (Map<?, ?>) row;
                mode = String.valueOf(permit.get("mode"));
                if (mode.equals("paused")) label = AppCopy.PERMIT_PAUSED;
                else if (mode.equals("qa")) {
                    Object until = permit.get("expiresAt");
                    long remaining = until instanceof Number ? ((Number) until).longValue() - System.currentTimeMillis() : 0;
                    if (remaining <= 0) mode = "paused";
                    label = remaining > 0 ? String.format(java.util.Locale.ROOT, AppCopy.PERMIT_TESTING_FMT, permit.get("package"), (remaining + 59999) / 60000) : AppCopy.PERMIT_TEST_ENDED;
                } else if (!mode.equals("full")) label = AppCopy.PERMIT_REVIEW;
            }
        }
        k.body(box, label);
        final String current = mode;
        LinearLayout row = k.buttonRow(box);
        Button pause = k.rowButton(row, "paused".equals(current) ? AppCopy.ACTION_ALLOW_ACCESS : AppCopy.ACTION_PAUSE, UiKit.SECONDARY, () -> {
            if (session.busy || current == null) return;
            if ("paused".equals(current)) allowFull(id); else setPermit(id, "paused", "");
        });
        pause.setEnabled(current != null);
        k.rowButton(row, AppCopy.ACTION_RENAME, UiKit.SECONDARY, () -> { if (!session.busy) renameConnection(id); });
        LinearLayout more = k.buttonRow(box);
        k.rowButton(more, AppCopy.ACTION_LIMIT_APP, UiKit.SECONDARY, () -> { if (!session.busy) limitApp(id); });
        k.rowButton(more, AppCopy.REMOVE, UiKit.DANGER, () -> { if (!session.busy) new AlertDialog.Builder(this).setTitle(String.format(AppCopy.REMOVE_NAMED_FMT, AgentConfig.connectionName(this, id)))
                .setMessage(AppCopy.REMOVE_CONNECTION_BODY)
                .setPositiveButton(AppCopy.REMOVE, (a, b) -> forgetAgent(id)).setNegativeButton(AppCopy.CANCEL, null).show(); });
        TextView tag = k.body(box, String.format(AppCopy.CONNECTION_ID_FMT, id.substring(Math.max(0, id.length() - 6)))); tag.setTextSize(12); tag.setPadding(0, k.dp(10), 0, 0);
    }
    void newConnectionCode() { session.page = "connect"; session.code = ""; session.clientName = ""; renderNow(); }
    private void renameConnection(String id) {
        UiKit k = kit; LinearLayout form = k.form();
        EditText name = k.field(form, AppCopy.FIELD_CONNECTION_LABEL, AppCopy.FIELD_CONNECTION_NAME_HINT, AgentConfig.connectionName(this, id));
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(AppCopy.ACTION_RENAME).setMessage(AppCopy.RENAME_CONNECTION_BODY)
                .setView(form).setPositiveButton(AppCopy.SAVE, null).setNegativeButton(AppCopy.CANCEL, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { AgentConfig.renameConnection(this, id, name.getText().toString()); dialog.dismiss(); renderNow(); }
            catch (RuntimeException e) { name.setError(e.getMessage()); }
        })); dialog.show();
    }
    private void allowFull(String id) {
        new AlertDialog.Builder(this).setTitle(String.format(AppCopy.ALLOW_NAMED_FMT, AgentConfig.connectionName(this, id)))
                .setMessage(AppCopy.ALLOW_FULL_BODY)
                .setPositiveButton(AppCopy.ACTION_ALLOW, (a, b) -> setPermit(id, "full", ""))
                .setNegativeButton(AppCopy.CANCEL, null).show();
    }
    private void limitApp(String id) {
        UiKit k = kit; LinearLayout form = k.form();
        java.util.TreeMap<String, String> apps = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (android.content.pm.ResolveInfo app : getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            String pkgName = app.activityInfo.packageName;
            if (seen.add(pkgName)) apps.put(app.loadLabel(getPackageManager()).toString() + "\n" + pkgName, pkgName);
        }
        Map<String, Integer> nameCounts = new java.util.HashMap<>();
        for (String key : apps.keySet()) nameCounts.merge(key.substring(0, key.indexOf('\n')), 1, Integer::sum);
        java.util.ArrayList<String> labels = new java.util.ArrayList<>(); labels.add(AppCopy.SELECT_APP);
        for (Map.Entry<String, String> entry : apps.entrySet()) {
            String label = entry.getKey().substring(0, entry.getKey().indexOf('\n'));
            labels.add(nameCounts.get(label) > 1 ? label + " (" + entry.getValue() + ")" : label);
        }
        labels.add(AppCopy.ENTER_PACKAGE);
        java.util.ArrayList<String> packages = new java.util.ArrayList<>(); packages.add(""); packages.addAll(apps.values()); packages.add("");
        android.widget.Spinner picker = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        picker.setAdapter(adapter); picker.setMinimumHeight(k.dp(52)); form.addView(picker, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout manual = k.column(); form.addView(manual);
        EditText pkg = k.field(manual, AppCopy.FIELD_PACKAGE, AppCopy.FIELD_PACKAGE_HINT, ""); manual.setVisibility(View.GONE);
        picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long itemId) {
                manual.setVisibility(position == labels.size() - 1 ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(AppCopy.LIMIT_APP_TITLE)
                .setMessage(String.format(AppCopy.LIMIT_APP_BODY_FMT, AgentConfig.connectionName(this, id)))
                .setView(form).setPositiveButton(AppCopy.ACTION_ALLOW_15_MIN, null).setNegativeButton(AppCopy.CANCEL, null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int selected = picker.getSelectedItemPosition();
            if (selected == 0) { toast(AppCopy.ERROR_CHOOSE_APP); return; }
            String name = selected == labels.size() - 1 ? pkg.getText().toString().trim() : packages.get(selected);
            if (!name.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")) { pkg.setError(AppCopy.ERROR_PACKAGE); return; }
            dialog.dismiss(); setPermit(id, "qa", name);
        })); dialog.show();
    }
    private void setPermit(String id, String mode, String pkg) {
        Session state = session; Context app = getApplicationContext(); state.busy = true; renderNow();
        new Thread(() -> {
            try {
                AgentConfig c = AgentConfig.load(app);
                SimpleHttp.postJsonOnce(local(c, "/configure"), Json.stringify(Json.mapOf("permit", Json.mapOf("agentId", id, "mode", mode, "package", pkg, "expiresAt", System.currentTimeMillis() + 900000L))), 4000, c.localToken);
                String name = AgentConfig.connectionName(app, id);
                state.done(String.format(mode.equals("qa") ? AppCopy.PERMIT_SET_QA_FMT : mode.equals("paused") ? AppCopy.PERMIT_SET_PAUSED_FMT : AppCopy.PERMIT_SET_FULL_FMT, name));
            } catch (Exception e) { state.done(AppCopy.PERMIT_NOT_CHANGED); }
        }, "backstage-permit").start();
    }
    private void forgetAgent(String id) {
        Session state = session; Context app = getApplicationContext(); state.busy = true; renderNow();
        new Thread(() -> {
            try {
                AgentConfig c = AgentConfig.load(app);
                configure(c, null, null, id); // Revocation must be acknowledged before removing the saved link.
                c.forgetLink(id); c.save(app);
                try {
                    SimpleHttp.requestNoRedirect("POST", c.relayUrl + "/v1/device/revoke-connection",
                            Json.stringify(Json.mapOf("agentId", id)), 10000, c.deviceToken);
                } catch (Exception ignored) { /* Local key removal already blocks all phone commands. */ }
                state.done(AppCopy.CONNECTION_REMOVED);
            } catch (Exception e) { state.done(AppCopy.REMOVAL_UNCONFIRMED); }
        }, "backstage-revoke").start();
    }

    // ---- Sign-in and linking ----

    void passwordLogin() {
        if (session.busy) return;
        final Session state = session;
        if (state.email.trim().isEmpty() || state.password.isEmpty()) { state.message = AppCopy.ERROR_EMAIL_PASSWORD; renderNow(); return; }
        state.busy = true; state.message = AppCopy.SIGNING_IN; renderNow();
        PhoneSignIn.passwordLogin(this, state.email.trim(), state.password, signInCallback(state));
    }
    void signUpStart() {
        if (session.busy) return;
        final Session state = session;
        if (state.password.length() < 8) { state.message = AppCopy.ERROR_PASSWORD_SHORT; renderNow(); return; }
        if (!state.signupSent && !state.password.equals(state.confirmPassword)) { state.message = AppCopy.ERROR_PASSWORD_MISMATCH; renderNow(); return; }
        state.busy = true; state.message = AppCopy.CREATING_ACCOUNT; renderNow();
        PhoneSignIn.signUpStart(this, state.email.trim(), state.password, new PhoneSignIn.Sent() {
            public void sent() { state.signupSent = true; state.done(AppCopy.SIGN_UP_CODE_SENT); ui.post(() -> { if (!isDestroyed()) renderNow(); }); }
            public void failed(String message) { state.done(message); ui.post(() -> { if (!isDestroyed()) renderNow(); }); }
        });
    }
    void signUpVerify() {
        if (session.busy) return;
        final Session state = session; state.busy = true; state.message = AppCopy.VERIFYING_CODE; renderNow();
        PhoneSignIn.signUpVerify(this, state.email.trim(), state.emailCode.trim(), signInCallback(state));
    }
    void passwordResetStart() {
        if (session.busy) return;
        final Session state = session; state.busy = true; state.message = AppCopy.SENDING_CODE; renderNow();
        PhoneSignIn.passwordResetStart(this, state.email.trim(), new PhoneSignIn.Sent() {
            public void sent() { state.resetSent = true; state.done(AppCopy.RESET_CODE_SENT); ui.post(() -> { if (!isDestroyed()) renderNow(); }); }
            public void failed(String message) { state.done(message); ui.post(() -> { if (!isDestroyed()) renderNow(); }); }
        });
    }
    void passwordResetVerify() {
        if (session.busy) return;
        final Session state = session;
        if (state.password.length() < 8) { state.message = AppCopy.ERROR_PASSWORD_SHORT; renderNow(); return; }
        state.busy = true; state.message = AppCopy.RESETTING_PASSWORD; renderNow();
        PhoneSignIn.passwordResetVerify(this, state.email.trim(), state.emailCode.trim(), state.password, signInCallback(state));
    }
    void emailCode() {
        if (session.busy) return;
        final Session state = session; state.busy = true; state.message = AppCopy.SENDING_CODE; renderNow();
        PhoneSignIn.emailStart(this, state.email.trim(), new PhoneSignIn.Sent() {
            public void sent() { state.emailSent = true; state.done(AppCopy.EMAIL_CODE_SENT); ui.post(() -> { if (!isDestroyed()) renderNow(); }); }
            public void failed(String message) { state.done(message); ui.post(() -> { if (!isDestroyed()) renderNow(); }); }
        });
    }
    void emailSignIn() {
        if (session.busy) return;
        final Session state = session; state.busy = true; state.message = AppCopy.SIGNING_IN; renderNow();
        PhoneSignIn.emailVerify(this, state.email.trim(), state.emailCode.trim(), signInCallback(state));
    }
    void recoveryDialog() {
        UiKit k = kit; LinearLayout form = k.form(); EditText code = k.field(form, AppCopy.FIELD_RECOVERY_CODE, AppCopy.FIELD_RECOVERY_CODE_HINT, "");
        new AlertDialog.Builder(this).setTitle(AppCopy.ACTION_RECOVERY_CODE).setView(form).setPositiveButton(AppCopy.SIGN_IN_TITLE, (d, w) -> {
            final Session state = session; state.busy = true; state.message = AppCopy.SIGNING_IN; renderNow();
            PhoneSignIn.recovery(this, code.getText().toString().trim(), signInCallback(state));
        }).setNegativeButton(AppCopy.CANCEL, null).show();
    }
    /** After any sign-in method the device joins the account the same way. */
    private PhoneSignIn.Callback signInCallback(Session state) {
        final Context app = getApplicationContext();
        return new PhoneSignIn.Callback() {
            public void ready(RelayLinker.Result result) {
                AgentConfig current = AgentConfig.load(app);
                current.relayUrl = result.relay; current.deviceToken = result.token;
                current.deviceName = result.name == null ? "" : result.name; current.deviceId = result.deviceId == null ? "" : result.deviceId;
                current.upsertLink(result.agentId, result.linkKey); current.save(app);
                AgentConfig.setAccessEnabled(app, true); state.page = "main"; state.authMode = "login";
                state.emailSent = false; state.signupSent = false; state.resetSent = false;
                state.emailCode = ""; state.password = ""; state.confirmPassword = ""; state.done("");
                ui.post(() -> { if (!isDestroyed()) { config = AgentConfig.load(app); renderNow(); } });
            }
            public void failed(String message) { state.done(message); ui.post(() -> { if (!isDestroyed()) renderNow(); }); }
        };
    }
    void nativeSignIn(boolean create) {
        if (session.busy) return;
        final Session state = session;
        state.busy = true; state.message = AppCopy.OPENING_PASSKEY; renderNow();
        PhoneSignIn.Callback callback = signInCallback(state);
        if (PhoneSignIn.pending(this)) PhoneSignIn.resume(getApplicationContext(), callback);
        else PhoneSignIn.start(this, create, callback);
    }

    void linkRelay() {
        final String server, code, clientName;
        try { server = SetupRules.relayUrl(session.server); code = LinkPairing.normalizeCode(session.code); clientName = "manual".equals(session.page) || session.clientName.isBlank() ? "" : SetupRules.connectionName(session.clientName); }
        catch (RuntimeException e) { session.message = AppCopy.CODE_CHECK; renderNow(); return; }
        if (config.hasAssistant() && !SetupRules.sameServer(server, config.relayUrl)) {
            session.message = AppCopy.OTHER_SERVER; renderNow(); return;
        }
        final Context app = getApplicationContext(); final Session state = session;
        state.busy = true; state.message = AppCopy.APPROVING_CONNECTION; renderNow();
        new Thread(() -> {
            try {
                AgentConfig current = AgentConfig.load(app);
                boolean first = !current.hasAssistant();
                RelayLinker.Result result = RelayLinker.connect(server, code, current.relayUrl, current.deviceToken, Build.MODEL, Build.VERSION.SDK_INT, RelayLinker.HTTP);
                current.relayUrl = result.relay; current.deviceToken = result.token;
                current.deviceName = result.name == null ? "" : result.name; current.deviceId = result.deviceId == null ? "" : result.deviceId;
                current.upsertLink(result.agentId, result.linkKey); current.save(app);
                if (!clientName.isEmpty()) AgentConfig.renameConnection(app, result.agentId, clientName);
                if (first) AgentConfig.setAccessEnabled(app, true);
                // Saving a verified link succeeds even before Android startup is authorized.
                boolean acknowledged = true;
                try {
                    if (SetupRules.isCurrentDaemon(Json.object(SimpleHttp.getOnce(local(current, "/health"), 1500, null))))
                        configure(current, result.agentId, result.linkKey, null);
                } catch (java.net.ConnectException ignored) { /* Not started yet; startup reads the saved link. */ }
                catch (Exception e) { acknowledged = false; }
                state.code = ""; state.page = "main";
                state.done(acknowledged ? AppCopy.ASSISTANT_CONNECTED : AppCopy.ASSISTANT_SAVED_RESTART);
            } catch (Exception e) { state.done(SetupRules.linkError(e)); }
        }, "backstage-link").start();
    }
    void pasteSetupFromClipboard() {
        ClipboardManager manager = getSystemService(ClipboardManager.class);
        ClipData clip = manager == null ? null : manager.getPrimaryClip();
        CharSequence raw = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
        SetupRules.SetupInput parsed = SetupRules.parseSetupInput(raw == null ? "" : raw.toString());
        if (parsed == null) { toast(AppCopy.CLIPBOARD_NO_CODE); return; }
        applySetupInput(parsed);
        toast(AppCopy.CODE_PASTED);
    }
    void applySetupInput(SetupRules.SetupInput parsed) {
        if (!parsed.server.isEmpty()) session.server = parsed.server;
        session.code = parsed.code;
        if (!parsed.clientName.isEmpty() && session.clientName.isBlank()) session.clientName = parsed.clientName;
        renderNow();
    }
    void serverDialog() {
        UiKit k = kit; LinearLayout form = k.form(); EditText field = k.field(form, AppCopy.FIELD_SERVER, SetupRules.DEFAULT_RELAY, session.server);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(AppCopy.ACTION_CHANGE_SERVER).setMessage(AppCopy.SERVER_BODY)
                .setView(form).setPositiveButton(AppCopy.SAVE, null).setNegativeButton(AppCopy.CANCEL, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { session.server = SetupRules.relayUrl(field.getText().toString()); dialog.dismiss(); renderNow(); }
            catch (RuntimeException e) { field.setError(e.getMessage()); }
        })); dialog.show();
    }

    // ---- Phone access, pause, restart ----

    private void maybeStart() {
        if (session.busy || startIssued || !SetupRules.shouldStart(config.accessEnabled, config.hasAssistant(), config.paired)) return;
        startIssued = true; startTime = System.currentTimeMillis();
        try { startForegroundService(new Intent(this, AgentService.class)); }
        catch (RuntimeException e) { startIssued = false; session.message = AppCopy.START_FAILED; }
    }
    void pauseAccess() {
        AgentConfig.setAccessEnabled(this, false); startIssued = false;
        RecoveryNotifier.clear(this);
        Context app = getApplicationContext(); Session state = session;
        state.busy = true; state.message = AppCopy.PAUSING; config = AgentConfig.load(this); renderNow();
        new Thread(() -> {
            app.stopService(new Intent(app, AgentService.class));
            try {
                AgentConfig c = AgentConfig.load(app);
                if (!ConnectionRecovery.foreignService(c.daemonPort)) SimpleHttp.postJsonOnce(local(c, "/shutdown"), "{}", 4000, c.localToken);
                state.done(AppCopy.PAUSE_REQUESTED);
            } catch (java.net.ConnectException e) { state.done(AppCopy.PAUSE_DONE); }
            catch (Exception e) { state.done(AppCopy.PAUSE_UNCONFIRMED); }
        }, "backstage-pause").start();
    }
    void resumeAccess() {
        AgentConfig.setAccessEnabled(this, true); startIssued = false; config = AgentConfig.load(this); maybeStart(); renderNow();
    }
    void restartConnection() {
        Context app = getApplicationContext(); Session state = session;
        state.busy = true; state.message = AppCopy.RESTARTING; renderNow();
        new Thread(() -> {
            try {
                app.stopService(new Intent(app, AgentService.class));
                AgentConfig c = AgentConfig.load(app);
                if (ConnectionRecovery.foreignService(c.daemonPort)) {
                    AgentConfig.savePort(app, ConnectionRecovery.availablePort(c.daemonPort));
                    AgentConfig.setAccessEnabled(app, true);
                    state.restartRequested = true; state.page = "main";
                    state.done(AppCopy.STARTING_CONNECTION);
                    return;
                }
                boolean canEnableWireless = app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
                if (Settings.Global.getInt(app.getContentResolver(), "adb_wifi_enabled", 0) == 0 && !canEnableWireless) {
                    state.done(AppCopy.RESTART_NEEDS_WIRELESS);
                    return;
                }
                try { SimpleHttp.postJsonOnce(local(c, "/shutdown"), "{}", 4000, c.localToken); }
                catch (java.net.ConnectException ignored) { }
                // Wait for the old helper to exit before requesting a fresh start.
                boolean down = false;
                for (int i = 0; i < 20; i++) {
                    Thread.sleep(250);
                    try { SimpleHttp.getOnce(local(c, "/health"), 1000, null); }
                    catch (java.net.ConnectException e) { down = true; break; }
                }
                if (!down) throw new IllegalStateException("Still stopping");
                AgentConfig.setAccessEnabled(app, true);
                AdbDiscovery.refresh(app);
                try { app.startForegroundService(new Intent(app, AgentService.class).setAction(AgentService.ACTION_RECONNECT)); }
                catch (RuntimeException ignored) { }
                state.restartRequested = true;
                state.page = "main"; state.done(AppCopy.STARTING_CONNECTION);
            } catch (Exception e) { state.done(AppCopy.RESTART_FAILED); }
        }, "backstage-restart").start();
    }
    private void retryRelayNow() {
        toast(AppCopy.STATUS_RECONNECTING);
        daemon.post("/reconnect", Map.of(), (r, e) -> { });
    }

    private void refreshHealth() {
        if (!checking.compareAndSet(false, true)) return;
        final int port = config.daemonPort;
        final boolean readPermits = !accountServer();
        final Context app = getApplicationContext();
        new Thread(() -> {
            boolean up = false, present = false, unknown = false, online = false, conflict = false;
            String lastErr = "", usbFns = "", wifiNow = "";
            int confirmCount = 0;
            Map<String, Object> permissions = null;
            try {
                Map<String, Object> h = Json.object(SimpleHttp.getOnce("http://127.0.0.1:" + port + "/health", 1200, null));
                present = ConnectionRecovery.isBackstage(h); conflict = !present; up = SetupRules.isCurrentDaemon(h); online = SetupRules.relayConnected(h);
                Object usbObj = h.get("usbUnlockedFunctions");
                if (usbObj != null) usbFns = String.valueOf(usbObj);
                Object wifiObj = h.get("wifi");
                if (wifiObj != null) wifiNow = String.valueOf(wifiObj);
                if (h.get("pendingConfirmations") instanceof Number) confirmCount = ((Number) h.get("pendingConfirmations")).intValue();
                Object relayObj = h.get("relay");
                if (relayObj instanceof Map) {
                    Object err = ((Map<?, ?>) relayObj).get("lastError");
                    if (err != null) lastErr = String.valueOf(err);
                }
                Object installed = h.get("linkedAgents");
                if (!(installed instanceof List)) online = false;
                else for (DaemonConfig.Link link : AgentConfig.load(app).links)
                    if (!((List<?>) installed).contains(link.agentId)) online = false;
            } catch (java.net.ConnectException ignored) { }
            catch (Exception e) { unknown = true; }
            if (up && readPermits) {
                try {
                    AgentConfig current = AgentConfig.load(app);
                    Map<String, Object> reply = Json.object(SimpleHttp.getOnce(local(current, "/session"), 1200, current.localToken));
                    Object found = reply.get("permits");
                    if (found instanceof Map) {
                        @SuppressWarnings("unchecked") Map<String, Object> cast = (Map<String, Object>) found;
                        permissions = cast;
                    }
                } catch (Exception ignored) { }
            }
            final Map<String, Object> shownPermits = permissions;
            final boolean found = up, ready = online, reachable = present, uncertain = unknown, occupied = conflict;
            final String foundErr = lastErr, foundUsb = usbFns, foundWifi = wifiNow;
            final int confirms = confirmCount;
            ui.post(() -> {
                checking.set(false); if (!active || isFinishing() || isDestroyed() || AgentConfig.load(this).daemonPort != port) return;
                boolean changed = !checked || daemonUp != found || daemonPresent != reachable || healthUnknown != uncertain || portConflict != occupied
                        || connected != ready || !usbUnlockedFunctions.equals(foundUsb) || !relayLastError.equals(foundErr)
                        || !java.util.Objects.equals(String.valueOf(permits), String.valueOf(shownPermits));
                portConflict = occupied; daemonUp = found; daemonPresent = reachable; healthUnknown = uncertain; connected = ready; relayLastError = foundErr;
                usbUnlockedFunctions = foundUsb; wifi = foundWifi; pendingConfirmations = confirms; permits = shownPermits; checked = true;
                if (changed && !session.busy) renderNow();
            });
        }, "backstage-health").start();
    }
    private void startMdns() {
        mdnsListener = new AdbMdns.Listener() {
            @Override public void onPairingService(String host, int port) {
                if (!AgentConfig.load(AgentActivity.this).paired || "access".equals(session.page)) PairingNotifier.showPairing(AgentActivity.this, port);
            }
            @Override public void onConnectService(String host, int port) { }
            @Override public void onLost(String type) { }
        };
        AdbDiscovery.acquire(this, mdnsListener);
    }
    int livePairPort() { return AdbDiscovery.latestPairPort() > 0 ? AdbDiscovery.latestPairPort() : config.lastPairPort; }
    boolean areNotificationsEnabled() {
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        return nm != null && nm.areNotificationsEnabled();
    }
    void cancelConfirmNotification(String confirmId) {
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm != null) nm.cancel(confirmId.hashCode());
    }
    boolean isIgnoringBatteryOptimizations() {
        try {
            android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
            return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (RuntimeException e) {
            return true;
        }
    }
    @android.annotation.SuppressLint("BatteryLife")
    void requestBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        }
    }
    void fixUsbConfig() {
        daemon.post("/fix-usb-config", Map.of(), (reply, error) -> {
            boolean fixed = error == null && Boolean.TRUE.equals(reply.get("ok"));
            if (!active || isFinishing() || isDestroyed()) return;
            toast(fixed ? AppCopy.USB_FIXED : AppCopy.USB_FIX_FAILED);
            if (fixed) { usbUnlockedFunctions = "none"; renderNow(); }
        });
    }
    Intent resolveOemAutostartIntent() {
        Intent[] candidates = new Intent[] {
                new Intent("com.zte.powersavemode.intent.action.AppManagerActivityMFV"),
                new Intent("com.zte.powersavemode.intent.action.AppManagerActivity"),
                new Intent().setComponent(new android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                new Intent().setComponent(new android.content.ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")),
                new Intent().setComponent(new android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                new Intent().setComponent(new android.content.ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
                new Intent().setComponent(new android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                new Intent().setComponent(new android.content.ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
                new Intent().setComponent(new android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
        };
        android.content.pm.PackageManager pm = getPackageManager();
        for (Intent candidate : candidates) {
            try {
                if (pm.resolveActivity(candidate, 0) != null) return candidate;
            } catch (RuntimeException ignored) { }
        }
        return null;
    }
    void openAutostart(Intent intent) {
        AgentConfig.setBootBlocked(this, false);
        AgentConfig.setOemAutostartAcked(this, true);
        config = AgentConfig.load(this);
        try { startActivity(intent); }
        catch (RuntimeException e) { toast(AppCopy.AUTOSTART_OPEN_FAILED); }
        renderNow();
    }
    void dismissAutostart() {
        AgentConfig.setOemAutostartAcked(this, true);
        config = AgentConfig.load(this);
        renderNow();
    }
    private boolean isDevOptionsOn() {
        try { return Settings.Global.getInt(getContentResolver(), "development_settings_enabled", 0) == 1; }
        catch (RuntimeException e) { return true; }
    }
    private boolean isWirelessOn() {
        try { return Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 1; }
        catch (RuntimeException e) { return false; }
    }
    String smartAccessLabel() {
        return SetupRules.accessButtonLabel(isDevOptionsOn(), isWirelessOn(), config.paired);
    }
    /** One-tap fix: About phone when Developer options are off, else Wireless debugging. */
    void openAccessSettingsSmart() {
        if (!isDevOptionsOn()) { openAboutPhone(); return; }
        if (!config.paired && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 4211);
            return;
        }
        WirelessDebuggingSettings.open(this);
    }
    void openAboutPhone() {
        // Build number lives under About phone (sometimes inside Software information).
        openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS);
    }
    void openNotificationSettings() {
        try { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); }
        catch (android.content.ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
        }
    }
    /** Re-show the pairing notification on demand. */
    void showPairingNow() {
        config = AgentConfig.load(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 4214);
            session.message = AppCopy.ALLOW_NOTIFICATIONS_THEN;
            renderNow();
            return;
        }
        if (config.lastPairPort > 0) {
            PairingNotifier.showPairing(this, config.lastPairPort);
            toast(AppCopy.PAIRING_SHOWN);
        } else {
            session.message = AppCopy.PAIRING_OPEN_FIRST;
            session.page = "access";
            renderNow();
            openAccessSettingsSmart();
        }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 4211) WirelessDebuggingSettings.open(this);
        if (request == 4214) {
            config = AgentConfig.load(this);
            if (grants.length > 0 && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED && config.lastPairPort > 0) {
                PairingNotifier.showPairing(this, config.lastPairPort);
                toast(AppCopy.PAIRING_SHOWN);
            }
            renderNow();
            return;
        }
        if (request == 4212 || request == 4213) renderNow();
    }
    void locationAccess() {
        if (LocationProvider.allowed(this)) { openAppSettings(); return; }
        if (getPreferences(MODE_PRIVATE).getBoolean("locationAsked", false) && !shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_COARSE_LOCATION)) { openAppSettings(); return; }
        new AlertDialog.Builder(this).setTitle(AppCopy.ACTION_ALLOW_LOCATION)
                .setMessage(AppCopy.LOCATION_BODY)
                .setPositiveButton(AppCopy.ACTION_CONTINUE, (d, w) -> { getPreferences(MODE_PRIVATE).edit().putBoolean("locationAsked", true).apply(); requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION}, 4212); })
                .setNegativeButton(AppCopy.CANCEL, null).show();
    }
    private void openAppSettings() { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName()))); }
    private void openSettings(String action) {
        try { startActivity(new Intent(action)); }
        catch (android.content.ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }
    void manualPairDialog() {
        UiKit k = kit; LinearLayout form = k.form();
        k.body(form, AppCopy.PAIR_DIALOG_BODY);
        EditText code = k.field(form, AppCopy.FIELD_PAIR_CODE, AppCopy.FIELD_PAIR_CODE_HINT, ""); code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        int defaultPort = livePairPort();
        EditText port = k.field(form, AppCopy.FIELD_PORT, AppCopy.FIELD_PORT_HINT, defaultPort > 0 ? String.valueOf(defaultPort) : "");
        port.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(AppCopy.ACTION_ENTER_PAIR_CODE).setView(form)
                .setPositiveButton(AppCopy.ACTION_PAIR, null).setNegativeButton(AppCopy.CANCEL, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String typed = SetupRules.normalizePairingDigits(code.getText().toString());
            int number = SetupRules.parseHostPort(port.getText().toString(), 0);
            if (typed.isEmpty()) { code.setError(AppCopy.ERROR_PAIR_CODE); return; }
            if (number == 0) { port.setError(AppCopy.ERROR_PAIR_PORT); return; }
            dialog.dismiss(); pairPhone(typed, number);
        })); dialog.show();
    }
    private void pairPhone(String code, int port) {
        Context app = getApplicationContext(); Session state = session; state.busy = true; state.message = AppCopy.CHECKING_PAIR_CODE; renderNow();
        new Thread(() -> {
            try {
                AdbKey key = AdbKey.loadOrCreate(new File(app.getFilesDir(), "adb"), "backstage@" + Build.MODEL);
                AdbPairing.pair("127.0.0.1", port, code, key, 15000);
                AgentConfig.saveAdbState(app, null, port, true);
                AgentConfig.setAccessEnabled(app, true);
                AgentService.lastDiagnosis = "starting";
                AgentConfig current = AgentConfig.load(app);
                if (SetupRules.shouldStart(true, current.hasAssistant(), true)) {
                    try { app.startForegroundService(new Intent(app, AgentService.class).setAction(AgentService.ACTION_RECONNECT)); } catch (RuntimeException ignored) { }
                }
                startIssued = false;
                state.page = "main"; state.done(AppCopy.PAIR_APPROVED);
            } catch (Exception e) { state.done(AppCopy.PAIR_REJECTED); }
        }, "backstage-pair").start();
    }
    private static String local(AgentConfig c, String path) { return "http://127.0.0.1:" + c.daemonPort + path; }
    private static void configure(AgentConfig c, String agent, String key, String forget) throws Exception {
        Map<String, Object> body = Json.mapOf("relayUrl", c.relayUrl, "deviceToken", c.deviceToken, "deviceName", c.deviceName, "deviceId", c.deviceId);
        if (agent != null) body.put("addLink", Json.mapOf("agentId", agent, "linkKey", key));
        if (forget != null) body.put("forgetAgent", forget);
        SimpleHttp.postJsonOnce(local(c, "/configure"), Json.stringify(body), 4000, c.localToken);
    }

    // ---- Settings actions ----

    void portDialog() {
        if (!checked || daemonPresent || healthUnknown || config.accessEnabled) { showText(AppCopy.PAUSE_FIRST_TITLE, AppCopy.PAUSE_FIRST_BODY); return; }
        UiKit k = kit; LinearLayout form = k.form(); EditText port = k.field(form, AppCopy.ACTION_CHANGE_PORT, "18422", String.valueOf(config.daemonPort));
        port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(AppCopy.ACTION_CHANGE_PORT).setView(form).setPositiveButton(AppCopy.SAVE, null).setNegativeButton(AppCopy.CANCEL, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int value = AgentConfig.parsePort(port.getText().toString(), 0);
            if (value == 0) { port.setError(AppCopy.ERROR_PORT); return; }
            config.daemonPort = value; config.save(this); dialog.dismiss(); toast(AppCopy.PORT_SAVED); renderNow();
        })); dialog.show();
    }
    void copyLocalToken() { copy(config.localToken, true); toast(AppCopy.TOKEN_COPIED); }
    void showUsbCommand() { showText(AppCopy.ACTION_USB_COMMAND, AppCopy.USB_COMMAND_BODY + "\n\n" + StartScripts.oneLiner(this)); }
    void showDiagnosticLog() { showText(AppCopy.ACTION_DIAGNOSTIC_LOG, AgentService.tailLog(this, 25)); }
    void showConnectionDetails() {
        showText(AppCopy.ACTION_CONNECTION_DETAILS, String.join("\n",
                AppCopy.DETAIL_VERSION + ": " + BuildInfo.VERSION,
                AppCopy.DETAIL_SERVER + ": " + config.relayUrl,
                AppCopy.ACTION_CHANGE_PORT + ": " + config.daemonPort,
                AppCopy.DETAIL_ADDRESSES + ": " + String.join(", ", LanAddresses.ipv4()),
                AppCopy.DETAIL_PAIRED + ": " + (config.paired ? AppCopy.DETAIL_YES : AppCopy.DETAIL_NO),
                AppCopy.DETAIL_PAIR_PORT + ": " + config.lastPairPort,
                AppCopy.DETAIL_CONNECT_PORT + ": " + config.lastConnectPort));
    }
    private void showText(String title, String body) {
        TextView text = new TextView(this); text.setText(body.isEmpty() ? AppCopy.NO_DETAILS : body); text.setTextIsSelectable(true);
        text.setPadding(kit.dp(24), kit.dp(16), kit.dp(24), kit.dp(16)); text.setTextColor(kit.ink); text.setTextSize(14);
        ScrollView scroll = new ScrollView(this); scroll.addView(text);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton(AppCopy.DONE, null).show();
    }
    private void copy(String value, boolean sensitive) {
        ClipData clip = ClipData.newPlainText(AppCopy.APP_NAME, value);
        if (sensitive && Build.VERSION.SDK_INT >= 33) { android.os.PersistableBundle extra = new android.os.PersistableBundle(); extra.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(extra); }
        ClipboardManager manager = getSystemService(ClipboardManager.class); if (manager != null) manager.setPrimaryClip(clip);
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
}
