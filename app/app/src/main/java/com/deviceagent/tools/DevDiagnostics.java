package com.deviceagent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsers for developer diagnostics folded into android.logs:
 * - kind="jank" (`dumpsys gfxinfo <pkg>`)
 * - kind="anr" (`logcat -b events -d | grep am_anr` / dropbox summaries)
 */
public final class DevDiagnostics {
    private DevDiagnostics() {}

    private static final Pattern TOTAL_FRAMES = Pattern.compile("Total frames rendered:\\s*(\\d+)");
    private static final Pattern JANKY_FRAMES = Pattern.compile("Janky frames:\\s*(\\d+)\\s*\\(([0-9.]+)%\\)");
    private static final Pattern P50 = Pattern.compile("50th percentile:\\s*(\\d+)ms");
    private static final Pattern P90 = Pattern.compile("90th percentile:\\s*(\\d+)ms");
    private static final Pattern P95 = Pattern.compile("95th percentile:\\s*(\\d+)ms");
    private static final Pattern P99 = Pattern.compile("99th percentile:\\s*(\\d+)ms");
    private static final Pattern MISSED_VSYNC = Pattern.compile("Number Missed Vsync:\\s*(\\d+)");
    private static final Pattern HIGH_INPUT = Pattern.compile("Number High input latency:\\s*(\\d+)");
    private static final Pattern SLOW_UI = Pattern.compile("Number Slow UI thread:\\s*(\\d+)");
    private static final Pattern AM_ANR = Pattern.compile(
            "^(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+)\\s+.*?am_anr\\s*:\\s*\\[\\d+,(\\d+),([^,\\]]+),(?:[^,\\]]*,)?(.*)\\]\\s*$");

    public static Map<String, Object> parseGfxInfo(String pkg, String text) {
        String body = text == null ? "" : text;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("kind", "jank");
        out.put("package", pkg);
        Integer total = intMatch(TOTAL_FRAMES, body);
        if (total != null) out.put("totalFrames", total);
        Matcher j = JANKY_FRAMES.matcher(body);
        if (j.find()) {
            out.put("jankyFrames", Integer.parseInt(j.group(1)));
            out.put("jankyPercent", Double.parseDouble(j.group(2)));
        }
        putIfPresent(out, "p50Ms", intMatch(P50, body));
        putIfPresent(out, "p90Ms", intMatch(P90, body));
        putIfPresent(out, "p95Ms", intMatch(P95, body));
        putIfPresent(out, "p99Ms", intMatch(P99, body));
        putIfPresent(out, "missedVsync", intMatch(MISSED_VSYNC, body));
        putIfPresent(out, "highInputLatency", intMatch(HIGH_INPUT, body));
        putIfPresent(out, "slowUiThread", intMatch(SLOW_UI, body));
        if (total == null) {
            out.put("available", false);
            out.put("note", "No frame stats recorded yet for " + pkg + ". Interact with the app first.");
        } else {
            out.put("available", true);
        }
        return out;
    }

    public static Map<String, Object> parseAnrs(String eventsText, String pkg, int max) {
        List<Map<String, Object>> records = new ArrayList<>();
        if (eventsText != null && !eventsText.isBlank()) {
            String[] lines = eventsText.split("\\R");
            for (int i = lines.length - 1; i >= 0 && records.size() < max; i--) {
                Matcher m = AM_ANR.matcher(lines[i]);
                if (!m.find()) continue;
                String proc = m.group(3).trim();
                if (pkg != null && !pkg.isBlank() && !proc.equals(pkg) && !proc.startsWith(pkg + ":")) continue;
                records.add(Json.mapOf(
                        "time", m.group(1).trim(),
                        "pid", Integer.parseInt(m.group(2)),
                        "process", proc,
                        "reason", m.group(4).trim()));
            }
        }
        return Json.mapOf("ok", true, "kind", "anr", "count", records.size(), "anrs", records);
    }

    private static Integer intMatch(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static void putIfPresent(Map<String, Object> out, String key, Object val) {
        if (val != null) out.put(key, val);
    }
}
