package com.deviceagent.app;

/**
 * Single source of truth for every user-facing string in the Android Backstage app.
 * Authored and reviewed for clarity, brevity, and zero filler or internal jargon.
 */
public final class AppCopy {
    private AppCopy() {}

    // App & navigation
    public static final String APP_NAME = "Backstage";
    public static final String TAB_LIVE = "Live";
    public static final String TAB_ACTIVITY = "Activity";
    public static final String TAB_ASSISTANTS = "Assistants";
    public static final String TAB_SETTINGS = "Settings";

    // Common actions
    public static final String CANCEL = "Cancel";
    public static final String CLOSE = "Close";
    public static final String SAVE = "Save";
    public static final String DONE = "Done";
    public static final String COPY = "Copy";
    public static final String CLEAR = "Clear";
    public static final String REMOVE = "Remove";
    public static final String DISMISS = "Dismiss";
    public static final String RETRY = "Try again";
    public static final String PLEASE_WAIT = "Please wait…";
    public static final String BACK = "Back";
    public static final String HELP = "Help";

    // Live tab — connection status
    public static final String STATUS_CONNECTED = "Connected";
    public static final String STATUS_CONNECTING = "Connecting…";
    public static final String STATUS_RECONNECTING = "Reconnecting…";
    public static final String STATUS_PAUSED = "Paused";
    public static final String STATUS_OFFLINE = "Offline";
    public static final String STATUS_NEEDS_ACCESS = "Needs access";
    public static final String ACTION_PAUSE = "Pause";
    public static final String ACTION_RESUME = "Resume";
    public static final String ACTION_RECONNECT = "Reconnect";
    public static final String ACTION_OPEN_WIRELESS = "Open Wireless debugging";

    // Live tab — health & recovery cards (shown only when action is needed)
    public static final String BATTERY_TITLE = "Battery restriction active";
    public static final String BATTERY_BODY = "Allow unrestricted battery so Backstage stays connected.";
    public static final String BATTERY_ACTION = "Allow";

    public static final String USB_TITLE = "USB setting can disconnect Backstage";
    public static final String USB_BODY = "Switch default USB mode to No data transfer to prevent disconnects.";
    public static final String USB_ACTION = "Fix now";
    public static final String USB_FIXED = "USB mode updated.";
    public static final String USB_FIX_FAILED = "Could not update USB mode.";

    public static final String AUTOSTART_TITLE = "Auto-start blocked";
    public static final String AUTOSTART_BODY = "Allow Backstage to start automatically after reboot.";
    public static final String AUTOSTART_ACTION = "Open settings";

    // Live tab — Prepare & Confirm prompts
    public static final String SECTION_CONFIRMATIONS = "Needs your input";
    public static final String ACTION_APPROVE = "Approve";
    public static final String ACTION_TAKE_OVER = "Take over";
    public static final String ACTION_WATCH = "Watch";
    public static final String ACTION_HAND_BACK = "Hand back";
    public static final String ACTION_DONE_HAND_BACK = "Done — hand back";
    public static final String ACTION_MAIN_SCREEN = "Main screen";
    public static final String ACTION_TYPE = "Type";
    public static final String ACTION_SEND = "Send";
    public static final String ACTION_ENTER = "Enter";
    public static final String ACTION_DELETE_KEY = "Del";
    public static final String TYPE_HINT = "Type on screen…";

    // Live tab — Watch Backstage Live
    public static final String SECTION_ACTIVE_SCREENS = "Active screens";
    public static final String EMPTY_ACTIVE_SCREENS = "No active screens";
    public static final String LABEL_PRIVATE_SCREEN = "Virtual display";
    public static final String LABEL_MAIN_SCREEN = "Main screen";
    public static final String LABEL_AGENT_ACTIVE = "Agent active";
    public static final String LABEL_YOU_IN_CONTROL = "You’re in control · Agent paused";
    public static final String LABEL_IN_CONTROL_SHORT = "You’re in control";
    public static final String LABEL_RECORDING = "Recording";
    public static final String ACTION_STOP = "Stop";
    public static final String PREVIEW_HIDDEN_BLOCKED = "Preview hidden for blocked app";
    public static final String PREVIEW_UNAVAILABLE = "Loading live screen…";
    public static final String HANDOFF_DONE = "Moved to main screen.";
    public static final String HANDOFF_FAILED = "Could not move screen.";
    public static final String TAKEOVER_ON = "You’re in control. Agent is paused.";
    public static final String TAKEOVER_OFF = "Handed back to agent.";
    public static final String TAKEOVER_FAILED = "Could not update control.";
    public static final String SESSION_ENDED = "Screen closed.";
    public static final String SESSION_STOPPED = "Stopped.";

    // Activity tab — Timeline, Privacy Fence, Shared Inbox
    public static final String SEGMENT_TIMELINE = "Timeline";
    public static final String SEGMENT_BLOCKED = "Blocked apps";
    public static final String SEGMENT_SHARED = "Shared";

    public static final String EMPTY_TIMELINE = "No recent activity";
    public static final String TIMELINE_CLEARED = "Timeline cleared.";

    public static final String BLOCKED_DESC = "Agents cannot open these apps, see their screen, or read their notifications.";
    public static final String EMPTY_BLOCKED = "No blocked apps";
    public static final String ACTION_MANAGE_BLOCKED = "Choose blocked apps";
    public static final String DIALOG_BLOCKED_TITLE = "Blocked apps";
    public static final String SEARCH_APPS_HINT = "Search apps";
    public static final String BLOCKED_SAVED = "Blocked apps updated.";
    public static final String BLOCKED_SAVE_FAILED = "Could not update blocked apps.";

    public static final String SHARED_DESC = "Share links, photos, or files to Backstage from any app.";
    public static final String EMPTY_SHARED = "Nothing shared yet";

    // Assistants tab
    public static final String SECTION_ASSISTANTS = "Assistants";
    public static final String ACTION_CONNECT_AGENT = "Connect an agent";
    public static final String EMPTY_ASSISTANTS = "No assistants connected";
    public static final String LOADING_ACCOUNT = "Loading…";
    public static final String REQUEST_ACCESS_FMT = "%s wants access";
    public static final String REQUEST_MATCH_CODE = "Confirm this code matches your computer:";
    public static final String ACTION_ALLOW = "Allow";
    public static final String ACTION_DENY = "Deny";
    public static final String ACTION_STOP_WORK = "Stop active screens";
    public static final String ACTION_REMOVE_ACCESS = "Remove access";
    public static final String DIALOG_DEVICES_ACCESS = "Allowed devices";
    public static final String CHECK_FUTURE_DEVICES = "Include new devices added later";
    public static final String ERROR_SELECT_DEVICE = "Select at least one device or remove access.";

    // Devices section
    public static final String SECTION_DEVICES = "Devices";
    public static final String BADGE_THIS_PHONE = "This device";
    public static final String DEVICE_ONLINE = "Online";
    public static final String DEVICE_OFFLINE = "Offline";
    public static final String DEVICE_PAUSED = "Paused";
    public static final String DEVICE_RECONNECT = "Needs reconnecting";
    public static final String DEVICE_CHECKING = "Checking…";
    public static final String ACTION_ADD_DEVICE = "Add device";
    public static final String DIALOG_REMOVE_DEVICE = "Remove device";
    public static final String FIELD_DEVICE_NAME = "Name";

    // Settings tab
    public static final String SECTION_ACCOUNT = "Account";
    public static final String ACTION_SIGN_IN_METHODS = "Sign-in methods";
    public static final String SUB_SIGN_IN_METHODS = "Email, password, passkeys, and backup codes";
    public static final String ACTION_MANAGE_BROWSER = "Manage in browser";
    public static final String SUB_MANAGE_BROWSER = "Plan, billing, and full account settings";

    public static final String SECTION_OPTIONS = "Optional features";
    public static final String SECTION_LOCATION = "Location";
    public static final String LOCATION_PRECISE = "Precise";
    public static final String LOCATION_APPROX = "Approximate";
    public static final String LOCATION_OFF = "Off";
    public static final String ACTION_ALLOW_LOCATION = "Share location";
    public static final String ACTION_MANAGE_LOCATION = "Location access";
    public static final String SUB_LOCATION = "Optional. Lets agents check your location only when a task asks for it.";

    public static final String SECTION_CONNECTION = "Advanced";
    public static final String ACTION_ADVANCED_TOGGLE = "Developer & troubleshooting";
    public static final String SUB_ADVANCED_TOGGLE = "Connection info, logs, USB setup, and local port";
    public static final String STATE_SHOW = "Show";
    public static final String STATE_HIDE = "Hide";
    public static final String ACTION_DIAGNOSTIC_LOG = "Troubleshooting log";
    public static final String SUB_DIAGNOSTIC_LOG = "Recent connection events to share if you need help";
    public static final String ACTION_USB_COMMAND = "Start from a computer (USB)";
    public static final String SUB_USB_COMMAND = "Backup setup command if Wireless debugging isn’t available";
    public static final String ACTION_COPY_LOCAL_TOKEN = "Copy local access key";
    public static final String SUB_COPY_LOCAL_TOKEN = "For developers connecting custom scripts on this device";
    public static final String ACTION_CHANGE_PORT = "Local connection port";
    public static final String SUB_CHANGE_PORT = "Internal port Backstage uses on this device; change only if another app conflicts";
    public static final String ACTION_STAY_ONLINE_OFF_WIFI = "Stay connected on mobile data";
    public static final String SUB_STAY_ONLINE_OFF_WIFI = "Optional. Keeps agents connected off Wi-Fi by leaving USB debugging on while active.";
    public static final String ACTION_ALLOW_SCREEN_OFF = "Work while screen is off";
    public static final String SUB_ALLOW_SCREEN_OFF = "Optional. Lets agents run tasks on the hidden virtual display while your phone is locked.";
    public static final String ALLOW_SCREEN_OFF_BODY = "By default, agents can only act while your phone is unlocked.\n\nTurn this on to let agents run apps on the hidden virtual display while your phone’s screen is off or in your pocket. Your real screen stays black and locked, and Blocked apps still apply.";
    public static final String ACTION_ALLOW_SHELL = "Allow command-line access";
    public static final String SUB_ALLOW_SHELL = "Optional, for developers. Lets coding agents run system commands and bypass Blocked apps.";
    public static final String ACTION_OPTIONAL_TOUR = "Optional features guide";
    public static final String SUB_OPTIONAL_TOUR = "Review what each optional feature does and why it’s off by default";
    public static final String TOUR_TITLE = "Optional features";
    public static final String TOUR_INTRO = "Your phone is connected. Everything below is off by default — turn on only what you want, or skip and change these anytime in Settings.";
    public static final String TOUR_FINISH = "Finish setup";
    public static final String TOUR_SKIP = "Skip for now";
    public static final String TOUR_SCREEN_OFF_DESC = "Lets agents use apps on the hidden virtual display while your phone is locked or in your pocket.\n\nWhy it’s useful: Run tasks from your laptop without keeping your screen awake.\n\nWhy it’s optional: Off by default so nothing runs unless your phone is unlocked.";
    public static final String TOUR_MOBILE_DATA_DESC = "Keeps Backstage connected over 5G or LTE when you leave Wi-Fi.\n\nWhy it’s useful: Control your phone from anywhere, not just on home or work Wi-Fi.\n\nWhy it’s optional: Leaves Android’s USB debugging on while Backstage is active (turned back off whenever you pause).";
    public static final String TOUR_BLOCKED_APPS_DESC = "Choose apps agents can never open, see, or read notifications from.\n\nWhy it’s useful: Keeps banking, password managers, and private chats off-limits on-device.\n\nWhy it’s optional: No apps are blocked until you pick them. Backstage itself is always protected.";
    public static final String TOUR_LOCATION_DESC = "Lets agents check your current location only when a task asks for it.\n\nWhy it’s useful: Helps with nearby places, transit, and local searches.\n\nWhy it’s optional: Off until you grant Android’s location permission; never tracked in the background.";
    public static final String TOUR_SHELL_DESC = "Lets coding agents run system terminal commands on this phone.\n\nWhy it’s useful: Needed for developer builds, diagnostics, and scripts.\n\nWhy it’s optional: Developer setting — terminal commands can bypass Blocked apps, so keep it off unless you need it.";
    public static final String STATE_ON = "On";
    public static final String STATE_OFF = "Off";
    public static final String ACTION_TURN_ON = "Turn on";
    public static final String ACTION_TURN_OFF = "Turn off";
    public static final String OFF_WIFI_BODY = "Normally, Android pauses Backstage when you leave Wi-Fi.\n\nTurn this on to stay connected on 5G or LTE. While active, Backstage leaves Android’s USB debugging switched on, and turns it back off whenever you pause access.";
    public static final String ALLOW_SHELL_BODY = "Optional, for developers. Lets your agents run system-level terminal commands on this device.\n\nWarning: these commands can bypass your Blocked apps list. Keep this off unless a coding agent asks for it.";
    public static final String TOKEN_COPIED = "Local access key copied. Only needed for custom scripts on this device.";
    public static final String COPIED = "Copied.";

    // Sign-in & setup
    public static final String SIGN_IN_TITLE = "Log in";
    public static final String SIGN_UP_TITLE = "Create account";
    public static final String SIGN_IN_FINISHING = "Finishing sign-in…";
    public static final String FIELD_EMAIL = "Email";
    public static final String FIELD_EMAIL_HINT = "you@example.com";
    public static final String FIELD_PASSWORD = "Password";
    public static final String FIELD_PASSWORD_HINT = "At least 8 characters";
    public static final String FIELD_CONFIRM_PASSWORD = "Confirm password";
    public static final String FIELD_CURRENT_PASSWORD = "Current password";
    public static final String FIELD_NEW_PASSWORD = "New password";
    public static final String FIELD_EMAIL_CODE = "Verification code";
    public static final String ACTION_LOG_IN = "Log in";
    public static final String ACTION_CREATE_ACCOUNT = "Create account";
    public static final String ACTION_VERIFY_CREATE = "Verify & create account";
    public static final String ACTION_FORGOT_PASSWORD = "Forgot password?";
    public static final String ACTION_RESET_PASSWORD = "Reset password";
    public static final String ACTION_SEND_RESET_CODE = "Send reset code";
    public static final String ACTION_SIGN_IN_EMAIL_CODE = "Sign in with email code";
    public static final String ACTION_BACK_TO_LOGIN = "Back to log in";
    public static final String ACTION_CHANGE_EMAIL_STEP = "Change email";
    public static final String ACTION_EMAIL_CODE = "Email me a code";
    public static final String ACTION_SEND_NEW_CODE = "Send new code";
    public static final String ACTION_PASSKEY_SIGN_IN = "Sign in with passkey";
    public static final String ACTION_PASSKEY_CREATE = "Create account with passkey";
    public static final String ACTION_RECOVERY_CODE = "Use recovery code";
    public static final String ACTION_CONNECTION_CODE = "Use connection code";
    public static final String ERROR_PASSWORD_SHORT = "Password must be at least 8 characters.";
    public static final String ERROR_PASSWORD_MISMATCH = "Passwords do not match.";
    public static final String ERROR_EMAIL_PASSWORD = "Enter your email and password.";
    public static final String CREATING_ACCOUNT = "Creating account…";
    public static final String VERIFYING_CODE = "Verifying code…";
    public static final String RESETTING_PASSWORD = "Resetting password…";
    public static final String SIGN_UP_BODY = "Create an account with your email and password. We will email a 6-digit code to verify your address.";
    public static final String SIGN_UP_CODE_SENT = "We emailed a 6-digit verification code. Enter it to finish creating your account.";
    public static final String RESET_BODY = "Enter your email and we will send a 6-digit code to reset your password.";
    public static final String RESET_CODE_SENT = "If that email has an account, we sent a 6-digit reset code.";
    public static final String OTP_BODY = "Get a one-time 6-digit sign-in code by email.";
    public static final String PASSWORD_TITLE = "Password";
    public static final String PASSWORD_SET_BODY = "Password is set. You can log in with your email and password.";
    public static final String PASSWORD_NONE_BODY = "No password set yet. Add one to log in with email and password.";
    public static final String ACTION_SET_PASSWORD = "Set password";
    public static final String ACTION_CHANGE_PASSWORD = "Change password";
    public static final String PASSWORD_SAVED = "Password saved.";

    public static final String SETUP_ACCESS_TITLE = "Enable device access";
    public static final String ACTION_OPEN_DEV_OPTIONS = "Open Developer options";
    public static final String ACTION_ENTER_PAIR_CODE = "Enter pairing code";
    public static final String ACTION_PAIR = "Pair";
    public static final String FIELD_PORT = "Pairing port";
    public static final String FIELD_PAIR_CODE = "6-digit code";

    // Status details
    public static final String PAUSED_BODY = "Assistants cannot reach this device until you resume.";
    public static final String PAUSE_UNCONFIRMED = "Could not confirm pause. Tap Pause to try again.";
    public static final String PAUSE_STILL_ACTIVE = "Access is still active. Tap Pause to stop it, or Resume to keep it on.";
    public static final String PORT_CONFLICT = "Another app is using Backstage’s connection port. Tap Reconnect to switch automatically.";
    public static final String RESTART_FOR_UPDATE = "Tap Reconnect to finish the update.";
    public static final String WIRELESS_OFF_CONNECTED = "Wireless debugging is off. Access works now, but turn it on so Backstage can reconnect later.";
    public static final String RELAY_REMOVED = "This device was removed from your account. Sign in again to reconnect.";
    public static final String RECONNECTING_BODY = "Device access is ready. Waiting for the Backstage server.";
    public static final String PAIR_REVOKED = "Android revoked Wireless debugging access. Enter a new 6-digit pairing code to reconnect.";
    public static final String ACTION_PAIR_AGAIN = "Pair device again";
    public static final String WIRELESS_OFF = "Wireless debugging is off. Connect to Wi-Fi and turn it on to restore access.";
    public static final String ACCESS_NEEDS_HAND = "Check Wireless debugging, or enter a new pairing code.";
    public static final String DAEMON_UNAVAILABLE = "Backstage is not running. Resume or reconnect on the Live tab.";

    // Pause, restart, pairing
    public static final String PAUSING = "Pausing…";
    public static final String RESUMING = "Resuming…";
    public static final String PAUSE_REQUESTED = "Stopping access…";
    public static final String PAUSE_DONE = "Access is paused.";
    public static final String RESTARTING = "Reconnecting…";
    public static final String STARTING_CONNECTION = "Starting connection…";
    public static final String RESTART_NEEDS_WIRELESS = "Connect to Wi-Fi and turn on Wireless debugging, then tap Reconnect.";
    public static final String RESTART_FAILED = "Could not restart access. Check Wireless debugging and try again.";
    public static final String START_FAILED = "Could not start access. Tap Reconnect to try again.";
    public static final String PAIRING_SHOWN = "Pairing notification shown. Pull down to enter the code.";
    public static final String PAIRING_OPEN_FIRST = "Open Wireless debugging and tap Pair device with pairing code, then enter the code here or in the notification.";
    public static final String ALLOW_NOTIFICATIONS_THEN = "Allow notifications, then tap Show pairing notification again.";
    public static final String PAIR_DIALOG_BODY = "Enter the 6-digit code and the 5-digit port shown under IP address & port in Wireless debugging. Keep that screen open in split screen if the code expires.";
    public static final String FIELD_PAIR_CODE_HINT = "6 digits";
    public static final String FIELD_PORT_HINT = "5-digit number after the colon (e.g. 39821)";
    public static final String ERROR_PAIR_CODE = "Enter the 6-digit code";
    public static final String ERROR_PAIR_PORT = "Enter the 5-digit pairing port";
    public static final String CHECKING_PAIR_CODE = "Checking pairing code…";
    public static final String PAIR_APPROVED = "Device paired. Starting connection…";
    public static final String PAIR_REJECTED = "Code rejected. Open a new pairing code in Wireless debugging and try again.";

    // Setup: sign in and connection code
    public static final String SIGN_IN_BODY = "Log in with your Backstage email and password.";
    public static final String FIELD_EMAIL_CODE_HINT = "123456";
    public static final String SENDING_CODE = "Sending code…";
    public static final String EMAIL_CODE_SENT = "We emailed a 6-digit code. It expires in 10 minutes.";
    public static final String SIGNING_IN = "Signing in…";
    public static final String OPENING_PASSKEY = "Opening passkey sign-in…";
    public static final String FIELD_RECOVERY_CODE = "Recovery code";
    public static final String FIELD_RECOVERY_CODE_HINT = "ABCD-EFGH-JKLM";
    public static final String LINK_REVIEW = "Review the server and code, then tap Connect.";
    public static final String LINK_INCOMPLETE = "This setup link is incomplete. Request a new one from your assistant.";
    public static final String CONNECT_CODE_TITLE = "Enter connection code";
    public static final String CONNECT_CODE_BODY = "Enter a code from Backstage on another Android device or from your assistant.";
    public static final String FIELD_CONNECTION_NAME = "Connection name (optional)";
    public static final String FIELD_CONNECTION_NAME_HINT = "e.g. Work laptop";
    public static final String FIELD_CONNECTION_CODE = "Connection code";
    public static final String FIELD_CONNECTION_CODE_HINT = "ABCD-EFGHJK";
    public static final String SERVER_FMT = "Server: %s";
    public static final String CONNECT_TRUST_BODY = "Connected assistants can use apps and run actions on this device. You can pause or remove access anytime.";
    public static final String CONNECTING = "Connecting…";
    public static final String ACTION_CONNECT = "Connect";
    public static final String ACTION_PASTE_CODE = "Paste code or link";
    public static final String ACTION_CHANGE_SERVER = "Change server";
    public static final String ACTION_WHERE_CODE = "Where do I get a code?";
    public static final String WHERE_CODE_TITLE = "Get a connection code";
    public static final String WHERE_CODE_BODY = "Adding this device to an account you already have? On your other Android device, open Backstage and tap Add device; it shows the code.\n\nConnecting an assistant on your computer? Its Backstage connection page shows the code.\n\nUsing the command line? Run backstage pair and keep it open.\n\nThis code is different from Android’s 6-digit pairing code.";
    public static final String CODE_CHECK = "Check the connection code and server address. The code looks like ABCD-EFGHJK.";
    public static final String OTHER_SERVER = "This device already uses another server. Remove its connections on the Assistants tab before switching servers.";
    public static final String APPROVING_CONNECTION = "Approving connection…";
    public static final String ASSISTANT_CONNECTED = "Assistant connected.";
    public static final String ASSISTANT_SAVED_RESTART = "Assistant linked and saved. Tap Reconnect on the Live tab to apply it.";
    public static final String CLIPBOARD_NO_CODE = "No connection code or link found on clipboard";
    public static final String CODE_PASTED = "Code pasted";
    public static final String FIELD_SERVER = "Secure server address";
    public static final String SERVER_BODY = "Use the server shown with your assistant’s connection code.";

    // Setup: phone access
    public static final String ACCESS_WIFI_BODY = "Connect to Wi-Fi for this step. Once Backstage is running, mobile data works too.";
    public static final String ACTION_FIND_DEV_OPTIONS = "Can’t find Developer options?";
    public static final String DEV_OPTIONS_TITLE = "Show Developer options";
    public static final String DEV_OPTIONS_BODY = "Open Settings → About phone. Tap Build number 7 times, then enter your screen-lock PIN if Android asks.\n\nReturn here and tap Open Wireless debugging. Some phones put Build number inside Software information.";
    public static final String ACTION_OPEN_ABOUT_PHONE = "Open About phone";
    public static final String STEP_WIRELESS_BODY = "Turn it on, then tap Pair device with pairing code.";
    public static final String STEP_CODE_TITLE = "Enter Android’s code";
    public static final String STEP_CODE_NOTIFY = "Use the Backstage notification while the code stays open.";
    public static final String STEP_CODE_MANUAL = "Allow notifications when asked, or enter the code here.";
    public static final String ACTION_ALLOW_NOTIFICATIONS = "Allow notifications";
    public static final String ACTION_SHOW_PAIRING = "Show pairing notification";
    public static final String PAIRING_WAITING = "Waiting for Android’s pairing code…";
    public static final String PAIRING_SAVED = "Android approval saved. Return to Backstage when you’re ready.";
    public static final String PAIRING_AVAILABLE = "Pairing is available. Enter Android’s 6-digit code in the notification.";
    public static final String HELP_STOPPED_TITLE = "If the connection stopped";
    public static final String HELP_STOPPED_BODY = "Check your internet connection. After a restart, turn on Wireless debugging if Backstage cannot reconnect.";

    // Account and sign-in methods
    public static final String WAIT_FOR_ACCOUNT = "Wait for your account to load, then try again.";
    public static final String EMAIL_ADD_BODY = "Add an email to sign in with a code on any device.";
    public static final String EMAIL_UNAVAILABLE = "Email sign-in is not available on this server.";
    public static final String EMAIL_CURRENT_FMT = "%s · sign in with a code sent here";
    public static final String ACTION_ADD_EMAIL = "Add email";
    public static final String ACTION_CHANGE_EMAIL = "Change email";
    public static final String ACTION_REMOVE_EMAIL = "Remove email";
    public static final String EMAIL_REMOVED = "Email removed.";
    public static final String EMAIL_DIALOG_TITLE = "Email for signing in";
    public static final String EMAIL_SENT = "We emailed a 6-digit code.";
    public static final String EMAIL_ADDED = "Email added";
    public static final String ACTION_CONFIRM = "Confirm";
    public static final String RECOVERY_CODES_TITLE = "Recovery codes";
    public static final String RECOVERY_LEFT_FMT = "%d unused. Each signs you in once if you lose your other methods.";
    public static final String RECOVERY_NONE = "Save recovery codes in case you lose your email and passkeys.";
    public static final String ACTION_REPLACE_RECOVERY = "Replace recovery codes";
    public static final String ACTION_CREATE_RECOVERY = "Create recovery codes";
    public static final String RECOVERY_SAVE_BODY = "Save these somewhere safe. Each signs you in once. They are shown only now.";
    public static final String RECOVERY_COPIED = "Recovery codes copied";
    public static final String PASSKEYS_TITLE = "Passkeys";
    public static final String PASSKEYS_NONE = "No passkey yet. Add one in a browser to sign in with your fingerprint or screen lock.";
    public static final String REMOVE_PASSKEY_FMT = "Remove %s";
    public static final String REMOVE_PASSKEY_TITLE = "Remove this passkey?";
    public static final String PASSKEY_REMOVED = "Passkey removed.";
    public static final String OPEN_SITE_FALLBACK = "Open android-backstage.com in your browser to manage access.";

    // Assistants and devices
    public static final String ASSISTANT_CAN_USE_FMT = "Can use %s";
    public static final String USES_ALL_DEVICES = "all your devices";
    public static final String USES_THIS_PHONE = "this device";
    public static final String USES_NO_DEVICE = "no device";
    public static final String ALLOWING = "Allowing…";
    public static final String DENYING = "Denying…";
    public static final String SAVING = "Saving…";
    public static final String REMOVING = "Removing…";
    public static final String STOPPING = "Stopping…";
    public static final String RENAMING = "Renaming…";
    public static final String ASSISTANT_RESUMED_FMT = "%s can use your devices again.";
    public static final String ASSISTANT_PAUSED_FMT = "%s is paused. Its commands are refused until you resume it.";
    public static final String SCREENS_CLOSED_FMT = "%s’s screens were closed.";
    public static final String UPDATED_FMT = "%s updated.";
    public static final String REMOVE_NAMED_FMT = "Remove %s?";
    public static final String REMOVE_ASSISTANT_BODY_FMT = "Every chat in %s loses access to your devices. Your other assistants and devices are not affected. You can connect it again later.";
    public static final String REMOVED_FMT = "%s removed.";
    public static final String RENAMED_FMT = "Renamed to %s";
    public static final String FIELD_DEVICE_NAME_HINT = "e.g. Work phone";
    public static final String ERROR_NAME_REQUIRED = "Give it a name";
    public static final String DEVICE_NAME_BODY = "Your assistants use this name to pick the device.";
    public static final String DEVICE_LOST_LINK = "This device lost its link. Open Backstage on it and sign in to reconnect.";
    public static final String REMOVE_DEVICE_BODY = "Your assistants can no longer use it. To add it again, use Add device.";
    public static final String FIELD_NEW_DEVICE_HINT = "Name for the new device";
    public static final String ADD_DEVICE_BODY = "Install Backstage on the new device. You will get a code to enter there.";
    public static final String ACTION_GET_CODE = "Get a code";
    public static final String GETTING_CODE = "Getting a code…";
    public static final String ADD_CODE_BODY = "On the new device, open Backstage, tap Use connection code and type:";
    public static final String ADD_CODE_WAITING_FMT = "Waiting for %s… The code works for ten minutes.";
    public static final String ADD_NAMED_FMT = "Add %s";
    public static final String CODE_EXPIRED = "The code expired. Start again.";
    public static final String DEVICE_JOINED_FMT = "%s joined your account";
    public static final String CONNECTION_INTERRUPTED = "Connection interrupted. Trying again…";

    // Connect an agent
    public static final String CONNECT_PHONE_FIRST = "Connect this device first. Once it is online, connect your agents here.";
    public static final String AGENT_INSTRUCTIONS_FMT = "Connect this agent to my Android Backstage. Follow %s";
    public static final String CONNECT_AGENT_BODY = "On your computer, open android-backstage.com and choose your agent. Or add this address to it:";
    public static final String ACTION_COPY_ADDRESS = "Copy address";
    public static final String ADDRESS_COPIED = "Address copied";
    public static final String ACTION_COPY_INSTRUCTIONS = "Copy instructions for my agent";
    public static final String PASTE_INTO_AGENT = "Paste it into your agent";
    public static final String CONNECT_AGENT_APPROVE = "When your agent signs in, enter your email on the Backstage page. This device then shows the request. Check that the digits match and tap Allow.";
    public static final String CHOOSE_AGENT_TITLE = "Choose your agent";
    public static final String AGENT_CHATGPT = "ChatGPT";
    public static final String AGENT_CURSOR = "Cursor";
    public static final String AGENT_CODEX = "Codex";
    public static final String AGENT_CLAUDE_CODE = "Claude Code";
    public static final String AGENT_OTHER = "Another agent";
    public static final String AGENT_YOURS = "your agent";
    public static final String CREATING_REQUEST = "Creating connection request…";
    public static final String REQUEST_EXPIRED = "Your connection request expired. Tap Connect an agent to start again.";
    public static final String INVITE_MESSAGE_FMT = "Connect %1$s to my Android Backstage phone. Read https://relay.android-backstage.com/work/connect/instructions?host=%2$s and follow the local setup for this host. My one-use invitation is %3$s. It approves automatically—install the connection without replacing other connections, then verify it automatically with android_devices (never ask me to open Claude Code or run /mcp). Never print access tokens.";
    public static final String CHATGPT_STEP_PLUGIN = "Open ChatGPT in a browser. Add Android Backstage in Plugins, then connect it using the address below.";
    public static final String CHATGPT_DEV_MODE = "If Plugins has no add button, enable Developer mode in ChatGPT’s Security and login settings. Your account or workspace must allow custom plugins.";
    public static final String ACTION_OPEN_CHATGPT = "Open ChatGPT";
    public static final String PASTE_ADDRESS_CHATGPT = "Paste into ChatGPT’s connection address";
    public static final String CHATGPT_STEP_CODE = "On the Backstage page that opens, paste this one-use code to connect immediately.";
    public static final String ACTION_COPY_CONNECTION_CODE = "Copy connection code";
    public static final String PASTE_CODE_PAGE = "Paste into the Backstage connection page";
    public static final String INVITE_PASTE_FMT = "Paste this message into %s. It connects and verifies automatically—no extra confirmation needed.";
    public static final String ACTION_COPY_SETUP_MESSAGE = "Copy setup message";
    public static final String PASTE_INTO_FMT = "Paste into %s";
    public static final String USE_CODE_IF_PAGE = "Use this if your agent opens a Backstage connection page";
    public static final String CODEX_LOCAL_ONLY = "Run this in a local Codex session on your computer (cloud tasks cannot connect directly).";
    public static final String INVITE_LASTS = "This request lasts 10 minutes. You can switch apps and return here.";
    public static final String WAITING_FOR_FMT = "Waiting for %s…";
    public static final String CONNECT_NAMED_FMT = "Connect %s";
    public static final String ACTION_START_AGAIN = "Start again";
    public static final String REQUEST_ENDED = "The request ended. Tap Start again to retry.";
    public static final String CANT_REACH_RETRYING = "Couldn’t reach Backstage. Checking again…";
    public static final String INVITE_CHECK_DIGITS = "Check that your agent shows these six digits. Allowing it lets it use apps and run actions on this device.";
    public static final String ALLOW_NAMED_FMT = "Allow %s?";
    public static final String ACCESS_ALLOWED_RETURN = "Access allowed. Return to your agent to finish connecting.";
    public static final String REQUEST_DENIED = "Request denied.";

    // Approval code
    public static final String ACTION_APPROVAL_CODE = "Enter approval code";
    public static final String APPROVAL_CODE_BODY = "Your assistant shows a code like ABCD-EFGH when it asks to connect. Enter it here.";
    public static final String FIELD_APPROVAL_CODE = "Approval code";
    public static final String FIELD_APPROVAL_CODE_HINT = "ABCD-EFGH";
    public static final String ACTION_NEXT = "Next";
    public static final String ERROR_APPROVAL_CODE = "Enter the 8-character code";
    public static final String CHECKING_CODE = "Checking the code…";
    public static final String APPROVE_DEVICES_BODY = "This assistant can use apps and run actions on the selected devices. You can change or remove access anytime in Assistants.";
    public static final String CONNECTED_RETURN_FMT = "%s is connected. Return to it on your computer.";

    // Connections on another server
    public static final String PERMIT_RESUME = "Resume your connection to check access.";
    public static final String PERMIT_FULL = "Full access · all chats on this connection";
    public static final String PERMIT_PAUSED = "Paused · no device access";
    public static final String PERMIT_TESTING_FMT = "Testing %s · %d min left";
    public static final String PERMIT_TEST_ENDED = "Paused · test access ended";
    public static final String PERMIT_REVIEW = "Access needs review";
    public static final String ACTION_ALLOW_ACCESS = "Allow access";
    public static final String ACTION_RENAME = "Rename";
    public static final String ACTION_LIMIT_APP = "Limit to an app";
    public static final String REMOVE_CONNECTION_BODY = "Every chat using this connection loses access. Reconnecting needs a new code.";
    public static final String CONNECTION_ID_FMT = "Connection ID · %s";
    public static final String FIELD_CONNECTION_LABEL = "Connection name";
    public static final String RENAME_CONNECTION_BODY = "Name the app or computer. This name applies to every chat using this connection.";
    public static final String ALLOW_FULL_BODY = "Every chat using this connection can use apps and run actions on your Android device.";
    public static final String LIMIT_APP_TITLE = "Test one app for 15 minutes";
    public static final String LIMIT_APP_BODY_FMT = "For %s. Lets the agent open and inspect this app only, without tapping or running system commands. Access pauses after 15 minutes.";
    public static final String ACTION_ALLOW_15_MIN = "Allow for 15 minutes";
    public static final String SELECT_APP = "Select an app…";
    public static final String ERROR_CHOOSE_APP = "Choose the app to allow";
    public static final String ENTER_PACKAGE = "Enter an app ID manually…";
    public static final String FIELD_PACKAGE = "App ID";
    public static final String FIELD_PACKAGE_HINT = "com.example.app";
    public static final String ERROR_PACKAGE = "Enter a valid app ID (like com.example.app)";
    public static final String PERMIT_SET_QA_FMT = "%s: one app for 15 minutes.";
    public static final String PERMIT_SET_PAUSED_FMT = "%s: access paused.";
    public static final String PERMIT_SET_FULL_FMT = "%s: full access allowed.";
    public static final String PERMIT_NOT_CHANGED = "Access was not changed. Resume your device connection and try again.";
    public static final String CONNECTION_REMOVED = "Connection removed.";
    public static final String REMOVAL_UNCONFIRMED = "Removal was not confirmed. Pause access to block the assistant, then resume and retry removal.";

    // Settings dialogs
    public static final String ACTION_CONNECTION_DETAILS = "Connection info";
    public static final String SUB_CONNECTION_DETAILS = "Server address, app version, and network status";
    public static final String DETAIL_VERSION = "App version";
    public static final String DETAIL_SERVER = "Server";
    public static final String DETAIL_ADDRESSES = "Wi-Fi IP address";
    public static final String DETAIL_PAIRED = "Device paired";
    public static final String DETAIL_PAIR_PORT = "Last pairing port";
    public static final String DETAIL_CONNECT_PORT = "Last wireless port";
    public static final String DETAIL_YES = "Yes";
    public static final String DETAIL_NO = "No";
    public static final String NO_DETAILS = "No details yet.";
    public static final String USB_COMMAND_BODY = "Only needed if you can’t use Wireless debugging. Connect this device to a computer with USB debugging turned on and run:";
    public static final String PORT_DIALOG_BODY = "Backstage uses this internal port number on your device. Only change it if another app conflicts with the current port.";
    public static final String PAUSE_FIRST_TITLE = "Pause access first";
    public static final String PAUSE_FIRST_BODY = "Pause access on the Live tab before changing the local connection port.";
    public static final String ERROR_PORT = "Use a number from 1 to 65535";
    public static final String PORT_SAVED = "Local connection port saved";
    public static final String LOCATION_BODY = "Optional. Lets your agents check your location when a task asks for it (like finding nearby places). Location is only checked when requested and stops as soon as the request finishes.";
    public static final String LOCATION_BACKGROUND_TITLE = "Allow location in the background";
    public static final String LOCATION_BACKGROUND_FMT = "Open Permissions, then Location. Choose “%s” so requests work while you use other apps.";
    public static final String ACTION_CONTINUE = "Continue";
    public static final String AUTOSTART_OPEN_FAILED = "Could not open auto-start settings";

    // Share sheet feedback
    public static final String SHARED_TOAST = "Shared to Backstage";
    public static final String SHARE_FAILED_TOAST = "Could not share. Open Backstage and try again.";

    // Settings tab — manual in-app updates
    public static final String SECTION_UPDATES = "App updates";
    public static final String ACTION_CHECK_UPDATES = "Check for updates";
    public static final String SUB_CHECK_UPDATES = "Check for a newer version of Backstage";
    public static final String UPDATE_CHECKING = "Checking…";
    public static final String UPDATE_UP_TO_DATE = "Up to date";
    public static final String UPDATE_UP_TO_DATE_FMT = "You’re on the latest version (%s).";
    public static final String UPDATE_AVAILABLE_FMT = "v%s ready";
    public static final String UPDATE_TITLE = "Update available";
    public static final String UPDATE_BODY_FMT = "Backstage %s is ready to install. The download is verified automatically before updating.";
    public static final String UPDATE_ACTION = "Update now";
    public static final String UPDATE_OPEN_BROWSER = "Download in browser";
    public static final String UPDATE_IN_PROGRESS = "Updating Backstage…";
    public static final String UPDATE_RESTARTING = "Installing update. Reopen Backstage in about a minute.";
    public static final String UPDATE_FAILED = "Could not start update. Make sure Backstage is connected and try again.";
    public static final String UPDATE_CHECK_FAILED = "Could not check for updates. Check your internet connection.";

    public static String activeScreensLabel(int count) {
        return count == 1 ? "Active on 1 screen" : "Active on " + count + " screens";
    }

    public static String blockedCountLabel(int count) {
        if (count <= 0) return EMPTY_BLOCKED;
        return count == 1 ? "1 blocked app" : count + " blocked apps";
    }

    /** Plain-English timeline label for a recorded tool call. */
    public static String toolLabel(String tool) {
        if (tool == null) return "";
        String t = tool.startsWith("android.") ? tool.substring(8) : tool;
        switch (t) {
            case "observe": return "Read screen";
            case "open": return "Opened app";
            case "tap": case "tap_text": return "Tapped screen";
            case "type": return "Typed text";
            case "key": return "Pressed key";
            case "gesture": return "Swiped screen";
            case "scroll": case "scroll_until": return "Scrolled screen";
            case "wait": return "Waited for screen";
            case "sequence": return "Ran multiple steps";
            case "task_begin": case "task": return "Opened virtual display";
            case "task_finish": return "Closed virtual display";
            case "task_share": return "Shared virtual display";
            case "handoff": return "Moved to main screen";
            case "record": return "Recorded screen";
            case "confirm": return "Asked for confirmation";
            case "notification": return "Checked notifications";
            case "clipboard": return "Used clipboard";
            case "location": return "Checked location";
            case "media": return "Controlled media";
            case "personal": return "Used phone shortcut";
            case "events": return "Updated background alerts";
            case "shell": return "Ran system command";
            case "pull": case "push": case "fetch": case "file": return "Transferred file";
            case "install": return "Installed app";
            case "device_control": return "Changed device setting";
            case "audio": return "Used audio";
            case "logs": return "Read diagnostic logs";
            case "packages": return "Checked installed apps";
            case "developer": return "Ran developer tool";
            default: {
                String clean = t.replace('_', ' ').trim();
                return clean.isEmpty() ? clean : clean.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + clean.substring(1);
            }
        }
    }
}
