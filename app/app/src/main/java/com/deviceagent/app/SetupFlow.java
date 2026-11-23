package com.deviceagent.app;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.deviceagent.daemon.DaemonConfig;

/** Sign-in and phone-access setup, shown until this phone is linked and paired. */
final class SetupFlow {
    private final AgentActivity a;
    private final UiKit k;
    final ScrollView scroll;
    private final LinearLayout root;
    /** Status line inside the pairing steps, updated in place every tick. */
    TextView pairingHint;

    SetupFlow(AgentActivity activity, UiKit kit) {
        a = activity; k = kit;
        scroll = new ScrollView(a); scroll.setFillViewport(true); scroll.setBackgroundColor(k.canvas); scroll.setClipToPadding(false);
        LinearLayout wrap = k.column(); wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        root = k.column(); root.setPadding(k.dp(20), k.dp(12), k.dp(20), k.dp(28));
        int width = Math.min(a.getResources().getDisplayMetrics().widthPixels, k.dp(600));
        wrap.addView(root, new LinearLayout.LayoutParams(width, -2));
        scroll.addView(wrap);
    }

    void render() {
        root.removeAllViews(); pairingHint = null;
        header();
        AgentActivity.Session s = a.session;
        if ("help".equals(s.page)) help();
        else if ("access".equals(s.page)) accessScreen();
        else if ("connect".equals(s.page) || "manual".equals(s.page) || !a.config.hasAssistant()) connectScreen();
        else accessScreen();
        updateHint();
    }

    private Runnable guard(Runnable r) { return () -> { if (!a.session.busy) r.run(); }; }
    private String busyLabel(String label) { return a.session.busy ? AppCopy.PLEASE_WAIT : label; }

    private void header() {
        LinearLayout row = k.row(); row.setPadding(0, k.dp(4), 0, k.dp(8));
        ImageView mark = new ImageView(a); mark.setImageResource(R.drawable.backstage_art); mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(mark, new LinearLayout.LayoutParams(k.dp(36), k.dp(36)));
        TextView name = k.text(null, AppCopy.APP_NAME, 19, k.ink, true); name.setPadding(k.dp(10), 0, 0, 0);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        boolean main = "main".equals(a.session.page);
        android.widget.Button nav = k.buttonView(main ? AppCopy.HELP : AppCopy.BACK, UiKit.QUIET);
        nav.setOnClickListener(v -> guard(() -> { a.session.page = main ? "help" : "main"; a.session.message = ""; a.renderNow(); }).run());
        row.addView(nav);
        root.addView(row);
    }

    private void heading(String title) {
        TextView t = k.text(root, title, 28, k.ink, true); t.setPadding(0, k.dp(18), 0, k.dp(4)); t.setAccessibilityHeading(true);
    }

    private void connectScreen() {
        AgentActivity.Session s = a.session;
        if (SetupRules.sameServer(s.server, SetupRules.DEFAULT_RELAY) && s.code.isEmpty() && !"manual".equals(s.page)) {
            heading(AppCopy.SIGN_IN_TITLE);
            LinearLayout account = k.card(root);
            if (PhoneSignIn.pending(a)) k.body(account, AppCopy.SIGN_IN_FINISHING);
            k.body(account, AppCopy.SIGN_IN_BODY);
            EditText email = k.field(account, AppCopy.FIELD_EMAIL, AppCopy.FIELD_EMAIL_HINT, s.email);
            email.setEnabled(!s.busy);
            email.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            UiKit.watch(email, value -> s.email = value);
            if (s.emailSent) {
                EditText code = k.field(account, AppCopy.FIELD_EMAIL_CODE, AppCopy.FIELD_EMAIL_CODE_HINT, s.emailCode);
                code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); code.setEnabled(!s.busy);
                UiKit.watch(code, value -> s.emailCode = value);
                k.button(account, busyLabel(AppCopy.SIGN_IN_TITLE), UiKit.PRIMARY, guard(a::emailSignIn));
                k.button(account, AppCopy.ACTION_SEND_NEW_CODE, UiKit.SECONDARY, guard(a::emailCode));
            } else k.button(account, busyLabel(AppCopy.ACTION_EMAIL_CODE), UiKit.PRIMARY, guard(a::emailCode));
            LinearLayout other = k.card(root);
            k.button(other, busyLabel(AppCopy.ACTION_PASSKEY_SIGN_IN), UiKit.SECONDARY, guard(() -> a.nativeSignIn(false)));
            k.button(other, AppCopy.ACTION_PASSKEY_CREATE, UiKit.SECONDARY, guard(() -> a.nativeSignIn(true)));
            k.button(other, AppCopy.ACTION_RECOVERY_CODE, UiKit.QUIET, guard(a::recoveryDialog));
            k.button(other, AppCopy.ACTION_CONNECTION_CODE, UiKit.QUIET, guard(() -> { s.page = "manual"; a.renderNow(); }));
            return;
        }
        heading(AppCopy.CONNECT_CODE_TITLE);
        LinearLayout card = k.card(root);
        k.body(card, AppCopy.CONNECT_CODE_BODY);
        if (!"manual".equals(s.page)) {
            EditText clientName = k.field(card, AppCopy.FIELD_CONNECTION_NAME, AppCopy.FIELD_CONNECTION_NAME_HINT, s.clientName);
            clientName.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});
            UiKit.watch(clientName, value -> s.clientName = value);
        }
        EditText code = k.field(card, AppCopy.FIELD_CONNECTION_CODE, AppCopy.FIELD_CONNECTION_CODE_HINT, s.code);
        code.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setTypeface(Typeface.MONOSPACE); code.setTextSize(20);
        UiKit.watch(code, value -> {
            s.code = value;
            if (value.toLowerCase(java.util.Locale.ROOT).contains("androidbackstage://connect")) {
                SetupRules.SetupInput parsed = SetupRules.parseSetupInput(value);
                if (parsed != null) a.applySetupInput(parsed);
            }
        });
        TextView server = k.body(card, String.format(AppCopy.SERVER_FMT, s.server)); server.setTextSize(13);
        k.body(card, AppCopy.CONNECT_TRUST_BODY);
        k.button(card, s.busy ? AppCopy.CONNECTING : AppCopy.ACTION_CONNECT, UiKit.PRIMARY, guard(a::linkRelay));
        LinearLayout row = k.buttonRow(card);
        k.rowButton(row, AppCopy.ACTION_PASTE_CODE, UiKit.SECONDARY, guard(a::pasteSetupFromClipboard));
        k.rowButton(row, AppCopy.ACTION_CHANGE_SERVER, UiKit.SECONDARY, guard(a::serverDialog));
        k.button(root, AppCopy.ACTION_WHERE_CODE, UiKit.QUIET, () -> new AlertDialog.Builder(a).setTitle(AppCopy.WHERE_CODE_TITLE)
                .setMessage(AppCopy.WHERE_CODE_BODY).setPositiveButton(AppCopy.DONE, null).show());
    }

    private void accessScreen() {
        heading(AppCopy.SETUP_ACCESS_TITLE);
        LinearLayout card = k.card(root);
        k.body(card, AppCopy.ACCESS_WIFI_BODY);
        phoneAccessSteps(card, true);
        k.button(root, AppCopy.ACTION_FIND_DEV_OPTIONS, UiKit.QUIET, () -> new AlertDialog.Builder(a)
                .setTitle(AppCopy.DEV_OPTIONS_TITLE).setMessage(AppCopy.DEV_OPTIONS_BODY)
                .setPositiveButton(AppCopy.ACTION_OPEN_ABOUT_PHONE, (d, w) -> a.openAboutPhone())
                .setNegativeButton(AppCopy.BACK, null).show());
    }

    /** The Wireless debugging steps; also shown on the Live tab while a linked phone is unpaired. */
    void phoneAccessSteps(LinearLayout card, boolean showOpen) {
        boolean notifications = a.areNotificationsEnabled();
        if (!notifications) {
            step(card, "1", AppCopy.ACTION_ALLOW_NOTIFICATIONS, AppCopy.STEP_CODE_NOTIFY);
            k.button(card, AppCopy.ACTION_ALLOW_NOTIFICATIONS, UiKit.PRIMARY, a::openNotificationSettings);
            step(card, "2", AppCopy.ACTION_OPEN_WIRELESS, AppCopy.STEP_WIRELESS_BODY);
            if (showOpen) k.button(card, a.smartAccessLabel(), UiKit.SECONDARY, guard(a::openAccessSettingsSmart));
        } else {
            step(card, "1", AppCopy.ACTION_OPEN_WIRELESS, AppCopy.STEP_WIRELESS_BODY);
            step(card, "2", AppCopy.STEP_CODE_TITLE, AppCopy.STEP_CODE_NOTIFY);
            if (showOpen) k.button(card, a.smartAccessLabel(), UiKit.PRIMARY, guard(a::openAccessSettingsSmart));
            if (a.livePairPort() > 0) k.button(card, AppCopy.ACTION_SHOW_PAIRING, UiKit.SECONDARY, a::showPairingNow);
        }
        pairingHint = k.body(card, AppCopy.PAIRING_WAITING); pairingHint.setPadding(0, k.dp(12), 0, 0);
        k.button(card, AppCopy.ACTION_ENTER_PAIR_CODE, UiKit.SECONDARY, guard(a::manualPairDialog));
    }

    void updateHint() {
        if (pairingHint == null) return;
        pairingHint.setText(a.config.paired ? AppCopy.PAIRING_SAVED : a.livePairPort() > 0 ? AppCopy.PAIRING_AVAILABLE : AppCopy.PAIRING_WAITING);
    }

    private void step(LinearLayout parent, String number, String title, String body) {
        LinearLayout row = k.row(); row.setGravity(Gravity.TOP); row.setPadding(0, k.dp(14), 0, 0);
        TextView n = k.text(null, number, 13, k.green, true); n.setGravity(Gravity.CENTER);
        n.setBackground(k.shape(k.greenSoft, 99, 0));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(k.dp(26), k.dp(26)); np.setMarginEnd(k.dp(12));
        row.addView(n, np);
        LinearLayout col = k.column(); k.text(col, title, 15, k.ink, true); k.body(col, body);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(row);
    }

    private void help() {
        heading(AppCopy.HELP);
        LinearLayout box = k.card(root);
        k.title(box, AppCopy.HELP_STOPPED_TITLE);
        k.body(box, AppCopy.HELP_STOPPED_BODY);
        k.button(box, AppCopy.SETUP_ACCESS_TITLE, UiKit.PRIMARY, guard(() -> { a.session.page = "access"; a.renderNow(); }));
        k.button(box, AppCopy.ACTION_RECONNECT, UiKit.SECONDARY, guard(a::restartConnection));
        if (a.accountServer()) k.button(box, AppCopy.ACTION_APPROVAL_CODE, UiKit.SECONDARY, guard(a::approveDialog));
        for (DaemonConfig.Link link : a.config.links) a.connectionCard(k, root, link);
        if (!a.accountServer()) k.button(root, AppCopy.ACTION_CONNECTION_CODE, UiKit.SECONDARY, guard(a::newConnectionCode));
        LinearLayout advanced = k.card(root);
        k.title(advanced, AppCopy.SECTION_CONNECTION);
        k.button(advanced, AppCopy.ACTION_CONNECTION_DETAILS, UiKit.SECONDARY, a::showConnectionDetails);
        k.button(advanced, AppCopy.ACTION_DIAGNOSTIC_LOG, UiKit.SECONDARY, a::showDiagnosticLog);
        if (a.accountServer()) k.button(advanced, AppCopy.ACTION_MANAGE_BROWSER, UiKit.SECONDARY, a::openAccountPage);
        k.button(advanced, AppCopy.ACTION_USB_COMMAND, UiKit.SECONDARY, a::showUsbCommand);
        k.button(advanced, AppCopy.ACTION_COPY_LOCAL_TOKEN, UiKit.SECONDARY, a::copyLocalToken);
        k.button(advanced, AppCopy.ACTION_CHANGE_PORT, UiKit.SECONDARY, a::portDialog);
        TextView version = k.body(root, AppCopy.APP_NAME + " " + BuildInfo.VERSION);
        version.setGravity(Gravity.CENTER); version.setPadding(0, k.dp(18), 0, 0); version.setTextSize(12);
    }
}
