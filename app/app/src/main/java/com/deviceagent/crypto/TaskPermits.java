package com.deviceagent.crypto;

import com.deviceagent.tools.Json;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Local-only authority. Scoped QA never exposes raw shell or arbitrary UI input. */
public final class TaskPermits {
    private final Path file;
    private final Map<String,Object> permits = new LinkedHashMap<>();
    public TaskPermits(String configPath) {
        file=java.nio.file.Paths.get(configPath).resolveSibling("permits.json");
        try { if(Files.exists(file)) permits.putAll(Json.object(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException("Cannot read task permissions",e); }
    }
    public synchronized void set(String agent, String mode, String pkg, long expiresAt) {
        if(agent==null||agent.isBlank()||!Set.of("full","paused","qa").contains(mode)) throw new IllegalArgumentException("invalid permit");
        if("qa".equals(mode) && (pkg==null||!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+") || expiresAt<=System.currentTimeMillis() || expiresAt>System.currentTimeMillis()+3600000L))
            throw new IllegalArgumentException("QA needs a package and an expiry within one hour");
        Map<String,Object> updated = new LinkedHashMap<>(permits);
        updated.put(agent,Json.mapOf("mode",mode,"package",pkg==null?"":pkg,"expiresAt",expiresAt));
        try {
            PrivateState.write(file,Json.stringify(updated));
            permits.clear(); permits.putAll(updated);
        } catch(Exception e){
            // A failed authority update must not leave a broader live permission.
            permits.put(agent,Json.mapOf("mode","paused","expiresAt",0));
            throw new IllegalStateException("Cannot save task permissions; agent paused in this process",e);
        }
    }

    public synchronized void authorize(String agent,String tool,Map<String,Object> args) {
        Object row=permits.get(agent); if(!(row instanceof Map))return; // Existing full-trust links retain explicit pairing authority.
        Map<?,?> p=(Map<?,?>)row;String mode=String.valueOf(p.get("mode"));
        if("full".equals(mode))return;
        Object exp=p.get("expiresAt");long until=exp instanceof Number?((Number)exp).longValue():0;
        if(!"qa".equals(mode)||until<=System.currentTimeMillis())throw new SecurityException("Agent paused or task permit expired");
        String pkg=String.valueOf(p.get("package"));
        String target;
        switch(tool){
            case "android.logs": case "android.packages": target=String.valueOf(args.get("package"));break;
            default: throw new SecurityException("Tool is outside this QA permit (shell and unrestricted input are disabled)");
        }
        if(!pkg.equals(target))throw new SecurityException("Package is outside this task permit");
    }
    public synchronized Map<String,Object> snapshot(){return new LinkedHashMap<>(permits);}
}
