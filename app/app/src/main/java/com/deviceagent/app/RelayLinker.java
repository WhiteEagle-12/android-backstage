package com.deviceagent.app;

import com.deviceagent.crypto.LinkPairing;
import com.deviceagent.rpc.SimpleHttp;
import com.deviceagent.tools.Json;
import java.util.Map;

/** A verified link is independent of whether the local phone helper has started. */
public final class RelayLinker {
    public interface Transport {
        String request(String method, String url, String body, int timeout, String token) throws Exception;
    }
    public static final Transport HTTP = (method, url, body, timeout, token) ->
            SimpleHttp.requestNoRedirect(method, url, body, timeout, token);
    public static final class Result {
        public final String relay, token, name, deviceId, agentId, linkKey;
        Result(String relay, String token, String name, String deviceId, String agentId, String linkKey) {
            this.relay = relay; this.token = token; this.name = name; this.deviceId = deviceId;
            this.agentId = agentId; this.linkKey = linkKey;
        }
    }
    private RelayLinker() {}

    public static Result connect(String server, String typed, String oldServer, String oldToken,
                                 String model, int sdk, Transport transport) throws Exception {
        String relay = SetupRules.relayUrl(server);
        String code = LinkPairing.normalizeCode(typed);
        String session = LinkPairing.sessionId(code);
        com.deviceagent.adb.Spake2 bob = LinkPairing.bob();
        byte[] msg = bob.generateMsg(LinkPairing.passwordBytes(code));
        String token = null;
        if (oldServer != null && !oldServer.isBlank() && SetupRules.sameServer(relay, oldServer)) token = oldToken;
        String base = relay + "/v1/pair/session/" + session;
        Map<String, Object> claimed = Json.object(transport.request("POST", base + "/device",
                Json.stringify(Json.mapOf("msg", LinkPairing.b64url(msg), "model", model,
                        "sdk", sdk, "agentVersion", BuildInfo.VERSION)), 20000, token));
        String newToken = required(claimed, "deviceToken");
        String agent = required(claimed, "agentId");
        byte[] key = LinkPairing.linkKey(bob.processMsg(LinkPairing.unb64url(required(claimed, "agentMsg"))));
        transport.request("PUT", base + "/device-confirm",
                Json.stringify(Json.mapOf("confirm", LinkPairing.confirmBB64(key))), 15000, newToken);
        for (int i = 0; i < 3; i++) {
            String raw = transport.request("GET", base + "/confirm", null, 25000, newToken);
            Map<String, Object> reply = Json.object(raw.isEmpty() ? "{}" : raw);
            if (Boolean.TRUE.equals(reply.get("pending"))) continue;
            if (!LinkPairing.confirmEquals(LinkPairing.confirmAB64(key), Json.string(reply, "confirm")))
                throw new IllegalStateException("Confirmation mismatch");
            return new Result(relay, newToken, Json.string(claimed, "name"),
                    Json.string(claimed, "deviceId"), agent, LinkPairing.b64url(key));
        }
        throw new IllegalStateException("Confirmation timed out");
    }
    private static String required(Map<String, Object> map, String key) {
        String value = Json.string(map, key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Incomplete pairing response");
        return value;
    }
}
