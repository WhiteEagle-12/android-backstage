/**
 * JSON state for accounts, devices, and pairing sessions.
 * Tokens are stored as SHA-256 hashes; plaintext is shown once at mint time.
 */
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { writePrivateJson } from "./persist.mjs";

export const SESSION_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789";
export const SESSION_TTL_MS = 10 * 60 * 1000;
export const OPAQUE_MAX = 1024;

export function nowIso() {
  return new Date().toISOString();
}

export function randomToken(prefix) {
  return `${prefix}${crypto.randomBytes(32).toString("base64url")}`;
}

export function hashToken(token) {
  return crypto.createHash("sha256").update(String(token), "utf8").digest("hex");
}

export function hashesEqual(a, b) {
  const left = Buffer.from(String(a || ""), "utf8");
  const right = Buffer.from(String(b || ""), "utf8");
  if (left.length !== right.length || left.length === 0) return false;
  return crypto.timingSafeEqual(left, right);
}

function emptyState() {
  return { accounts: {}, devices: {}, sessions: {}, connections: {} };
}

function randomSessionId() {
  let id = "";
  for (let i = 0; i < 4; i += 1) {
    id += SESSION_ALPHABET[crypto.randomInt(SESSION_ALPHABET.length)];
  }
  return id;
}

export function createStore(dataDir, { log = console } = {}) {
  fs.mkdirSync(dataDir, { recursive: true });
  const statePath = path.join(dataDir, "state.json");
  let state = emptyState();
  if (fs.existsSync(statePath)) {
    try {
      const parsed = JSON.parse(fs.readFileSync(statePath, "utf8"));
      state = {
        accounts: parsed.accounts && typeof parsed.accounts === "object" ? parsed.accounts : {},
        devices: parsed.devices && typeof parsed.devices === "object" ? parsed.devices : {},
        sessions: parsed.sessions && typeof parsed.sessions === "object" ? parsed.sessions : {},
        connections: parsed.connections || {},
      };
    } catch (err) {
      throw new Error(`failed to read ${statePath}: ${err.message}`);
    }
  }

  // Remove the legacy phone administration credential from existing relay state.
  let migrated = false;
  for (const device of Object.values(state.devices)) {
    if (Object.hasOwn(device, 'localToken')) { delete device.localToken; migrated = true; }
  }
  let firstApiKey = null;
  // O(1) hash indices kept in sync with state; hashesEqual still runs on match.
  const accountsByHash = new Map();
  const devicesByHash = new Map();
  const connectionsByHash = new Map();

  function rebuildIndices() {
    accountsByHash.clear();
    devicesByHash.clear();
    connectionsByHash.clear();
    for (const a of Object.values(state.accounts)) if (a?.keyHash) accountsByHash.set(a.keyHash, a.id);
    for (const d of Object.values(state.devices)) if (d?.tokenHash) devicesByHash.set(d.tokenHash, d.id);
    for (const c of Object.values(state.connections)) if (c?.keyHash) connectionsByHash.set(c.keyHash, c.id);
  }
  rebuildIndices();

  function persist() {
    writePrivateJson(statePath, state);
  }

  if (migrated) persist();

  if (Object.keys(state.accounts).length === 0) {
    const apiKey = randomToken("bsk_");
    const keyHash = hashToken(apiKey);
    state.accounts.default = {
      id: "default",
      name: "default",
      keyHash,
      createdAt: nowIso(),
    };
    accountsByHash.set(keyHash, "default");
    persist();
    const keyPath = path.join(dataDir, "first-api-key.txt");
    fs.writeFileSync(keyPath, `${apiKey}\n`, { mode: 0o600 });
    try {
      fs.chmodSync(keyPath, 0o600);
    } catch {
      /* best-effort on platforms that ignore mode */
    }
    firstApiKey = apiKey;
    log.log(`Android Backstage first API key (shown once):\n${apiKey}\nWrote ${keyPath}`);
  }

  function sweepSessions() {
    const now = Date.now();
    let changed = false;
    for (const [id, session] of Object.entries(state.sessions)) {
      if (!session?.expiresAt || Date.parse(session.expiresAt) <= now) {
        if (session.connectionId && !session.agentConfirm && session.deviceId && !session.existingDevice) {
          const dev = state.devices[session.deviceId];
          if (dev?.tokenHash) devicesByHash.delete(dev.tokenHash);
          delete state.devices[session.deviceId];
          if (!Object.values(state.devices).some(d => d.accountId === session.accountId)) {
            const acct = state.accounts[session.accountId];
            if (acct?.keyHash) accountsByHash.delete(acct.keyHash);
            delete state.accounts[session.accountId];
          }
        }
        delete state.sessions[id];
        changed = true;
      }
    }
    if (changed) persist();
  }

  function createConnection(name, clientIp = null) {
    sweepSessions();
    const pendingConnects = Object.values(state.sessions).filter(s => s.connectionId);
    if (pendingConnects.length >= 512)
      throw Object.assign(new Error("Too many pending connections. Try again shortly."), {status:429});
    if (clientIp && pendingConnects.filter(s => s.clientIp === clientIp).length >= 8)
      throw Object.assign(new Error("Too many pending connections from this address. Try again shortly."), {status:429});
    const token = randomToken("bsc_");
    const id = `conn_${crypto.randomBytes(16).toString("hex")}`;
    const pairing = createSession({accountId:id, name});
    const session = state.sessions[pairing.sessionId];
    session.connectionId = id;
    session.connectionHash = hashToken(token);
    if (clientIp) session.clientIp = String(clientIp).slice(0, 64);
    persist();
    return {...pairing, token};
  }

  function connectionByKey(token) {
    if (!String(token || "").startsWith("bsc_")) return null;
    const hashed = hashToken(token);
    const id = connectionsByHash.get(hashed);
    const conn = id ? state.connections[id] : null;
    if (conn && hashesEqual(conn.keyHash, hashed) && state.devices[conn.deviceId]) return conn;
    return null;
  }

  function revokeConnection(deviceId, agentId) {
    for (const [id, connection] of Object.entries(state.connections)) {
      if (connection.deviceId === deviceId && connection.agentId === agentId) {
        if (connection.keyHash) connectionsByHash.delete(connection.keyHash);
        delete state.connections[id];
      }
    }
    const device = state.devices[deviceId];
    if (device) device.agents = (device.agents || []).filter(id => id !== agentId);
    persist();
  }

  function revokeConnectionById(connectionId) {
    const connection = state.connections[connectionId];
    if (!connection) return { revoked: false, droppedDeviceId: null };
    if (connection.keyHash) connectionsByHash.delete(connection.keyHash);
    delete state.connections[connectionId];
    const device = state.devices[connection.deviceId];
    let droppedDeviceId = null;
    if (device) {
      device.agents = (device.agents || []).filter(id => id !== connection.agentId);
      const acct = state.accounts[device.accountId];
      const remainingConns = Object.values(state.connections).some(c => c.deviceId === device.id);
      if (acct?.name === "phone" && !remainingConns && device.agents.length === 0) {
        if (device.tokenHash) devicesByHash.delete(device.tokenHash);
        delete state.devices[device.id];
        droppedDeviceId = device.id;
        if (!Object.values(state.devices).some(d => d.accountId === acct.id)) {
          if (acct.keyHash) accountsByHash.delete(acct.keyHash);
          delete state.accounts[acct.id];
        }
      }
    }
    persist();
    return { revoked: true, droppedDeviceId };
  }

  function approveConnection(session, confirm) {
    session.agentConfirm = confirm;
    if (session.connectionId) {
      state.connections[session.connectionId] = {id:session.connectionId, keyHash:session.connectionHash,
        deviceId:session.deviceId, agentId:session.agentId, createdAt:nowIso()};
      connectionsByHash.set(session.connectionHash, session.connectionId);
    }
    const device = state.devices[session.deviceId];
    if (device) device.agents = [...new Set([...(device.agents || []), session.agentId])];
    // The proof, permission and phone membership commit together.
    persist();
  }

  function accountById(id) {
    return state.accounts[id] || null;
  }

  function accountByKey(token) {
    if (!token || !String(token).startsWith("bsk_")) return null;
    const hashed = hashToken(token);
    const id = accountsByHash.get(hashed);
    const account = id ? state.accounts[id] : null;
    if (account && hashesEqual(account.keyHash, hashed)) return account;
    return null;
  }

  function createAccount({ name }) {
    const label = String(name || "").trim() || "account";
    const id = `acct_${crypto.randomBytes(8).toString("hex")}`;
    const apiKey = randomToken("bsk_");
    const keyHash = hashToken(apiKey);
    state.accounts[id] = {
      id,
      name: label,
      keyHash,
      createdAt: nowIso(),
    };
    accountsByHash.set(keyHash, id);
    persist();
    return { id, name: label, apiKey };
  }

  function rotateAccountKey(id) {
    const account=state.accounts[id];
    if(!account)throw Object.assign(new Error("unknown account"),{status:404});
    const apiKey=randomToken("bsk_");
    const nextHash=hashToken(apiKey);
    const updated={...account,keyHash:nextHash,rotatedAt:nowIso()};
    writePrivateJson(statePath,{...state,accounts:{...state.accounts,[id]:updated}});
    if(account.keyHash)accountsByHash.delete(account.keyHash);
    accountsByHash.set(nextHash,id);
    state.accounts[id]=updated;
    if(id==="default")fs.rmSync(path.join(dataDir,"first-api-key.txt"),{force:true});
    return {id,apiKey};
  }

  function devicesForAccount(accountId) {
    return Object.values(state.devices).filter((d) => d.accountId === accountId);
  }

  function normalizeName(value) {
    return String(value || "").trim().toLowerCase();
  }

  function findDevice(accountId, nameOrAlias) {
    const key = normalizeName(nameOrAlias);
    if (!key) return null;
    const devices = devicesForAccount(accountId);
    const matches = devices.filter(d => normalizeName(d.id) === key ||
      normalizeName(d.name) === key || (d.aliases || []).some(alias => normalizeName(alias) === key));
    if (matches.length > 1) throw Object.assign(
      new Error(`ambiguous device "${nameOrAlias}"; use a unique device ID. Not using another device.`), {status:409});
    return matches[0] || null;
  }

  function nameTaken(accountId, name, exceptId = null) {
    const key = normalizeName(name);
    if (!key) return false;
    for (const d of devicesForAccount(accountId)) {
      if (exceptId && d.id === exceptId) continue;
      if (normalizeName(d.id) === key) return true;
      if (normalizeName(d.name) === key) return true;
      for (const alias of d.aliases || []) {
        if (normalizeName(alias) === key) return true;
      }
    }
    return false;
  }

  function uniqueDeviceName(accountId, base = "phone") {
    const raw = String(base || "").trim() || "phone";
    if (!nameTaken(accountId, raw)) return raw;
    for (let i = 2; i < 10_000; i += 1) {
      const candidate = `${raw}-${i}`;
      if (!nameTaken(accountId, candidate)) return candidate;
    }
    throw new Error("could not allocate a unique device name");
  }

  function createSession({ accountId, name }) {
    sweepSessions();
    if (Object.values(state.sessions).filter(s => s.accountId === accountId).length >= 8) {
      throw Object.assign(new Error("pairing session quota exceeded"), {status:429});
    }
    const assigned = uniqueDeviceName(accountId, name || "phone");
    let id = "";
    for (let i = 0; i < 40; i += 1) {
      id = randomSessionId();
      if (!state.sessions[id]) break;
    }
    if (state.sessions[id]) throw new Error("could not allocate a session id");
    const expiresAt = new Date(Date.now() + SESSION_TTL_MS).toISOString();
    state.sessions[id] = {
      id,
      accountId,
      name: assigned,
      expiresAt,
      agentId: null,
      agentMsg: null,
      deviceMsg: null,
      deviceConfirm: null,
      agentConfirm: null,
      deviceId: null,
      claimed: false,
    };
    persist();
    return { sessionId: id, name: assigned, expiresAt };
  }

  function sessionById(id) {
    sweepSessions();
    return state.sessions[String(id || "").trim()] || null;
  }

  function sessionByDeviceId(deviceId) {
    sweepSessions();
    for (const session of Object.values(state.sessions)) {
      if (session.deviceId === deviceId) return session;
    }
    return null;
  }

  function updateSession(id, patch) {
    const session = state.sessions[id];
    if (!session) return null;
    Object.assign(session, patch);
    persist();
    return session;
  }

  function deleteSession(id) {
    const session = state.sessions[id];
    if (!session) return false;
    if (session.connectionId && !session.agentConfirm && !session.existingDevice) {
      if (session.deviceId) {
        const dev = state.devices[session.deviceId];
        if (dev?.tokenHash) devicesByHash.delete(dev.tokenHash);
        delete state.devices[session.deviceId];
      }
      if (!Object.values(state.devices).some(d => d.accountId === session.accountId)) {
        const acct = state.accounts[session.accountId];
        if (acct?.keyHash) accountsByHash.delete(acct.keyHash);
        delete state.accounts[session.accountId];
      }
    }
    delete state.sessions[id];
    persist();
    return true;
  }

  function createDevice({ accountId, name, token, model, sdk, agentVersion, agents }) {
    if (devicesForAccount(accountId).length >= 25) throw Object.assign(new Error("device quota exceeded"), {status:429});
    const id = `dev_${crypto.randomBytes(12).toString("hex")}`;
    const tokenHash = hashToken(token);
    const device = {
      id,
      accountId,
      name,
      aliases: [],
      tokenHash,
      model: model || null,
      sdk: sdk ?? null,
      agentVersion: agentVersion || null,
      inboundUrl: null,

      capabilities: null,
      lanAddresses: null,
      agents: Array.isArray(agents) ? [...agents] : [],
      createdAt: nowIso(),
      lastSeen: null,
    };
    state.devices[id] = device;
    devicesByHash.set(tokenHash, id);
    persist();
    return device;
  }

  function deviceById(id) {
    return state.devices[id] || null;
  }

  function deviceByToken(token) {
    if (!token || !String(token).startsWith("bsd_")) return null;
    const hashed = hashToken(token);
    const id = devicesByHash.get(hashed);
    const device = id ? state.devices[id] : null;
    if (device && hashesEqual(device.tokenHash, hashed)) return device;
    return null;
  }

  function updateDevice(id, patch) {
    const device = state.devices[id];
    if (!device) return null;
    const oldHash = device.tokenHash;
    Object.assign(device, patch);
    if (patch.tokenHash && patch.tokenHash !== oldHash) {
      if (oldHash) devicesByHash.delete(oldHash);
      devicesByHash.set(patch.tokenHash, id);
    }
    persist();
    return device;
  }

  function touchDevice(id) {
    const device = deviceById(id);
    if (!device) return null;
    device.lastSeen = nowIso(); // Liveness is transient, not a whole-registry disk write per poll.
    return device;
  }

  function deleteDevice(id) {
    const device = state.devices[id];
    if (!device) return false;
    if (device.tokenHash) devicesByHash.delete(device.tokenHash);
    delete state.devices[id];
    for (const [key, connection] of Object.entries(state.connections)) {
      if (connection.deviceId === id) {
        if (connection.keyHash) connectionsByHash.delete(connection.keyHash);
        delete state.connections[key];
      }
    }
    persist();
    return true;
  }

  return {
    dataDir,
    createConnection, connectionByKey, approveConnection, revokeConnection, revokeConnectionById,
    firstApiKey,
    persist,
    snapshot: () => state,
    accountById,
    accountByKey,
    createAccount,
    rotateAccountKey,
    devicesForAccount,
    findDevice,
    nameTaken,
    uniqueDeviceName,
    createSession,
    sessionById,
    sessionByDeviceId,
    updateSession,
    deleteSession,
    sweepSessions,
    createDevice,
    deviceById,
    deviceByToken,
    updateDevice,
    touchDevice,
    deleteDevice,
  };
}
