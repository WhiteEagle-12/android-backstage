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

    private static Map<String, Object> searchContacts(Context shell, String query, int limit) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Map<String, ContactEntry> byId = new LinkedHashMap<>();
        try {
            Uri phoneUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
            String[] phoneCols = {
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
            };
            String sel = needle.isEmpty() ? null
                    : "LOWER(" + ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + ") LIKE ? OR "
                    + ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?";
            String[] selArgs = needle.isEmpty() ? null : new String[]{"%" + needle + "%", "%" + needle + "%"};
            try (Cursor c = shell.getContentResolver().query(phoneUri, phoneCols, sel, selArgs,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String id = c.getString(0);
                        String name = c.getString(1);
                        String num = c.getString(2);
                        if (id == null) continue;
                        if (byId.size() >= limit && !byId.containsKey(id)) continue;
                        ContactEntry entry = byId.computeIfAbsent(id, k -> new ContactEntry(name == null ? "" : name));
                        if (num != null && !num.isBlank()) entry.phones.add(num.trim());
                    }
                }
            }
            Uri emailUri = ContactsContract.CommonDataKinds.Email.CONTENT_URI;
            String[] emailCols = {
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Email.ADDRESS
            };
            String emailSel = needle.isEmpty() ? null
                    : "LOWER(" + ContactsContract.CommonDataKinds.Email.DISPLAY_NAME + ") LIKE ? OR LOWER("
                    + ContactsContract.CommonDataKinds.Email.ADDRESS + ") LIKE ?";
            String[] emailArgs = needle.isEmpty() ? null : new String[]{"%" + needle + "%", "%" + needle + "%"};
            try (Cursor c = shell.getContentResolver().query(emailUri, emailCols, emailSel, emailArgs, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String id = c.getString(0);
                        String name = c.getString(1);
                        String addr = c.getString(2);
                        if (id == null) continue;
                        if (byId.size() >= limit && !byId.containsKey(id)) continue;
                        ContactEntry entry = byId.computeIfAbsent(id, k -> new ContactEntry(name == null ? "" : name));
                        if (addr != null && !addr.isBlank()) entry.emails.add(addr.trim());
                    }
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
