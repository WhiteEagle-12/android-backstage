/**
 * Zero-dependency S3 / Cloudflare R2 SigV4 uploader & presigned GET generator.
 *
 * Keeps large encrypted file transfers (APKs, MP4s, backups) off GCP egress by
 * putting opaque BSF1 ciphertext directly into Cloudflare R2 ($0 egress) and
 * returning a short-lived presigned GET URL to the phone.
 *
 * Enabled only when RELAY_R2_ENDPOINT, RELAY_R2_BUCKET, RELAY_R2_ACCESS_KEY_ID,
 * and RELAY_R2_SECRET_ACCESS_KEY are set.
 */
import crypto from "node:crypto";
import fs from "node:fs";

const sha256Hex = (data) => crypto.createHash("sha256").update(data).digest("hex");
const hmac = (key, data) => crypto.createHmac("sha256", key).update(data, "utf8").digest();

function rfc3986(value) {
  return encodeURIComponent(String(value)).replace(/[!'()*]/g, (c) => "%" + c.charCodeAt(0).toString(16).toUpperCase());
}

function canonicalPath(key) {
  return "/" + String(key).split("/").map(rfc3986).join("/");
}

function signingKey(secretAccessKey, dateStamp, region, service) {
  const kDate = hmac("AWS4" + secretAccessKey, dateStamp);
  const kRegion = hmac(kDate, region);
  const kService = hmac(kRegion, service);
  return hmac(kService, "aws4_request");
}

function amzTimestamps(now = new Date()) {
  const iso = now.toISOString().replace(/[:-]|\.\d{3}/g, "");
  return { amzDate: iso, dateStamp: iso.slice(0, 8) };
}

export function createR2Store({
  endpoint,
  bucket,
  accessKeyId,
  secretAccessKey,
  region = "auto",
  prefix = "relay-files/",
  fetchImpl = fetch,
  now = () => new Date(),
}) {
  const base = new URL(endpoint);
  const cleanPrefix = prefix ? (prefix.endsWith("/") ? prefix : prefix + "/") : "";

  function objectPath(key) {
    return canonicalPath(`${bucket}/${key}`);
  }

  function presignGet(key, ttlSec = 900) {
    const expires = Math.max(60, Math.min(86400, Math.round(ttlSec)));
    const { amzDate, dateStamp } = amzTimestamps(now());
    const scope = `${dateStamp}/${region}/s3/aws4_request`;
    const pathname = objectPath(key);
    const params = [
      ["X-Amz-Algorithm", "AWS4-HMAC-SHA256"],
      ["X-Amz-Credential", `${accessKeyId}/${scope}`],
      ["X-Amz-Date", amzDate],
      ["X-Amz-Expires", String(expires)],
      ["X-Amz-SignedHeaders", "host"],
    ].sort((a, b) => a[0].localeCompare(b[0]));
    const canonicalQuery = params.map(([k, v]) => `${rfc3986(k)}=${rfc3986(v)}`).join("&");
    const canonicalHeaders = `host:${base.host}\n`;
    const canonicalRequest = ["GET", pathname, canonicalQuery, canonicalHeaders, "host", "UNSIGNED-PAYLOAD"].join("\n");
    const stringToSign = ["AWS4-HMAC-SHA256", amzDate, scope, sha256Hex(canonicalRequest)].join("\n");
    const sig = crypto.createHmac("sha256", signingKey(secretAccessKey, dateStamp, region, "s3")).update(stringToSign, "utf8").digest("hex");
    return `${base.origin}${pathname}?${canonicalQuery}&X-Amz-Signature=${sig}`;
  }

  async function putBuffer(key, body, contentType = "application/octet-stream") {
    const { amzDate, dateStamp } = amzTimestamps(now());
    const scope = `${dateStamp}/${region}/s3/aws4_request`;
    const pathname = objectPath(key);
    const payloadHash = sha256Hex(body);
    const headers = {
      host: base.host,
      "x-amz-content-sha256": payloadHash,
      "x-amz-date": amzDate,
    };
    const signedKeys = Object.keys(headers).sort();
    const canonicalHeaders = signedKeys.map((k) => `${k}:${headers[k]}\n`).join("");
    const signedHeaders = signedKeys.join(";");
    const canonicalRequest = ["PUT", pathname, "", canonicalHeaders, signedHeaders, payloadHash].join("\n");
    const stringToSign = ["AWS4-HMAC-SHA256", amzDate, scope, sha256Hex(canonicalRequest)].join("\n");
    const sig = crypto.createHmac("sha256", signingKey(secretAccessKey, dateStamp, region, "s3")).update(stringToSign, "utf8").digest("hex");
    const authHeader = `AWS4-HMAC-SHA256 Credential=${accessKeyId}/${scope}, SignedHeaders=${signedHeaders}, Signature=${sig}`;
    const res = await fetchImpl(`${base.origin}${pathname}`, {
      method: "PUT",
      headers: {
        "content-type": contentType,
        "x-amz-content-sha256": payloadHash,
        "x-amz-date": amzDate,
        authorization: authHeader,
      },
      body,
      signal: AbortSignal.timeout(60_000),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw Object.assign(new Error(`R2 upload failed (HTTP ${res.status}): ${text.slice(0, 160)}`), { status: 502 });
    }
  }

  async function uploadFile(localPath, { ttlSec = 900, contentType = "application/octet-stream" } = {}) {
    const id = crypto.randomBytes(32).toString("hex");
    const key = `${cleanPrefix}${id}`;
    const body = fs.readFileSync(localPath);
    await putBuffer(key, body, contentType);
    const url = presignGet(key, ttlSec);
    return { id, url, expiresAt: now().getTime() + ttlSec * 1000 };
  }

  return { presignGet, putBuffer, uploadFile };
}

export function createR2FromEnv(env = process.env) {
  const endpoint = (env.RELAY_R2_ENDPOINT || "").trim();
  const bucket = (env.RELAY_R2_BUCKET || "").trim();
  const accessKeyId = (env.RELAY_R2_ACCESS_KEY_ID || "").trim();
  const secretAccessKey = (env.RELAY_R2_SECRET_ACCESS_KEY || "").trim();
  if (!endpoint || !bucket || !accessKeyId || !secretAccessKey) return null;
  return createR2Store({
    endpoint,
    bucket,
    accessKeyId,
    secretAccessKey,
    region: (env.RELAY_R2_REGION || "auto").trim(),
    prefix: (env.RELAY_R2_PREFIX || "relay-files/").trim(),
  });
}
