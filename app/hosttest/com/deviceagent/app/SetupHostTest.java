package com.deviceagent.app;

import com.deviceagent.crypto.LinkPairing;
import com.deviceagent.rpc.SimpleHttp;
import com.deviceagent.tools.Json;
import java.util.Map;

public final class SetupHostTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void run() throws Exception {
        com.sun.net.httpserver.HttpServer legacy=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicInteger shutdowns=new java.util.concurrent.atomic.AtomicInteger();
        legacy.createContext("/health",r -> {byte[] body="{\"ok\":true,\"service\":\"device-agent\",\"version\":\"0.4.5\"}".getBytes();r.sendResponseHeaders(200,body.length);r.getResponseBody().write(body);r.close();});
        legacy.createContext("/shutdown",r -> {shutdowns.incrementAndGet();r.sendResponseHeaders(200,-1);r.close();});legacy.start();
        try {
            int occupied=legacy.getAddress().getPort();
            check(ConnectionRecovery.foreignService(occupied),"old Device Agent must be a conflict, not an outdated Backstage");
            int available=ConnectionRecovery.availablePort(occupied);
            try(java.net.ServerSocket proof=new java.net.ServerSocket(available)){check(proof.isBound(),"replacement port is free");}
            check(shutdowns.get()==0,"must not shut down another app");
        }finally{legacy.stop(0);}
        check(ConnectionRecovery.isBackstage(Json.mapOf("ok",true,"service","backstage","version","old")),"old Backstage can be restarted");
        check(!ConnectionRecovery.isBackstage(Json.mapOf("ok",true,"service","device-agent")),"foreign service rejected");

        check(SetupRules.relayUrl(" Relay.Example.com/ ").equals("https://relay.example.com"), "normalize server");
        check(SetupRules.relayUrl("https://relay.example.com:443/").equals("https://relay.example.com"), "canonical HTTPS port");
        for (String bad : new String[]{"", "http://relay.example.com", "https://user:secret@relay.example.com", "https://relay.example.com?q=secret", "https://relay.example.com/#secret", "https://relay.example.com/path", "https://relay.example.com:99999"}) {
            try { SetupRules.relayUrl(bad); throw new AssertionError("Accepted invalid server: " + bad); }
            catch (IllegalArgumentException expected) { }
        }
        for (int mask=0; mask<8; mask++) check(SetupRules.shouldStart((mask&1)!=0,(mask&2)!=0,(mask&4)!=0)==(mask==7), "start needs consent, link and phone approval");
        check(SetupRules.accessButtonLabel(false,false,false).contains("About phone"), "dev-off routes to Build number");
        check(SetupRules.accessButtonLabel(true,false,false).contains("Wireless"), "wireless-off routes to Wireless debugging");
        check(SetupRules.accessButtonLabel(true,true,false).contains("Wireless"), "unpaired routes to Wireless debugging");
        Map<String,Object> h = Json.mapOf("ok",true,"service","backstage","version",BuildInfo.VERSION,"relay",Json.mapOf("connected",true));
        check(SetupRules.relayConnected(h), "current online daemon is ready");
        h.put("version","old"); check(!SetupRules.isCurrentDaemon(h) && !SetupRules.relayConnected(h), "old daemon cannot report ready");
        h.put("version",BuildInfo.VERSION); h.put("service","other"); check(!SetupRules.isCurrentDaemon(h), "wrong service");
        String error = SetupRules.linkError(new SimpleHttp.HttpException(403,"SECRET_TOKEN"));
        check(error.contains("new code") && !error.contains("SECRET_TOKEN"), "safe actionable HTTP error");
        check(!SetupRules.linkError(new IllegalStateException("SECRET_TOKEN")).contains("SECRET_TOKEN"), "safe unexpected error");
        check(SetupRules.connectionName("  ChatGPT Work  ").equals("ChatGPT Work"), "trim client name");
        for (String bad : new String[]{"", " ", "x".repeat(61), "bad\nname", "bad\u202ename"}) {
            try { SetupRules.connectionName(bad); throw new AssertionError("Invalid client name accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        SetupRules.SetupInput linkInput = SetupRules.parseSetupInput(
                "Open on your phone: androidbackstage://connect?relay=https%3A%2F%2Frelay.example.com&code=abcd-efghjk&name=Work+Mac");
        check(linkInput != null && "https://relay.example.com".equals(linkInput.server)
                && "ABCD-EFGHJK".equals(linkInput.code) && "Work Mac".equals(linkInput.clientName), "parse setup link");
        SetupRules.SetupInput codeInput = SetupRules.parseSetupInput("  abcd efghjk ");
        check(codeInput != null && codeInput.server.isEmpty() && "ABCD-EFGHJK".equals(codeInput.code), "parse bare code");
        check(SetupRules.parseSetupInput("not a code") == null, "reject invalid setup text");
        check(SetupRules.parseHostPort("192.168.1.5:39821", 0) == 39821, "parse ipv4:port");
        check(SetupRules.parseHostPort("[::1]:41234", 0) == 41234, "parse ipv6:port");
        check(SetupRules.parseHostPort("39821", 0) == 39821, "parse plain port");
        check(SetupRules.parseHostPort("99999", 0) == 0, "reject out of range port");
        check("123456".equals(SetupRules.normalizePairingDigits("123 456")), "normalize spaced digits");
        check("654321".equals(SetupRules.normalizePairingDigits("654-321")), "normalize dashed digits");
        check(SetupRules.normalizePairingDigits("12345").isEmpty(), "reject short pairing digits");
        check("refused".equals(SetupRules.classifyAdbError(new java.net.ConnectException("Connection refused"))), "classify refused");
        check("timeout".equals(SetupRules.classifyAdbError(new java.net.SocketTimeoutException("timed out"))), "classify timeout");
        check("auth_revoked".equals(SetupRules.classifyAdbError(new javax.net.ssl.SSLHandshakeException("bad_certificate"))), "classify tls auth");
        check("auth_revoked".equals(SetupRules.classifyAdbError(new java.io.EOFException())), "classify eof auth");
        check(SetupRules.isRelayAuthError("device token revoked; re-link in the app"), "detect relay token revoked");
        check(!SetupRules.isRelayAuthError("UnknownHostException: relay"), "ignore transient dns error");
        check(SetupRules.isRiskyUsbConfig("rndis"), "detect rndis usb config");
        check(SetupRules.isRiskyUsbConfig("rndis,adb"), "detect rndis+adb usb config");
        check(SetupRules.isRiskyUsbConfig("ncm"), "detect ncm usb config");
        check(!SetupRules.isRiskyUsbConfig("none"), "allow none usb config");
        check(!SetupRules.isRiskyUsbConfig("mtp"), "allow mtp usb config");
        check(!SetupRules.isRiskyUsbConfig(""), "allow empty usb config");
        rejectRedirect();
        longAddDevicePoll();
        exchange(false, false, false);
        exchange(true, false, false);
        exchange(false, true, false);
        exchange(false, false, true);
        System.out.println("PASS Setup: validation, pause intent, readiness, safe errors, verified linking with no running daemon");
    }
    private static void rejectRedirect() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicInteger forwarded = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/redirect", request -> {
            request.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/elsewhere");
            request.sendResponseHeaders(302,-1); request.close();
        });
        server.createContext("/elsewhere", request -> { forwarded.incrementAndGet(); request.sendResponseHeaders(200,-1); request.close(); });
        server.start();
        try {
            try { SimpleHttp.requestNoRedirect("GET", "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect", null, 1000, "private-token"); throw new AssertionError("Pairing followed a redirect"); }
            catch (SimpleHttp.HttpException expected) { check(expected.status==302,"redirect rejected"); }
            check(forwarded.get()==0,"credential-bearing pairing must stay on the approved server");
        } finally { server.stop(0); }
    }
    private static void longAddDevicePoll() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/work/phone/devices/add/poll", request -> {
            try {
                request.getRequestBody().readAllBytes();
                Thread.sleep(16000);
                byte[] body = "{\"pending\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                request.sendResponseHeaders(200, body.length);
                request.getResponseBody().write(body);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { request.close(); }
        });
        server.start();
        try {
            AccountClient client = new AccountClient("http://127.0.0.1:" + server.getAddress().getPort());
            check(client.pollAdd("ticket") == null, "add-phone polling must outlast the relay's long read");
        } finally { server.stop(0); }
    }
    private static void exchange(boolean sameServer, boolean mismatch, boolean pending) throws Exception {
        String code = "ABCD-EFGHJK";
        com.deviceagent.adb.Spake2 alice = LinkPairing.alice();
        byte[] aliceMsg = alice.generateMsg(LinkPairing.passwordBytes(code));
        final byte[][] key = {null}; final int[] claims={0}, polls={0};
        RelayLinker.Transport transport = (method,url,body,timeout,token) -> {
            check(!url.contains("127.0.0.1"), "link must not depend on a local daemon");
            if (method.equals("POST")) {
                claims[0]++;
                check(sameServer ? "old-token".equals(token) : token==null, "never send old token to another server");
                key[0] = LinkPairing.linkKey(alice.processMsg(LinkPairing.unb64url(Json.string(Json.object(body),"msg"))));
                return Json.stringify(Json.mapOf("deviceToken","new-token","name","phone","deviceId","device-id","agentId","agent-id","agentMsg",LinkPairing.b64url(aliceMsg)));
            }
            check("new-token".equals(token), "confirmation uses issued device token");
            if (method.equals("PUT")) {
                check(LinkPairing.confirmEquals(LinkPairing.confirmBB64(key[0]),Json.string(Json.object(body),"confirm")), "device confirms shared key");
                return "{}";
            }
            polls[0]++;
            return pending ? "{\"pending\":true}" : Json.stringify(Json.mapOf("confirm", mismatch ? "mismatch" : LinkPairing.confirmAB64(key[0])));
        };
        try {
            RelayLinker.Result result = RelayLinker.connect("https://relay.example.com",code,sameServer ? "https://relay.example.com/" : "https://old.example.com","old-token","Test",35,transport);
            check(!mismatch && !pending,"unverified link must fail");
            check(result.agentId.equals("agent-id") && result.token.equals("new-token") && result.linkKey.equals(LinkPairing.b64url(key[0])),"verified result retained without configure endpoint");
        } catch (IllegalStateException expected) { if (!mismatch && !pending) throw expected; }
        check(claims[0]==1,"single-use claim is not retried");
        check(polls[0]==(pending ? 3 : 1),"bounded confirmation polling");
    }
}
