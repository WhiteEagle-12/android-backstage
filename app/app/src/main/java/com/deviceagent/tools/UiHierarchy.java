package com.deviceagent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UiHierarchy {
    private static final Pattern NODE = Pattern.compile("<node\\b([^>]*?)/?>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z0-9:-]+)=['\"]([^'\"]*)['\"]");
    private static final Pattern BOUNDS = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");

    private UiHierarchy() {}

    /**
     * Slice of a {@code uiautomator dump --windows} document for one display.
     * Returns null when the document has no {@code <display id="N">} block for it.
     * A dump without display blocks is the legacy single-display format, which
     * only ever describes display 0.
     */
    public static String displaySection(String xml, int displayId) {
        if (xml == null) return null;
        Matcher m = Pattern.compile(
                "<display\\b[^>]*\\bid=['\"]" + displayId + "['\"][^>]*>(.*?)</display>", Pattern.DOTALL).matcher(xml);
        if (m.find()) return m.group(1);
        if (displayId == 0 && !xml.contains("<display")) return xml;
        return null;
    }

    public static List<Map<String, Object>> parse(String xml) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        Matcher node = NODE.matcher(xml);
        int index = 0;
        while (node.find()) {
            Map<String, String> attrs = attributes(node.group(1));
            String text = attrs.getOrDefault("text", "");
            String desc = attrs.getOrDefault("content-desc", "");
            String resourceId = attrs.getOrDefault("resource-id", "");
            boolean clickable = "true".equals(attrs.get("clickable"));
            if (text.isEmpty() && desc.isEmpty() && resourceId.isEmpty() && !clickable) continue;
            int[] box = bounds(attrs.getOrDefault("bounds", ""));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", index++);
            String cls = attrs.getOrDefault("class", "");
            int dot = cls.lastIndexOf('.');
            row.put("class", dot < 0 ? cls : cls.substring(dot + 1));
            row.put("package", attrs.getOrDefault("package", ""));
            row.put("resourceId", resourceId);
            row.put("text", text);
            row.put("contentDescription", desc);
            row.put("clickable", clickable);
            row.put("enabled", !"false".equals(attrs.get("enabled")));
            row.put("bounds", attrs.getOrDefault("bounds", ""));
            if (box != null) {
                row.put("centerX", (box[0] + box[2]) / 2);
                row.put("centerY", (box[1] + box[3]) / 2);
            }
            nodes.add(row);
        }
        return nodes;
    }

    public static Map<String, Object> find(List<Map<String, Object>> nodes, String query) {
        return find(nodes, query, 0, 0);
    }

    /**
     * Best match for {@code query}. The accessibility tree also lists rows that
     * are scrolled out of view, whose bounds fall outside the display; when the
     * display size is known those are only used if nothing visible matches.
     */
    public static Map<String, Object> find(List<Map<String, Object>> nodes, String query, int displayWidth, int displayHeight) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("text is required");
        String needle = query.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> exact = null;
        Map<String, Object> exactText = null;
        Map<String, Object> partial = null;
        Map<String, Object> offscreen = null;
        for (Map<String, Object> node : nodes) {
            if (Boolean.FALSE.equals(node.get("enabled"))) continue;
            boolean textMatches = String.valueOf(node.getOrDefault("text", "")).equalsIgnoreCase(query.trim());
            String hay = (node.getOrDefault("text", "") + " " + node.getOrDefault("contentDescription", "")).trim();
            boolean isExact = textMatches || String.valueOf(node.getOrDefault("contentDescription", "")).equalsIgnoreCase(query.trim());
            boolean isPartial = !isExact && hay.toLowerCase(Locale.ROOT).contains(needle);
            if (!isExact && !isPartial) continue;
            if (!visible(node, displayWidth, displayHeight)) {
                if (offscreen == null || (isExact && !Boolean.TRUE.equals(offscreen.get("_exact")))) {
                    offscreen = new LinkedHashMap<>(node);
                    offscreen.put("_exact", isExact);
                }
                continue;
            }
            if (textMatches) exactText = preferClickable(exactText, node);
            if (isExact) exact = preferClickable(exact, node);
            else partial = preferClickable(partial, node);
        }
        Map<String, Object> hit = exactText != null ? exactText : exact != null ? exact : partial;
        if (hit == null && offscreen != null) {
            offscreen.remove("_exact");
            offscreen.put("offscreen", true);
            hit = offscreen;
        }
        if (hit == null) throw new InputNotSentException("target_not_found", "No matching control. Read the UI tree or scroll before trying again.");
        return hit;
    }

    private static boolean visible(Map<String, Object> node, int width, int height) {
        if (width <= 0 || height <= 0) return true;
        Object x = node.get("centerX");
        Object y = node.get("centerY");
        if (!(x instanceof Number) || !(y instanceof Number)) return false;
        int cx = ((Number) x).intValue();
        int cy = ((Number) y).intValue();
        return cx >= 0 && cy >= 0 && cx < width && cy < height;
    }

    private static Map<String, Object> preferClickable(Map<String, Object> current, Map<String, Object> candidate) {
        if (current == null) return candidate;
        boolean curClick = Boolean.TRUE.equals(current.get("clickable"));
        boolean candClick = Boolean.TRUE.equals(candidate.get("clickable"));
        return (!curClick && candClick) ? candidate : current;
    }

    private static Map<String, String> attributes(String raw) {
        Map<String, String> attrs = new LinkedHashMap<>();
        Matcher m = ATTR.matcher(raw);
        while (m.find()) {
            attrs.put(m.group(1), m.group(2)
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&apos;", "'"));
        }
        return attrs;
    }

    private static int[] bounds(String raw) {
        Matcher m = BOUNDS.matcher(raw);
        if (!m.find()) return null;
        return new int[]{
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))
        };
    }
}
