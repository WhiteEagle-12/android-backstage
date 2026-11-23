import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { writePrivateJson } from "./persist.mjs";
import { HttpError, bearerToken } from "./auth.mjs";

const MAX_BODY_BYTES = 64 * 1024;
const MAX_ATTEMPTS = 100;
const MAX_QUESTIONS = 8;
const MAX_ANSWERS = 100;
const MAX_EVENTS = 200;
const MAX_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const DEFAULT_TTL_MS = 48 * 60 * 60 * 1000;

function id() {
  return `learn_${crypto.randomBytes(12).toString("hex")}`;
}

function token(prefix) {
  return `${prefix}${crypto.randomBytes(32).toString("base64url")}`;
}

function hash(value) {
  return crypto.createHash("sha256").update(String(value), "utf8").digest("hex");
}

function same(a, b) {
  const left = Buffer.from(String(a || ""), "utf8");
  const right = Buffer.from(String(b || ""), "utf8");
  return left.length > 0 && left.length === right.length && crypto.timingSafeEqual(left, right);
}

function text(value, field, max) {
  if (typeof value !== "string" || value.length === 0 || value.length > max) {
    throw new HttpError(400, `${field} must be a non-empty string of at most ${max} characters`);
  }
  return value;
}

function optionalText(value, field, max) {
  if (value == null) return undefined;
  return text(value, field, max);
}

function boundedPrimitive(value, field) {
  if (typeof value === "string") return text(value, field, 200);
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "boolean") return value;
  throw new HttpError(400, `${field} must be a short string, number, or boolean`);
}

function validateAttempt(body, activityId) {
  if (!body || typeof body !== "object" || Array.isArray(body)) throw new HttpError(400, "result must be a JSON object");
  const answers = body.answers == null ? [] : body.answers;
  if (!Array.isArray(answers) || answers.length > MAX_ANSWERS) throw new HttpError(400, "answers must be an array of at most 100 items");
  const cleanAnswers = answers.map((answer, index) => {
    if (!answer || typeof answer !== "object" || Array.isArray(answer)) throw new HttpError(400, `answers[${index}] must be an object`);
    const out = { questionId: text(answer.questionId, `answers[${index}].questionId`, 100) };
    if (Object.hasOwn(answer, "value")) out.value = boundedPrimitive(answer.value, `answers[${index}].value`);
    if (answer.correct != null && typeof answer.correct !== "boolean") throw new HttpError(400, `answers[${index}].correct must be boolean`);
    if (answer.correct != null) out.correct = answer.correct;
    if (answer.timeMs != null) {
      if (!Number.isInteger(answer.timeMs) || answer.timeMs < 0 || answer.timeMs > 86_400_000) throw new HttpError(400, `answers[${index}].timeMs is invalid`);
      out.timeMs = answer.timeMs;
    }
    return out;
  });
  const questions = body.learnerQuestions == null ? [] : body.learnerQuestions;
  if (!Array.isArray(questions) || questions.length > MAX_QUESTIONS) throw new HttpError(400, "learnerQuestions must be an array of at most 8 items");
  const cleanQuestions = questions.map((question, index) => {
    if (!question || typeof question !== "object" || Array.isArray(question)) throw new HttpError(400, `learnerQuestions[${index}] must be an object`);
    const out = { text: text(question.text, `learnerQuestions[${index}].text`, 500) };
    if (question.questionId != null) out.questionId = text(question.questionId, `learnerQuestions[${index}].questionId`, 100);
    return out;
  });
  const score = body.score;
  let cleanScore;
  if (score != null) {
    if (!score || !Number.isFinite(score.earned) || !Number.isFinite(score.total) || score.earned < 0 || score.total < 0 || score.earned > score.total || score.total > 10000) {
      throw new HttpError(400, "score is invalid");
    }
    cleanScore = { earned: score.earned, total: score.total };
  }
  const events = body.events == null ? [] : body.events;
  if (!Array.isArray(events) || events.length > MAX_EVENTS) throw new HttpError(400, "events must be an array of at most 200 items");
  const allowedEvents = new Set(["selection", "attempt", "retry", "explore", "feedback", "question", "complete"]);
  const cleanEvents = events.map((event, index) => {
    if (!event || typeof event !== "object" || Array.isArray(event) || !allowedEvents.has(event.type)) throw new HttpError(400, `events[${index}].type is invalid`);
    const out = { type: event.type };
    if (event.atMs != null) {
      if (!Number.isInteger(event.atMs) || event.atMs < 0 || event.atMs > 86_400_000) throw new HttpError(400, `events[${index}].atMs is invalid`);
      out.atMs = event.atMs;
    }
    for (const field of ["questionId", "nodeId", "pointId", "faceId", "feedbackId"]) {
      if (event[field] != null) out[field] = text(event[field], `events[${index}].${field}`, 100);
    }
    if (event.value != null) out.value = boundedPrimitive(event.value, `events[${index}].value`);
    if (event.text != null) out.text = text(event.text, `events[${index}].text`, 500);
    return out;
  });
  return {
    activityId,
    answers: cleanAnswers,
    learnerQuestions: cleanQuestions,
    events: cleanEvents,
    ...(cleanScore ? { score: cleanScore } : {}),
    ...(Number.isInteger(body.durationMs) && body.durationMs >= 0 && body.durationMs <= 86_400_000 ? { durationMs: body.durationMs } : {}),
  };
}

export function createLearnResults(dataDir, { now = () => Date.now(), log = console } = {}) {
  const statePath = path.join(dataDir, "learn-results.json");
  let state = { activities: {} };
  if (fs.existsSync(statePath)) {
    try {
      const parsed = JSON.parse(fs.readFileSync(statePath, "utf8"));
      if (parsed && parsed.activities && typeof parsed.activities === "object") state = { activities: parsed.activities };
    } catch (err) {
      throw new Error(`failed to read ${statePath}: ${err.message}`);
    }
  }
  const persist = () => writePrivateJson(statePath, state);
  const sweep = () => {
    const current = now();
    let changed = false;
    for (const [activityId, activity] of Object.entries(state.activities)) {
      if (!activity || Number(activity.expiresAt) <= current) { delete state.activities[activityId]; changed = true; }
    }
    if (changed) persist();
  };
  sweep();

  function create({ accountId, ttlMs, maxAttempts }) {
    sweep();
    const ttl = ttlMs == null ? DEFAULT_TTL_MS : Number(ttlMs);
    if (!Number.isFinite(ttl) || ttl < 60_000 || ttl > MAX_TTL_MS) throw new HttpError(400, "ttlMs must be between 60000 and 604800000");
    const cap = maxAttempts == null ? MAX_ATTEMPTS : Number(maxAttempts);
    if (!Number.isInteger(cap) || cap < 1 || cap > MAX_ATTEMPTS) throw new HttpError(400, "maxAttempts must be between 1 and 100");
    if (Object.values(state.activities).filter((entry) => entry.accountId === accountId).length >= 100) throw new HttpError(429, "too many active learning activities");
    const activityId = id();
    const writeToken = token("lrw_");
    const readToken = token("lrr_");
    const activity = { activityId, accountId, writeHash: hash(writeToken), readHash: hash(readToken), createdAt: now(), expiresAt: now() + ttl, maxAttempts: cap, attempts: [] };
    state.activities[activityId] = activity;
    persist();
    return { activityId, writeToken, readToken, expiresAt: new Date(activity.expiresAt).toISOString(), maxAttempts: cap };
  }

  function get(activityId) {
    sweep();
    return state.activities[activityId] || null;
  }

  function write(req, activityId, body) {
    const activity = get(activityId);
    if (!activity || !same(activity.writeHash, hash(bearerToken(req)))) throw new HttpError(401, "invalid activity write capability");
    if (activity.attempts.length >= activity.maxAttempts) throw new HttpError(429, "activity result limit reached");
    const result = validateAttempt(body, activityId);
    activity.attempts.push({ attempt: activity.attempts.length + 1, receivedAt: new Date(now()).toISOString(), ...result });
    persist();
    return { ok: true, receivedAt: activity.attempts.at(-1).receivedAt, attempt: activity.attempts.length };
  }

  function read(req, activityId, after = 0) {
    const activity = get(activityId);
    if (!activity || !same(activity.readHash, hash(bearerToken(req)))) throw new HttpError(401, "invalid activity read capability");
    const cursor = Number.isInteger(after) && after >= 0 ? after : 0;
    return { activityId, createdAt: new Date(activity.createdAt).toISOString(), expiresAt: new Date(activity.expiresAt).toISOString(), next: activity.attempts.length, attempts: activity.attempts.slice(cursor) };
  }

  function close() { persist(); }
  void log;
  return { create, write, read, close, maxBodyBytes: MAX_BODY_BYTES };
}

export const LEARN_RESULT_MAX_BODY_BYTES = MAX_BODY_BYTES;
