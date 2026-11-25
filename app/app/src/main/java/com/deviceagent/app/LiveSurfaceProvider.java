package com.deviceagent.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Surface;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hands LiveScreenActivity's SurfaceView Surface directly to the shell-uid daemon over Binder
 * so SurfaceFlinger can composite the virtual display directly onto the screen at native refresh rate.
 */
public final class LiveSurfaceProvider extends ContentProvider {
    private static final class Entry {
        final Surface surface;
        final IBinder alive;
        Entry(Surface surface, IBinder alive) {
            this.surface = surface;
            this.alive = alive;
        }
    }

    private static final Map<String, Entry> PENDING = new LinkedHashMap<>();

    static synchronized void register(String token, Surface surface, IBinder alive) {
        if (token == null || token.isEmpty() || surface == null) return;
        PENDING.put(token, new Entry(surface, alive));
        while (PENDING.size() > 4) {
            Iterator<String> it = PENDING.keySet().iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();
        }
    }

    static synchronized void clear(String token) {
        if (token != null) PENDING.remove(token);
    }

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (!LocationAccess.callerAllowed(uid, android.os.Process.myUid())) {
            throw new SecurityException("Only the Backstage helper may attach a live surface");
        }
        Bundle out = new Bundle();
        if (!"take".equals(method) || arg == null || arg.isEmpty()) {
            out.putBoolean("ok", false);
            return out;
        }
        Entry entry;
        synchronized (LiveSurfaceProvider.class) {
            entry = PENDING.remove(arg);
        }
        if (entry == null || entry.surface == null || !entry.surface.isValid()) {
            out.putBoolean("ok", false);
            return out;
        }
        out.putBoolean("ok", true);
        out.putParcelable("surface", entry.surface);
        if (entry.alive != null) out.putBinder("alive", entry.alive);
        return out;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
