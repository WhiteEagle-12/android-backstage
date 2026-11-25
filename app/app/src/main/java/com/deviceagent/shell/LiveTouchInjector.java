package com.deviceagent.shell;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/**
 * Low-latency continuous multi-touch and key injector for live screen control.
 * Caches InputManager reflection handles and pre-allocates pointer arrays so 120Hz touch streams
 * inject asynchronously with near-zero latency and zero GC churn.
 */
@android.annotation.SuppressLint({"SoonBlockedPrivateApi", "PrivateApi"})
public final class LiveTouchInjector {
    private static final int MAX_POINTERS = 10;
    private static final int INJECT_ASYNC = 0;

    private static volatile Object inputManager;
    private static volatile Method injectMethod;
    private static volatile Method setDisplayMethod;

    private static void ensureInit() throws Exception {
        if (inputManager != null && injectMethod != null && setDisplayMethod != null) return;
        Class<?> cls = Class.forName("android.hardware.input.InputManager");
        Object mgr = cls.getMethod("getInstance").invoke(null);
        Method inj = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
        Method setDisp = InputEvent.class.getMethod("setDisplayId", int.class);
        inputManager = mgr;
        injectMethod = inj;
        setDisplayMethod = setDisp;
    }

    private final MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[MAX_POINTERS];
    private final MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[MAX_POINTERS];
    private final MotionEvent.PointerProperties[][] propsViews = new MotionEvent.PointerProperties[MAX_POINTERS + 1][];
    private final MotionEvent.PointerCoords[][] coordsViews = new MotionEvent.PointerCoords[MAX_POINTERS + 1][];

    private long downTime;
    private long lastEventTime;
    private int lastCount;
    private boolean pointersDown;

    public LiveTouchInjector() {
        for (int i = 0; i < MAX_POINTERS; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
        for (int n = 1; n <= MAX_POINTERS; n++) {
            propsViews[n] = new MotionEvent.PointerProperties[n];
            coordsViews[n] = new MotionEvent.PointerCoords[n];
            System.arraycopy(props, 0, propsViews[n], 0, n);
            System.arraycopy(coords, 0, coordsViews[n], 0, n);
        }
    }

    public boolean injectTouch(int displayId, int action, int relTimeMs, int count, int[] ids, float[] xs, float[] ys) {
        if (displayId <= 0 || count < 1 || count > MAX_POINTERS) return false;
        try {
            ensureInit();
            int masked = action & MotionEvent.ACTION_MASK;
            long now = SystemClock.uptimeMillis();
            if (masked == MotionEvent.ACTION_DOWN || !pointersDown) {
                downTime = now;
                lastEventTime = now;
            }
            long eventTime = Math.max(lastEventTime, downTime + Math.max(0, relTimeMs));
            // Keep eventTime close to real monotonic uptime if the client paused briefly.
            if (eventTime < now - 80L || eventTime > now + 80L) eventTime = Math.max(lastEventTime, now);
            lastEventTime = eventTime;

            for (int i = 0; i < count; i++) {
                props[i].id = ids[i];
                props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coords[i].x = xs[i];
                coords[i].y = ys[i];
                coords[i].pressure = (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) ? 0f : 1f;
                coords[i].size = 1f;
            }
            lastCount = count;
            if (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_POINTER_DOWN || masked == MotionEvent.ACTION_MOVE) {
                pointersDown = true;
            } else if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
                pointersDown = false;
            }

            MotionEvent ev = MotionEvent.obtain(
                    downTime, eventTime, action,
                    count, propsViews[count], coordsViews[count],
                    0, 0, 1f, 1f, 0, 0,
                    InputDevice.SOURCE_TOUCHSCREEN, 0);
            try {
                setDisplayMethod.invoke(ev, displayId);
                return Boolean.TRUE.equals(injectMethod.invoke(inputManager, ev, INJECT_ASYNC));
            } finally {
                ev.recycle();
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void cancelIfDown(int displayId) {
        if (!pointersDown || displayId <= 0 || lastCount < 1 || lastCount > MAX_POINTERS) return;
        pointersDown = false;
        try {
            ensureInit();
            long now = Math.max(lastEventTime, SystemClock.uptimeMillis());
            MotionEvent ev = MotionEvent.obtain(
                    downTime, now, MotionEvent.ACTION_CANCEL,
                    lastCount, propsViews[lastCount], coordsViews[lastCount],
                    0, 0, 1f, 1f, 0, 0,
                    InputDevice.SOURCE_TOUCHSCREEN, 0);
            try {
                setDisplayMethod.invoke(ev, displayId);
                injectMethod.invoke(inputManager, ev, INJECT_ASYNC);
            } finally {
                ev.recycle();
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean injectKey(int displayId, int keyCode) {
        if (displayId <= 0 || !isAllowedKey(keyCode)) return false;
        try {
            ensureInit();
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
            setDisplayMethod.invoke(down, displayId);
            injectMethod.invoke(inputManager, down, INJECT_ASYNC);
            KeyEvent up = new KeyEvent(now, now + 8L, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
            setDisplayMethod.invoke(up, displayId);
            return Boolean.TRUE.equals(injectMethod.invoke(inputManager, up, INJECT_ASYNC));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isAllowedKey(int code) {
        return code == KeyEvent.KEYCODE_BACK
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_DEL
                || code == KeyEvent.KEYCODE_FORWARD_DEL
                || code == KeyEvent.KEYCODE_TAB
                || code == KeyEvent.KEYCODE_ESCAPE
                || code == KeyEvent.KEYCODE_SPACE
                || code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER;
    }
}
