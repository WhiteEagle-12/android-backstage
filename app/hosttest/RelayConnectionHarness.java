package com.deviceagent.app;

import com.deviceagent.rpc.SimpleHttp;
import com.deviceagent.tools.Json;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Test driver: real phone pairing code, with transport confined to a loopback test relay. */
public final class RelayConnectionHarness {
    public static void main(String[] args) throws Exception {
        Map<String,Object> input = Json.object(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
        String target = Json.string(input,"url");
        if (!target.startsWith("http://127.0.0.1:")) throw new IllegalArgumentException("Loopback test relay required");
        RelayLinker.Result result = RelayLinker.connect("https://test.example", Json.string(input,"code"),
                "https://test.example", Json.string(input,"token"), "Java test phone", 35,
                (method,url,body,timeout,token) -> SimpleHttp.requestNoRedirect(method,
                        target + url.substring("https://test.example".length()),body,timeout,token));
        System.out.println(Json.stringify(Json.mapOf("deviceId",result.deviceId,"deviceToken",result.token,
                "agentId",result.agentId,"linkKey",result.linkKey)));
    }
}
