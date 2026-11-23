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
            String mode = s.authMode == null || s.authMode.isEmpty() ? "login" : s.authMode;
            heading("signup".equals(mode) ? AppCopy.SIGN_UP_TITLE : "reset".equals(mode) ? AppCopy.ACTION_RESET_PASSWORD : AppCopy.SIGN_IN_TITLE);
            LinearLayout account = k.card(root);
            if (PhoneSignIn.pending(a)) k.body(account, AppCopy.SIGN_IN_FINISHING);
            LinearLayout switcher = k.buttonRow(account);
            k.rowButton(switcher, AppCopy.ACTION_LOG_IN, "login".equals(mode) ? UiKit.PRIMARY : UiKit.SECONDARY, guard(() -> {
                s.authMode = "login"; s.emailSent = false; s.signupSent = false; s.resetSent = false; s.message = ""; a.renderNow();
            }));
            k.rowButton(switcher, AppCopy.ACTION_CREATE_ACCOUNT, "signup".equals(mode) ? UiKit.PRIMARY : UiKit.SECONDARY, guard(() -> {
                s.authMode = "signup"; s.emailSent = false; s.signupSent = false; s.resetSent = false; s.message = ""; a.renderNow();
            }));
            k.space(account, 8);
            if ("signup".equals(mode)) {
                k.body(account, s.signupSent ? AppCopy.SIGN_UP_CODE_SENT : AppCopy.SIGN_UP_BODY);
                EditText email = k.field(account, AppCopy.FIELD_EMAIL, AppCopy.FIELD_EMAIL_HINT, s.email);
                email.setEnabled(!s.busy && !s.signupSent);
                email.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
                UiKit.watch(email, value -> s.email = value);
                if (!s.signupSent) {
                    EditText pw = k.field(account, AppCopy.FIELD_PASSWORD, AppCopy.FIELD_PASSWORD_HINT, s.password);
                    pw.setEnabled(!s.busy);
                    pw.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    pw.setTypeface(Typeface.DEFAULT);
                    UiKit.watch(pw, value -> s.password = value);
                    EditText confirm = k.field(account, AppCopy.FIELD_CONFIRM_PASSWORD, AppCopy.FIELD_PASSWORD_HINT, s.confirmPassword);
                    confirm.setEnabled(!s.busy);
                    confirm.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    confirm.setTypeface(Typeface.DEFAULT);
                    UiKit.watch(confirm, value -> s.confirmPassword = value);
                    k.button(account, busyLabel(AppCopy.ACTION_CREATE_ACCOUNT), UiKit.PRIMARY, guard(a::signUpStart));
                } else {
                    EditText code = k.field(account, AppCopy.FIELD_EMAIL_CODE, AppCopy.FIELD_EMAIL_CODE_HINT, s.emailCode);
                    code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); code.setEnabled(!s.busy);
                    UiKit.watch(code, value -> s.emailCode = value);
                    k.button(account, busyLabel(AppCopy.ACTION_VERIFY_CREATE), UiKit.PRIMARY, guard(a::signUpVerify));
                    LinearLayout row = k.buttonRow(account);
                    k.rowButton(row, AppCopy.ACTION_SEND_NEW_CODE, UiKit.SECONDARY, guard(a::signUpStart));
                    k.rowButton(row, AppCopy.ACTION_CHANGE_EMAIL_STEP, UiKit.SECONDARY, guard(() -> { s.signupSent = false; s.emailCode = ""; s.message = ""; a.renderNow(); }));
                }
            } else if ("reset".equals(mode)) {
                k.body(account, s.resetSent ? AppCopy.RESET_CODE_SENT : AppCopy.RESET_BODY);
                EditText email = k.field(account, AppCopy.FIELD_EMAIL, AppCopy.FIELD_EMAIL_HINT, s.email);
                email.setEnabled(!s.busy && !s.resetSent);
                email.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
                UiKit.watch(email, value -> s.email = value);
                if (!s.resetSent) {
                    k.button(account, busyLabel(AppCopy.ACTION_SEND_RESET_CODE), UiKit.PRIMARY, guard(a::passwordResetStart));
                } else {
                    EditText code = k.field(account, AppCopy.FIELD_EMAIL_CODE, AppCopy.FIELD_EMAIL_CODE_HINT, s.emailCode);
                    code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); code.setEnabled(!s.busy);
                    UiKit.watch(code, value -> s.emailCode = value);
                    EditText pw = k.field(account, AppCopy.FIELD_NEW_PASSWORD, AppCopy.FIELD_PASSWORD_HINT, s.password);
                    pw.setEnabled(!s.busy);
                    pw.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    pw.setTypeface(Typeface.DEFAULT);
                    UiKit.watch(pw, value -> s.password = value);
                    k.button(account, busyLabel(AppCopy.ACTION_RESET_PASSWORD), UiKit.PRIMARY, guard(a::passwordResetVerify));
                    k.button(account, AppCopy.ACTION_SEND_NEW_CODE, UiKit.SECONDARY, guard(a::passwordResetStart));
                }
                k.button(account, AppCopy.ACTION_BACK_TO_LOGIN, UiKit.QUIET, guard(() -> { s.authMode = "login"; s.resetSent = false; s.emailCode = ""; s.password = ""; s.message = ""; a.renderNow(); }));
            } else if ("otp".equals(mode)) {
                k.body(account, AppCopy.OTP_BODY);
                EditText email = k.field(account, AppCopy.FIELD_EMAIL, AppCopy.FIELD_EMAIL_HINT, s.email);
                email.setEnabled(!s.busy);
                email.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
                UiKit.watch(email, value -> s.email = value);
                if (s.emailSent) {
                    EditText code = k.field(account, AppCopy.FIELD_EMAIL_CODE, AppCopy.FIELD_EMAIL_CODE_HINT, s.emailCode);
                    code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); code.setEnabled(!s.busy);
                    UiKit.watch(code, value -> s.emailCode = value);
                    k.button(account, busyLabel(AppCopy.ACTION_LOG_IN), UiKit.PRIMARY, guard(a::emailSignIn));
                    k.button(account, AppCopy.ACTION_SEND_NEW_CODE, UiKit.SECONDARY, guard(a::emailCode));
                } else {
                    k.button(account, busyLabel(AppCopy.ACTION_EMAIL_CODE), UiKit.PRIMARY, guard(a::emailCode));
                }
                k.button(account, AppCopy.ACTION_BACK_TO_LOGIN, UiKit.QUIET, guard(() -> { s.authMode = "login"; s.emailSent = false; s.emailCode = ""; s.message = ""; a.renderNow(); }));
            } else {
                k.body(account, AppCopy.SIGN_IN_BODY);
                EditText email = k.field(account, AppCopy.FIELD_EMAIL, AppCopy.FIELD_EMAIL_HINT, s.email);
                email.setEnabled(!s.busy);
                email.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
                UiKit.watch(email, value -> s.email = value);
                EditText pw = k.field(account, AppCopy.FIELD_PASSWORD, AppCopy.FIELD_PASSWORD_HINT, s.password);
                pw.setEnabled(!s.busy);
                pw.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                pw.setTypeface(Typeface.DEFAULT);
                UiKit.watch(pw, value -> s.password = value);
                k.button(account, busyLabel(AppCopy.ACTION_LOG_IN), UiKit.PRIMARY, guard(a::passwordLogin));
                LinearLayout links = k.buttonRow(account);
                k.rowButton(links, AppCopy.ACTION_FORGOT_PASSWORD, UiKit.QUIET, guard(() -> { s.authMode = "reset"; s.resetSent = false; s.password = ""; s.emailCode = ""; s.message = ""; a.renderNow(); }));
                k.rowButton(links, AppCopy.ACTION_SIGN_IN_EMAIL_CODE, UiKit.QUIET, guard(() -> { s.authMode = "otp"; s.emailSent = false; s.emailCode = ""; s.message = ""; a.renderNow(); }));
            }
            LinearLayout other = k.card(root);
            k.button(other, busyLabel(AppCopy.ACTION_PASSKEY_SIGN_IN), UiKit.SECONDARY, guard(() -> a.nativeSignIn(false)));
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
