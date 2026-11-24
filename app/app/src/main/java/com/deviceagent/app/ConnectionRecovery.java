package com.deviceagent.app;

import com.deviceagent.crypto.LocalAuth;
import com.deviceagent.rpc.SimpleHttp;
import com.deviceagent.tools.Json;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.util.Map;

/** Identifies port conflicts and proves daemon identity before sending any administrative request. */
public final class ConnectionRecovery {
    private ConnectionRecovery() {}
    public static boolean isBackstage(Map<String,Object> health) {
        return Boolean.TRUE.equals(health.get("ok")) && "backstage".equals(health.get("service"));
    }
    /** Fetch /health with a fresh HMAC challenge and verify the peer knows localToken. */
    public static Map<String, Object> verifiedHealth(int port, String localToken, int timeoutMs) throws java.io.IOException {
        String challenge = LocalAuth.challenge();
        String raw = SimpleHttp.requestOnceWithHeaders(
                "GET", "http://127.0.0.1:" + port + "/health", null, timeoutMs,
                Map.of("X-Backstage-Challenge", challenge));
        Map<String, Object> health = Json.object(raw);
        if (!isBackstage(health)) return null;
        if (localToken != null && !localToken.isBlank()) {
            String proof = Json.string(health, "proof");
            if (!LocalAuth.verifyDaemonProof(localToken, challenge, proof)) return null;
        }
        return health;
    }
    public static boolean foreignService(int port) throws java.io.IOException {
        return foreignService(port, null);
    }
    public static boolean foreignService(int port, String localToken) throws java.io.IOException {
        try {
            if (localToken != null && !localToken.isBlank()) {
                return verifiedHealth(port, localToken, 1200) == null;
            }
            return !isBackstage(Json.object(SimpleHttp.getOnce("http://127.0.0.1:"+port+"/health",1200,null)));
        }
        catch(java.net.ConnectException e) { return false; }
        catch(java.net.SocketTimeoutException e) { return false; }
        catch(SimpleHttp.HttpException e) { return true; }
        catch(IllegalArgumentException e) { return true; }
    }
    public static int availablePort(int current) throws java.io.IOException {
        for(int offset=1;offset<=32;offset++) {
            int candidate=18422+offset;
            if(candidate==current)continue;
            try(ServerSocket socket=new ServerSocket()) {
                socket.setReuseAddress(false);socket.bind(new InetSocketAddress(candidate));return candidate;
            }catch(java.net.BindException ignored){}
        }
        throw new java.io.IOException("No free local connection port");
    }
}
