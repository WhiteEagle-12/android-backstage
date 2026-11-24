/**
 * MCP stdio server: newline-delimited JSON-RPC on stdin/stdout.
 * Logs go to stderr only.
 */
import crypto from "node:crypto";
import readline from "node:readline";
import { VERSION, INSTRUCTIONS, mcpTools, asToolResult } from "./tools.mjs";

const ALWAYS_WRITE = new Set([
  "open", "tap", "type", "key", "gesture", "scroll", "scroll_until", "sequence",
  "install", "shell", "confirm", "handoff", "developer", "maintenance",
  "push", "fetch", "task_begin", "task_finish", "task_share", "media",
]);

function needsAutoRequestId(name, args) {
  const short = String(name || "").replace(/^android[_.]/, "");
  if (ALWAYS_WRITE.has(short)) return true;
  const op = args?.operation;
  if (short === "task") return ["begin", "finish", "share", "handoff", "record_start", "record_stop"].includes(op);
  if (short === "file") return op === "push" || op === "fetch";
  if (short === "artifact") return op === "begin" || op === "delete";
  if (short === "notification") return ["open", "action", "reply", "dismiss"].includes(op);
  if (short === "personal") return op === "alarm" || op === "timer";
  if (short === "events") return ["watch", "set_rules", "webhook_set", "webhook_clear"].includes(op);
  if (short === "record") return op === "start" || op === "stop";
  if (short === "clipboard") return args?.text !== undefined;
  if (short === "device") {
    if (op === "media") return true;
    if (op === "clipboard") return args?.text !== undefined;
    if (op === "control" || op === "audio") return args?.action && args.action !== "status";
  }
  return false;
}

function writeReply(msg) {
  process.stdout.write(`${JSON.stringify(msg)}\n`);
}

function mcpResult(id, result) {
  return { jsonrpc: "2.0", id, result };
}

function mcpError(id, message, code = -32000) {
  return { jsonrpc: "2.0", id, error: { code, message } };
}

export function initializeResult(params, deviceName) {
  return {
    protocolVersion: params?.protocolVersion || "2024-11-05",
    capabilities: {
      tools: { listChanged: false },
      prompts: { listChanged: false },
    },
    serverInfo: {
      name: deviceName ? `backstage-${deviceName}` : "backstage",
      version: VERSION,
    },
    instructions:
      INSTRUCTIONS +
      (deviceName
        ? `\n\nThis MCP session is locked to device "${deviceName}". Do not use tools from another device MCP.`
        : ""),
  };
}

export async function handleMcpMessage(msg, { deviceName, callTool }) {
  const { id, method, params } = msg;
  if (method && String(method).startsWith("notifications/")) return null;
  if (method === "initialize") return mcpResult(id, initializeResult(params, deviceName));
  if (method === "ping") return mcpResult(id, {});
  if (method === "tools/list") return mcpResult(id, { tools: mcpTools({developer:process.env.BACKSTAGE_DEVELOPER_TOOLS === "1"}) });
  if (method === "prompts/list") {
    return mcpResult(id, {
      prompts: [
        {
          name: "phone-operating-rules",
          description: "Background-first rules; this stdio session is locked to one device",
          arguments: [],
        },
      ],
    });
  }
  if (method === "prompts/get") {
    const extra = deviceName ? `\nLocked device: ${deviceName}` : "";
    return mcpResult(id, {
      description: "Android Backstage operating rules",
      messages: [{ role: "user", content: { type: "text", text: INSTRUCTIONS + extra } }],
    });
  }
  if (method === "tools/call") {
    const name = params?.name;
    let args = params?.arguments || {};
    if (args && typeof args === "object" && !Array.isArray(args) && args.requestId === undefined && needsAutoRequestId(name, args)) {
      args = { ...args, requestId: "auto_" + crypto.randomBytes(12).toString("hex") };
    }
    try {
      const payload = await callTool(name, args);
      return { jsonrpc: "2.0", id, result: asToolResult(payload, false) };
    } catch (err) {
      const message = err?.message || String(err);
      return { jsonrpc: "2.0", id, result: asToolResult({ error: message }, true) };
    }
  }
  return mcpError(id, `unknown method: ${method}`);
}

export async function runMcpStdio({ deviceName, callTool, stdin = process.stdin, stdout = process.stdout }) {
  const rl = readline.createInterface({ input: stdin, crlfDelay: Infinity });
  const write = (msg) => {
    stdout.write(`${JSON.stringify(msg)}\n`);
  };
  for await (const line of rl) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    let msg;
    try {
      msg = JSON.parse(trimmed);
    } catch {
      write({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "invalid JSON" } });
      continue;
    }
    if (!msg || typeof msg !== "object" || Array.isArray(msg)) {
      write({ jsonrpc: "2.0", id: null, error: { code: -32600, message: "invalid request" } });
      continue;
    }
    try {
      const reply = await handleMcpMessage(msg, { deviceName, callTool });
      if (reply) write(reply);
    } catch (err) {
      process.stderr.write(`${err?.message || err}\n`);
      if (msg.id !== undefined) {
        write(mcpError(msg.id, err?.message || String(err)));
      }
    }
  }
}

void writeReply;
