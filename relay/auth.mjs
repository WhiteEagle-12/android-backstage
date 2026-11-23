/** Bearer-token helpers for API keys, device tokens, and the optional admin token. */
import crypto from "node:crypto";

export function bearerToken(req) {
  const header = String(req.headers.authorization || "");
  const match = /^Bearer\s+(\S+)/i.exec(header);
  return match ? match[1] : "";
}

export class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
    this.name = "HttpError";
  }
}

export function requireAccount(req, store) {
  const token = bearerToken(req);
  const account = store.accountByKey(token);
  if (account) return account;
  const connection = store.connectionByKey(token);
  const path = new URL(req.url, 'http://localhost').pathname;
  const allowed = (req.method === 'GET' && ['/v1/me','/v1/devices','/v1/events','/v1/events/stream'].includes(path)) ||
    (req.method === 'POST' && ['/v1/call','/v1/jobs','/v1/files','/v1/uploads'].includes(path)) ||
    (req.method === 'DELETE' && path === '/v1/connection') ||
    (/^\/v1\/jobs\/[a-zA-Z0-9_-]+$/.test(path) && ['GET','DELETE'].includes(req.method)) ||
    (/^\/v1\/uploads\/[a-f0-9]{32}(?:\/complete)?$/.test(path) && ['GET','PUT','POST'].includes(req.method));
  if (!connection || !allowed) throw new HttpError(401, "unauthorized");
  return {...connection, name:'phone', connection:true};
}

export function requireDeviceAuth(req, store) {
  const token = bearerToken(req);
  const device = store.deviceByToken(token);
  if (!device) throw new HttpError(401, "unauthorized");
  return device;
}

export function requireAdmin(req, adminToken) {
  if (!adminToken) throw new HttpError(404, "admin api disabled");
  const given = crypto.createHash("sha256").update(bearerToken(req), "utf8").digest();
  const expected = crypto.createHash("sha256").update(String(adminToken), "utf8").digest();
  if (!crypto.timingSafeEqual(given, expected)) throw new HttpError(401, "unauthorized");
}
