import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, it } from "node:test";
import {
  linkKey,
  callKey,
  resultKey,
  sealEnvelope,
  openEnvelope,
  encryptBytes,
  decryptBytes,
  FILE_CHUNK,
} from "../crypto.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

describe("crypto", () => {
  it("derives keys, seals an envelope, and encrypts a BSF1 blob (fixed vectors)", () => {
    const lk = Buffer.from("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20", "hex");
    const ck = callKey(lk);
    const rk = resultKey(lk);
    const derivedLink = linkKey(Buffer.alloc(64, 0x5a));
    const plaintext =
      '{"id":"t1","name":"android.shell","arguments":{"command":"id"},"timeoutMs":12000}';
    const envelope = sealEnvelope({
      key: ck,
      from: "agt_0123456789abcdef",
      n: "1700000000000",
      plaintext,
    });
    const opened = openEnvelope({ key: ck, envelope });
    assert.equal(opened.toString("utf8"), plaintext);

    const fileKey = Buffer.alloc(32, 0x11);
    const filePlain = Buffer.from("hello backstage", "utf8");
    const { blob, sha256 } = encryptBytes(filePlain, fileKey);
    const dec = decryptBytes(blob, fileKey);
    assert.equal(dec.plaintext.toString("utf8"), "hello backstage");
    assert.equal(dec.sha256, sha256);
    assert.equal(blob.subarray(0, 4).toString("ascii"), "BSF1");
    assert.equal(blob.readUInt32BE(4), FILE_CHUNK);

    const vectors = {
      linkKey: lk.toString("hex"),
      callKey: ck.toString("hex"),
      resultKey: rk.toString("hex"),
      linkKeyFromAll5aSpakeK: derivedLink.toString("hex"),
      envelope,
      envelopePlaintext: plaintext,
      from: "agt_0123456789abcdef",
      n: "1700000000000",
      file: {
        fileKey: fileKey.toString("hex"),
        plaintext: "hello backstage",
        sha256,
        chunkSize: FILE_CHUNK,
        blobHex: blob.toString("hex"),
      },
    };

    const outDir = path.join(__dirname, "vectors");
    fs.mkdirSync(outDir, { recursive: true });
    const outPath = path.join(outDir, "e2e-vectors.json");
    fs.writeFileSync(outPath, `${JSON.stringify(vectors, null, 2)}\n`);

    console.log("=== Android Backstage e2e vectors (for the Android engineer) ===");
    console.log(JSON.stringify(vectors, null, 2));
    console.log(`wrote ${outPath}`);
  });

  it("round-trips a multi-chunk file with a tiny chunk size", () => {
    const fileKey = Buffer.alloc(32, 0x22);
    const plain = Buffer.alloc(50, 0x61);
    const { blob, sha256 } = encryptBytes(plain, fileKey, 16);
    const dec = decryptBytes(blob, fileKey);
    assert.ok(dec.plaintext.equals(plain));
    assert.equal(dec.sha256, sha256);
  });
});
