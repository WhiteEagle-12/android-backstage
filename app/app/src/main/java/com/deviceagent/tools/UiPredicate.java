package com.deviceagent.tools;

import java.util.List;
import java.util.Map;

/** All node predicates must match a single node, never different controls. */
public final class UiPredicate {
    private UiPredicate() {}
    public static void validate(Map<String,Object> args) {
        boolean any = false;
        for (String key : List.of("text", "resourceId", "package", "activity")) {
            if (!args.containsKey(key)) continue;
            if (!(args.get(key) instanceof String s) || s.isBlank()) throw new IllegalArgumentException(key + " must be nonempty");
            any = true;
        }
        if (!any) throw new IllegalArgumentException("Provide text, resourceId, package, or activity to wait for");
        if (args.containsKey("activity") && !args.containsKey("package")) throw new IllegalArgumentException("activity requires package");
    }
    public static boolean matches(Map<String,Object> args, List<Map<String,Object>> nodes,
                                  List<Map<String,Object>> windows, String focusedComponent) {
        String pkg = (String)args.get("package"), activity = (String)args.get("activity");
        if (activity != null) {
            String full = activity.startsWith(".") ? pkg + activity : activity;
            String expected = pkg + "/" + full;
            String actual = focusedComponent == null ? "" : focusedComponent;
            if (actual.startsWith(pkg + "/.")) actual = pkg + "/" + pkg + actual.substring(pkg.length()+1);
            if (!expected.equals(actual)) return false;
        }
        if (pkg != null && activity == null) {
            boolean found = false;
            for (Map<String,Object> window : windows) {
                if (!Boolean.TRUE.equals(window.get("focused"))) continue;
                for (Map<String,Object> node : nodes) if (window.get("id").equals(node.get("window")) && pkg.equals(node.get("package"))) found = true;
            }
            if (!found) return false;
        }
        String text = (String)args.get("text"), resource = (String)args.get("resourceId");
        if (text == null && resource == null) return true;
        for (Map<String,Object> node : nodes) {
            if (Boolean.FALSE.equals(node.get("visible"))) continue;
            if (pkg != null && !pkg.equals(node.get("package"))) continue;
            if (resource != null && !resource.equals(node.get("resourceId"))) continue;
            if (text != null && !textMatches(text, node.get("text"), args) && !textMatches(text, node.get("contentDescription"), args)) continue;
            return true;
        }
        return false;
    }
    private static boolean textMatches(String wanted,Object value,Map<String,Object> args) {
        String actual = value == null ? "" : value.toString();
        return Boolean.TRUE.equals(args.get("contains")) ? actual.contains(wanted) : actual.equals(wanted);
    }
}
