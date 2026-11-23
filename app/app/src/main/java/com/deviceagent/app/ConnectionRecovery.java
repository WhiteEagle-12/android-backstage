package com.deviceagent.app;

import com.deviceagent.rpc.SimpleHttp;
import com.deviceagent.tools.Json;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.util.Map;

/** Identifies port conflicts before sending any administrative request. */
public final class ConnectionRecovery {
    private ConnectionRecovery() {}
    public static boolean isBackstage(Map<String,Object> health) {
        return Boolean.TRUE.equals(health.get("ok")) && "backstage".equals(health.get("service"));
    }
    public static boolean foreignService(int port) throws java.io.IOException {
        try { return !isBackstage(Json.object(SimpleHttp.getOnce("http://127.0.0.1:"+port+"/health",1200,null))); }
        catch(java.net.ConnectException e) { return false; }
        catch(java.net.SocketTimeoutException e) { return true; }
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
