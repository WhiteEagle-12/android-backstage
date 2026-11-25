package com.deviceagent.shell;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import com.deviceagent.protocol.ShellCommand;
import com.deviceagent.tools.GesturePlan;
import com.deviceagent.tools.Json;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fast background personal shortcuts executed in the shell-uid daemon without
 * opening UI apps: recent camera photos, structured contacts search, alarms,
 * and countdown timers.
 */
public final class PersonalHost {
    private PersonalHost() {}

    public static Map<String, Object> handle(Context shell, Map<String, Object> args) {
        String op = String.valueOf(args.getOrDefault("operation", ""));
        return switch (op) {
            case "recent_photos" -> recentPhotos(shell, GesturePlan.integer(args, "limit", 5, 1, 20));
            case "contacts" -> searchContacts(shell, Json.string(args, "query"), GesturePlan.integer(args, "limit", 10, 1, 25));
            case "alarm" -> setAlarm(args);
            case "timer" -> setTimer(args);
            default -> Json.mapOf("ok", false, "code", "invalid_argument", "error", "Unknown personal operation: " + op);
        };
    }

    private static Map<String, Object> recentPhotos(Context shell, int limit) {
        List<Map<String, Object>> photos = new ArrayList<>();
        try {
            String[] cols = {
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT
            };
            try (Cursor c = shell.getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cols, null, null,
                    MediaStore.Images.Media.DATE_MODIFIED + " DESC")) {
                if (c != null) {
                    while (c.moveToNext() && photos.size() < limit) {
                        String path = c.getString(0);
                        if (path == null || path.isBlank()) continue;
                        long taken = c.getLong(4);
                        long modified = c.getLong(5) * 1000L;
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("path", path);
                        row.put("name", c.getString(1) == null ? new File(path).getName() : c.getString(1));
                        row.put("mimeType", c.getString(2) == null ? "image/jpeg" : c.getString(2));
                        row.put("size", c.getLong(3));
                        row.put("dateTakenMs", taken > 0 ? taken : modified);
                        if (c.getInt(6) > 0) row.put("width", c.getInt(6));
                        if (c.getInt(7) > 0) row.put("height", c.getInt(7));
                        photos.add(row);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (photos.isEmpty()) {
            // Direct fallback to standard Camera/Screenshots directories if MediaStore denies shell.
            List<File> found = new ArrayList<>();
            for (String dirPath : List.of("/sdcard/DCIM/Camera", "/sdcard/Pictures/Screenshots", "/sdcard/DCIM/Screenshots")) {
                File dir = new File(dirPath);
                File[] list = dir.isDirectory() ? dir.listFiles() : null;
                if (list == null) continue;
                for (File f : list) {
                    if (!f.isFile()) continue;
                    String lower = f.getName().toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                            || lower.endsWith(".webp") || lower.endsWith(".heic")) {
                        found.add(f);
                    }
                }
            }
            found.sort(Comparator.comparingLong(File::lastModified).reversed());
            for (File f : found) {
                if (photos.size() >= limit) break;
                String lower = f.getName().toLowerCase(Locale.ROOT);
                String mime = lower.endsWith(".png") ? "image/png"
                        : lower.endsWith(".webp") ? "image/webp"
                        : lower.endsWith(".heic") ? "image/heic" : "image/jpeg";
                photos.add(Json.mapOf(
                        "path", f.getAbsolutePath(),
                        "name", f.getName(),
                        "mimeType", mime,
                        "size", f.length(),
                        "dateTakenMs", f.lastModified()));
            }
        }
        return Json.mapOf("ok", true, "count", photos.size(), "photos", photos);
    }

    private static final java.util.regex.Pattern CONTACT_ROW =
            java.util.regex.Pattern.compile("^Row: \\d+ contact_id=(.*?), display_name=(.*), data1=(.*)$");

    /** contact_id, display_name and value rows from content://com.android.contacts/data/{kind}, sorted by name. */
    private static List<String[]> contactRows(String kind) {
        android.os.Bundle r = LocalShell.run("content query --uri content://com.android.contacts/data/" + kind
                + " --projection contact_id:display_name:data1 --sort 'display_name ASC'", null, 15000);
        byte[] out = r.getByteArray("stdout");
        if (r.getInt("exit") != 0) throw new IllegalStateException(r.getString("error", "content query exited " + r.getInt("exit")));
        List<String[]> rows = new ArrayList<>();
        for (String line : new String(out == null ? new byte[0] : out, java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
            var m = CONTACT_ROW.matcher(line);
            if (!m.matches() || m.group(1).equals("NULL")) continue;
            rows.add(new String[]{m.group(1), m.group(2).equals("NULL") ? "" : m.group(2), m.group(3).equals("NULL") ? "" : m.group(3)});
        }
        return rows;
    }

    private static Map<String, Object> searchContacts(Context shell, String query, int limit) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Map<String, ContactEntry> byId = new LinkedHashMap<>();
        try {
            // The helper is not a registered app, so the contacts provider refuses its resolver.
            // The content command gets a provider handle for the shell user, as location does.
            for (String kind : new String[]{"phones", "emails"}) {
                for (String[] row : contactRows(kind)) {
                    String id = row[0], name = row[1], value = row[2];
                    if (!needle.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(needle)
                            && !value.toLowerCase(Locale.ROOT).contains(needle)) continue;
                    if (byId.size() >= limit && !byId.containsKey(id)) continue;
                    ContactEntry entry = byId.computeIfAbsent(id, k -> new ContactEntry(name));
                    if (!value.isBlank()) (kind.equals("phones") ? entry.phones : entry.emails).add(value.trim());
                }
            }
        } catch (Throwable t) {
            return Json.mapOf("ok", false, "code", "contacts_unavailable", "error",
                    "Contacts could not be read: " + t.getMessage());
        }
        List<Map<String, Object>> rows = new ArrayList<>(byId.size());
        for (ContactEntry e : byId.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", e.name);
            if (!e.phones.isEmpty()) row.put("phones", new ArrayList<>(e.phones));
            if (!e.emails.isEmpty()) row.put("emails", new ArrayList<>(e.emails));
            rows.add(row);
        }
        return Json.mapOf("ok", true, "count", rows.size(), "contacts", rows);
    }

    private static Map<String, Object> setAlarm(Map<String, Object> args) {
        int hour = GesturePlan.integer(args, "hour", -1, 0, 23);
        int minute = GesturePlan.integer(args, "minute", -1, 0, 59);
        if (hour < 0 || minute < 0) {
            return Json.mapOf("ok", false, "code", "invalid_argument", "error", "hour (0-23) and minute (0-59) are required.");
        }
        StringBuilder cmd = new StringBuilder("am start -a android.intent.action.SET_ALARM")
                .append(" --ei android.intent.extra.alarm.HOUR ").append(hour)
                .append(" --ei android.intent.extra.alarm.MINUTES ").append(minute)
                .append(" --ez android.intent.extra.alarm.SKIP_UI true");
        String message = Json.string(args, "message");
        if (message != null && !message.isBlank()) {
            cmd.append(" --es android.intent.extra.alarm.MESSAGE ").append(ShellCommand.quote(message));
        }
        android.os.Bundle res = LocalShell.run(cmd.toString(), null, 5000);
        int exit = res.getInt("exit", -1);
        byte[] outBytes = res.getByteArray("stdout");
        String out = outBytes == null ? "" : new String(outBytes, java.nio.charset.StandardCharsets.UTF_8);
        boolean ok = exit == 0 && !out.contains("Error:");
        Map<String, Object> reply = Json.mapOf("ok", ok, "inputDelivered", ok, "hour", hour, "minute", minute);
        if (message != null && !message.isBlank()) reply.put("message", message);
        if (!ok) {
            reply.put("code", "alarm_failed");
            reply.put("error", "Android could not set the alarm.");
        }
        return reply;
    }

    private static Map<String, Object> setTimer(Map<String, Object> args) {
        int seconds = GesturePlan.integer(args, "seconds", -1, 1, 86400);
        if (seconds < 1) {
            return Json.mapOf("ok", false, "code", "invalid_argument", "error", "seconds (1-86400) is required.");
        }
        StringBuilder cmd = new StringBuilder("am start -a android.intent.action.SET_TIMER")
                .append(" --ei android.intent.extra.alarm.LENGTH ").append(seconds)
                .append(" --ez android.intent.extra.alarm.SKIP_UI true");
        String message = Json.string(args, "message");
        if (message != null && !message.isBlank()) {
            cmd.append(" --es android.intent.extra.alarm.MESSAGE ").append(ShellCommand.quote(message));
        }
        android.os.Bundle res = LocalShell.run(cmd.toString(), null, 5000);
        int exit = res.getInt("exit", -1);
        byte[] outBytes = res.getByteArray("stdout");
        String out = outBytes == null ? "" : new String(outBytes, java.nio.charset.StandardCharsets.UTF_8);
        boolean ok = exit == 0 && !out.contains("Error:");
        Map<String, Object> reply = Json.mapOf("ok", ok, "inputDelivered", ok, "seconds", seconds);
        if (message != null && !message.isBlank()) reply.put("message", message);
        if (!ok) {
            reply.put("code", "timer_failed");
            reply.put("error", "Android could not start the timer.");
        }
        return reply;
    }

    private static final class ContactEntry {
        final String name;
        final Set<String> phones = new LinkedHashSet<>();
        final Set<String> emails = new LinkedHashSet<>();
        ContactEntry(String name) { this.name = name; }
    }
}
