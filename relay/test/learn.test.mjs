import test from "node:test";
import assert from "node:assert/strict";
import { withRelay, httpJson } from "./helpers.mjs";

test("learn runner is disabled by default and serves fixed static assets only when enabled", async () => {
  await withRelay(async ({ url }) => {
    const disabled = await httpJson(`${url}/learn/`);
    assert.equal(disabled.status, 404);
  });
  await withRelay({ enableLearn: true }, async ({ url }) => {
    const page = await httpJson(`${url}/learn/`);
    assert.equal(page.status, 200);
    assert.match(page.text, /id="activity"/);
    assert.equal(page.headers.get("content-security-policy"), "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
    assert.equal(page.headers.get("x-content-type-options"), "nosniff");

    const runner = await httpJson(`${url}/learn/runner.js`);
    assert.equal(runner.status, 200);
    assert.match(runner.text, /parseActivityHash/);
    const stylesheet = await httpJson(`${url}/learn/style.css`);
    assert.equal(stylesheet.status, 200);
    const app = await httpJson(`${url}/learn/app.js`);
    assert.match(app.text, /closest\('\.diagram-node'\)/);
    assert.match(app.text, /closest\('\.chart-point'\)/);
    assert.match(app.text, /learnerQuestions/);
    assert.match(app.text, /localStorage/);

    const hidden = await httpJson(`${url}/learn/../server.mjs`);
    assert.equal(hidden.status, 404);
    const method = await httpJson(`${url}/learn/`, { method: "POST" });
    assert.equal(method.status, 405);
  });
});

test("learning results use separate capabilities, bounded events, and incremental reads", async () => {
  await withRelay({ adminToken: "test-admin", enableLearn: true }, async ({ url, dataDir }) => {
    const created = await httpJson(`${url}/v1/learn/activities`, {
      method: "POST",
      headers: { authorization: "Bearer test-admin", "content-type": "application/json" },
      body: { ttlMs: 60_000, maxAttempts: 2 },
    });
    assert.equal(created.status, 201);
    assert.match(created.body.writeToken, /^lrw_/);
    assert.match(created.body.readToken, /^lrr_/);
    const path = `${url}/v1/learn/activities/${created.body.activityId}/results`;
    const origin = url;
    const result = await httpJson(path, {
      method: "POST",
      headers: { authorization: `Bearer ${created.body.writeToken}`, origin, "content-type": "application/json" },
      body: {
        answers: [{ questionId: "q1", value: "b", correct: true, timeMs: 1200 }],
        events: [
          { type: "selection", questionId: "q1", value: "b", atMs: 1200 },
          { type: "feedback", questionId: "q1", feedbackId: "correct", atMs: 1300 },
        ],
        learnerQuestions: [{ questionId: "q1", text: "Why does this answer work?" }],
        score: { earned: 1, total: 1 },
      },
    });
    assert.equal(result.status, 201);
    assert.equal((await httpJson(path, { headers: { authorization: `Bearer ${created.body.readToken}` } })).status, 200);
    const read = await httpJson(`${path}?after=0`, { headers: { authorization: `Bearer ${created.body.readToken}` } });
    assert.equal(read.body.next, 1);
    assert.equal(read.body.attempts[0].events[1].feedbackId, "correct");
    assert.equal(read.body.attempts[0].learnerQuestions[0].text, "Why does this answer work?");
    const empty = await httpJson(`${path}?after=1`, { headers: { authorization: `Bearer ${created.body.readToken}` } });
    assert.deepEqual(empty.body.attempts, []);
    assert.equal(empty.headers.get("access-control-allow-origin"), null);
    const wrongOrigin = await httpJson(path, {
      method: "POST", headers: { authorization: `Bearer ${created.body.writeToken}`, origin: "https://evil.example", "content-type": "application/json" }, body: { answers: [] },
    });
    assert.equal(wrongOrigin.status, 403);
    const wrongCap = await httpJson(path, {
      method: "POST", headers: { authorization: `Bearer ${created.body.readToken}`, origin, "content-type": "application/json" }, body: { answers: [] },
    });
    assert.equal(wrongCap.status, 401);
    const state = JSON.parse(await (await import("node:fs/promises")).readFile(`${dataDir}/learn-results.json`, "utf8"));
    assert.equal(state.activities[created.body.activityId].writeHash.includes(created.body.writeToken), false);
    assert.equal(state.activities[created.body.activityId].readHash.includes(created.body.readToken), false);
  });
});
