package com.deviceagent.app;

import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Account, location and connection settings, grouped into cards of rows. */
final class SettingsTab extends TabPage {
    SettingsTab(AgentActivity activity, UiKit kit) { super(activity, kit, AppCopy.TAB_SETTINGS); }

    @Override void render() {
        begin();
        if (a.accountServer()) put("account", "account", box -> {
            k.section(box, AppCopy.SECTION_ACCOUNT);
            LinearLayout card = group(box);
            row(card, AppCopy.ACTION_SIGN_IN_METHODS, null, guard(a::signInMethodsDialog), true);
            row(card, AppCopy.ACTION_MANAGE_BROWSER, null, a::openAccountPage, false);
        });
        String location = a.locationState();
        put("location", "location|" + location, box -> {
            k.section(box, AppCopy.SECTION_LOCATION);
            LinearLayout card = group(box);
            row(card, LocationProvider.allowed(a) ? AppCopy.ACTION_MANAGE_LOCATION : AppCopy.ACTION_ALLOW_LOCATION, location, a::locationAccess, false);
        });
        put("connection", "connection|" + a.config.daemonPort + "|" + a.config.keepUsbDebugging + "|" + a.config.allowShell, box -> {
            k.section(box, AppCopy.SECTION_CONNECTION);
            LinearLayout card = group(box);
            row(card, AppCopy.ACTION_STAY_ONLINE_OFF_WIFI, a.config.keepUsbDebugging ? AppCopy.STATE_ON : AppCopy.STATE_OFF, this::offWifiDialog, true);
            row(card, AppCopy.ACTION_ALLOW_SHELL, a.config.allowShell ? AppCopy.STATE_ON : AppCopy.STATE_OFF, this::shellDialog, true);
            row(card, AppCopy.ACTION_CHANGE_PORT, String.valueOf(a.config.daemonPort), a::portDialog, true);
            row(card, AppCopy.ACTION_COPY_LOCAL_TOKEN, null, a::copyLocalToken, true);
            row(card, AppCopy.ACTION_USB_COMMAND, null, a::showUsbCommand, true);
            row(card, AppCopy.ACTION_CONNECTION_DETAILS, null, a::showConnectionDetails, true);
            row(card, AppCopy.ACTION_DIAGNOSTIC_LOG, null, a::showDiagnosticLog, false);
        });
        String updateVal = a.updateStatus.isEmpty() ? "v" + BuildInfo.VERSION : a.updateStatus;
        put("updates", "updates|" + a.checkingUpdate + "|" + updateVal + "|" + (a.availableUpdate == null ? 0 : a.availableUpdate.versionCode), box -> {
            k.section(box, AppCopy.SECTION_UPDATES);
            LinearLayout card = group(box);
            row(card, AppCopy.ACTION_CHECK_UPDATES, updateVal, () -> {
                if (a.availableUpdate != null && a.availableUpdate.versionCode > a.installedVersionCode()) a.showUpdateDialog(a.availableUpdate);
                else a.checkForUpdates();
            }, false);
            TextView version = k.body(box, AppCopy.APP_NAME + " " + BuildInfo.VERSION);
            version.setTextSize(12); version.setGravity(Gravity.CENTER); version.setPadding(0, k.dp(22), 0, k.dp(8));
        });
        end();
    }

    private void offWifiDialog() {
        boolean next = !a.config.keepUsbDebugging;
        new android.app.AlertDialog.Builder(a)
                .setTitle(AppCopy.ACTION_STAY_ONLINE_OFF_WIFI)
                .setMessage(AppCopy.OFF_WIFI_BODY)
                .setPositiveButton(next ? AppCopy.ACTION_TURN_ON : AppCopy.ACTION_TURN_OFF, (d, w) -> {
                    AgentConfig.setKeepUsbDebugging(a, next);
                    a.config = AgentConfig.load(a);
                    render();
                    if (a.config.accessEnabled) a.restartConnection();
                })
                .setNegativeButton(AppCopy.CANCEL, null)
                .show();
    }

    private void shellDialog() {
        boolean next = !a.config.allowShell;
        new android.app.AlertDialog.Builder(a)
                .setTitle(AppCopy.ACTION_ALLOW_SHELL)
                .setMessage(AppCopy.ALLOW_SHELL_BODY)
                .setPositiveButton(next ? AppCopy.ACTION_TURN_ON : AppCopy.ACTION_TURN_OFF, (d, w) -> {
                    AgentConfig.setAllowShell(a, next);
                    a.config = AgentConfig.load(a);
                    render();
                    a.daemon.post("/shell-access", com.deviceagent.tools.Json.mapOf("enabled", next), (reply, error) -> {});
                })
                .setNegativeButton(AppCopy.CANCEL, null)
                .show();
    }

    private LinearLayout group(LinearLayout box) {
        LinearLayout card = k.card(box); card.setPadding(k.dp(4), k.dp(4), k.dp(4), k.dp(4)); return card;
    }

    private void row(LinearLayout card, String label, String value, Runnable action, boolean divider) {
        LinearLayout row = k.row(); row.setPadding(k.dp(14), 0, k.dp(10), 0); row.setMinimumHeight(k.dp(54));
        row.setBackground(k.ripple(null, 16)); row.setClickable(true); row.setFocusable(true);
        row.setOnClickListener(v -> action.run());
        TextView l = k.text(null, label, 15, k.ink, false);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        if (value != null && !value.isEmpty()) {
            TextView v = k.text(null, value, 14, k.muted, false); v.setPadding(k.dp(8), 0, k.dp(6), 0);
            row.addView(v);
            row.setContentDescription(label + ", " + value);
        }
        ImageView chevron = new ImageView(a); chevron.setImageDrawable(new AppIcons(AppIcons.CHEVRON, k.muted));
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron, new LinearLayout.LayoutParams(k.dp(18), k.dp(18)));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        if (divider) { View line = k.divider(card); ((LinearLayout.LayoutParams) line.getLayoutParams()).setMargins(k.dp(14), 0, k.dp(14), 0); }
    }
}
