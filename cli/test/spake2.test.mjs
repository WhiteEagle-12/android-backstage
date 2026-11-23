import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, it } from "node:test";
import {
  Spake2,
  ALICE_NAME,
  BOB_NAME,
  encode,
  M,
  N,
  M_ENCODED,
  N_ENCODED,
} from "../spake2.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const VECTOR = JSON.parse(
  fs.readFileSync(path.resolve(__dirname, "../../docs/spake2-backstage-vector.json"), "utf8"),
);

describe("spake2", () => {
  it("M/N match BoringSSL encoded constants", () => {
    assert.equal(encode(M).toString("hex"), M_ENCODED.toString("hex"));
    assert.equal(encode(N).toString("hex"), N_ENCODED.toString("hex"));
  });

  it("matches the Java known-answer vector", () => {
    const alice = new Spake2("alice", Buffer.from(VECTOR.aliceName, "utf8"), Buffer.from(VECTOR.bobName, "utf8"));
    const bob = new Spake2("bob", Buffer.from(VECTOR.bobName, "utf8"), Buffer.from(VECTOR.aliceName, "utf8"));
    const aliceMsg = alice.generateMsg(
      Buffer.from(VECTOR.password, "utf8"),
      Buffer.from(VECTOR.aliceRandom64, "hex"),
    );
    const bobMsg = bob.generateMsg(Buffer.from(VECTOR.password, "utf8"), Buffer.from(VECTOR.bobRandom64, "hex"));
    assert.equal(aliceMsg.toString("hex"), VECTOR.aliceMsg);
    assert.equal(bobMsg.toString("hex"), VECTOR.bobMsg);
    const keyA = alice.processMsg(bobMsg);
    const keyB = bob.processMsg(aliceMsg);
    assert.equal(keyA.toString("hex"), VECTOR.key);
    assert.equal(keyB.toString("hex"), VECTOR.key);
    assert.ok(ALICE_NAME.equals(Buffer.from(VECTOR.aliceName, "utf8")));
    assert.ok(BOB_NAME.equals(Buffer.from(VECTOR.bobName, "utf8")));
  });

  it("A/B agree with random scalars", () => {
    const password = Buffer.from("TEST-CODE99", "utf8");
    const alice = new Spake2("alice", ALICE_NAME, BOB_NAME);
    const bob = new Spake2("bob", BOB_NAME, ALICE_NAME);
    const aMsg = alice.generateMsg(password);
    const bMsg = bob.generateMsg(password);
    const keyA = alice.processMsg(bMsg);
    const keyB = bob.processMsg(aMsg);
    assert.equal(keyA.length, 64);
    assert.ok(keyA.equals(keyB));
  });
});
