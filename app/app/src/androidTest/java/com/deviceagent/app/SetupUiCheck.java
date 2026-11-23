package com.deviceagent.app;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;

/** Device UI smoke checks on a private display. Never launches on the user's screen. */
public final class SetupUiCheck extends Instrumentation {
    private Activity activity;
    private VirtualDisplay display;
    private ImageReader reader;
    private android.os.HandlerThread frames;
    private int passed;
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        int resultCode = Activity.RESULT_CANCELED;
        try {
            Context app = getTargetContext();
            // Match Android CTS: allow test launches on an owned private display.
            // This permission belongs only to this instrumentation session.
            getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                    .adoptShellPermissionIdentity("android.permission.INTERNAL_SYSTEM_WINDOW");
            if (AgentConfig.load(app).hasRelay()) throw new IllegalStateException("Use an unlinked test install; existing connections are preserved.");
            AgentConfig.setAccessEnabled(app, true);
            new BootReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
            if (!AgentConfig.load(app).accessEnabled) throw new AssertionError("A reboot must preserve enabled access");
            AgentConfig.setAccessEnabled(app, false);
            new BootReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
            if (AgentConfig.load(app).accessEnabled) throw new AssertionError("A reboot must preserve a deliberate pause");
            passed += 2;
            android.app.NotificationManager notifications = app.getSystemService(android.app.NotificationManager.class);
            if (notifications.areNotificationsEnabled()) {
                RecoveryNotifier.showWirelessOff(app);
                boolean posted = false;
                for (int attempt = 0; attempt < 20 && !posted; attempt++) {
                    for (android.service.notification.StatusBarNotification item : notifications.getActiveNotifications())
                        if (item.getId() == RecoveryNotifier.ID
                                && RecoveryNotifier.CHANNEL.equals(item.getNotification().getChannelId())
                                && item.getNotification().contentIntent != null) posted = true;
                    if (!posted) Thread.sleep(100);
                }
                RecoveryNotifier.clear(app);
                if (!posted) throw new AssertionError("Recovery alert needs its own channel and settings action");
                passed++;
            }
            reader = ImageReader.newInstance(1080,2400, PixelFormat.RGBA_8888,3);
            frames = new android.os.HandlerThread("backstage-review-frames"); frames.start();
            reader.setOnImageAvailableListener(source -> { android.media.Image image = source.acquireLatestImage(); if (image != null) image.close(); }, new android.os.Handler(frames.getLooper()));
            display = app.getSystemService(DisplayManager.class).createVirtualDisplay("Backstage setup review",1080,2400,420,reader.getSurface(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            if (display == null || display.getDisplay().getDisplayId() == 0) throw new IllegalStateException("A private test display is required");
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplay().getDisplayId());
            Intent launch = new Intent(app, AgentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            if (!app.getSystemService(android.app.ActivityManager.class).isActivityStartAllowedOnDisplay(app, display.getDisplay().getDisplayId(), launch))
                throw new IllegalStateException("Android does not allow this app on the private test display");
            String code = arguments.getString("connectionCode");
            if (code != null) launch.setAction(Intent.ACTION_VIEW).setData(new android.net.Uri.Builder()
                    .scheme("androidbackstage").authority("connect")
                    .appendQueryParameter("relay", SetupRules.DEFAULT_RELAY).appendQueryParameter("code", code).build());
            activity = startActivitySync(launch, options.toBundle());
            waitForIdleSync(); Thread.sleep(1500);
            if (code != null) {
                tap("Connect assistant");
                long deadline = System.currentTimeMillis() + 100000;
                while (!AgentConfig.load(app).hasAssistant() && System.currentTimeMillis() < deadline) Thread.sleep(500);
                if (!AgentConfig.load(app).hasAssistant()) { capture("07-link-failed"); throw new AssertionError("Assistant connection did not finish"); }
                if (!SetupRules.sameServer(AgentConfig.load(app).relayUrl, SetupRules.DEFAULT_RELAY)) throw new AssertionError("Wrong server");
                Thread.sleep(2500); capture("07-linked");
                result.putString("stream", "PASS: phone completed encrypted pairing with the public relay on private display " + display.getDisplay().getDisplayId());
            } else {
            assertText("Sign in to Backstage"); assertText("Create account"); assertText("Sign in"); capture("01-sign-in");
            tap("I already have a connection code"); assertText("Enter connection code"); capture("02-manual");
            tap("Connect assistant"); assertText("Check the connection code and server address."); capture("02-code-error");
            tap("Back"); tap("Help"); assertText("If the connection stopped"); capture("03-help");
            tap("Open phone access setup"); assertText("Allow phone access"); assertText("Open Wireless debugging"); assertText("Enter the code in this app"); capture("04-phone-access");
            tap("Back"); tap("Help"); assertText("Advanced"); capture("05-advanced");
            tap("Back");
            Intent link = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("androidbackstage://connect?relay=https%3A%2F%2Fexample.com&code=ABCD-EFGHJK"),app,AgentActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            runUi(() -> activity.startActivity(link, options.toBundle()));
            waitForIdleSync(); Thread.sleep(500);
            assertText("Server: https://example.com"); assertText("Connect assistant");
            if (AgentConfig.load(app).hasRelay()) throw new AssertionError("A deep link must not connect automatically");
            passed++; capture("06-review-link");
            for(String agent : new String[]{"chatgpt","cursor","codex","claude-code","other"}) {
                runUi(() -> {
                    try {
                        app.getSharedPreferences("backstage-invitation",0).edit().putString("code","test-invitation").putString("agent",agent).putLong("expires",System.currentTimeMillis()+600000).commit();
                        java.lang.reflect.Method method=AgentActivity.class.getDeclaredMethod("showInvitation",AccountClient.class,String.class,String.class);method.setAccessible(true);
                        method.invoke(activity,new AccountClient("http://127.0.0.1:1"),"test-invitation",agent);
                    }catch(Exception e){throw new RuntimeException(e);}
                });
                waitForIdleSync();
                assertText(agent.equals("chatgpt")?"Copy connection code":"Copy setup message");
                assertText("You can switch apps and return here.");capture("08-"+agent);
                runUi(() -> { try { java.lang.reflect.Field field=AgentActivity.class.getDeclaredField("currentInvitationDialog");field.setAccessible(true);((android.app.AlertDialog)field.get(activity)).dismiss(); }catch(Exception e){throw new RuntimeException(e);} });
                if(!app.getSharedPreferences("backstage-invitation",0).getString("code","").equals("test-invitation"))throw new AssertionError("Closing a screen lost the saved invitation");
                passed++;
            }
            app.getSharedPreferences("backstage-invitation",0).edit().clear().commit();

            AgentConfig original = AgentConfig.load(app);
            AgentConfig linked = AgentConfig.load(app);
            linked.relayUrl = "https://example.invalid";
            linked.deviceToken = "setup-check-token";
            linked.upsertLink("setup-check-agent", "setup-check-key");
            linked.save(app);
            runUi(() -> {
                try {
                    java.lang.reflect.Field configField = AgentActivity.class.getDeclaredField("config");
                    configField.setAccessible(true); configField.set(activity, AgentConfig.load(app));
                    java.lang.reflect.Field daemonField = AgentActivity.class.getDeclaredField("daemonUp");
                    daemonField.setAccessible(true); daemonField.setBoolean(activity, false);
                    java.lang.reflect.Field sessionField = AgentActivity.class.getDeclaredField("session");
                    sessionField.setAccessible(true); Object session = sessionField.get(activity);
                    java.lang.reflect.Field pageField = session.getClass().getDeclaredField("page");
                    pageField.setAccessible(true); pageField.set(session, "main");
                    java.lang.reflect.Method render = AgentActivity.class.getDeclaredMethod("render");
                    render.setAccessible(true); render.invoke(activity);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            assertText("Allow phone access");
            assertMissing("Assistants");
            original.save(app);

            result.putString("stream", "OK (" + passed + " tests)\nPASS " + passed + " UI checks on private display " + display.getDisplay().getDisplayId() + ". Screenshots: " + new File(app.getExternalFilesDir(null),"ui-review"));
            }
            resultCode = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("stream","FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            if (activity != null) runUi(() -> activity.finishAndRemoveTask());
            if (display != null) display.release(); if (reader != null) reader.close(); if (frames != null) frames.quitSafely();
            getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).dropShellPermissionIdentity();
        }
        finish(resultCode,result);
    }
    private void runUi(Runnable action) {
        Throwable[] failure = {null};
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError(failure[0].getMessage(), failure[0]);
    }
    private View reviewView() {
        try { java.lang.reflect.Field field=AgentActivity.class.getDeclaredField("currentInvitationDialog");field.setAccessible(true);android.app.AlertDialog dialog=(android.app.AlertDialog)field.get(activity);if(dialog!=null&&dialog.isShowing())return dialog.getWindow().getDecorView(); }catch(Exception ignored){}
        return activity.getWindow().getDecorView();
    }
    private void tap(String label) {
        runUi(() -> { View view = find(reviewView(),label,true); if (view == null || !view.performClick()) throw new AssertionError("Missing action: " + label); });
        waitForIdleSync();
    }
    private void assertText(String text) {
        runUi(() -> { if (find(reviewView(),text,false)==null) throw new AssertionError("Missing text: " + text); }); passed++;
    }
    private void assertMissing(String text) {
        runUi(() -> { if (find(reviewView(),text,false)!=null) throw new AssertionError("Unexpected text: " + text); }); passed++;
    }
    private View find(View view, String text, boolean exact) {
        if (view instanceof TextView) {
            String value=((TextView)view).getText().toString();
            if (exact ? value.equals(text) : value.contains(text)) return view;
        }
        if (view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) { View child=find(((ViewGroup)view).getChildAt(i),text,exact); if(child!=null)return child; }
        return null;
    }
    private void capture(String name) {
        try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        runUi(() -> {
            try {
                View view=reviewView();
                Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888); view.draw(new Canvas(bitmap));
                File directory=new File(getTargetContext().getExternalFilesDir(null),"ui-review"); directory.mkdirs();
                try(FileOutputStream out=new FileOutputStream(new File(directory,name+".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,out); }
                bitmap.recycle();
            } catch(Exception e) { throw new RuntimeException(e); }
        });
    }
}
