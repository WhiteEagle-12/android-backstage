import crypto from 'node:crypto';
import fs from 'node:fs';
import {promisify} from 'node:util';
import {writePrivateJson} from '../cli/state.mjs';
import * as webauthn from '@simplewebauthn/server';
const scryptAsync = promisify(crypto.scrypt);

export const sessionCookie = '__Host-backstage-owner';
export const challengeCookie = '__Host-backstage-passkey';
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
// session tokens and hashed one-time codes are stored here. An account can sign
// in with an emailed code, a passkey, or a recovery code; any one is enough.
// Phone link secrets live in the connection store.
export function createOwners({stateFile, origin, androidOrigins = [], mailer = null, dailyEmailCap = Number(process.env.BACKSTAGE_DAILY_EMAIL_CAP || 5000), now = Date.now}) {
  const rpID = new URL(origin).hostname;
  const loaded = fs.existsSync(stateFile) ? JSON.parse(fs.readFileSync(stateFile, 'utf8')) : {owners:{}, credentials:{}, sessions:{}};
  const state = {owners: loaded.owners || {}, credentials: loaded.credentials || {}, sessions: loaded.sessions || {}};
  // Ephemeral codes & WebAuthn challenges stay strictly in RAM (zero-retention + avoids fsync stalls).
  const challenges = new Map();
  const emailCodes = new Map();
  const emailRates = new Map();
  const passwordRates = new Map();
  let emailDay = { day: '', count: 0 };
  const save = () => writePrivateJson(stateFile, state);
  if (loaded.challenges || loaded.emailCodes) save();
  function prune() {
    const t = now();
    for (const [key, row] of Object.entries(state.sessions)) if (row.expires <= t) delete state.sessions[key];
    for (const [key, row] of challenges) if (row.expires <= t) challenges.delete(key);
    for (const [key, row] of emailCodes) if (row.expires <= t) emailCodes.delete(key);
    for (const map of [emailRates, passwordRates]) {
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
  const ownerByEmail = email => Object.keys(state.owners).find(id => state.owners[id].email === email) || null;
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
  function otherMethods(ownerId, except = {}) {
    const row = state.owners[ownerId] || {};
    return (row.email && !except.email ? 1 : 0) + Object.values(state.credentials).filter(c => c.ownerId === ownerId && c.id !== except.passkey).length + ((row.recovery || []).length && !except.recovery ? 1 : 0);
  }
  function owner(req) {
    const token = cookieValue(req, sessionCookie);
    const row = token && Object.hasOwn(state.sessions, hash(token)) ? state.sessions[hash(token)] : null;
    return row && row.expires > now() && Object.hasOwn(state.owners,row.ownerId) ? row.ownerId : null;
  }
  return {
    owner,
    issue,
    ownerByEmail: value => {const email = normalEmail(value); return email ? ownerByEmail(email) : null;},
    exists: id => Object.hasOwn(state.owners,id),
    emailAvailable: () => !!mailer?.configured(),
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
        'Your Android Backstage verification code is {code}.\n\nEnter this code to finish creating your account. It expires in 10 minutes. If you did not sign up, ignore this email.',
        { passwordHash }
      );
    },
    /** Create account step 2: verify OTP and create the verified account with its hashed password. */
    verifySignUp(value, code) {
      prune();
      const email = normalEmail(value);
      if (!email) throw new Error('Enter a valid email address.');
      const row = consumeOtp(email, 'signup', code);
      if (ownerByEmail(email)) throw new Error('An account with that email already exists. Log in instead.');
      const ownerId = 'owner_' + random();
      const t = now();
      state.owners[ownerId] = { createdAt: t, email, passwordHash: row.passwordHash, passwordUpdatedAt: t };
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
      if (ownerEmail) {
        for (const scope of ['signin', 'signup', 'reset']) emailCodes.delete(emailCodeKey(ownerEmail, null, scope));
        emailRates.delete(hash('rate:' + ownerEmail));
        passwordRates.delete(hash('pw:' + ownerEmail));
      }
      save();
      if (res) setCookie(res, sessionCookie, '', 0);
      return true;
    },
    /** Replace the account's recovery codes. Each signs in once. */
    newRecoveryCodes(ownerId) {
      if (!Object.hasOwn(state.owners, ownerId)) throw new Error('Unknown account.');
      const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
      const codes = Array.from({length: 10}, () => Array.from({length: 12}, () => alphabet[crypto.randomInt(alphabet.length)]).join('').replace(/(.{4})(.{4})(.{4})/, '$1-$2-$3'));
      state.owners[ownerId].recovery = codes.map(code => hash('recovery:' + recoveryShape(code)));
      save(); return codes;
    },
    verifyRecovery(code) {
      const digest = hash('recovery:' + recoveryShape(code));
      const ownerId = Object.keys(state.owners).find(id => (state.owners[id].recovery || []).includes(digest));
      if (!ownerId) throw new Error('That recovery code is not valid or was already used.');
      state.owners[ownerId].recovery = state.owners[ownerId].recovery.filter(d => d !== digest); save();
      return ownerId;
    },
    /** Sign-in methods on the account, without secrets. */
    methods(ownerId) {
      const row = state.owners[ownerId] || {};
      return {email: row.email || null, emailAvailable: !!mailer?.configured(),
        hasPassword: !!row.passwordHash,
        passkeys: Object.values(state.credentials).filter(c => c.ownerId === ownerId).map(c => ({id: c.id, label: c.label || 'Passkey', createdAt: c.createdAt || null})),
        recoveryCodesLeft: (row.recovery || []).length};
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
    signOut(req, res) { delete state.sessions[hash(cookieValue(req,sessionCookie))]; save(); setCookie(res,sessionCookie,'',0); },
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
        state.owners[ownerId] ||= {createdAt:now()};
        state.credentials[credential.id] = {id:credential.id,ownerId,publicKey:Buffer.from(credential.publicKey).toString('base64url'),counter:credential.counter,transports:credential.transports,createdAt:now(),label:native?'Phone passkey':'Passkey'};
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
