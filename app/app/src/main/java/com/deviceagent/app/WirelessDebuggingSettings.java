package com.deviceagent.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.provider.Settings;
import android.service.quicksettings.TileService;

/** Opens the Wireless debugging page where Android Settings supports its tile shortcut. */
final class WirelessDebuggingSettings {
    private WirelessDebuggingSettings() {}

    static void open(Activity activity) {
        // AOSP Settings handles the wireless debugging tile's long-press by opening
        // WirelessDebuggingFragment. OEM Settings apps may only support Developer options.
        Intent direct = new Intent(TileService.ACTION_QS_TILE_PREFERENCES).setPackage("com.android.settings");
        direct.putExtra(Intent.EXTRA_COMPONENT_NAME, new ComponentName("com.android.settings",
                "com.android.settings.development.qstile.DevelopmentTiles$WirelessDebugging"));
        if (launch(activity, direct)) return;
        if (launch(activity, new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))) return;
        launch(activity, new Intent(Settings.ACTION_SETTINGS));
    }

    private static boolean launch(Activity activity, Intent intent) {
        try { activity.startActivity(intent); return true; }
        catch (android.content.ActivityNotFoundException | SecurityException ignored) { return false; }
    }
}
