package com.deviceagent.shell;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Headless virtual displays owned by the shell-uid user service, one per task
 * session, so several agents can each work on their own private screen.
 *
 * Apps launched onto it ({@code am start --display N}) render into an
 * {@link ImageReader} instead of the phone screen, so the agent can tap, type,
 * dump the UI tree, and take screenshots there without touching what the user is
 * looking at. Same mechanism as {@code scrcpy --new-display}: shell has
 * {@code ADD_TRUSTED_DISPLAY}, so the display can be trusted, public (anything
 * may launch on it), and keep its own focus (taps here do not steal focus from
 * the real screen).
 */
public final class VirtualDisplayHost {
    /** uid 2000 owns exactly this package; DisplayManagerService checks it. */
    static final String SHELL_PACKAGE = "com.android.shell";
    static final String DISPLAY_NAME = "device-agent";

    // DisplayManager.VIRTUAL_DISPLAY_FLAG_*: most are @hide, the values are stable.
    static final int FLAG_PUBLIC = 1 << 0;
    static final int FLAG_OWN_CONTENT_ONLY = 1 << 3;
    static final int FLAG_SUPPORTS_TOUCH = 1 << 6;
    static final int FLAG_ROTATES_WITH_CONTENT = 1 << 7;
    static final int FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    static final int FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;
    static final int FLAG_TRUSTED = 1 << 10;
    static final int FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    static final int FLAG_ALWAYS_UNLOCKED = 1 << 12;
    static final int FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13;
    static final int FLAG_OWN_FOCUS = 1 << 14;
    static final int FLAG_STEAL_TOP_FOCUS_DISABLED = 1 << 16;

    private static final int DEFAULT_WIDTH = 1080;
    private static final int DEFAULT_HEIGHT = 2400;
    private static final int DEFAULT_DPI = 420;
    private static final int MAX_IMAGES = 3;
    /** New private displays are refused below this much free memory instead of at a fixed count. */
    static final long MIN_AVAILABLE_BYTES = 600L * 1024 * 1024;

    private final Context baseContext;
    private final Object lock = new Object();

    /** PowerManager.WAKE_REASON_APPLICATION (@hide). */
    private static final int WAKE_REASON_APPLICATION = 2;

    private static final long DEFAULT_IDLE_MS = 5L * 60_000L;
    private static final long IDLE_CHECK_MS = 30_000L;

    private PowerManager powerManager;
    private DisplayManager displayManagerRef;
    private String contextPath;
    private HandlerThread idleThread;
    private Handler idleHandler;
    private String lastIdleRelease;
    private final Map<Integer, Slot> slots = new LinkedHashMap<>();

    /** One private display and everything that keeps it rendering. */
    private final class Slot {
        // Never take the display-control lock from the buffer consumer. Display
        // service calls can wait for rendering, which itself needs free buffers.
        final Object frameLock = new Object();
        VirtualDisplay display;
        volatile ImageReader reader;
        HandlerThread thread;
        PowerManager.WakeLock wakeLock;
        Image latest;
        volatile long frames;
        volatile long lastFrameAt;
        long createdAt;
        long lastUsedAt;
        long idleMs;
        int width, height, dpi, flags;
        boolean decorations;
        String sessionId;
        volatile DisplayRecorder recorder;
        volatile Surface liveSurface;
        volatile String liveToken;
        volatile IBinder liveAlive;
        volatile IBinder.DeathRecipient liveDeath;
        final List<String> attempts = new ArrayList<>();
        final Runnable idleCheck = () -> checkIdle(this);
        final Runnable liveGuardCheck = () -> checkLiveGuard(this);

        int id() { return display.getDisplay().getDisplayId(); }
        boolean valid() { return display != null && display.getDisplay() != null && display.getDisplay().isValid(); }

        void detachLiveLocked() {
            if (idleHandler != null) idleHandler.removeCallbacks(liveGuardCheck);
            Surface oldSurface = liveSurface;
            IBinder oldAlive = liveAlive;
            IBinder.DeathRecipient oldDeath = liveDeath;
            liveSurface = null;
            liveToken = null;
            liveAlive = null;
            liveDeath = null;
            if (oldAlive != null && oldDeath != null) {
                try { oldAlive.unlinkToDeath(oldDeath, 0); } catch (RuntimeException ignored) { }
            }
            if (display != null && reader != null) {
                try { display.setSurface(reader.getSurface()); } catch (RuntimeException ignored) { }
            }
            if (oldSurface != null) {
                try { oldSurface.release(); } catch (RuntimeException ignored) { }
            }
        }

        void onFrame(ImageReader r) {
            synchronized (frameLock) {
                if (reader != r) return;
                Image image;
                try {
                    image = r.acquireLatestImage();
                } catch (RuntimeException e) {
                    return;
                }
                if (image == null) return;
                if (latest != null) latest.close();
                latest = image;
                frames++;
                lastFrameAt = System.currentTimeMillis();
            }
        }

        void release() {
            detachLiveLocked();
            DisplayRecorder rec = recorder;
            recorder = null;
            if (rec != null) rec.stopAndWait();
            if (wakeLock != null) {
                try {
                    if (wakeLock.isHeld()) wakeLock.release();
                } catch (RuntimeException ignored) {
                }
                wakeLock = null;
            }
            if (idleHandler != null) idleHandler.removeCallbacks(idleCheck);
            if (display != null) {
                try {
                    display.release();
                } catch (RuntimeException ignored) {
                }
                display = null;
            }
            synchronized (frameLock) {
                if (latest != null) {
                    try {
                        latest.close();
                    } catch (RuntimeException ignored) {
                    }
                    latest = null;
                }
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (RuntimeException ignored) {
                    }
                    reader = null;
                }
            }
            if (thread != null) {
                thread.quitSafely();
                thread = null;
            }
        }
    }

    public VirtualDisplayHost(Context baseContext) {
        this.baseContext = baseContext;
    }

    public Bundle handle(String op, Bundle args) {
        Bundle safeArgs = args == null ? new Bundle() : args;
        try {
            switch (op == null ? "" : op) {
                case "start":
                    return start(safeArgs);
                case "stop":
                    return stop(safeArgs);
                case "status":
                    return status(safeArgs);
                case "screenshot":
                    return screenshot(safeArgs);
                case "record_start":
                    return recordStart(safeArgs);
                case "record_stop":
                    return recordStop(safeArgs);
                case "record_status":
                    return recordStatus(safeArgs);
                case "wake":
                    return wake(safeArgs);
                case "touch":
                    touch(safeArgs);
                    return status(safeArgs);
                case "attach_surface":
                    return attachSurface(safeArgs);
                case "detach_surface":
                    return detachSurface(safeArgs);
                case "probe_power":
                    return probePower();
                default:
                    return error("unknown virtual display op: " + op);
            }
        } catch (Throwable t) {
            return error(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static Integer requestedId(Bundle args) {
        Object value = args.get("displayId");
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String && ((String) value).matches("[0-9]{1,9}")) return Integer.parseInt((String) value);
        return null;
    }

    private Bundle start(Bundle args) throws Exception {
        synchronized (lock) {
            long available = availableMemory();
            if (available >= 0 && available < MIN_AVAILABLE_BYTES) {
                Bundle out = error("The phone is low on memory. Finish another private task or close apps, then try again.");
                out.putString("code", "device_memory_low");
                out.putLong("availableBytes", available);
                return out;
            }
            Context shell = shellContext();
            DisplayManager dm = displayManager(shell);
            displayManagerRef = dm;
            if (powerManager == null) {
                try {
                    powerManager = shell.getSystemService(PowerManager.class);
                } catch (RuntimeException e) {
                    powerManager = null;
                }
            }
            if (idleThread == null) {
                // Idle teardown performs synchronous display-service calls. It must
                // not occupy an ImageReader callback thread while those calls wait.
                idleThread = new HandlerThread("device-agent-vd-idle");
                idleThread.start();
                idleHandler = new Handler(idleThread.getLooper());
            }
            Slot slot = new Slot();
            int[] size = defaultSize(dm);
            slot.width = positive(args.getInt("width", 0), size[0]);
            slot.height = positive(args.getInt("height", 0), size[1]);
            slot.dpi = positive(args.getInt("dpi", 0), size[2]);
            slot.decorations = args.getBoolean("decorations", true);
            slot.sessionId = args.getString("sessionId");
            int idleMinutes = args.getInt("idleMinutes", -1);
            slot.idleMs = idleMinutes < 0 ? DEFAULT_IDLE_MS : idleMinutes * 60_000L;

            slot.reader = ImageReader.newInstance(slot.width, slot.height, PixelFormat.RGBA_8888, MAX_IMAGES);
            slot.thread = new HandlerThread("device-agent-vd-" + (slots.size() + 1));
            slot.thread.start();
            slot.reader.setOnImageAvailableListener(slot::onFrame, new Handler(slot.thread.getLooper()));
            slot.lastUsedAt = System.currentTimeMillis();
            Surface surface = slot.reader.getSurface();

            Exception last = null;
            for (int candidate : flagCandidates(slot.decorations)) {
                try {
                    slot.display = dm.createVirtualDisplay(DISPLAY_NAME, slot.width, slot.height, slot.dpi, surface, candidate);
                    if (slot.display == null) {
                        slot.attempts.add(label(candidate) + " -> null");
                        continue;
                    }
                    slot.flags = candidate;
                    slot.attempts.add(label(candidate) + " -> ok");
                    break;
                } catch (RuntimeException e) {
                    last = e;
                    slot.attempts.add(label(candidate) + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            if (slot.display == null) {
                slot.release();
                Bundle out = error("could not create a virtual display" + (last == null ? "" : ": " + last.getMessage()));
                out.putStringArray("attempts", slot.attempts.toArray(new String[0]));
                return out;
            }
            slot.frames = 0;
            slot.lastFrameAt = 0;
            slot.createdAt = System.currentTimeMillis();
            slots.put(slot.id(), slot);
            if (slot.idleMs > 0) idleHandler.postDelayed(slot.idleCheck, IDLE_CHECK_MS);
            // New display groups start awake, but hold them there and be explicit anyway.
            Bundle woke = wakeLocked(slot);
            Bundle out = statusLocked(slot);
            out.putBoolean("created", true);
            out.putString("wake", woke.getString("after") + " " + java.util.Arrays.toString(woke.getStringArray("steps")));
            return out;
        }
    }

    /** MemAvailable from /proc/meminfo, or -1 when it cannot be read. */
    static long availableMemory() {
        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            for (String line; (line = in.readLine()) != null; ) {
                if (line.startsWith("MemAvailable:")) return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024L;
            }
        } catch (Exception ignored) {
        }
        return -1L;
    }

    /** Lists the power APIs this build exposes, so the wake strategy can be chosen per device. */
    private Bundle probePower() throws Exception {
        Bundle out = new Bundle();
        out.putBoolean("ok", true);
        List<String> pmMethods = new ArrayList<>();
        for (java.lang.reflect.Method m : PowerManager.class.getDeclaredMethods()) {
            String n = m.getName();
            if (n.contains("akeUp") || n.contains("WakeLock") || n.contains("userActivity") || n.contains("goToSleep") || n.contains("Interactive")) {
                pmMethods.add(n + java.util.Arrays.toString(m.getParameterTypes()).replace("class ", "").replace("interface ", ""));
            }
        }
        out.putStringArray("powerManager", pmMethods.toArray(new String[0]));
        List<String> svcMethods = new ArrayList<>();
        Class<?> ipm = Class.forName("android.os.IPowerManager");
        for (java.lang.reflect.Method m : ipm.getMethods()) {
            String n = m.getName();
            if (n.contains("akeUp") || n.contains("userActivity") || n.contains("goToSleep") || n.contains("acquireWakeLock")) {
                svcMethods.add(n + java.util.Arrays.toString(m.getParameterTypes()).replace("class ", "").replace("interface ", ""));
            }
        }
        out.putStringArray("iPowerManager", svcMethods.toArray(new String[0]));
        return out;
    }

    /** Stops one display, or every display when no displayId is given. */
    private Bundle stop(Bundle args) {
        synchronized (lock) {
            Integer id = requestedId(args);
            List<Integer> stopped = new ArrayList<>();
            for (Integer key : new ArrayList<>(slots.keySet())) {
                if (id != null && !id.equals(key)) continue;
                slots.remove(key).release();
                stopped.add(key);
            }
            Bundle out = new Bundle();
            out.putBoolean("ok", true);
            out.putBoolean("active", !slots.isEmpty());
            out.putBoolean("stopped", !stopped.isEmpty());
            if (id != null && !stopped.isEmpty()) out.putInt("displayId", id);
            out.putIntArray("stoppedDisplays", stopped.stream().mapToInt(Integer::intValue).toArray());
            return out;
        }
    }

    /** Any agent use of a display counts as activity for its idle timeout. */
    private void touch(Bundle args) {
        synchronized (lock) {
            Slot slot = slot(args);
            if (slot != null) slot.lastUsedAt = System.currentTimeMillis();
        }
    }

    private Slot slot(Bundle args) {
        Integer id = requestedId(args);
        return id == null ? null : slots.get(id);
    }

    /**
     * Safeguard: a display nobody is using keeps its power group awake (battery)
     * and keeps whatever app was left there running. Release it after
     * {@code idleMinutes} without any agent activity.
     */
    private void checkIdle(Slot slot) {
        synchronized (lock) {
            if (slot.display == null || slot.idleMs <= 0 || idleHandler == null || slots.get(slot.id()) != slot) return;
            long idleFor = System.currentTimeMillis() - slot.lastUsedAt;
            if (idleFor >= slot.idleMs) {
                lastIdleRelease = "released display " + slot.id() + " after "
                        + (idleFor / 60_000L) + " min idle at " + System.currentTimeMillis();
                slots.remove(slot.id());
                slot.release();
                return;
            }
            idleHandler.postDelayed(slot.idleCheck, IDLE_CHECK_MS);
        }
    }

    /** With a displayId, that display's status. Without one, every active display. */
    private Bundle status(Bundle args) {
        synchronized (lock) {
            Integer id = requestedId(args);
            if (id != null) {
                Slot slot = slots.get(id);
                if (slot != null && slot.valid()) return statusLocked(slot);
                Bundle out = new Bundle();
                out.putBoolean("ok", true);
                out.putBoolean("active", false);
                if (lastIdleRelease != null) out.putString("lastIdleRelease", lastIdleRelease);
                return out;
            }
            slots.values().removeIf(slot -> {
                if (slot.valid()) return false;
                slot.release();
                return true;
            });
            Bundle out = new Bundle();
            out.putBoolean("ok", true);
            out.putBoolean("active", !slots.isEmpty());
            out.putInt("count", slots.size());
            out.putLong("availableBytes", availableMemory());
            ArrayList<Bundle> list = new ArrayList<>();
            for (Slot slot : slots.values()) list.add(statusLocked(slot));
            out.putParcelableArrayList("displays", list);
            if (lastIdleRelease != null) out.putString("lastIdleRelease", lastIdleRelease);
            return out;
        }
    }

    private Bundle statusLocked(Slot slot) {
        Bundle out = new Bundle();
        out.putBoolean("ok", true);
        out.putBoolean("active", slot.valid());
        if (lastIdleRelease != null) out.putString("lastIdleRelease", lastIdleRelease);
        if (!slot.valid()) return out;
        out.putLong("idleMs", System.currentTimeMillis() - slot.lastUsedAt);
        out.putLong("idleTimeoutMs", slot.idleMs);
        Display d = slot.display.getDisplay();
        out.putInt("displayId", d.getDisplayId());
        if (slot.sessionId != null) out.putString("sessionId", slot.sessionId);
        out.putString("displayState", stateName(d.getState()));
        out.putString("display0State", defaultDisplayState());
        out.putBoolean("wakeLockHeld", slot.wakeLock != null && slot.wakeLock.isHeld());
        out.putInt("width", slot.width);
        out.putInt("height", slot.height);
        out.putInt("dpi", slot.dpi);
        out.putInt("flags", slot.flags);
        out.putString("flagsLabel", label(slot.flags));
        out.putBoolean("decorations", slot.decorations);
        out.putBoolean("ownFocus", (slot.flags & FLAG_OWN_FOCUS) != 0);
        out.putBoolean("trusted", (slot.flags & FLAG_TRUSTED) != 0);
        out.putBoolean("publicDisplay", (slot.flags & FLAG_PUBLIC) != 0);
        out.putLong("frames", slot.frames);
        out.putLong("lastFrameAgeMs", slot.lastFrameAt == 0 ? -1 : System.currentTimeMillis() - slot.lastFrameAt);
        out.putLong("createdAt", slot.createdAt);
        out.putBoolean("recording", slot.recorder != null && slot.recorder.isRunning());
        out.putString("context", contextPath);
        out.putStringArray("attempts", slot.attempts.toArray(new String[0]));
        return out;
    }

    private Bundle attachSurface(Bundle args) throws Exception {
        Integer id = requestedId(args);
        String authority = args.getString("authority");
        String token = args.getString("token");
        if (id == null || id <= 0) return error("displayId is required");
        if (authority == null || authority.isBlank() || token == null || token.isBlank()) {
            return error("authority and token are required");
        }
        synchronized (lock) {
            Slot slot = slots.get(id);
            if (slot == null || !slot.valid()) return error("no active virtual display with that id");
            if (slot.recorder != null && slot.recorder.isRunning()) {
                return error("live surface unavailable while recording");
            }
        }
        Context shell = shellContext();
        Bundle reply = shell.getContentResolver().call(Uri.parse("content://" + authority), "take", token, null);
        if (reply == null || !reply.getBoolean("ok", false)) {
            return error("could not retrieve live surface");
        }
        Surface surface = extractSurface(reply);
        IBinder alive = reply.getBinder("alive");
        if (surface == null || !surface.isValid()) {
            if (surface != null) {
                try { surface.release(); } catch (RuntimeException ignored) { }
            }
            return error("live surface is invalid");
        }
        synchronized (lock) {
            Slot slot = slots.get(id);
            if (slot == null || !slot.valid() || (slot.recorder != null && slot.recorder.isRunning())) {
                try { surface.release(); } catch (RuntimeException ignored) { }
                return error("virtual display unavailable");
            }
            slot.detachLiveLocked();
            try {
                slot.display.setSurface(surface);
            } catch (RuntimeException e) {
                try { surface.release(); } catch (RuntimeException ignored) { }
                if (slot.reader != null) {
                    try { slot.display.setSurface(slot.reader.getSurface()); } catch (RuntimeException ignored) { }
                }
                return error("setSurface failed: " + e.getMessage());
            }
            slot.liveSurface = surface;
            slot.liveToken = token;
            slot.liveAlive = alive;
            if (alive != null) {
                IBinder.DeathRecipient death = () -> detachSurfaceByToken(id, token);
                try {
                    alive.linkToDeath(death, 0);
                    slot.liveDeath = death;
                } catch (Exception e) {
                    slot.detachLiveLocked();
                    return error("caller died before attach");
                }
            }
            slot.lastUsedAt = System.currentTimeMillis();
            wakeLocked(slot);
            if (idleHandler != null) idleHandler.postDelayed(slot.liveGuardCheck, 350L);
            Bundle out = new Bundle();
            out.putBoolean("ok", true);
            out.putInt("displayId", id);
            out.putInt("width", slot.width);
            out.putInt("height", slot.height);
            return out;
        }
    }

    @SuppressWarnings("deprecation")
    private static Surface extractSurface(Bundle bundle) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return bundle.getParcelable("surface", Surface.class);
        }
        return bundle.getParcelable("surface");
    }

    private Bundle detachSurface(Bundle args) {
        Integer id = requestedId(args);
        String token = args.getString("token");
        synchronized (lock) {
            if (id != null && id > 0) {
                Slot slot = slots.get(id);
                if (slot != null && (token == null || token.isEmpty() || token.equals(slot.liveToken))) {
                    slot.detachLiveLocked();
                }
            } else {
                for (Slot slot : slots.values()) {
                    if (token == null || token.isEmpty() || token.equals(slot.liveToken)) {
                        slot.detachLiveLocked();
                    }
                }
            }
            Bundle out = new Bundle();
            out.putBoolean("ok", true);
            return out;
        }
    }

    private void detachSurfaceByToken(int displayId, String token) {
        synchronized (lock) {
            Slot slot = slots.get(displayId);
            if (slot != null && token != null && token.equals(slot.liveToken)) {
                slot.detachLiveLocked();
            }
        }
    }

    private void checkLiveGuard(Slot slot) {
        int displayId = -1;
        synchronized (lock) {
            if (slot.liveSurface == null || !slot.valid() || slots.get(slot.id()) != slot) return;
            if (slot.recorder != null && slot.recorder.isRunning()) {
                slot.detachLiveLocked();
                return;
            }
            displayId = slot.id();
        }
        String top = SystemBinders.topActivityOnDisplay(displayId);
        int slash = top.indexOf('/');
        boolean blocked = slash > 0 && com.deviceagent.tools.PrivacyFence.isGloballyBlocked(top.substring(0, slash));
        synchronized (lock) {
            if (slot.liveSurface == null || !slot.valid() || slots.get(displayId) != slot) return;
            if (blocked || !slot.liveSurface.isValid()) {
                slot.detachLiveLocked();
                return;
            }
            slot.lastUsedAt = System.currentTimeMillis();
            if (idleHandler != null) idleHandler.postDelayed(slot.liveGuardCheck, 350L);
        }
    }

    private Bundle screenshot(Bundle args) {
        long started=System.nanoTime();
        float scale = args.getFloat("scale", 0.5f);
        if (scale <= 0f || scale > 1f) scale = 0.5f;
        int quality = Math.max(30, Math.min(100, args.getInt("quality", 85)));
        String format = args.getString("format", "webp");
        long sinceFrame = args.getLong("sinceFrame", -1L);
        Bitmap scaled = null;
        int displayId, sourceWidth, sourceHeight;
        long frameAt, frameSeq;
        boolean liveAttached;
        touch(args);
        synchronized (lock) {
            Slot slot = slot(args);
            if (slot == null || slot.display == null) return error("no virtual display with that id; begin a task first");
            displayId = slot.id();
            sourceWidth = slot.width;
            sourceHeight = slot.height;
            liveAttached = slot.liveSurface != null;
            if (!liveAttached) {
                synchronized (slot.frameLock) {
                    if (slot.latest == null) return error("no frame rendered yet; launch an app on the virtual display first");
                    sourceWidth = slot.latest.getWidth();
                    sourceHeight = slot.latest.getHeight();
                    frameAt = slot.lastFrameAt;
                    frameSeq = slot.frames;
                    if (sinceFrame > 0 && frameSeq == sinceFrame) {
                        Bundle unchanged = new Bundle();
                        unchanged.putBoolean("ok", true);
                        unchanged.putBoolean("unchanged", true);
                        unchanged.putInt("displayId", displayId);
                        unchanged.putInt("sourceWidth", sourceWidth);
                        unchanged.putInt("sourceHeight", sourceHeight);
                        unchanged.putLong("frameSeq", frameSeq);
                        unchanged.putLong("frameAt", frameAt);
                        return unchanged;
                    }
                    scaled = toScaledBitmap(slot.latest, scale);
                }
            } else {
                frameAt = System.currentTimeMillis();
                frameSeq = ++slot.frames;
            }
        }
        if (liveAttached) {
            try {
                Bundle raw = LocalShell.run("screencap -d " + displayId + " -p", null, 3000);
                byte[] pngBytes = raw.getByteArray("stdout");
                if (pngBytes == null || pngBytes.length == 0) {
                    return error("could not capture live virtual display");
                }
                Bitmap full = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.length);
                if (full == null) return error("could not decode live virtual display frame");
                sourceWidth = full.getWidth();
                sourceHeight = full.getHeight();
                if (scale >= 1f) {
                    scaled = full;
                } else {
                    int outW = Math.max(1, Math.round(sourceWidth * scale));
                    int outH = Math.max(1, Math.round(sourceHeight * scale));
                    scaled = Bitmap.createScaledBitmap(full, outW, outH, true);
                    if (scaled != full) full.recycle();
                }
            } catch (Exception e) {
                return error("live capture failed: " + e.getMessage());
            }
        }
        long encodingStarted=System.nanoTime();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        String mime;
        if ("jpeg".equalsIgnoreCase(format) && scaled.compress(Bitmap.CompressFormat.JPEG, quality, encoded)) {
            mime = "image/jpeg";
        } else if (android.os.Build.VERSION.SDK_INT >= 30
                && scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, encoded)) {
            mime = "image/webp";
        } else {
            encoded.reset();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, encoded);
            mime = "image/png";
        }
        Bundle out = new Bundle();
        out.putBoolean("ok", true);
        out.putInt("displayId", displayId);
        out.putInt("width", scaled.getWidth());
        out.putInt("height", scaled.getHeight());
        out.putInt("sourceWidth", sourceWidth);
        out.putInt("sourceHeight", sourceHeight);
        out.putFloat("scale", scale);
        out.putLong("frameSeq", frameSeq);
        out.putLong("frameAt",frameAt);
        out.putLong("frameAgeMs",Math.max(0,System.currentTimeMillis()-frameAt));
        out.putString("mime", mime);
        out.putByteArray("png", encoded.toByteArray());
        out.putLong("screenshotCaptureMs",(encodingStarted-started)/1_000_000);
        out.putLong("screenshotEncodingMs",(System.nanoTime()-encodingStarted)/1_000_000);
        scaled.recycle();
        return out;
    }

    private Bundle recordStart(Bundle args) {
        float scale = args.getFloat("scale", 0.5f);
        if (scale < 0.25f || scale > 0.75f) scale = 0.5f;
        int fps = Math.max(1, Math.min(15, args.getInt("fps", 8)));
        int maxSeconds = Math.max(1, Math.min(30, args.getInt("maxSeconds", 10)));
        String path = args.getString("path");
        final float finalScale = scale;
        synchronized (lock) {
            Slot slot = slot(args);
            if (slot == null || !slot.valid()) return error("no virtual display with that id; begin a task first");
            slot.detachLiveLocked();
            if (slot.recorder != null && slot.recorder.isRunning()) {
                return Bundles.fromMap(slot.recorder.statusMap());
            }
            int displayId = slot.id();
            String dest = (path != null && !path.isBlank())
                    ? path
                    : "/data/local/tmp/backstage/recordings/rec-" + displayId + "-" + System.currentTimeMillis() + ".mp4";
            int w = Math.max(128, Math.round(slot.width * finalScale));
            int h = Math.max(128, Math.round(slot.height * finalScale));
            DisplayRecorder rec = new DisplayRecorder(displayId, dest, w, h, fps, maxSeconds, () -> {
                String top = SystemBinders.topActivityOnDisplay(displayId);
                int slash = top.indexOf('/');
                if (slash > 0 && com.deviceagent.tools.PrivacyFence.isGloballyBlocked(top.substring(0, slash))) {
                    return null;
                }
                synchronized (slot.frameLock) {
                    if (slot.latest == null) return null;
                    return toScaledBitmap(slot.latest, finalScale);
                }
            });
            slot.recorder = rec;
            rec.start();
            return Bundles.fromMap(rec.statusMap());
        }
    }

    private Bundle recordStop(Bundle args) {
        DisplayRecorder rec;
        synchronized (lock) {
            Slot slot = slot(args);
            if (slot == null) return error("no virtual display with that id");
            rec = slot.recorder;
        }
        if (rec == null) return error("no recording is active on this display");
        rec.stopAndWait();
        return Bundles.fromMap(rec.statusMap());
    }

    private Bundle recordStatus(Bundle args) {
        DisplayRecorder rec;
        synchronized (lock) {
            Slot slot = slot(args);
            if (slot == null) return error("no virtual display with that id");
            rec = slot.recorder;
        }
        if (rec == null) {
            Bundle out = new Bundle();
            out.putBoolean("ok", true);
            out.putString("state", "idle");
            return out;
        }
        return Bundles.fromMap(rec.statusMap());
    }

    /**
     * A Context whose package name is {@code com.android.shell}. DisplayManagerService
     * rejects createVirtualDisplay when the package does not belong to the calling uid.
     */
    private Context shellContext() throws Exception {
        Context base = baseContext;
        if (base == null) {
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Object thread = activityThread.getMethod("systemMain").invoke(null);
                if (thread != null) {
                    base = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
                    contextPath = "ActivityThread.systemMain";
                }
            } catch (Throwable t) {
                contextPath = "none:" + t.getClass().getSimpleName();
            }
        } else {
            contextPath = "provided";
        }
        if (base == null) {
            throw new IllegalStateException("no Context for virtual display (" + contextPath + ")");
        }
        try {
            Context shell = base.createPackageContext(SHELL_PACKAGE, 0);
            try {
                java.lang.reflect.Field op = shell.getClass().getDeclaredField("mOpPackageName");
                op.setAccessible(true);
                op.set(shell, SHELL_PACKAGE);
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    java.lang.reflect.Field attr = shell.getClass().getDeclaredField("mAttributionSource");
                    attr.setAccessible(true);
                    attr.set(shell, new android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName(SHELL_PACKAGE).build());
                }
            } catch (Throwable ignored) {
            }
            contextPath += "+createPackageContext";
            return shell;
        } catch (Exception e) {
            contextPath += "+wrapper";
            return new ShellContext(base);
        }
    }

    private static DisplayManager displayManager(Context shell) throws Exception {
        if (shell instanceof ShellContext) {
            // getSystemService would bind the manager to the wrapped ContextImpl and
            // report the wrong package; construct it against the wrapper instead.
            Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            return ctor.newInstance(shell);
        }
        DisplayManager dm = shell.getSystemService(DisplayManager.class);
        if (dm == null) throw new IllegalStateException("DisplayManager unavailable");
        return dm;
    }

    private static int[] defaultSize(DisplayManager dm) {
        try {
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d != null) {
                DisplayMetrics metrics = new DisplayMetrics();
                d.getRealMetrics(metrics);
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return new int[]{metrics.widthPixels, metrics.heightPixels,
                            metrics.densityDpi > 0 ? metrics.densityDpi : DEFAULT_DPI};
                }
            }
        } catch (RuntimeException ignored) {
        }
        return new int[]{DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_DPI};
    }

    /**
     * Most capable first. Each step drops the flag most likely to be refused by
     * this OEM build; the last one is a plain private display that at least
     * renders our own launches.
     */
    static List<Integer> flagCandidates(boolean decorations) {
        int base = FLAG_OWN_CONTENT_ONLY | FLAG_SUPPORTS_TOUCH | FLAG_ROTATES_WITH_CONTENT
                | FLAG_DESTROY_CONTENT_ON_REMOVAL | FLAG_TOUCH_FEEDBACK_DISABLED;
        if (decorations) base |= FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;
        int trusted = base | FLAG_PUBLIC | FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP | FLAG_OWN_FOCUS;
        List<Integer> list = new ArrayList<>();
        list.add(trusted | FLAG_ALWAYS_UNLOCKED | FLAG_STEAL_TOP_FOCUS_DISABLED);
        list.add(trusted | FLAG_ALWAYS_UNLOCKED);
        list.add(trusted | FLAG_STEAL_TOP_FOCUS_DISABLED);
        list.add(trusted);
        list.add(base | FLAG_PUBLIC | FLAG_TRUSTED | FLAG_OWN_FOCUS);
        list.add(base | FLAG_PUBLIC | FLAG_TRUSTED);
        list.add(base | FLAG_PUBLIC);
        list.add(base);
        return list;
    }

    static String label(int flags) {
        Map<String, Integer> names = new LinkedHashMap<>();
        names.put("public", FLAG_PUBLIC);
        names.put("ownContent", FLAG_OWN_CONTENT_ONLY);
        names.put("touch", FLAG_SUPPORTS_TOUCH);
        names.put("rotates", FLAG_ROTATES_WITH_CONTENT);
        names.put("destroyOnRemoval", FLAG_DESTROY_CONTENT_ON_REMOVAL);
        names.put("decorations", FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS);
        names.put("trusted", FLAG_TRUSTED);
        names.put("ownGroup", FLAG_OWN_DISPLAY_GROUP);
        names.put("alwaysUnlocked", FLAG_ALWAYS_UNLOCKED);
        names.put("noTouchFeedback", FLAG_TOUCH_FEEDBACK_DISABLED);
        names.put("ownFocus", FLAG_OWN_FOCUS);
        names.put("noStealTopFocus", FLAG_STEAL_TOP_FOCUS_DISABLED);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : names.entrySet()) {
            if ((flags & e.getValue()) == 0) continue;
            if (sb.length() > 0) sb.append('|');
            sb.append(e.getKey());
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /** Single-pass crop + scale from ImageReader buffer directly into target Bitmap. */
    private static Bitmap toScaledBitmap(Image image, float scale) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int w = image.getWidth();
        int h = image.getHeight();
        int paddedWidth = rowStride / pixelStride;
        Bitmap padded = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888);
        buffer.rewind();
        padded.copyPixelsFromBuffer(buffer);
        if (scale >= 1f && paddedWidth == w) return padded;
        int outW = Math.max(1, Math.round(w * scale));
        int outH = Math.max(1, Math.round(h * scale));
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(padded, new android.graphics.Rect(0, 0, w, h), new android.graphics.Rect(0, 0, outW, outH), paint);
        padded.recycle();
        return out;
    }

    static String stateName(int state) {
        switch (state) {
            case Display.STATE_OFF: return "OFF";
            case Display.STATE_ON: return "ON";
            case Display.STATE_DOZE: return "DOZE";
            case Display.STATE_DOZE_SUSPEND: return "DOZE_SUSPEND";
            case Display.STATE_ON_SUSPEND: return "ON_SUSPEND";
            case Display.STATE_VR: return "VR";
            case Display.STATE_UNKNOWN: return "UNKNOWN";
            default: return "state-" + state;
        }
    }

    /**
     * Each virtual display lives in its own power group. Pressing the power
     * button puts every group to sleep, and nothing wakes ours again on its own,
     * so apps on it freeze and frames go black. Shell holds DEVICE_POWER, so we
     * wake exactly that group (never display 0) and then hold a display-bound
     * wake lock so it cannot time out again while the display exists.
     */
    private Bundle wake(Bundle args) {
        touch(args);
        synchronized (lock) {
            Slot slot = slot(args);
            if (slot == null || !slot.valid()) return error("no virtual display with that id; begin a task first");
            return wakeLocked(slot);
        }
    }

    private Bundle wakeLocked(Slot slot) {
        Bundle out = new Bundle();
        Display d = slot.display.getDisplay();
        int id = d.getDisplayId();
        out.putInt("displayId", id);
        out.putString("before", stateName(d.getState()));
        List<String> steps = new ArrayList<>();
        PowerManager pm = powerManager;
        if (pm == null) {
            out.putBoolean("ok", false);
            out.putString("error", "no PowerManager (display created without a shell context)");
            return out;
        }
        try {
            java.lang.reflect.Method wakeUp = PowerManager.class.getMethod("wakeUp", long.class, int.class, String.class, int.class);
            wakeUp.invoke(pm, android.os.SystemClock.uptimeMillis(), WAKE_REASON_APPLICATION, "device-agent:vd", id);
            steps.add("wakeUp(displayId=" + id + ") ok");
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            steps.add("wakeUp failed: " + c.getClass().getSimpleName() + ": " + c.getMessage());
        }
        steps.add(holdWakeLock(slot, pm, id));
        String after = stateName(d.getState());
        for (int i = 0; i < 10 && !"ON".equals(after); i++) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            after = stateName(d.getState());
        }
        out.putString("after", after);
        out.putBoolean("ok", "ON".equals(after));
        if (!"ON".equals(after)) out.putString("error", "virtual display still " + after + " after wake");
        out.putStringArray("steps", steps.toArray(new String[0]));
        out.putString("display0", defaultDisplayState());
        return out;
    }

    private static String holdWakeLock(Slot slot, PowerManager pm, int displayId) {
        if (slot.wakeLock != null && slot.wakeLock.isHeld()) return "wakeLock already held";
        try {
            java.lang.reflect.Method newWakeLock = PowerManager.class.getMethod("newWakeLock", int.class, String.class, int.class);
            PowerManager.WakeLock wl = (PowerManager.WakeLock) newWakeLock.invoke(
                    pm, PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "device-agent:vd", displayId);
            wl.setReferenceCounted(false);
            wl.acquire();
            slot.wakeLock = wl;
            return "display-bound SCREEN_BRIGHT wake lock held";
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            // Never fall back to a plain wake lock: that would keep the user's screen on.
            return "wakeLock unavailable: " + c.getClass().getSimpleName() + ": " + c.getMessage();
        }
    }

    private String defaultDisplayState() {
        try {
            DisplayManager dm = displayManagerRef;
            Display d0 = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            return d0 == null ? "unknown" : stateName(d0.getState());
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static Bundle error(String message) {
        Bundle out = new Bundle();
        out.putBoolean("ok", false);
        out.putString("error", message);
        return out;
    }

    /** Fallback when createPackageContext is refused: lie about the package only. */
    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) {
            super(base);
        }

        @Override
        public String getPackageName() {
            return SHELL_PACKAGE;
        }

        @Override
        public String getOpPackageName() {
            return SHELL_PACKAGE;
        }

        @Override
        public String getAttributionTag() {
            return null;
        }
    }
}
