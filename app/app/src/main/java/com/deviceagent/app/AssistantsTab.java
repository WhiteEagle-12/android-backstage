package com.deviceagent.app;

import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deviceagent.daemon.DaemonConfig;

/** Access requests, connected assistants and the devices on this account. */
final class AssistantsTab extends TabPage {
    AssistantsTab(AgentActivity activity, UiKit kit) { super(activity, kit, AppCopy.TAB_ASSISTANTS); }

    @Override void render() {
        begin();
        if (!a.accountServer()) {
            put("links", "links|" + a.permitsSignature() + "|" + a.config.links.size(), box -> {
                for (DaemonConfig.Link link : a.config.links) a.connectionCard(k, box, link);
                k.button(box, AppCopy.ACTION_CONNECTION_CODE, UiKit.SECONDARY, guard(a::newConnectionCode));
            });
            end();
            return;
        }
        StringBuilder req = new StringBuilder("requests");
        for (AccountClient.Request r : a.requests) req.append('|').append(r.id);
        if (!a.requests.isEmpty()) put("requests", req.toString(), box -> { for (AccountClient.Request r : a.requests) requestCard(box, r); });
        put("assistants", "assistants|" + a.accountVersion + "|" + a.accountError, this::assistantsSection);
        if (a.account != null) put("devices", "devices|" + a.accountVersion, this::devicesSection);
        end();
    }

    private void requestCard(LinearLayout box, AccountClient.Request r) {
        LinearLayout card = k.card(box);
        card.setBackground(k.shape(k.card, 20, k.green));
        k.title(card, String.format(AppCopy.REQUEST_ACCESS_FMT, r.names));
        k.body(card, AppCopy.REQUEST_MATCH_CODE);
        TextView digits = k.text(card, r.check, 30, k.ink, true);
        digits.setLetterSpacing(0.18f); digits.setPadding(0, k.dp(10), 0, k.dp(2));
        LinearLayout row = k.buttonRow(card);
        k.rowButton(row, AppCopy.ACTION_ALLOW, UiKit.PRIMARY, guard(() -> a.decideRequest(r, true)));
        k.rowButton(row, AppCopy.ACTION_DENY, UiKit.SECONDARY, guard(() -> a.decideRequest(r, false)));
    }

    private void assistantsSection(LinearLayout box) {
        k.section(box, AppCopy.SECTION_ASSISTANTS);
        if (a.account == null) {
            LinearLayout card = k.card(box);
            k.body(card, a.accountError.isEmpty() ? AppCopy.LOADING_ACCOUNT : a.accountError);
            if (!a.accountError.isEmpty()) k.button(card, AppCopy.RETRY, UiKit.SECONDARY, () -> a.refreshAccount(true));
            return;
        }
        if (a.account.assistants.isEmpty()) { k.empty(box, AppCopy.EMPTY_ASSISTANTS); return; }
        for (AccountClient.Assistant as : a.account.assistants) {
            LinearLayout row = k.rowCard(box, guard(() -> a.assistantDialog(as)));
            ImageView icon = a.assistantIcon(k, as.icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(k.dp(40), k.dp(40)); ip.setMarginEnd(k.dp(14));
            row.addView(icon, ip);
            LinearLayout col = k.column();
            k.text(col, as.name, 16, k.ink, true);
            String state = a.assistantState(as);
            TextView st = k.text(col, state, 13, as.paused ? k.danger : k.muted, false); st.setPadding(0, k.dp(2), 0, 0);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(chevron(), new LinearLayout.LayoutParams(k.dp(20), k.dp(20)));
            row.setContentDescription(as.name + ", " + state);
        }
    }

    private void devicesSection(LinearLayout box) {
        k.section(box, AppCopy.SECTION_DEVICES);
        for (AccountClient.Device d : a.account.devices) {
            LinearLayout row = k.rowCard(box, guard(() -> a.deviceDialog(d)));
            String state = d.needsReconnect ? AppCopy.DEVICE_RECONNECT : d.online ? AppCopy.DEVICE_ONLINE
                    : "offline".equals(d.status) ? AppCopy.DEVICE_OFFLINE : "paused".equals(d.status) ? AppCopy.DEVICE_PAUSED : AppCopy.DEVICE_CHECKING;
            int color = d.needsReconnect ? k.danger : d.online ? k.green : k.muted;
            k.dot(row, color, 10);
            LinearLayout col = k.column();
            LinearLayout name = k.row();
            TextView n = k.text(null, d.name, 16, k.ink, true);
            name.addView(n, new LinearLayout.LayoutParams(-2, -2));
            if (d.thisDevice) { TextView badge = k.pill(null, AppCopy.BADGE_THIS_PHONE, k.green, k.greenSoft);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2); bp.setMarginStart(k.dp(8)); name.addView(badge, bp); }
            col.addView(name);
            TextView meta = k.text(col, state + (d.model.isEmpty() ? "" : "  ·  " + d.model), 13, d.needsReconnect ? k.danger : k.muted, false);
            meta.setPadding(0, k.dp(2), 0, 0);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(chevron(), new LinearLayout.LayoutParams(k.dp(20), k.dp(20)));
            row.setContentDescription(d.name + ", " + state);
        }
        k.button(box, AppCopy.ACTION_ADD_DEVICE, UiKit.SECONDARY, guard(a::addDeviceDialog));
    }

    private ImageView chevron() {
        ImageView v = new ImageView(a); v.setImageDrawable(new AppIcons(AppIcons.CHEVRON, k.muted));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return v;
    }
}
