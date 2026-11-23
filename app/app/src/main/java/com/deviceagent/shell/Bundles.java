package com.deviceagent.shell;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bundle <-> Map for the shell-side hosts, shared by the daemon and the app-side Binder client. */
public final class Bundles {
    private Bundles() {}

    public static Map<String, Object> toMap(Bundle reply) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (reply == null) {
            out.put("ok", false);
            out.put("error", "no reply from shell service");
            return out;
        }
        for (String key : reply.keySet()) {
            Object v = reply.get(key);
            if (v instanceof String[]) {
                out.put(key, Arrays.asList((String[]) v));
            } else if (v instanceof int[]) {
                List<Integer> ints = new ArrayList<>();
                for (int i : (int[]) v) ints.add(i);
                out.put(key, ints);
            } else if (v instanceof Bundle) {
                out.put(key, toMap((Bundle) v));
            } else if (v instanceof List) {
                List<Object> items = new ArrayList<>();
                for (Object item : (List<?>) v) items.add(item instanceof Bundle ? toMap((Bundle) item) : item);
                out.put(key, items);
            } else {
                out.put(key, v);
            }
        }
        return out;
    }

    public static Bundle fromMap(Map<String, Object> args) {
        Bundle in = new Bundle();
        if (args == null) return in;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) in.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof Integer) in.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Long) in.putLong(e.getKey(), (Long) v);
            else if (v instanceof Float) in.putFloat(e.getKey(), (Float) v);
            else if (v instanceof Double) in.putFloat(e.getKey(), ((Double) v).floatValue());
            else if (v != null) in.putString(e.getKey(), String.valueOf(v));
        }
        return in;
    }
}
