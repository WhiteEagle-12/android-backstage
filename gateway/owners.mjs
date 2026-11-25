import crypto from 'node:crypto';
import fs from 'node:fs';
import {promisify} from 'node:util';
import {writePrivateJson} from '../cli/state.mjs';
import {PLANS, normalizePlan, planLimits} from './plans.mjs';
import {generateTotpSecret, totpOtpAuthUri, verifyTotp} from './totp.mjs';
import * as webauthn from '@simplewebauthn/server';
const scryptAsync = promisify(crypto.scrypt);

export const sessionCookie = '__Host-backstage-owner';
export const challengeCookie = '__Host-backstage-passkey';
export const mfaCookie = '__Host-backstage-mfa';
export const TRIAL_DAYS = 7;
export const TRIAL_MS = TRIAL_DAYS * 86400000;
const DAY_MS = 86400000;

const random = () => crypto.randomBytes(32).toString('base64url');
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
export function cookieValue(req, name) {
  const values = String(req.headers.cookie || '').split(';').map(value => value.trim()).filter(value => value.startsWith(name + '='));
  return values.length === 1 ? values[0].slice(name.length + 1) : '';
}
export function setCookie(res, name, value, seconds) {
  res.append('Set-Cookie', `${name}=${value}; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=${seconds}`);
}

export const normalEmail = value => {const email = String(value || '').trim().toLowerCase();return /^[^\s@]{1,64}@[^\s@]{1,190}\.[a-z]{2,24}$/.test(email) ? email : null;};
const recoveryShape = value => String(value || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
const validatePassword = value => {
  if (typeof value !== 'string' || value.length < 8) throw new Error('Password must be at least 8 characters.');
  if (value.length > 128) throw new Error('Password must be 128 characters or fewer.');
  return value;
};
const hashPassword = async password => {
  const salt = crypto.randomBytes(16);
  const key = await scryptAsync(password, salt, 32, { N: 16384, r: 8, p: 1 });
  return `scrypt$${salt.toString('base64url')}$${key.toString('base64url')}`;
};
const DUMMY_SALT = crypto.randomBytes(16);
const checkPasswordHash = async (password, stored) => {
  const parts = typeof stored === 'string' ? stored.split('$') : [];
  if (parts.length !== 3 || parts[0] !== 'scrypt') {
    await scryptAsync(typeof password === 'string' ? password : '', DUMMY_SALT, 32, { N: 16384, r: 8, p: 1 });
    return false;
  }
  try {
    const salt = Buffer.from(parts[1], 'base64url');
    const expected = Buffer.from(parts[2], 'base64url');
    const derived = await scryptAsync(password, salt, expected.length, { N: 16384, r: 8, p: 1 });
    return derived.length === expected.length && crypto.timingSafeEqual(derived, expected);
  } catch {
    return false;
  }
};

// Single writer, persistent owner records. Only passkey public keys, hashed
// session tokens, hashed one-time codes, and optional sealed TOTP secrets are
// stored here. New signups start on a 7-day Pro free trial (no card needed)
// and switch automatically to Personal (free) when the trial expires.
export function createOwners({stateFile, origin, androidOrigins = [], mailer = null, vault = null, accountUrl = null, dailyEmailCap = Number(process.env.BACKSTAGE_DAILY_EMAIL_CAP || 5000), now = Date.now}) {
  const rpID = new URL(origin).hostname;
  const resolvedAccountUrl = accountUrl || `${origin}/work/account#billing`;
  const loaded = fs.existsSync(stateFile) ? JSON.parse(fs.readFileSync(stateFile, 'utf8')) : {owners:{}, credentials:{}, sessions:{}};
  const state = {owners: loaded.owners || {}, credentials: loaded.credentials || {}, sessions: loaded.sessions || {}};
  // Ephemeral codes, WebAuthn challenges, MFA setup secrets & pending 2FA cookies stay strictly in RAM.
  const challenges = new Map();
  const emailCodes = new Map();
  const emailRates = new Map();
  const passwordRates = new Map();
  const mfaRates = new Map();
  const mfaSetups = new Map();
  const mfaPending = new Map();
  let emailDay = { day: '', count: 0 };
  const save = () => writePrivateJson(stateFile, state);
  if (loaded.challenges || loaded.emailCodes) save();
  function prune() {
    const t = now();
    for (const [key, row] of Object.entries(state.sessions)) if (row.expires <= t) delete state.sessions[key];
    for (const [key, row] of challenges) if (row.expires <= t) challenges.delete(key);
    for (const [key, row] of emailCodes) if (row.expires <= t) emailCodes.delete(key);
    for (const [key, row] of mfaSetups) if (row.expires <= t) mfaSetups.delete(key);
    for (const [key, row] of mfaPending) if (row.expires <= t) mfaPending.delete(key);
    for (const map of [emailRates, passwordRates, mfaRates]) {
      for (const [key, list] of map) {
        const recent = list.filter(at => at > t - 900000);
        if (recent.length) map.set(key, recent); else map.delete(key);
      }
    }
  }
  const evictIfFull = (map, max) => {
    while (map.size >= max) {
      const oldest = map.keys().next().value;
      if (oldest === undefined) break;
      map.delete(oldest);
    }
  };
  const sealSecret = (ownerId, secret) => vault?.seal ? vault.seal(secret, 'totp:' + ownerId) : secret;
  const openSecret = (ownerId, stored) => {
    if (!stored) return null;
    if (typeof stored === 'string') return stored;
    if (vault?.open) return vault.open(stored, 'totp:' + ownerId);
    throw new Error('MFA vault key is unavailable on this server.');
  };
  const ownerByEmail = email => Object.keys(state.owners).find(id => state.owners[id].email === email) || null;
  const ownerByStripeCustomer = customerId => {
    if (!customerId) return null;
    return Object.keys(state.owners).find(id => state.owners[id]?.billing?.customerId === customerId) || null;
  };
  const equalHash = (a, b) => typeof a === 'string' && typeof b === 'string' && a.length === b.length && crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b));
  const emailCodeKey = (email, linkOwner, purpose = null) => {
    const scope = purpose || (linkOwner ? 'link:' + linkOwner : 'signin');
    return hash(scope + ':' + email);
  };
  async function sendOtp(email, scope, subject, body, extra = {}) {
    if (!mailer?.configured()) throw new Error('Email verification is not set up on this server.');
    const t = now();
    const rateKey = hash('rate:' + email);
    const recent = (emailRates.get(rateKey) || []).filter(at => at > t - 900000);
    if (recent.length >= 5) throw new Error('Too many codes were sent. Wait a few minutes.');
    const day = new Date(t).toISOString().slice(0, 10);
    if (emailDay.day !== day) emailDay = { day, count: 0 };
    if (emailDay.count >= dailyEmailCap) throw new Error('Email delivery is temporarily busy. Try again in a bit.');
    const code = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
    await mailer.send(email, subject.replace('{code}', code), body.replace('{code}', code));
    evictIfFull(emailRates, 5000);
    emailRates.set(rateKey, [...recent, t]);
    emailDay.count++;
    evictIfFull(emailCodes, 2000);
    const key = hash(scope + ':' + email);
    emailCodes.set(key, { codeHash: hash(scope + ':' + email + ':' + code), expires: t + 600000, attempts: 0, scope, ...extra });
    return { sent: true, expiresIn: 600 };
  }
  function consumeOtp(email, scope, code) {
    const key = email && hash(scope + ':' + email);
    const row = key && emailCodes.get(key);
    if (!row || row.scope !== scope) throw new Error('That code expired. Ask for a new one.');
    if (++row.attempts > 5) { emailCodes.delete(key); throw new Error('Too many wrong codes. Ask for a new one.'); }
    if (!equalHash(hash(scope + ':' + email + ':' + String(code || '').replace(/\D/g, '')), row.codeHash)) {
      throw new Error('That code is not right. Check the email and try again.');
    }
    emailCodes.delete(key);
    return row;
  }
  function issue(res, ownerId) {
    const token = random();
    state.sessions[hash(token)] = {ownerId, expires: now() + 30*86400000}; save();
    setCookie(res, sessionCookie, token, 30*86400); return ownerId;
  }
  function issueMfaChallenge(res, ownerId) {
    prune();
    evictIfFull(mfaPending, 5000);
    const token = random();
    mfaPending.set(hash(token), { ownerId, expires: now() + 300000, attempts: 0 });
    setCookie(res, mfaCookie, token, 300);
    return { mfaRequired: true };
  }
  function otherMethods(ownerId, except = {}) {
    const row = state.owners[ownerId] || {};
    return (row.email && !except.email ? 1 : 0) + Object.values(state.credentials).filter(c => c.ownerId === ownerId && c.id !== except.passkey).length + ((row.recovery || []).length && !except.recovery ? 1 : 0);
  }
  function owner(req) {
    const token = cookieValue(req, sessionCookie);
    const row = token && Object.hasOwn(state.sessions, hash(token)) ? state.sessions[hash(token)] : null;
    return row && row.expires > now() && Object.hasOwn(state.owners,row.ownerId) ? row.ownerId : null;
  }
  function effectivePlanOf(ownerId) {
    const row = state.owners[ownerId];
    if (!row) return 'free';
    const basePlan = normalizePlan(row.plan);
    if (basePlan !== 'free') return basePlan;
    if (typeof row.trialEndsAt === 'number' && row.trialEndsAt > now()) return 'pro';
    return 'free';
  }
  function trialStateOf(ownerId) {
    const row = state.owners[ownerId] || {};
    const t = now();
    const basePlan = normalizePlan(row.plan);
    const hasTrial = typeof row.trialEndsAt === 'number';
    const active = basePlan === 'free' && hasTrial && row.trialEndsAt > t;
    const expired = basePlan === 'free' && hasTrial && row.trialEndsAt <= t;
    const daysLeft = active ? Math.max(1, Math.ceil((row.trialEndsAt - t) / DAY_MS)) : 0;
    return {
      active,
      expired,
      startedAt: row.trialStartedAt || null,
      endsAt: row.trialEndsAt || null,
      daysLeft,
      promoEligible: basePlan === 'free',
      promoPriceMonthly: '$4.50',
      promoPercent: 50,
    };
  }
  function newRecoveryCodes(ownerId) {
    if (!Object.hasOwn(state.owners, ownerId)) throw new Error('Unknown account.');
    const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    const codes = Array.from({length: 10}, () => Array.from({length: 12}, () => alphabet[crypto.randomInt(alphabet.length)]).join('').replace(/(.{4})(.{4})(.{4})/, '$1-$2-$3'));
    state.owners[ownerId].recovery = codes.map(code => hash('recovery:' + recoveryShape(code)));
    save(); return codes;
  }
  function consumeRecoveryForOwner(ownerId, code) {
    const row = state.owners[ownerId];
    if (!row || !Array.isArray(row.recovery) || !row.recovery.length) return false;
    const digest = hash('recovery:' + recoveryShape(code));
    if (!row.recovery.includes(digest)) return false;
    row.recovery = row.recovery.filter(d => d !== digest);
    save();
    return true;
  }
  function verifyMfaCode(ownerId, rawCode) {
    prune();
    const row = state.owners[ownerId];
    if (!row?.mfa?.enabled) return true;
    const t = now();
    const rateKey = hash('mfa:' + ownerId);
    const recent = (mfaRates.get(rateKey) || []).filter(at => at > t - 900000);
    if (recent.length >= 10) throw new Error('Too many failed verification codes. Wait a few minutes.');
    const secret = openSecret(ownerId, row.mfa.secret);
    const check = verifyTotp(secret, rawCode, { now: t, lastUsedStep: row.mfa.lastStep ?? -1 });
    if (check.ok) {
      row.mfa.lastStep = check.step;
      mfaRates.delete(rateKey);
      save();
      return true;
    }
    if (consumeRecoveryForOwner(ownerId, rawCode)) {
      mfaRates.delete(rateKey);
      return true;
    }
    evictIfFull(mfaRates, 5000);
    mfaRates.set(rateKey, [...recent, t]);
    throw new Error('Invalid verification code. Enter the 6-digit code from your authenticator app or a recovery code.');
  }

  return {
    owner,
    issue,
    issueMfaChallenge,
    ownerByEmail: value => {const email = normalEmail(value); return email ? ownerByEmail(email) : null;},
    ownerByStripeCustomer,
    exists: id => Object.hasOwn(state.owners,id),
    emailAvailable: () => !!mailer?.configured(),
    mfaEnabled: id => Boolean(state.owners[id]?.mfa?.enabled),
    /** Start TOTP authenticator setup for a signed-in owner (stored in RAM until confirmed). */
    startMfaSetup(ownerId) {
      prune();
      if (!Object.hasOwn(state.owners, ownerId)) throw new Error('Unknown account.');
      const secret = generateTotpSecret();
      const email = state.owners[ownerId].email || ownerId;
      evictIfFull(mfaSetups, 2000);
      mfaSetups.set(ownerId, { secret, expires: now() + 600000 });
      return { secret, uri: totpOtpAuthUri({ secret, accountName: email }) };
    },
    /** Confirm TOTP setup with a live 6-digit code; auto-creates backup recovery codes if none exist. */
    confirmMfaSetup(ownerId, code) {
      prune();
      if (!Object.hasOwn(state.owners, ownerId)) throw new Error('Unknown account.');
      const pending = mfaSetups.get(ownerId);
      if (!pending || pending.expires <= now()) throw new Error('Setup expired. Start authenticator setup again.');
      const check = verifyTotp(pending.secret, code, { now: now() });
      if (!check.ok) throw new Error('That code did not match. Check the 6-digit code in your authenticator app and try again.');
      mfaSetups.delete(ownerId);
      state.owners[ownerId].mfa = {
        enabled: true,
        secret: sealSecret(ownerId, pending.secret),
        enabledAt: now(),
        lastStep: check.step,
      };
      let recoveryCodes = null;
      if (!(state.owners[ownerId].recovery || []).length) {
        recoveryCodes = newRecoveryCodes(ownerId);
      } else {
        save();
      }
      return { enabled: true, recoveryCodes };
    },
    /** Verify a TOTP or recovery code for an owner (also used by native login). */
    verifyMfa: verifyMfaCode,
    /** Verify pending web 2FA cookie + code and issue a full browser session. */
    completeMfaLogin(req, res, code) {
      prune();
      const token = cookieValue(req, mfaCookie);
      const key = token ? hash(token) : '';
      const row = key ? mfaPending.get(key) : null;
      if (!row || row.expires <= now() || !Object.hasOwn(state.owners, row.ownerId)) {
        setCookie(res, mfaCookie, '', 0);
        throw new Error('Verification expired. Sign in again.');
      }
      if (++row.attempts > 8) {
        mfaPending.delete(key);
        setCookie(res, mfaCookie, '', 0);
        throw new Error('Too many failed verification attempts. Sign in again.');
      }
      verifyMfaCode(row.ownerId, code);
      mfaPending.delete(key);
      setCookie(res, mfaCookie, '', 0);
      return issue(res, row.ownerId);
    },
    /** Disable TOTP MFA after verifying a live TOTP/recovery code or current password. */
    async disableMfa(ownerId, { code = null, password = null } = {}) {
      if (!Object.hasOwn(state.owners, ownerId)) throw new Error('Unknown account.');
      const row = state.owners[ownerId];
      if (!row.mfa?.enabled) return { enabled: false };
      let verified = false;
      if (typeof code === 'string' && code.trim()) {
        verifyMfaCode(ownerId, code);
        verified = true;
      } else if (typeof password === 'string' && password && row.passwordHash) {
        if (!(await checkPasswordHash(password, row.passwordHash))) {
          throw new Error('Current password is incorrect.');
        }
        verified = true;
      }
      if (!verified) throw new Error('Enter your 6-digit authenticator code (or current password) to turn off two-factor authentication.');
      delete row.mfa;
      save();
      return { enabled: false };
    },
    /** Revoke all other active browser sessions except the caller's current cookie. */
    signOutOtherSessions(req, ownerId) {
      const current = hash(cookieValue(req, sessionCookie));
      let removed = 0;
      for (const [id, sess] of Object.entries(state.sessions)) {
        if (sess.ownerId === ownerId && id !== current) {
          delete state.sessions[id];
          removed++;
        }
      }
      if (removed) save();
      return removed;
    },
    /** Create account step 1: validate email & password, hash password in RAM, send 6-digit verification OTP. */
    async startSignUp(value, password) {
      prune();
      const email = normalEmail(value);
      if (!email) throw new Error('Enter a valid email address.');
      validatePassword(password);
      if (ownerByEmail(email)) throw new Error('An account with that email already exists. Log in instead.');
      const passwordHash = await hashPassword(password);
      return await sendOtp(
        email,
        'signup',
        '{code} — verify your Android Backstage account',
        'Your Android Backstage verification code is {code}.\n\nEnter this code to finish creating your account and start your 7-day Pro free trial (no card needed). It expires in 10 minutes. If you did not sign up, ignore this email.',
        { passwordHash }
      );
    },
    /** Create account step 2: verify OTP and create the verified account with a 7-day Pro free trial (no card needed). */
    verifySignUp(value, code) {
      prune();
      const email = normalEmail(value);
      if (!email) throw new Error('Enter a valid email address.');
      const row = consumeOtp(email, 'signup', code);
      if (ownerByEmail(email)) throw new Error('An account with that email already exists. Log in instead.');
      const ownerId = 'owner_' + random();
      const t = now();
      state.owners[ownerId] = {
        createdAt: t,
        email,
        passwordHash: row.passwordHash,
        passwordUpdatedAt: t,
        plan: 'free',
        trialStartedAt: t,
        trialEndsAt: t + TRIAL_MS,
        trialRemindersSent: {},
      };
      save();
      return ownerId;
    },
    /** Standard email + password login with constant-time async verification and per-email rate limiting. */
    async verifyPassword(value, password) {
      prune();
      const email = normalEmail(value);
      if (!email || typeof password !== 'string' || !password) throw new Error('Enter your email and password.');
      const t = now();
      const rateKey = hash('pw:' + email);
      const recent = (passwordRates.get(rateKey) || []).filter(at => at > t - 900000);
      if (recent.length >= 10) throw new Error('Too many failed sign-in attempts. Wait a few minutes or reset your password.');
      const ownerId = ownerByEmail(email);
      const stored = ownerId ? state.owners[ownerId]?.passwordHash : null;
      const ok = await checkPasswordHash(password, stored);
      if (!ownerId || !stored || !ok) {
        evictIfFull(passwordRates, 5000);
        passwordRates.set(rateKey, [...recent, t]);
        throw new Error('Incorrect email or password.');
      }
      passwordRates.delete(rateKey);
      return ownerId;
    },
    /** Forgot password step 1: send 6-digit reset OTP if the email exists (never reveals whether it exists). */
    async startPasswordReset(value) {
      prune();
      const email = normalEmail(value);
      if (!email) throw new Error('Enter a valid email address.');
      if (!mailer?.configured()) throw new Error('Password reset by email is not set up on this server.');
      const ownerId = ownerByEmail(email);
      if (!ownerId) return { sent: true, expiresIn: 600 };
      return await sendOtp(
        email,
        'reset',
        '{code} — reset your Android Backstage password',
        'Your Android Backstage password reset code is {code}.\n\nEnter this code to choose a new password. It expires in 10 minutes. If you did not ask to reset your password, ignore this email.',
        { resetOwner: ownerId }
      );
    },
    /** Forgot password step 2: verify reset OTP, update password, invalidate old browser sessions, and return ownerId. */
    async verifyPasswordReset(value, code, newPassword) {
      prune();
      const email = normalEmail(value);
      if (!email) throw new Error('Enter a valid email address.');
      validatePassword(newPassword);
      const row = consumeOtp(email, 'reset', code);
      const ownerId = row.resetOwner;
      if (!ownerId || !Object.hasOwn(state.owners, ownerId) || state.owners[ownerId].email !== email) {
        throw new Error('That code expired. Ask for a new one.');
      }
      state.owners[ownerId].passwordHash = await hashPassword(newPassword);
      state.owners[ownerId].passwordUpdatedAt = now();
      for (const [id, sess] of Object.entries(state.sessions)) if (sess.ownerId === ownerId) delete state.sessions[id];
      passwordRates.delete(hash('pw:' + email));
      save();
      return ownerId;
    },
    /** Set or change password for a signed-in account. */
    async setPassword(ownerId, newPassword, { currentPassword = null, requireCurrent = false } = {}) {
      if (!Object.hasOwn(state.owners, ownerId)) throw new Error('Unknown account.');
      if (!state.owners[ownerId].email) throw new Error('Add an email address before setting a password.');
      validatePassword(newPassword);
      const existing = state.owners[ownerId].passwordHash;
      if (existing && requireCurrent) {
        if (typeof currentPassword !== 'string' || !(await checkPasswordHash(currentPassword, existing))) {
          throw new Error('Current password is incorrect.');
        }
      }
      state.owners[ownerId].passwordHash = await hashPassword(newPassword);
      state.owners[ownerId].passwordUpdatedAt = now();
      save();
      return true;
    },
    /** Email a six-digit code for existing accounts (sign-in) or adding an email to a signed-in account (linkOwner). */
    async startEmail(value, {linkOwner = null} = {}) {
      prune();
      const email = normalEmail(value);
      if (!email) throw new Error('Enter a valid email address.');
      if (!mailer?.configured()) throw new Error('Email sign-in is not set up on this server. Use a passkey.');
      if (linkOwner) {
        const taken = ownerByEmail(email);
        if (taken && taken !== linkOwner) throw new Error('That email already belongs to another account.');
      } else if (!ownerByEmail(email)) {
        // Never create accounts via one-time email codes and never reveal whether an email exists.
        return { sent: true, expiresIn: 600 };
      }
      const scope = linkOwner ? 'link:' + linkOwner : 'signin';
      const subject = linkOwner ? '{code} — confirm your Android Backstage email' : '{code} is your Android Backstage code';
      const body = linkOwner
        ? 'Your Android Backstage email confirmation code is {code}.\n\nEnter this code only if you asked to link this email to your existing account. It expires in 10 minutes.'
        : 'Your Android Backstage sign-in code is {code}.\n\nIt expires in 10 minutes. If you did not ask for it, ignore this email.';
      return await sendOtp(email, scope, subject, body, { linkOwner: linkOwner || null });
    },
    /** Check an emailed code for an existing account (or link-email confirmation). Never creates new accounts. */
    verifyEmail(value, code, { expectedLinkOwner = null } = {}) {
      prune();
      const email = normalEmail(value);
      const expectedOwner = expectedLinkOwner || null;
      const scope = expectedOwner ? 'link:' + expectedOwner : 'signin';
      const row = consumeOtp(email, scope, code);
      if ((row.linkOwner || null) !== expectedOwner) throw new Error('That code expired. Ask for a new one.');
      if (expectedOwner) {
        if (!Object.hasOwn(state.owners, expectedOwner)) throw new Error('Unknown account.');
        const taken = ownerByEmail(email);
        if (taken && taken !== expectedOwner) throw new Error('That email already belongs to another account.');
        state.owners[expectedOwner].email = email; save(); return expectedOwner;
      }
      const ownerId = ownerByEmail(email);
      if (!ownerId) throw new Error('No account found for that email. Create an account instead.');
      return ownerId;
    },
    /** Permanently delete an owner, all passkeys, sessions, pending challenges, and email codes. */
    deleteOwner(ownerId, req = null, res = null) {
      if (!Object.hasOwn(state.owners, ownerId)) return false;
      const ownerEmail = state.owners[ownerId]?.email || null;
      delete state.owners[ownerId];
      for (const [id, cred] of Object.entries(state.credentials)) if (cred.ownerId === ownerId) delete state.credentials[id];
      for (const [id, sess] of Object.entries(state.sessions)) if (sess.ownerId === ownerId) delete state.sessions[id];
      for (const [id, ch] of challenges) if (ch.ownerId === ownerId || ch.existingOwner === ownerId) challenges.delete(id);
      for (const [id, ec] of emailCodes) if (ec.linkOwner === ownerId) emailCodes.delete(id);
      mfaSetups.delete(ownerId);
      for (const [id, mp] of mfaPending) if (mp.ownerId === ownerId) mfaPending.delete(id);
      mfaRates.delete(hash('mfa:' + ownerId));
      if (ownerEmail) {
        for (const scope of ['signin', 'signup', 'reset']) emailCodes.delete(emailCodeKey(ownerEmail, null, scope));
        emailRates.delete(hash('rate:' + ownerEmail));
        passwordRates.delete(hash('pw:' + ownerEmail));
      }
      save();
      if (res) { setCookie(res, sessionCookie, '', 0); setCookie(res, mfaCookie, '', 0); }
      return true;
    },
    /** Replace the account's recovery codes. Each signs in once. */
    newRecoveryCodes,
    verifyRecovery(code) {
      const digest = hash('recovery:' + recoveryShape(code));
      const ownerId = Object.keys(state.owners).find(id => (state.owners[id].recovery || []).includes(digest));
      if (!ownerId) throw new Error('That recovery code is not valid or was already used.');
      state.owners[ownerId].recovery = state.owners[ownerId].recovery.filter(d => d !== digest); save();
      return ownerId;
    },
    /** Effective hosted plan id ('free' | 'pro' | 'team') — honors active 7-day Pro trial automatically. */
    planOf: effectivePlanOf,
    /** Trial metadata for an owner. */
    trialOf: trialStateOf,
    /** Send 3-day, 1-day, and day-of trial reminder emails (idempotent across restarts). */
    async processTrialReminders() {
      if (!mailer?.configured()) return { sent: 0 };
      const t = now();
      let sentCount = 0;
      for (const [ownerId, row] of Object.entries(state.owners)) {
        if (!row.email || normalizePlan(row.plan) !== 'free' || typeof row.trialEndsAt !== 'number') continue;
        row.trialRemindersSent ||= {};
        const remaining = row.trialEndsAt - t;
        let kind = null, subject = '', body = '';
        if (remaining <= 0 && !row.trialRemindersSent.d0) {
          kind = 'd0';
          subject = 'Your Android Backstage Pro trial ends today — launch promo $4.50/mo';
          body = `Your 7-day Android Backstage Pro free trial ends today.\n\nNo card was collected, so your account automatically switches to the Personal plan (free forever: 1 device, 1 assistant, 1 virtual display).\n\nWant to keep Pro (5 devices, parallel virtual displays, screen recording, webhooks, and 128 MB transfers)? Subscribe during our first month of launch to lock in 50% off — $4.50/mo monthly (was $9/mo) or $42/yr yearly ($3.50/mo, was $84/yr) for as long as you keep your subscription active:\n\n${resolvedAccountUrl}\n`;
        } else if (remaining > 0 && remaining <= DAY_MS && !row.trialRemindersSent.d1) {
          kind = 'd1';
          subject = '1 day left on your Android Backstage Pro trial — first-month launch promo';
          body = `You have 1 day left on your 7-day Android Backstage Pro free trial.\n\nWhen the trial ends tomorrow, your account switches to Personal (free) with no charge. Subscribe during our first month of launch to lock in 50% off — $4.50/mo monthly or $42/yr yearly ($3.50/mo) for as long as you keep your subscription active:\n\n${resolvedAccountUrl}\n`;
        } else if (remaining > DAY_MS && remaining <= 3 * DAY_MS && !row.trialRemindersSent.d3) {
          kind = 'd3';
          subject = '3 days left on your Android Backstage Pro trial';
          body = `You have 3 days left on your 7-day Android Backstage Pro free trial.\n\nNo credit card is on file — if you do nothing, your account simply switches to Personal (free) when the trial ends. Subscribe during our first month of launch to lock in 50% off — $4.50/mo monthly or $42/yr yearly ($3.50/mo) for as long as you keep your subscription active:\n\n${resolvedAccountUrl}\n`;
        }
        if (!kind) continue;
        try {
          await mailer.send(row.email, subject, body);
          row.trialRemindersSent[kind] = t;
          save();
          sentCount++;
        } catch {
          // Transient email error: retry on the next sweep without marking as sent.
        }
      }
      return { sent: sentCount };
    },
    /** Update an owner's hosted plan (admin / billing). */
    setPlan(ownerId, plan) {
      if (!Object.hasOwn(state.owners, ownerId)) throw new Error('Unknown account.');
      const key = String(plan || '').trim().toLowerCase();
      if (!Object.hasOwn(PLANS, key)) throw new Error('Plan must be free, pro, or team.');
      state.owners[ownerId].plan = key;
      state.owners[ownerId].planUpdatedAt = now();
      if (key === 'free' && state.owners[ownerId].trialEndsAt && state.owners[ownerId].trialEndsAt > now()) {
        // Explicit admin downgrade to free ends an active trial immediately.
        state.owners[ownerId].trialEndsAt = now();
      }
      save();
      return { ownerId, plan: effectivePlanOf(ownerId), limits: planLimits(effectivePlanOf(ownerId)) };
    },
    /** Apply Stripe subscription state from billing webhooks. */
    applySubscription(ownerId, { plan, status = 'active', customerId = null, subscriptionId = null, promoLocked = false, cancelAtPeriodEnd = false, currentPeriodEnd = null } = {}) {
      if (!Object.hasOwn(state.owners, ownerId)) throw new Error('Unknown account.');
      const key = normalizePlan(plan);
      const row = state.owners[ownerId];
      row.plan = key;
      row.planUpdatedAt = now();
      if (key !== 'free' && typeof row.trialEndsAt === 'number' && row.trialEndsAt > now()) {
        row.trialConvertedAt = now();
      } else if (key === 'free' && typeof row.trialEndsAt === 'number' && row.trialEndsAt > now()) {
        row.trialEndsAt = now();
      }
      row.billing = {
        ...(row.billing || {}),
        customerId: customerId || row.billing?.customerId || null,
        subscriptionId: subscriptionId || row.billing?.subscriptionId || null,
        status,
        promoLocked: Boolean(promoLocked),
        cancelAtPeriodEnd: Boolean(cancelAtPeriodEnd),
        currentPeriodEnd: currentPeriodEnd || row.billing?.currentPeriodEnd || null,
        updatedAt: now(),
      };
      save();
      return { ownerId, plan: effectivePlanOf(ownerId), billing: row.billing };
    },
    markBillingStatus(ownerId, status) {
      if (!Object.hasOwn(state.owners, ownerId)) return false;
      state.owners[ownerId].billing = { ...(state.owners[ownerId].billing || {}), status, updatedAt: now() };
      save();
      return true;
    },
    /** List all owners (non-secret metadata for local admin tooling). */
    listOwners() {
      return Object.entries(state.owners).map(([id, row]) => ({
        id,
        email: row.email || null,
        plan: effectivePlanOf(id),
        basePlan: normalizePlan(row.plan),
        trial: trialStateOf(id),
        createdAt: row.createdAt || null,
        hasPassword: !!row.passwordHash,
        mfaEnabled: Boolean(row.mfa?.enabled),
        passkeyCount: Object.values(state.credentials).filter(c => c.ownerId === id).length,
        recoveryCodesLeft: (row.recovery || []).length,
      }));
    },
    /** Sign-in methods, plan, trial, billing, and MFA metadata on the account, without secrets. */
    methods(ownerId) {
      const row = state.owners[ownerId] || {};
      const plan = effectivePlanOf(ownerId);
      const trial = trialStateOf(ownerId);
      const billing = row.billing ? {
        status: row.billing.status || null,
        hasCustomer: Boolean(row.billing.customerId),
        customerId: row.billing.customerId || null,
        promoLocked: Boolean(row.billing.promoLocked),
        cancelAtPeriodEnd: Boolean(row.billing.cancelAtPeriodEnd),
        currentPeriodEnd: row.billing.currentPeriodEnd || null,
      } : { status: null, hasCustomer: false, customerId: null, promoLocked: false, cancelAtPeriodEnd: false, currentPeriodEnd: null };
      const activeSessions = Object.values(state.sessions).filter(s => s.ownerId === ownerId && s.expires > now()).length;
      return {
        email: row.email || null,
        emailAvailable: !!mailer?.configured(),
        plan,
        basePlan: normalizePlan(row.plan),
        limits: planLimits(plan),
        trial,
        billing,
        hasPassword: !!row.passwordHash,
        mfaEnabled: Boolean(row.mfa?.enabled),
        mfaEnabledAt: row.mfa?.enabledAt || null,
        activeSessions,
        passkeys: Object.values(state.credentials).filter(c => c.ownerId === ownerId).map(c => ({id: c.id, label: c.label || 'Passkey', createdAt: c.createdAt || null})),
        recoveryCodesLeft: (row.recovery || []).length,
      };
    },
    removePasskey(ownerId, id) {
      const credential = state.credentials[id];
      if (!credential || credential.ownerId !== ownerId) throw new Error('That passkey is not on this account.');
      if (!otherMethods(ownerId, {passkey: id})) throw new Error('Add an email or another passkey before removing your only way to sign in.');
      delete state.credentials[id]; save(); return true;
    },
    removeEmail(ownerId) {
      if (!state.owners[ownerId]?.email) return false;
      if (!otherMethods(ownerId, {email: true})) throw new Error('Add a passkey before removing your only way to sign in.');
      delete state.owners[ownerId].email;
      delete state.owners[ownerId].passwordHash;
      delete state.owners[ownerId].passwordUpdatedAt;
      save(); return true;
    },
    signOut(req, res) { delete state.sessions[hash(cookieValue(req,sessionCookie))]; save(); setCookie(res,sessionCookie,'',0); setCookie(res,mfaCookie,'',0); },
    async options(req, res, mode, native = false) {
      prune();
      if (!['register','authenticate'].includes(mode)) throw new Error('Cannot start sign-in. Try again shortly.');
      const existingOwner = native ? null : owner(req);
      if (native && !androidOrigins.length) throw new Error('Phone sign-in is not available on this server.');
      const ownerId = mode === 'register' ? existingOwner || 'owner_' + random() : null;
      const options = mode === 'register' ? await webauthn.generateRegistrationOptions({
        rpName:'Android Backstage', rpID, userName:'Backstage phone', userID:Buffer.from(ownerId), attestationType:'none',
        authenticatorSelection:{residentKey:'required',userVerification:'required'},
        excludeCredentials:Object.values(state.credentials).filter(c=>c.ownerId===ownerId).map(c=>({id:c.id,transports:c.transports}))
      }) : await webauthn.generateAuthenticationOptions({rpID,userVerification:'required'});
      const token = random();
      evictIfFull(challenges, 2000);
      challenges.set(hash(token), {mode,ownerId,existingOwner,native,challenge:options.challenge,expires:now()+300000});
      setCookie(res,challengeCookie,token,300); return options;
    },
    async verify(req, res, response, native = false) {
      const key = hash(cookieValue(req,challengeCookie)), pending = challenges.get(key);
      challenges.delete(key); setCookie(res,challengeCookie,'',0);
      if (!pending || !!pending.native !== native || pending.expires <= now()) throw new Error('Sign-in expired. Try again.');
      let ownerId;
      if (pending.mode === 'register') {
        if (pending.existingOwner && owner(req) !== pending.existingOwner) throw new Error('Sign in again before adding a passkey.');
        const verification = await webauthn.verifyRegistrationResponse({response,expectedChallenge:pending.challenge,expectedOrigin:native ? androidOrigins : origin,expectedRPID:rpID,requireUserVerification:true});
        if (!verification.verified || !verification.registrationInfo) throw new Error('Passkey was not verified.');
        const {credential} = verification.registrationInfo;
        if (Object.hasOwn(state.credentials,credential.id)) throw new Error('This passkey is already registered. Sign in instead.');
        ownerId = pending.ownerId;
        const t = now();
        state.owners[ownerId] ||= {createdAt:t, plan:'free', trialStartedAt:t, trialEndsAt:t+TRIAL_MS, trialRemindersSent:{}};
        state.credentials[credential.id] = {id:credential.id,ownerId,publicKey:Buffer.from(credential.publicKey).toString('base64url'),counter:credential.counter,transports:credential.transports,createdAt:t,label:native?'Phone passkey':'Passkey'};
      } else {
        const credential = typeof response?.id === 'string' && Object.hasOwn(state.credentials,response.id) ? state.credentials[response.id] : null;
        if (!credential) throw new Error('No matching passkey. Use the passkey saved when you connected your phone.');
        const verification = await webauthn.verifyAuthenticationResponse({response,expectedChallenge:pending.challenge,expectedOrigin:native ? androidOrigins : origin,expectedRPID:rpID,requireUserVerification:true,
          credential:{...credential,publicKey:Buffer.from(credential.publicKey,'base64url')}});
        if (!verification.verified) throw new Error('Passkey was not verified.');
        credential.counter = Math.max(credential.counter,verification.authenticationInfo.newCounter); ownerId = credential.ownerId;
      }
      if (native) { save(); return ownerId; }
      return issue(res, ownerId);
    }
  };
}
