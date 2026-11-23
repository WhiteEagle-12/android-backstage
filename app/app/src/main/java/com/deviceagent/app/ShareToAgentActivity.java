package com.deviceagent.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;
import com.deviceagent.rpc.SimpleHttp;
import com.deviceagent.tools.Json;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Android Share sheet target: accepts links, text, photos, screenshots, or PDFs
 * from any app and hands them to the local Backstage inbox + event push.
 */
public final class ShareToAgentActivity extends Activity {
    private static final long MAX_FILE_BYTES = 25L * 1024 * 1024;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }
        final String action = intent.getAction();
        final String mime = intent.getType() == null ? "text/plain" : intent.getType();
        final String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        final String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        final String referrer = getReferrer() == null ? "" : getReferrer().getHost();
        final List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) uris.add(stream);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }

        final android.content.Context appCtx = getApplicationContext();
        new Thread(() -> {
            try {
                List<Map<String, Object>> files = copyUris(appCtx, uris, mime);
                AgentConfig cfg = AgentConfig.load(appCtx);
                int port = cfg.daemonPort > 0 ? cfg.daemonPort : 18422;
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("mimeType", mime);
                if (text != null && !text.isBlank()) body.put("text", text);
                if (subject != null && !subject.isBlank()) body.put("subject", subject);
                if (referrer != null && !referrer.isBlank()) body.put("sourceApp", referrer);
                if (!files.isEmpty()) body.put("files", files);
                SimpleHttp.postJsonOnce("http://127.0.0.1:" + port + "/inbox", Json.stringify(body), 5_000, cfg.localToken);
                runOnUiThread(() -> Toast.makeText(appCtx, AppCopy.SHARED_TOAST, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(appCtx, AppCopy.SHARE_FAILED_TOAST, Toast.LENGTH_LONG).show());
            }
        }, "backstage-share-inbox").start();
        finish();
    }

    private static List<Map<String, Object>> copyUris(android.content.Context ctx, List<Uri> uris, String fallbackMime) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (uris.isEmpty()) return out;
        File dir = new File(ctx.getExternalFilesDir(null) != null ? ctx.getExternalFilesDir(null) : ctx.getCacheDir(), "inbox");
        dir.mkdirs();
        for (Uri uri : uris) {
            if (uri == null) continue;
            String name = displayName(ctx, uri);
            String mime = ctx.getContentResolver().getType(uri);
            if (mime == null || mime.isBlank()) mime = fallbackMime;
            String safeName = UUID.randomUUID().toString().substring(0, 8) + "-" + name.replaceAll("[^A-Za-z0-9._-]", "_");
            File dest = new File(dir, safeName);
            long total = 0;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(dest)) {
                if (in == null) continue;
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) >= 0) {
                    total += r;
                    if (total > MAX_FILE_BYTES) {
                        dest.delete();
                        total = -1;
                        break;
                    }
                    fos.write(buf, 0, r);
                }
            } catch (Exception ignored) {
                dest.delete();
                continue;
            }
            if (total < 0) continue;
            dest.setReadable(true, false);
            out.add(Json.mapOf("name", name, "mimeType", mime, "path", dest.getAbsolutePath(), "size", total));
        }
        return out;
    }

    private static String displayName(android.content.Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isBlank()) return n;
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null || last.isBlank() ? "shared-file" : last;
    }
}
