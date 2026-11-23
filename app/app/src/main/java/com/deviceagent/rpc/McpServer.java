package com.deviceagent.rpc;

import com.deviceagent.app.AgentInstructions;
import com.deviceagent.app.BuildInfo;
import com.deviceagent.tools.AndroidTools;
import com.deviceagent.tools.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpServer {
    private final AndroidTools tools;

    public McpServer(AndroidTools tools) {
        this.tools = tools;
    }

    public String handleLine(String line) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        Object id = null;
        try {
            Map<String, Object> request = Json.object(line);
            id = request.get("id");
            String method = Json.string(request, "method");
            Object rawParams = request.get("params");
            Map<String, Object> params = rawParams instanceof Map ? (Map<String, Object>) rawParams : Map.of();
            if (method != null && method.startsWith("notifications/")) {
                return null;
            }
            response.put("id", id);
            response.put("result", dispatch(method, params));
        } catch (RuntimeException e) {
            response.put("id", id);
            response.put("error", Json.mapOf("code", -32000, "message", e.getMessage()));
        }
        return Json.stringify(response);
    }

    private Object dispatch(String method, Map<String, Object> params) {
        if (method == null) throw new IllegalArgumentException("method is required");
        return switch (method) {
            case "initialize" -> initialize();
            case "ping" -> Map.of();
            case "tools/list" -> Json.mapOf("tools", mcpTools());
            case "tools/call" -> call(params);
            case "prompts/list" -> Json.mapOf("prompts", List.of(Json.mapOf(
                    "name", "phone-operating-rules",
                    "description", "Fastest-path rules and hand-the-phone-back policy",
                    "arguments", List.of())));
            case "prompts/get" -> Json.mapOf(
                    "description", "Android Backstage operating rules",
                    "messages", List.of(Json.mapOf(
                            "role", "user",
                            "content", Json.mapOf("type", "text", "text", AgentInstructions.TEXT))));
            default -> throw new IllegalArgumentException("unknown method: " + method);
        };
    }

    private Map<String, Object> initialize() {
        return Json.mapOf(
                "protocolVersion", "2024-11-05",
                "capabilities", Json.mapOf(
                        "tools", Json.mapOf("listChanged", false),
                        "prompts", Json.mapOf("listChanged", false)),
                "serverInfo", Json.mapOf("name", "backstage", "version", BuildInfo.VERSION),
                "instructions", AgentInstructions.TEXT);
    }

    public List<Map<String, Object>> mcpTools() {
        List<Map<String, Object>> list = new ArrayList<>();
        tools.catalog().forEach((name, spec) -> {
            Map<String, Object> info = (Map<String, Object>) spec;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("description", info.get("description"));
            row.put("inputSchema", info.get("inputSchema"));
            list.add(row);
        });
        return list;
    }

    private Map<String, Object> call(Map<String, Object> params) {
        String name = Json.string(params, "name");
        Object raw = params.get("arguments");
        Map<String, Object> args = raw instanceof Map ? (Map<String, Object>) raw : Map.of();
        Object result = tools.call(name, args);
        return Json.mapOf(
                "content", List.of(Json.mapOf("type", "text", "text", Json.stringify(result))),
                "structuredContent", result,
                "isError", result instanceof Map && java.util.Set.of("failed","unknown").contains(((Map<?,?>)result).get("outcome")));
    }

}
