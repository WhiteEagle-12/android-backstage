package com.deviceagent.app;

import com.deviceagent.crypto.LinkPairing;
import com.deviceagent.daemon.DaemonConfig;
import com.deviceagent.rpc.SimpleHttp;
import com.deviceagent.tools.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The phone manages its owner's Backstage account. It proves it holds the link key
 * it already shares with the server (HMAC over a server nonce) and receives a short
 * session. No password, passkey or extra credential is stored on the phone.
 */
public final class AccountClient {
    public static final class Device {
        public String id = "", name = "", status = "", model = ""; public boolean online, needsReconnect, thisDevice;
    }
    public static final class Assistant {
        public String id = "", name = "", icon = "mcp", origin = "", lastUsedAt = ""; public Object access = "all"; public long createdAt; public boolean paused; public int screens;
        public boolean all() { return "all".equals(access); }
        public boolean canUse(String deviceId) { return all() || (access instanceof List && ((List<?>) access).contains(deviceId)); }
    }
    public static final class Account {
        public String thisDevice = ""; public final List<Device> devices = new ArrayList<>(); public final List<Assistant> assistants = new ArrayList<>();
    }
    public static final class Approval { public String clientName = "", clientOrigin = "", icon = "mcp"; }
    /** An agent asking for access, approved from a notification or the app. */
    public static final class Request { public String id = "", names = "", check = ""; public long expiresAt; }
    public static final class Methods { public String email = ""; public boolean emailAvailable, hasPassword; public int recoveryLeft; public final List<String[]> passkeys = new ArrayList<>(); }
    public static final class Update { public int versionCode; public String versionName = "", url = "", sha256 = ""; }

    private static String str(Map<String, Object> m, String key) { String v = Json.string(m, key); return v == null || "null".equals(v) ? "" : v; }
    private final String base; // e.g. https://relay.android-backstage.com/work
    private String token; private long expires;

    public AccountClient(String relayUrl) { this.base = relayUrl.replaceAll("/+$", "") + "/work"; }

    public boolean signedIn() { return token != null && System.currentTimeMillis() < expires - 60000; }

    /** Try each saved link until the server recognises one. */
    public synchronized void signIn(List<DaemonConfig.Link> links) throws IOException {
        if (signedIn()) return;
        IOException last = new IOException("This phone is not linked to a Backstage account yet.");
        for (DaemonConfig.Link link : links) {
            try {
                Map<String, Object> challenge = Json.object(SimpleHttp.postJsonOnce(base + "/phone/challenge", Json.stringify(Json.mapOf("agentId", link.agentId)), 8000, null));
                String nonce = str(challenge, "nonce");
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(LinkPairing.unb64url(link.linkKey), "HmacSHA256"));
                String proof = LinkPairing.b64url(mac.doFinal(("backstage owner v1\n" + nonce).getBytes(StandardCharsets.UTF_8)));
                Map<String, Object> session = Json.object(SimpleHttp.postJsonOnce(base + "/phone/session", Json.stringify(Json.mapOf("agentId", link.agentId, "nonce", nonce, "mac", proof)), 8000, null));
                token = str(session, "token");
                expires = System.currentTimeMillis() + Json.integer(session, "expiresIn", 3600) * 1000L;
                return;
            } catch (SimpleHttp.HttpException e) { last = e; if (e.status != 404) throw e; }
            catch (java.security.GeneralSecurityException e) { throw new IOException(e); }
        }
        throw last;
    }
    public synchronized void signOut() { token = null; expires = 0; }

    private Map<String, Object> post(String path, Map<String, Object> body) throws IOException {
        try { return Json.object(SimpleHttp.postJsonOnce(base + path, Json.stringify(body), 15000, token)); }
        catch (SimpleHttp.HttpException e) { if (e.status == 401) signOut(); throw e; }
    }

    public Account account() throws IOException {
        Map<String, Object> reply;
        try { reply = Json.object(SimpleHttp.getOnce(base + "/phone/account", 15000, token)); }
        catch (SimpleHttp.HttpException e) { if (e.status == 401) signOut(); throw e; }
        Account out = new Account(); out.thisDevice = str(reply, "thisDevice");
        Object devices = reply.get("devices");
        if (devices instanceof List) for (Object row : (List<?>) devices) if (row instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) row; Device d = new Device();
            d.id = str(m, "id"); d.name = str(m, "name"); d.status = str(m, "status"); d.model = str(m, "model");
            d.online = Boolean.TRUE.equals(m.get("online")); d.needsReconnect = Boolean.TRUE.equals(m.get("needsReconnect")); d.thisDevice = Boolean.TRUE.equals(m.get("thisDevice"));
            out.devices.add(d);
        }
        Object assistants = reply.get("assistants");
        if (assistants instanceof List) for (Object row : (List<?>) assistants) if (row instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) row; Assistant a = new Assistant();
            a.id = str(m, "id"); a.name = str(m, "name"); a.icon = str(m, "icon"); a.origin = str(m, "origin");
            a.access = m.get("access"); Object created = m.get("createdAt"); a.createdAt = created instanceof Number ? ((Number) created).longValue() : 0;
            a.paused = Boolean.TRUE.equals(m.get("paused")); a.lastUsedAt = str(m, "lastUsedAt"); a.screens = m.get("sessions") instanceof List ? ((List<?>) m.get("sessions")).size() : 0;
            out.assistants.add(a);
        }
        return out;
    }
    public Map<String,Object> invitation() throws IOException { return post("/phone/invitations", Json.mapOf()); }
    public Map<String,Object> invitationStatus(String code) throws IOException { return post("/phone/invitations/status", Json.mapOf("code",code)); }
    public void cancelInvitation(String code) throws IOException { post("/phone/invitations/cancel", Json.mapOf("code",code)); }
    public void rename(String deviceId, String name) throws IOException { post("/phone/devices/rename", Json.mapOf("deviceId", deviceId, "name", name)); }
    public void removeDevice(String deviceId) throws IOException { post("/phone/devices/remove", Json.mapOf("deviceId", deviceId)); }
    /** Returns ticket, code, link, deviceName for the device that will join. */
    public Map<String, Object> addDevice(String name) throws IOException { return post("/phone/devices/add", Json.mapOf("name", name)); }
    /** Null while waiting; the new device's ID when it joined. */
    public String pollAdd(String ticket) throws IOException {
        // The relay may hold its pairing read for 25 seconds before reporting
        // pending. Keep this read open longer than that hold.
        Map<String, Object> reply;
        try { reply = Json.object(SimpleHttp.postJsonOnce(base + "/phone/devices/add/poll", Json.stringify(Json.mapOf("ticket", ticket)), 40000, token)); }
        catch (SimpleHttp.HttpException e) { if (e.status == 401) signOut(); throw e; }
        return Boolean.TRUE.equals(reply.get("pending")) ? null : Json.string(reply, "deviceId");
    }
    public void cancelAdd(String ticket) { try { post("/phone/devices/add/poll", Json.mapOf("ticket", ticket, "cancel", true)); } catch (IOException ignored) { } }
    public void setAccess(String grantId, boolean all, List<String> deviceIds) throws IOException {
        Map<String, Object> body = Json.mapOf("grantId", grantId); if (all) body.put("allDevices", true); else body.put("devices", deviceIds); post("/phone/assistants/access", body);
    }
    public void removeAssistant(String grantId) throws IOException { post("/phone/assistants/remove", Json.mapOf("grantId", grantId)); }
    public Approval preview(String code) throws IOException {
        Map<String, Object> m = post("/phone/approvals/preview", Json.mapOf("code", code)); Approval a = new Approval();
        a.clientName = str(m, "clientName"); a.clientOrigin = str(m, "clientOrigin"); a.icon = str(m, "icon"); return a;
    }
    public void approve(String code, boolean all, List<String> deviceIds) throws IOException {
        Map<String, Object> body = Json.mapOf("code", code); if (all) body.put("allDevices", true); else body.put("devices", deviceIds); post("/phone/approvals/approve", body);
    }
    public void deny(String code) throws IOException { post("/phone/approvals/deny", Json.mapOf("code", code)); }

    public void setPaused(String grantId, boolean paused) throws IOException { post("/phone/assistants/pause", Json.mapOf("grantId", grantId, "paused", paused)); }
    public int stopWork(String grantId) throws IOException { return Json.integer(post("/phone/assistants/stop", Json.mapOf("grantId", grantId)), "stopped", 0); }

    /** Pending agent requests. wait=true holds the reply up to 25 s until one arrives. */
    public List<Request> requests(boolean wait) throws IOException {
        Map<String, Object> reply;
        try { reply = Json.object(SimpleHttp.getOnce(base + "/phone/requests" + (wait ? "?wait=1" : ""), wait ? 35000 : 15000, token)); }
        catch (SimpleHttp.HttpException e) { if (e.status == 401) signOut(); throw e; }
        List<Request> out = new ArrayList<>();
        if (reply.get("requests") instanceof List) for (Object row : (List<?>) reply.get("requests")) if (row instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) row; Request r = new Request();
            r.id = str(m, "request"); r.check = str(m, "check");
            r.names = m.get("names") instanceof List ? String.join(", ", ((List<?>) m.get("names")).stream().map(String::valueOf).toArray(String[]::new)) : "";
            try { r.expiresAt = java.time.Instant.parse(str(m, "expiresAt")).toEpochMilli(); } catch (RuntimeException ignored) { }
            out.add(r);
        }
        return out;
    }
    public void decide(String requestId, boolean allow) throws IOException { post("/phone/requests/decide", Json.mapOf("request", requestId, "allow", allow, "allDevices", true)); }

    public Methods methods() throws IOException {
        Map<String, Object> m;
        try { m = Json.object(SimpleHttp.getOnce(base + "/phone/methods", 15000, token)); }
        catch (SimpleHttp.HttpException e) { if (e.status == 401) signOut(); throw e; }
        Methods out = new Methods(); out.email = str(m, "email"); out.emailAvailable = Boolean.TRUE.equals(m.get("emailAvailable")); out.hasPassword = Boolean.TRUE.equals(m.get("hasPassword")); out.recoveryLeft = Json.integer(m, "recoveryCodesLeft", 0);
        if (m.get("passkeys") instanceof List) for (Object row : (List<?>) m.get("passkeys")) if (row instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> k = (Map<String, Object>) row; out.passkeys.add(new String[]{str(k, "id"), str(k, "label")});
        }
        return out;
    }
    public void emailStart(String email) throws IOException { post("/phone/email/start", Json.mapOf("email", email)); }
    public void emailVerify(String email, String code) throws IOException { post("/phone/email/verify", Json.mapOf("email", email, "code", code)); }
    public void emailRemove() throws IOException { post("/phone/email/remove", Json.mapOf()); }
    public void setPassword(String currentPassword, String newPassword) throws IOException {
        Map<String, Object> body = Json.mapOf("password", newPassword);
        if (currentPassword != null && !currentPassword.isEmpty()) body.put("currentPassword", currentPassword);
        post("/phone/password", body);
    }
    public List<String> newRecoveryCodes() throws IOException {
        List<String> out = new ArrayList<>(); Object codes = post("/phone/recovery", Json.mapOf()).get("codes");
        if (codes instanceof List) for (Object c : (List<?>) codes) out.add(String.valueOf(c)); return out;
    }
    public void removePasskey(String id) throws IOException { post("/phone/passkeys/remove", Json.mapOf("id", id)); }

    /** The newest published app, or null. Needs no sign-in. */
    public Update latestApp() {
        try {
            Map<String, Object> m = Json.object(SimpleHttp.getOnce(base + "/app/latest", 15000, null)); Update u = new Update();
            u.versionCode = Json.integer(m, "versionCode", 0); u.versionName = str(m, "versionName"); u.url = str(m, "url"); u.sha256 = str(m, "sha256");
            return u.versionCode > 0 && u.url.startsWith("https://") && u.sha256.matches("[a-f0-9]{64}") ? u : null;
        } catch (Exception e) { return null; }
    }

    /** A short human message for an API failure. */
    public static String explain(Exception e) {
        if (e instanceof SimpleHttp.HttpException) {
            SimpleHttp.HttpException h = (SimpleHttp.HttpException) e;
            try { String m = str(Json.object(h.body), "message"); if (!m.isEmpty()) return m; } catch (RuntimeException ignored) { }
            if (h.status == 401) return "Sign-in expired. Try again.";
            if (h.status == 404) return "Not found. It may already be gone.";
            if (h.status == 429) return "Too many attempts. Wait a minute.";
        }
        return "Couldn’t reach the Backstage server. Check your internet.";
    }
}
