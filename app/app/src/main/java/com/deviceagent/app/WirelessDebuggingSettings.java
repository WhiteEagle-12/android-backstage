package com.deviceagent.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.service.quicksettings.TileService;

/**
 * Opens Wireless debugging directly where Android Settings supports its tile
 * shortcut, and otherwise opens Developer options scrolled & highlighted straight
 * to the Wireless debugging row.
 */
final class WirelessDebuggingSettings {
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String DEV_SETTINGS_ACTIVITY =
            "com.android.settings.Settings$DevelopmentSettingsDashboardActivity";
    private static final String WIRELESS_TILE_CLASS =
            "com.android.settings.development.qstile.DevelopmentTiles$WirelessDebugging";
    private static final String EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key";
    private static final String EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args";
    private static final String KEY_WIRELESS_DEBUGGING = "toggle_adb_wireless";

    private WirelessDebuggingSettings() {}

    static void open(Activity activity) {
        // 1. Fresh-task QS tile long-press intent straight to DevelopmentSettingsDashboardActivity.
        // AOSP Settings' handleQsTileLongPressActionIfAny() routes this directly into
        // WirelessDebuggingFragment (where "Pair device with pairing code" lives).
        // FLAG_ACTIVITY_CLEAR_TASK ensures an already-open Settings task cannot swallow the intent.
        // Highlight extras are also attached so OEM skins without tile routing still jump to the row.
        if (launch(activity, tileIntent().setClassName(SETTINGS_PACKAGE, DEV_SETTINGS_ACTIVITY))) return;
        if (launch(activity, tileIntent().setPackage(SETTINGS_PACKAGE))) return;

        // 2. Fallback: Developer options scrolled and highlighted directly to Wireless debugging.
        Intent dev = withHighlight(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        dev.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (launch(activity, dev)) return;

        launch(activity, new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static Intent tileIntent() {
        Intent intent = new Intent(TileService.ACTION_QS_TILE_PREFERENCES);
        intent.putExtra(Intent.EXTRA_COMPONENT_NAME, new ComponentName(SETTINGS_PACKAGE, WIRELESS_TILE_CLASS));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return withHighlight(intent);
    }

    private static Intent withHighlight(Intent intent) {
        intent.putExtra(EXTRA_FRAGMENT_ARG_KEY, KEY_WIRELESS_DEBUGGING);
        Bundle args = new Bundle();
        args.putString(EXTRA_FRAGMENT_ARG_KEY, KEY_WIRELESS_DEBUGGING);
        intent.putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, args);
        return intent;
    }

    private static boolean launch(Activity activity, Intent intent) {
        try { activity.startActivity(intent); return true; }
        catch (android.content.ActivityNotFoundException | SecurityException ignored) { return false; }
    }
}
