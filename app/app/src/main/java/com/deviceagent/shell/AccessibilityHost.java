package com.deviceagent.shell;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.deviceagent.tools.Json;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UI tree straight from the accessibility layer, in the shell-uid process.
 *
 * {@code uiautomator dump} does the same thing, but first calls
 * {@code waitForIdle(1000, 10000)}, which never returns early while the user is
 * scrolling on the real screen: every dump took ~11s. This connects a
 * {@link UiAutomation} (shell may do that), reads the windows of the requested
 * display immediately, and disconnects when the bounded tool call ends, so the slot stays free for anything
 * else. FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES keeps his own accessibility
 * services running while we look.
 */
// This host executes only in the shell-UID app_process helper, which has hidden-API access.
@android.annotation.SuppressLint("SoonBlockedPrivateApi")
public final class AccessibilityHost {
    private static final int MAX_NODES = 1500;
    private static final int MAX_DEPTH = 60;
    /** UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES (public constant, connect(int) is @hide). */
    private static final int FLAG_DONT_SUPPRESS = 1;
    private static final int SETTLE_ATTEMPTS = 12;
    private static final long SETTLE_MS = 100L;
    private static final long IDLE_DISCONNECT_MS = 60_000L;
    private static final long PID_CACHE_MS = 10_000L;
    private static final long FAST_TARGET_MS = 500L;
    /** One read holds the shared lock, so a stuck app must not stall every other screen. */
    private static final long READ_BUDGET_MS = 4_000L;
    private static final long SLOW_READ_LOG_MS = 2_000L;
    private long readDeadline = Long.MAX_VALUE;
    private static final class SlowRead extends RuntimeException { SlowRead() { super(null, null, false, false); } }

    private static final Method GET_SOURCE_NODE_ID = findSourceNodeIdMethod();
    private static Method findSourceNodeIdMethod() {
        try {
            Method m = AccessibilityNodeInfo.class.getDeclaredMethod("getSourceNodeId");
            m.setAccessible(true);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /** Shared by all displays. Waits are bounded so one stuck screen read cannot hang every display. */
    private static final class UiLock extends java.util.concurrent.locks.ReentrantLock {
        Thread holder(){return getOwner();}
    }
    private final UiLock lock = new UiLock();
    private static final long UI_LOCK_MAX_WAIT_MS = 30_000;
    private void acquire() throws InterruptedException {
        long wait=Math.min(com.deviceagent.tools.ActionDeadline.remaining(),UI_LOCK_MAX_WAIT_MS);
        if(lock.tryLock(wait,java.util.concurrent.TimeUnit.MILLISECONDS))return;
        Thread holder=lock.holder();
        if(holder!=null){
            StringBuilder trace=new StringBuilder("UI lock busy for ").append(wait).append(" ms; held by ").append(holder.getName());
            var frames=holder.getStackTrace();for(int i=0;i<Math.min(frames.length,16);i++)trace.append("\n  at ").append(frames[i]);
            com.deviceagent.daemon.DaemonLog.print(trace.toString());
        }
        throw new com.deviceagent.tools.InputNotSentException("ui_busy","Another screen read on the phone is still running. No input was sent. Try again in a few seconds.");
    }
    private HandlerThread thread;
    private android.os.Handler idleHandler;
    private UiAutomation pooledAutomation;
    private int scopeDepth;
    private long lastTreeAt;
    private int lastTreeDisplay = -1;
    private String lastTreeWindowId;
    private Map<String, Object> lastTreeMap;
    private final Map<String, String> pidCache = new java.util.HashMap<>();
    private long pidCacheAt;
    private final Runnable idleDisconnect = this::disconnectIfIdle;

    public com.deviceagent.transport.DeviceExecutor.AccessibilityScope openScope() {
        if(android.os.Build.VERSION.SDK_INT<34)return () -> {};
        try{acquire();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new com.deviceagent.tools.InputNotSentException("interrupted","The request was interrupted before it ran.");}
        scopeDepth++;
        return () -> {
            try {
                if(--scopeDepth==0){
                    liveNodes.clear();
                    lastTreeMap=null;lastTreeDisplay=-1;lastTreeWindowId=null;lastTreeAt=0;
                    scheduleIdleDisconnectLocked();
                }
            } finally {lock.unlock();}
        };
    }

    private void scheduleIdleDisconnectLocked() {
        if (pooledAutomation == null) return;
        if (idleHandler != null) {
            idleHandler.removeCallbacks(idleDisconnect);
            idleHandler.postDelayed(idleDisconnect, IDLE_DISCONNECT_MS);
        }
    }

    private void disconnectIfIdle() {
        if (!lock.tryLock()) return;
        try {
            if (scopeDepth == 0 && pooledAutomation != null) {
                UiAutomation release = pooledAutomation;
                pooledAutomation = null;
                liveNodes.clear();
                lastTreeMap = null;
                disconnectRaw(release);
            }
        } finally {
            lock.unlock();
        }
    }
    private final Map<String,AccessibilityNodeInfo> liveNodes=new LinkedHashMap<>();
    private final Map<String,String> fieldHints=new LinkedHashMap<>();
    private String fieldValue(AccessibilityNodeInfo node,String windowKey){
        String key=windowKey+":"+sourceId(node),hint=str(node.getHintText());
        if(node.isShowingHintText()&&!hint.isEmpty())fieldHints.put(key,hint);
        while(fieldHints.size()>512)fieldHints.remove(fieldHints.keySet().iterator().next());
        return com.deviceagent.tools.FieldText.value(str(node.getText()),node.isShowingHintText(),hint,str(node.getContentDescription()),fieldHints.getOrDefault(key,""));
    }

    public Map<String, Object> handleMap(String op, Map<String, Object> args) {
        Map<String, Object> safe = args == null ? Map.of() : args;
        if ("tree".equals(op)) {
            int displayId = safe.get("display") instanceof Number n ? n.intValue() : -1;
            boolean allNodes = Boolean.TRUE.equals(safe.get("all"));
            String windowId = safe.get("windowId") instanceof String s ? s : null;
            try {
                return treeMap(displayId, allNodes, windowId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Json.mapOf("ok", false, "code", "interrupted", "error", "UI read interrupted");
            } catch (Throwable t) {
                return Json.mapOf("ok", false, "error", t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        return Bundles.toMap(handle(op, Bundles.fromMap(safe)));
    }

    public Bundle handle(String op, Bundle args) {
        Bundle safe = args == null ? new Bundle() : args;
        try {
            switch (op == null ? "" : op) {
                case "tree":
                    return tree(safe.getInt("display", -1), safe.getBoolean("all", false),safe.getString("windowId"));
                case "wait":
                    return waitFor(safe);
                case "target":
                    return tapTarget(safe);
                case "probe":
                    return probe();
                default:
                    return error("unknown accessibility op: " + op);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Bundle out=error("UI read interrupted"); out.putString("code","interrupted"); return out;
        } catch (Throwable t) {
            return error(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static Bundle textFailure(String code,String message,boolean unknown,boolean accepted) {
        Bundle out=error(message);out.putString("code",code);
        if(accepted)out.putBoolean("inputDelivered",true);
        else if(unknown)out.putString("inputDelivered",null);
        else out.putBoolean("inputDelivered",false);
        out.putString("outcome",unknown||accepted?"unconfirmed":"input_not_sent");
        return out;
    }

    private Bundle probe() throws Exception {
        long start = System.currentTimeMillis();
        acquire();
        try {
            UiAutomation automation = connect();
            try {
                SparseArray<List<AccessibilityWindowInfo>> all = automation.getWindowsOnAllDisplays();
                Bundle out = new Bundle();
                out.putBoolean("ok", true);
                int[] displays = new int[all.size()];
                for (int i = 0; i < all.size(); i++) displays[i] = all.keyAt(i);
                out.putIntArray("displays", displays);
                out.putLong("ms", System.currentTimeMillis() - start);
                return out;
            } finally {
                disconnect(automation);
            }
        } finally { lock.unlock(); }
    }

    private Bundle tree(int displayId, boolean allNodes, String windowId) throws Exception {
        Map<String, Object> map = treeMap(displayId, allNodes, windowId);
        Bundle out = new Bundle();
        boolean ok = Boolean.TRUE.equals(map.get("ok"));
        out.putBoolean("ok", ok);
        if (!ok) {
            if (map.get("error") instanceof String e) out.putString("error", e);
            if (map.get("displays") instanceof int[] d) out.putIntArray("displays", d);
            return out;
        }
        out.putInt("display", displayId);
        out.putInt("count", ((Number) map.get("count")).intValue());
        out.putBoolean("truncated", Boolean.TRUE.equals(map.get("truncated")));
        out.putInt("settleRounds", ((Number) map.get("settleRounds")).intValue());
        out.putString("nodesJson", Json.stringify(map.get("nodes")));
        out.putString("windowsJson", Json.stringify(map.get("windows")));
        out.putLong("ms", ((Number) map.get("ms")).longValue());
        return out;
    }

    private Map<String, Object> treeMap(int displayId, boolean allNodes, String windowId) throws Exception {
        acquire();
        try {
            UiAutomation automation = connect();
            try {
                return readTreeMap(automation, displayId, allNodes, SETTLE_ATTEMPTS, windowId);
            } catch (Throwable t) {
                invalidatePooledLocked(automation);
                throw t;
            } finally {
                disconnect(automation);
            }
        } finally { lock.unlock(); }
    }

    private String pidForPackageLocked(String pkg, Map<String, String> processIds) {
        String cached = processIds.get(pkg);
        if (cached != null) return cached;
        if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) {
            processIds.put(pkg, "");
            return "";
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - pidCacheAt > PID_CACHE_MS || pidCache.isEmpty()) {
            Map<String, String> fresh = SystemBinders.runningAppPids();
            if (!fresh.isEmpty()) {
                pidCache.clear();
                pidCache.putAll(fresh);
                pidCacheAt = now;
            } else if (now - pidCacheAt > PID_CACHE_MS) {
                pidCache.clear();
                pidCacheAt = now;
            }
        }
        String pid = pidCache.get(pkg);
        if (pid == null) {
            // Single fast Binder refresh on miss (e.g., app just launched), then pidof fallback.
            Map<String, String> fresh = SystemBinders.runningAppPids();
            if (!fresh.isEmpty()) {
                pidCache.clear();
                pidCache.putAll(fresh);
                pidCacheAt = now;
                pid = pidCache.get(pkg);
            }
            if (pid == null) {
                Bundle process = LocalShell.run("pidof " + com.deviceagent.protocol.ShellCommand.quote(pkg), null, 1000);
                byte[] bytes = process.getByteArray("stdout");
                pid = bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!pid.isEmpty()) pidCache.put(pkg, pid);
            }
        }
        processIds.put(pkg, pid);
        return pid;
    }

    private Bundle readTree(UiAutomation automation, int displayId, boolean allNodes, int attempts) throws Exception {
        return readTree(automation, displayId, allNodes, attempts, null);
    }

    private Bundle readTree(UiAutomation automation, int displayId, boolean allNodes, int attempts, String windowId) throws Exception {
        Map<String, Object> map = readTreeMap(automation, displayId, allNodes, attempts, windowId);
        Bundle out = new Bundle();
        boolean ok = Boolean.TRUE.equals(map.get("ok"));
        out.putBoolean("ok", ok);
        if (!ok) {
            if (map.get("error") instanceof String e) out.putString("error", e);
            if (map.get("displays") instanceof int[] d) out.putIntArray("displays", d);
            return out;
        }
        out.putInt("display", displayId);
        out.putInt("count", ((Number) map.get("count")).intValue());
        out.putBoolean("truncated", Boolean.TRUE.equals(map.get("truncated")));
        out.putInt("settleRounds", ((Number) map.get("settleRounds")).intValue());
        out.putString("nodesJson", Json.stringify(map.get("nodes")));
        out.putString("windowsJson", Json.stringify(map.get("windows")));
        out.putLong("ms", ((Number) map.get("ms")).longValue());
        return out;
    }

    private Map<String, Object> readTreeMap(UiAutomation automation, int displayId, boolean allNodes, int attempts, String windowId) throws Exception {
        long start = System.currentTimeMillis();
        readDeadline = android.os.SystemClock.uptimeMillis() + READ_BUDGET_MS;
        try {
            Map<String, Object> out = readTreeMapWithin(automation, displayId, allNodes, attempts, windowId, start);
            long ms = System.currentTimeMillis() - start;
            if (ms > SLOW_READ_LOG_MS) System.err.println(java.time.Instant.now() + " [backstage] slow UI read display=" + displayId + " ms=" + ms + " ok=" + out.get("ok") + " nodes=" + out.get("count") + " settle=" + out.get("settleRounds"));
            return out;
        } catch (SlowRead e) {
            liveNodes.clear();
            System.err.println(java.time.Instant.now() + " [backstage] UI read stopped display=" + displayId + " ms=" + (System.currentTimeMillis() - start));
            return Json.mapOf("ok", false, "code", "ui_slow", "error", "the app on this screen did not answer within 4 seconds");
        } finally {
            readDeadline = Long.MAX_VALUE;
        }
    }

    private Map<String, Object> readTreeMapWithin(UiAutomation automation, int displayId, boolean allNodes, int attempts, String windowId, long start) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> windowRows = new ArrayList<>();
        int[] counter = new int[]{0};
        Map<String, String> processIds = new LinkedHashMap<>();
        int settleRounds = 0; boolean focusedAppRoot = false;
        // Window tracking switches on with the service-info flags and takes a
        // moment to expose roots, especially for a secondary display. Poll
        // briefly instead of uiautomator's 1-10s idle wait.
        for (int attempt = 0; attempt < attempts && (attempt == 0 || System.currentTimeMillis() - start < 1500); attempt++) {
            if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache();
            SparseArray<List<AccessibilityWindowInfo>> all = automation.getWindowsOnAllDisplays();
            List<AccessibilityWindowInfo> windows = all.get(displayId);
            if (windows == null || windows.isEmpty()) {
                if (attempt + 1 < attempts) {
                    Thread.sleep(SETTLE_MS);
                    settleRounds++;
                    continue;
                }
                int[] displays = new int[all.size()];
                for (int i = 0; i < all.size(); i++) displays[i] = all.keyAt(i);
                return Json.mapOf("ok", false, "error", "display " + displayId + " has no accessibility windows", "displays", displays);
            }
            rows.clear();
            liveNodes.clear();
            windowRows.clear();
            counter[0] = 0;
            focusedAppRoot = false; int selectedCount = 0;
            for (AccessibilityWindowInfo window : windows) {
                Map<String, Object> w = new LinkedHashMap<>();
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                CharSequence title = window.getTitle();
                w.put("id", window.getId());
                w.put("title", title == null ? "" : title.toString());
                w.put("type", windowType(window.getType()));
                w.put("layer", window.getLayer());
                w.put("focused", window.isFocused());
                w.put("active", window.isActive());
                w.put("bounds", rect(bounds));
                // Android can reassign numeric window IDs on every connection. Read
                // each root's identity, but traverse only the selected window's controls.
                com.deviceagent.tools.ActionDeadline.check();
                if (android.os.SystemClock.uptimeMillis() > readDeadline) throw new SlowRead();
                boolean selected = windowId == null && window.isFocused();
                AccessibilityNodeInfo root = root(window, selected);
                if (root != null) {
                    String pkg = str(root.getPackageName());
                    String pid = pidForPackageLocked(pkg, processIds);
                    AccessibilityNodeInfo firstChild = root.getChildCount() > 0 ? child(root, 0, selected) : null;
                    String key = pkg + ":" + pid + ":" + sourceId(root) + ":" + (firstChild == null ? "" : sourceId(firstChild));
                    if (!pid.isEmpty()) w.put("key", key);
                    w.put("package", pkg);
                    if (!pid.isEmpty()) w.put("windowId", com.deviceagent.tools.Observations.windowId(displayId, window.getType(), key));
                    if (windowId == null ? window.isFocused() : windowId.equals(w.get("windowId"))) {
                        if (!selected && windowId != null) {
                            // Re-fetch selected non-focused window root with prefetching enabled.
                            AccessibilityNodeInfo prefetched = root(window, true);
                            if (prefetched != null) root = prefetched;
                        }
                        focusedAppRoot = true; selectedCount++;
                        walk(root, 0, allNodes, rows, counter, window.getId(), -1, !pid.isEmpty() ? key : "");
                    }
                } else {
                    w.put("root", "unavailable");
                }
                windowRows.add(w);
            }
            if (selectedCount > 1) return Json.mapOf("ok", false, "error", "Several windows share this handle. Use a screenshot to choose the visible control.");
            if (focusedAppRoot || attempt + 1 == attempts) break;
            Thread.sleep(SETTLE_MS);
            settleRounds++;
        }
        if (!focusedAppRoot) {
            if (Boolean.getBoolean("backstage.traceWindows")) System.err.println("WINDOW READ requested=" + windowId + " windows=" + Json.stringify(windowRows));
            return Json.mapOf("ok", false, "error", "The requested window could not be read on display " + displayId + ". Observe the display and choose a current window.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("display", displayId);
        out.put("count", rows.size());
        out.put("truncated", counter[0] >= MAX_NODES);
        out.put("settleRounds", settleRounds);
        out.put("nodes", rows);
        out.put("windows", windowRows);
        out.put("ms", System.currentTimeMillis() - start);
        if (allNodes) {
            lastTreeDisplay = displayId;
            lastTreeWindowId = windowId;
            lastTreeAt = android.os.SystemClock.elapsedRealtime();
            lastTreeMap = Json.mapOf("nodes", rows, "windows", windowRows);
            if (windowId != null) lastTreeMap.put("windowId", windowId);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Bundle waitFor(Bundle args) throws Exception {
        Map<String,Object> predicates = Bundles.toMap(args);
        com.deviceagent.tools.UiPredicate.validate(predicates);
        int display = args.getInt("display", -1);
        if (display < 0) throw new IllegalArgumentException("A task display is required");
        int timeout = com.deviceagent.tools.GesturePlan.integer(predicates,"timeoutMs",5000,0,20000);
        long start = android.os.SystemClock.elapsedRealtime(), deadline = start + timeout;
        if (!lock.tryLock(Math.max(0,deadline-android.os.SystemClock.elapsedRealtime()),java.util.concurrent.TimeUnit.MILLISECONDS)) {
            Bundle out=error("Another UI read is in progress. Try again when it finishes."); out.putString("code","ui_busy"); return out;
        }
        try {
            UiAutomation automation = connect();
            try {
                int checks = 0;
                do {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    Bundle snapshot = readTree(automation,display,true,timeout==0?SETTLE_ATTEMPTS:1);
                    checks++;
                    String focused = "";
                    if (predicates.containsKey("activity")) {
                        long left = Math.max(1,deadline-android.os.SystemClock.elapsedRealtime());
                        Bundle reply = LocalShell.run(com.deviceagent.protocol.ShellCommand.focusOnDisplay(display),null,timeout==0?1000:Math.min(1000,left));
                        byte[] raw = reply.getByteArray("stdout");
                        focused = com.deviceagent.tools.TaskSnapshot.focusedComponent(raw == null ? "" : new String(raw,java.nio.charset.StandardCharsets.UTF_8));
                    }
                    boolean matched = snapshot.getBoolean("ok") && com.deviceagent.tools.UiPredicate.matches(predicates,
                            (List<Map<String,Object>>)(List<?>)Json.parse(snapshot.getString("nodesJson","[]")),
                            (List<Map<String,Object>>)(List<?>)Json.parse(snapshot.getString("windowsJson","[]")),focused);
                    long elapsed = android.os.SystemClock.elapsedRealtime()-start;
                    if (matched && (timeout==0 || elapsed<=timeout)) {
                        Bundle out=new Bundle(); out.putBoolean("ok",true); out.putBoolean("matched",true); out.putInt("display",display); out.putInt("checks",checks); out.putLong("elapsedMs",elapsed); return out;
                    }
                    if (timeout==0 || android.os.SystemClock.elapsedRealtime()>=deadline) {
                        Bundle out=error(timeout==0?"The UI does not match the assertion":"Timed out waiting for the UI");
                        out.putString("code",!snapshot.getBoolean("ok")?"ui_unavailable":timeout==0?"assertion_failed":"wait_timeout"); out.putBoolean("matched",false); out.putInt("display",display); out.putInt("checks",checks); out.putLong("elapsedMs",elapsed);
                        if (!snapshot.getBoolean("ok")) { out.putString("error","The UI could not be read. Check that the app is open on this virtual display."); out.putString("lastReadError",snapshot.getString("error")); }
                        return out;
                    }
                    Thread.sleep(Math.min(150,Math.max(1,deadline-android.os.SystemClock.elapsedRealtime())));
                } while (true);
            } finally { disconnect(automation); }
        } finally { lock.unlock(); }
    }

    private void walk(AccessibilityNodeInfo node, int depth, boolean allNodes,
                      List<Map<String, Object>> rows, int[] counter, int windowId, int parent, String windowKey) {
        com.deviceagent.tools.ActionDeadline.check();
        if (android.os.SystemClock.uptimeMillis() > readDeadline) throw new SlowRead();
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) return;
        counter[0]++;
        String text = str(node.getText());
        String desc = str(node.getContentDescription());
        String resourceId = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName();
        boolean clickable = node.isClickable();
        int ownIndex=parent;
        if (allNodes || !text.isEmpty() || !desc.isEmpty() || !resourceId.isEmpty() || clickable) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            Map<String, Object> row = new LinkedHashMap<>();
            ownIndex=rows.size();
            row.put("index", ownIndex);
            row.put("parent", parent);
            if(!windowKey.isEmpty())row.put("windowKey",windowKey);
            String nodeId=sourceId(node);
            row.put("nodeId",nodeId);row.put("longClickable",node.isLongClickable());
            if(!nodeId.isEmpty())liveNodes.put(windowId+":"+nodeId,node);
            String cls = str(node.getClassName());
            int dot = cls.lastIndexOf('.');
            row.put("class", dot < 0 ? cls : cls.substring(dot + 1));
            row.put("package", str(node.getPackageName()));
            row.put("resourceId", resourceId);
            row.put("text", text);
            row.put("contentDescription", desc);
            row.put("clickable", clickable);
            row.put("enabled", node.isEnabled());
            row.put("visible",node.isVisibleToUser());
            row.put("bounds", rect(bounds));
            row.put("centerX", bounds.centerX());
            row.put("centerY", bounds.centerY());
            if (node.isSelected()) row.put("selected", true);
            if (node.getPaneTitle()!=null) row.put("paneTitle",str(node.getPaneTitle()));
            String role=node.getExtras().getString("AccessibilityNodeInfo.roleDescription", "");
            if(!role.isEmpty()) row.put("roleDescription",role);
            if (node.isFocused()) row.put("focused", true);
            if (node.isScrollable()) row.put("scrollable", true);
            if (node.isCheckable()) row.put("checked", node.isChecked());
            String stateDescription=str(node.getStateDescription());
            if (!stateDescription.isEmpty()) row.put("stateDescription",stateDescription);
            AccessibilityNodeInfo labeledBy=node.getLabeledBy();
            if (labeledBy!=null) {
                row.put("labeledBy",sourceId(labeledBy));
                String label=str(labeledBy.getText());
                if (label.isEmpty()) label=str(labeledBy.getContentDescription());
                if (!label.isEmpty()) row.put("accessibilityLabel",label);
            }
            row.put("actions",node.getActionList().stream().map(AccessibilityNodeInfo.AccessibilityAction::getId).collect(java.util.stream.Collectors.toList()));
            AccessibilityNodeInfo.RangeInfo range=node.getRangeInfo();
            if (range!=null&&node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS))
                row.put("range",Json.mapOf("min",(double)range.getMin(),"max",(double)range.getMax(),"current",(double)range.getCurrent()));
            if (node.isEditable()) {
                row.put("editable",true);
                row.put("inputType",node.getInputType());
                if(!node.isPassword())row.put("fieldValue",fieldValue(node,windowKey));
                String known=fieldHints.get(windowKey+":"+nodeId);
                if(known!=null)row.put("fieldLabel",known);
            }
            if (node.isShowingHintText()) row.put("showingHintText",true);
            String tooltip=str(node.getTooltipText());if(!tooltip.isEmpty())row.put("tooltip",tooltip);
            if (node.isPassword()) row.put("password", true);
            if (node.isLongClickable()) row.put("longClickable", true);
            String hint = str(node.getHintText());
            if (!hint.isEmpty()) row.put("hint", hint);
            row.put("window", windowId);
            rows.add(row);
        }
        int children = node.getChildCount();
        for (int i = 0; i < children && counter[0] < MAX_NODES; i++) {
            AccessibilityNodeInfo child;
            try {
                child = child(node,i);
            } catch (RuntimeException e) {
                continue;
            }
            if (child == null) continue;
            walk(child, depth + 1, allNodes, rows, counter, windowId, ownIndex, windowKey);
        }
    }

    private static AccessibilityNodeInfo root(AccessibilityWindowInfo window, boolean prefetch){
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return prefetch
                    ? window.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
                    : window.getRoot(0);
        }
        return window.getRoot();
    }
    private static AccessibilityNodeInfo child(AccessibilityNodeInfo node,int index){
        return child(node, index, true);
    }
    private static AccessibilityNodeInfo child(AccessibilityNodeInfo node,int index, boolean prefetch){
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return prefetch
                    ? node.getChild(index, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
                    : node.getChild(index, 0);
        }
        return node.getChild(index);
    }
    private static String sourceId(AccessibilityNodeInfo node) {
        try {return GET_SOURCE_NODE_ID == null ? "" : String.valueOf(GET_SOURCE_NODE_ID.invoke(node));}
        catch(Exception e){return "";}
    }

    @SuppressWarnings("unchecked") private Bundle tapTarget(Bundle args) {
        boolean locked=false,dispatched=false;UiAutomation automation=null;
        try {
            int display=args.getInt("display",-1);
            if(display<0)throw new com.deviceagent.tools.InputNotSentException("invalid_arguments","A task display is required");
            acquire();locked=true;automation=connect();
            String selectedWindow=args.getString("windowId");
            Map<String,Object> reference=Json.mapOf("nodeId",args.getString("nodeId"),"windowKey",args.getString("windowKey"),"identity",args.getString("identity"),"targetSignature",args.getString("targetSignature"),"operation",args.getString("operation"));
            Map<String,Object> tree=null,match=null;
            AccessibilityNodeInfo target=null;
            // Fast path: reuse the live node from the just-completed readTree() inside the same
            // display turn, verifying live state via target.refresh() before sending input.
            if (lastTreeMap != null
                    && lastTreeDisplay == display
                    && java.util.Objects.equals(lastTreeWindowId, selectedWindow)
                    && android.os.SystemClock.elapsedRealtime() - lastTreeAt <= FAST_TARGET_MS) {
                try {
                    Map<String, Object> cachedMatch = com.deviceagent.tools.Observations.validateTarget(lastTreeMap, reference);
                    AccessibilityNodeInfo cachedTarget = liveNodes.get(cachedMatch.get("window") + ":" + cachedMatch.get("nodeId"));
                    if (cachedTarget != null && cachedTarget.refresh() && cachedTarget.isEnabled() && cachedTarget.isVisibleToUser()) {
                        tree = lastTreeMap;
                        match = cachedMatch;
                        target = cachedTarget;
                    }
                } catch (RuntimeException ignored) {
                    // Fall through to full tree refresh.
                }
            }
            if (target == null) {
                Map<String, Object> read = readTreeMap(automation, display, true, SETTLE_ATTEMPTS, selectedWindow);
                if (!Boolean.TRUE.equals(read.get("ok"))) throw new com.deviceagent.tools.InputNotSentException("observation_failed", "The display could not be read. Observe it again.");
                tree = Json.mapOf("nodes", read.get("nodes"), "windows", read.get("windows"));
                if (selectedWindow != null) tree.put("windowId", selectedWindow);
                match = com.deviceagent.tools.Observations.validateTarget(tree, reference);
                target = liveNodes.get(match.get("window") + ":" + match.get("nodeId"));
                if (target == null || !target.refresh() || !target.isEnabled() || !target.isVisibleToUser())
                    throw new com.deviceagent.tools.InputNotSentException("stale_target", "The control is no longer available. Observe the screen again.");
            }
            String operation=args.getString("operation","tap");
            final String boundNode=com.deviceagent.tools.Observations.str(match.get("nodeId")),boundWindow=com.deviceagent.tools.Observations.windowKey(match);
            Rect bounds=new Rect();target.getBoundsInScreen(bounds);
            if(!str(target.getText()).equals(match.get("text"))||!str(target.getContentDescription()).equals(match.get("contentDescription"))||!rect(bounds).equals(match.get("bounds")))throw new com.deviceagent.tools.InputNotSentException("stale_target","The control changed. Observe the screen again.");
            boolean focused=false;
            var currentWindows=automation.getWindowsOnAllDisplays().get(display);
            if(currentWindows!=null)for(var w:currentWindows)if((selectedWindow!=null||w.isFocused())&&w.getId()==target.getWindowId())focused=true;
            if(!focused||target.isSelected()!=Boolean.TRUE.equals(match.get("selected"))||target.isCheckable()&&target.isChecked()!=Boolean.TRUE.equals(match.get("checked")))throw new com.deviceagent.tools.InputNotSentException("stale_target","The control or focused window changed. Observe the screen again.");
            if(selectedWindow!=null)com.deviceagent.tools.TargetTap.point(tree,match);
            com.deviceagent.tools.ActionDeadline.check();
            Bundle out=new Bundle();out.putInt("display",display);
            if(operation.equals("type")&&!target.isEditable()&&target.getRangeInfo()!=null){
                AccessibilityNodeInfo.RangeInfo range=target.getRangeInfo();float wanted;
                try{wanted=Float.parseFloat(args.getString("text","").strip());}catch(NumberFormatException e){throw new com.deviceagent.tools.InputNotSentException("invalid_argument","Type a number to set this slider.");}
                if(Float.isNaN(wanted)||wanted<range.getMin()||wanted>range.getMax())throw new com.deviceagent.tools.InputNotSentException("invalid_argument","Type a number from "+range.getMin()+" to "+range.getMax()+".");
                Bundle progress=new Bundle();progress.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,wanted);
                dispatched=true;
                if(!target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),progress))return textFailure("progress_rejected","The slider rejected the value. Observe it before trying again.",false,true);
                out.putBoolean("inputDelivered",true);
                float now=Float.NaN;
                for(int attempt=0;attempt<5;attempt++){if(target.refresh()&&target.getRangeInfo()!=null){now=target.getRangeInfo().getCurrent();if(Math.abs(now-wanted)<0.5f)break;}Thread.sleep(50);}
                boolean matched=Math.abs(now-wanted)<0.5f;
                out.putBoolean("ok",matched);out.putBoolean("valueMatched",matched);out.putDouble("value",now);
                if(!matched){out.putString("code","progress_verification_failed");out.putString("error","The value was sent, but the slider shows "+now+". Observe it before trying again.");}
                return out;
            }
            if(operation.equals("type")){
                if(!target.isEditable()||!target.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT))throw new com.deviceagent.tools.InputNotSentException("text_input_unsupported","Choose a field that supports text input.");
                String mode=args.getString("mode","replace");
                var plan=com.deviceagent.tools.TextEditPlan.create(fieldValue(target,args.getString("windowKey","")),target.getTextSelectionStart(),target.getTextSelectionEnd(),args.getString("text"),mode.equals("replace"),target.isPassword());
                dispatched=true;
                if(!target.isFocused()&&!target.performAction(AccessibilityNodeInfo.ACTION_FOCUS))return textFailure("focus_rejected","The field could not take focus. Observe it before trying again.",false,false);
                Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,plan.value);
                if(!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value))return textFailure("text_input_rejected","The field rejected the text. Observe it before trying again.",false,true);
                out.putBoolean("inputDelivered",true);
                Bundle selection=new Bundle();selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,plan.cursor);selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,plan.cursor);target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,selection);
                boolean matched=false;
                for(int attempt=0;attempt<5;attempt++){if(target.refresh()&&!target.isPassword()&&com.deviceagent.tools.FieldText.matches(plan.value,fieldValue(target,args.getString("windowKey","")),(target.getInputType()&android.text.InputType.TYPE_MASK_CLASS)==android.text.InputType.TYPE_CLASS_PHONE,false)){matched=true;break;}Thread.sleep(50);}
                out.putBoolean("ok",matched);out.putBoolean("valueMatched",matched);
                if(!matched){out.putString("code",target.isPassword()?"value_hidden":"text_verification_failed");out.putString("error",target.isPassword()?"Text was sent, but this field hides its value.":"Text was sent, but the field value did not match. Observe it before trying again.");}
                return out;
            }
            if(operation.equals("scroll")){
                String dir = args.getString("direction", "down");
                int action=switch(dir){
                    case "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId();
                    case "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId();
                    case "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId();
                    default -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();};
                final int desired=action;
                List<AccessibilityNodeInfo.AccessibilityAction> actions = target.getActionList();
                if(actions.stream().noneMatch(a->a.getId()==desired))action=dir.equals("up")||dir.equals("left")?AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                final int chosen=action;
                boolean hasChosen = actions.stream().anyMatch(a->a.getId()==chosen);
                if (!hasChosen) {
                    // Android removes forward/down scroll actions when a RecyclerView/ScrollView
                    // is already at its boundary while keeping the opposite direction.
                    boolean hasAnyScroll = actions.stream().anyMatch(a -> {
                        int id = a.getId();
                        return id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                || id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                                || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()
                                || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()
                                || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()
                                || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId();
                    });
                    if (hasAnyScroll) {
                        out.putBoolean("ok", true);
                        out.putBoolean("inputDelivered", false);
                        out.putBoolean("endReached", true);
                        out.putBoolean("moved", false);
                        return out;
                    }
                    if (!target.isScrollable() || bounds.width() < 20 || bounds.height() < 20) {
                        throw new com.deviceagent.tools.InputNotSentException("scroll_unsupported", "This container does not support scrolling in that direction.");
                    }
                    // Fallback for Compose / WebView scrollable containers without a11y scroll actions.
                    String baseline = scrollContent(tree, match);
                    int amount = args.getInt("amount", 1);
                    for (int count = 0; count < amount; count++) {
                        com.deviceagent.tools.ActionDeadline.check();
                        dispatched = true;
                        swipeContainer(automation, display, bounds, dir);
                        Thread.sleep(100);
                    }
                    Map<String, Object> after = readTreeMap(automation, display, true, SETTLE_ATTEMPTS, selectedWindow);
                    Map<String, Object> afterTree = Json.mapOf("nodes", after.get("nodes"), "windows", after.get("windows"));
                    if (selectedWindow != null) afterTree.put("windowId", selectedWindow);
                    Map<String, Object> afterNode = com.deviceagent.tools.Observations.appNodes(afterTree).stream().filter(n -> boundNode.equals(n.get("nodeId")) && boundWindow.equals(n.get("windowKey"))).findFirst().orElse(null);
                    boolean moved = afterNode != null && !baseline.equals(scrollContent(afterTree, afterNode));
                    out.putBoolean("ok", true);
                    out.putBoolean("inputDelivered", true);
                    out.putBoolean("moved", moved);
                    out.putBoolean("endReached", !moved);
                    out.putString("inputMethod", "touch");
                    return out;
                }
                boolean accepted=false,end=false;
                String baseline=scrollContent(tree,match);
                for(int count=0;count<args.getInt("amount",1);count++){
                    com.deviceagent.tools.ActionDeadline.check();dispatched=true;
                    if(!target.performAction(action)){end=true;break;}accepted=true;Thread.sleep(80);
                    if(!target.refresh())break;
                }
                Map<String, Object> after = readTreeMap(automation, display, true, SETTLE_ATTEMPTS, selectedWindow);
                Map<String, Object> afterTree = Json.mapOf("nodes", after.get("nodes"), "windows", after.get("windows"));
                if(selectedWindow!=null)afterTree.put("windowId",selectedWindow);
                Map<String,Object> afterNode=com.deviceagent.tools.Observations.appNodes(afterTree).stream().filter(n->boundNode.equals(n.get("nodeId"))&&boundWindow.equals(n.get("windowKey"))).findFirst().orElse(null);
                out.putBoolean("ok",true);out.putBoolean("inputDelivered",accepted);out.putBoolean("endReached",end);
                if(afterNode!=null)out.putBoolean("moved",!baseline.equals(scrollContent(afterTree,afterNode)));
                return out;
            }
            int hold=args.getInt("holdMs",0);
            boolean accepted;
            if(hold>0||com.deviceagent.tools.TargetTap.webLink(tree,match)){
                int[] point=com.deviceagent.tools.TargetTap.point(tree,match);
                com.deviceagent.tools.ActionDeadline.check();
                dispatched=true;
                touch(automation,display,point[0],point[1],hold>0?hold:50);
                accepted=true;
                out.putString("inputMethod","touch");
            }else {
                if(!target.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK))throw new com.deviceagent.tools.InputNotSentException("tap_unsupported","This control does not support a tap. Choose another control or use holdMs for a long press.");
                dispatched=true;
                accepted=target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                out.putString("inputMethod","accessibility");
            }
            if(!accepted)return textFailure("input_rejected","The control rejected the input. Observe it before trying again.",false,false);
            out.putBoolean("ok",true);out.putBoolean("inputDelivered",true);return out;
        }catch(com.deviceagent.tools.InputNotSentException e){return textFailure(e.code,e.getMessage(),false,false);}
        catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();return textFailure(dispatched?"input_outcome_unknown":"observation_failed",dispatched?"The click has no receipt. Observe the screen before trying again.":"The control could not be read. Observe the screen again.",dispatched,false);}
        finally{liveNodes.clear();lastTreeMap=null;if(automation!=null)disconnect(automation);if(locked)lock.unlock();}
    }

    private static void swipeContainer(UiAutomation automation, int display, Rect bounds, String dir) throws Exception {
        int cx = bounds.centerX(), cy = bounds.centerY();
        int dx = Math.max(10, bounds.width() / 3), dy = Math.max(10, bounds.height() / 3);
        int x1 = cx, y1 = cy, x2 = cx, y2 = cy;
        switch (dir) {
            case "up" -> { y1 = Math.max(bounds.top + 8, cy - dy); y2 = Math.min(bounds.bottom - 8, cy + dy); }
            case "left" -> { x1 = Math.max(bounds.left + 8, cx - dx); x2 = Math.min(bounds.right - 8, cx + dx); }
            case "right" -> { x1 = Math.min(bounds.right - 8, cx + dx); x2 = Math.max(bounds.left + 8, cx - dx); }
            default -> { y1 = Math.min(bounds.bottom - 8, cy + dy); y2 = Math.max(bounds.top + 8, cy - dy); }
        }
        Method setDisplay = android.view.InputEvent.class.getMethod("setDisplayId", int.class);
        long down = android.os.SystemClock.uptimeMillis();
        boolean finished = false;
        try {
            if (!touchEvent(automation, setDisplay, display, x1, y1, down, android.view.MotionEvent.ACTION_DOWN))
                throw new IllegalStateException("Scroll down has no receipt");
            int steps = 10;
            for (int i = 1; i <= steps; i++) {
                float f = i / (float) steps;
                touchEvent(automation, setDisplay, display, Math.round(x1 + (x2 - x1) * f), Math.round(y1 + (y2 - y1) * f), down, android.view.MotionEvent.ACTION_MOVE);
                Thread.sleep(20);
            }
            if (!touchEvent(automation, setDisplay, display, x2, y2, down, android.view.MotionEvent.ACTION_UP))
                throw new IllegalStateException("Scroll up has no receipt");
            finished = true;
        } finally {
            if (!finished) cancelTouch(automation, setDisplay, display, x1, y1, down);
        }
    }

    private static void touch(UiAutomation automation,int display,int x,int y,int hold) throws Exception {
        Method setDisplay=android.view.InputEvent.class.getMethod("setDisplayId",int.class);
        long down=android.os.SystemClock.uptimeMillis();
        boolean finished=false;
        try {
            if(!touchEvent(automation,setDisplay,display,x,y,down,android.view.MotionEvent.ACTION_DOWN))throw new IllegalStateException("Touch down has no receipt");
            Thread.sleep(hold);
            if(!touchEvent(automation,setDisplay,display,x,y,down,android.view.MotionEvent.ACTION_UP))throw new IllegalStateException("Touch up has no receipt");
            finished=true;
        } finally {
            // Cancellation cannot activate the control, even after a lost receipt.
            if(!finished)cancelTouch(automation,setDisplay,display,x,y,down);
        }
    }
    private static void cancelTouch(UiAutomation automation,Method setDisplay,int display,int x,int y,long down){
        try{if(touchEvent(automation,setDisplay,display,x,y,down,android.view.MotionEvent.ACTION_CANCEL,true))return;}catch(Exception ignored){}
        try{touchEvent(automation,setDisplay,display,x,y,down,android.view.MotionEvent.ACTION_CANCEL,false);}catch(Exception ignored){}
    }
    private static boolean touchEvent(UiAutomation automation,Method setDisplay,int display,int x,int y,long down,int action) throws Exception {
        return touchEvent(automation,setDisplay,display,x,y,down,action,true);
    }
    private static boolean touchEvent(UiAutomation automation,Method setDisplay,int display,int x,int y,long down,int action,boolean sync) throws Exception {
        android.view.MotionEvent event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0);
        try {
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);setDisplay.invoke(event,display);
            return automation.injectInputEvent(event,sync);
        } finally {event.recycle();}
    }

    private static String scrollContent(Map<String,Object> tree,Map<String,Object> container){
        java.util.Set<Object> descendants=new java.util.HashSet<>();descendants.add(container.get("index"));List<Object> content=new ArrayList<>();
        for(var n:com.deviceagent.tools.Observations.appNodes(tree))if(descendants.contains(n.get("parent"))){descendants.add(n.get("index"));content.add(Json.mapOf("text",n.get("text"),"description",n.get("contentDescription"),"bounds",n.get("bounds")));}
        return Json.stringify(content);
    }

    private UiAutomation connect() throws Exception {
        if (idleHandler != null) idleHandler.removeCallbacks(idleDisconnect);
        if(pooledAutomation!=null){if(android.os.Build.VERSION.SDK_INT>=34)pooledAutomation.clearCache();return pooledAutomation;}
        if (thread == null || !thread.isAlive()) {
            thread = new HandlerThread("device-agent-a11y");
            thread.start();
            idleHandler = new android.os.Handler(thread.getLooper());
        }
        Looper looper = thread.getLooper();
        Class<?> connectionClass = Class.forName("android.app.UiAutomationConnection");
        Class<?> connectionInterface = Class.forName("android.app.IUiAutomationConnection");
        Object connection = connectionClass.getDeclaredConstructor().newInstance();
        Constructor<UiAutomation> ctor = UiAutomation.class.getDeclaredConstructor(Looper.class, connectionInterface);
        ctor.setAccessible(true);
        UiAutomation automation = ctor.newInstance(looper, connection);
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                try {
                    Method connect = UiAutomation.class.getDeclaredMethod("connect", int.class);
                    connect.setAccessible(true);
                    connect.invoke(automation, FLAG_DONT_SUPPRESS);
                } catch (NoSuchMethodException e) {
                    Method connect = UiAutomation.class.getDeclaredMethod("connect");
                    connect.setAccessible(true);
                    connect.invoke(automation);
                }
                last = null;
                break;
            } catch (java.lang.reflect.InvocationTargetException e) {
                // Another UiAutomation (e.g. a stray uiautomator dump) still holds the slot.
                last = e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
                Thread.sleep(300L);
            }
        }
        if (last != null) throw last;
        try {
            AccessibilityServiceInfo info = automation.getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                automation.setServiceInfo(info);
            }
        } catch (RuntimeException ignored) {
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            pooledAutomation = automation;
        }
        return automation;
    }

    private void invalidatePooledLocked(UiAutomation automation) {
        if (automation != null && automation == pooledAutomation) {
            pooledAutomation = null;
            disconnectRaw(automation);
        }
    }

    private void disconnect(UiAutomation automation) {
        if (automation == pooledAutomation) {
            if (scopeDepth == 0) scheduleIdleDisconnectLocked();
            return;
        }
        disconnectRaw(automation);
    }

    private void disconnectRaw(UiAutomation automation) {
        try {
            Method disconnect = UiAutomation.class.getDeclaredMethod("disconnect");
            disconnect.setAccessible(true);
            disconnect.invoke(automation);
        } catch (Throwable ignored) {
        }
    }

    private static String windowType(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "application";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "ime";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "system";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "a11y-overlay";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "divider";
            default: return "type-" + type;
        }
    }

    private static String rect(Rect r) {
        return "[" + r.left + "," + r.top + "][" + r.right + "," + r.bottom + "]";
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    private static Bundle error(String message) {
        Bundle out = new Bundle();
        out.putBoolean("ok", false);
        out.putString("error", message);
        return out;
    }
}
