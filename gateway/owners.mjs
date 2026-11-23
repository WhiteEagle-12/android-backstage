import crypto from 'node:crypto';
import fs from 'node:fs';
import {writePrivateJson} from '../cli/state.mjs';
import * as webauthn from '@simplewebauthn/server';

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

// Single writer, persistent owner records. Only passkey public keys, hashed
// session tokens and hashed one-time codes are stored here. An account can sign
// in with an emailed code, a passkey, or a recovery code; any one is enough.
// Phone link secrets live in the connection store.
export function createOwners({stateFile, origin, androidOrigins = [], mailer = null, now = Date.now}) {
  const rpID = new URL(origin).hostname;
  const state = fs.existsSync(stateFile) ? JSON.parse(fs.readFileSync(stateFile, 'utf8')) : {owners:{}, credentials:{}, sessions:{}, challenges:{}};
  state.emailCodes ||= {};
  const save = () => writePrivateJson(stateFile, state);
  function prune() {
    for (const table of ['sessions','challenges','emailCodes']) for (const [key,row] of Object.entries(state[table])) if (row.expires <= now()) delete state[table][key];
  }
  const ownerByEmail = email => Object.keys(state.owners).find(id => state.owners[id].email === email) || null;
  const equalHash = (a, b) => typeof a === 'string' && typeof b === 'string' && a.length === b.length && crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b));
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
    /** Email a six-digit code. Used to sign in, create an account, or add an email to one (linkOwner). */
    async startEmail(value, {linkOwner = null} = {}) {
      prune();
      const email = normalEmail(value);
      if (!email) throw new Error('Enter a valid email address.');
      if (!mailer?.configured()) throw new Error('Email sign-in is not set up on this server. Use a passkey.');
      if (linkOwner) { const taken = ownerByEmail(email); if (taken && taken !== linkOwner) throw new Error('That email already belongs to another account.'); }
      const key = hash('email:' + email), previous = state.emailCodes[key];
      const recent = (previous?.sent || []).filter(at => at > now() - 900000);
      if (recent.length >= 5 || Object.keys(state.emailCodes).length >= 1000) throw new Error('Too many codes were sent. Wait a few minutes.');
      const code = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
      state.emailCodes[key] = {codeHash: hash(email + ':' + code), expires: now() + 600000, attempts: 0, sent: [...recent, now()], linkOwner};
      save();
      await mailer.send(email, `${code} is your Android Backstage code`, `Your Android Backstage code is ${code}.\n\nIt expires in 10 minutes. If you did not ask for it, ignore this email.`);
      return {sent: true, expiresIn: 600};
    },
    /** Check an emailed code. Returns the account, creating one for a new address. */
    verifyEmail(value, code, { expectedLinkOwner = null } = {}) {
      prune();
      const email = normalEmail(value), key = email && hash('email:' + email), row = key && state.emailCodes[key];
      if (!row) throw new Error('That code expired. Ask for a new one.');
      if (expectedLinkOwner && row.linkOwner !== expectedLinkOwner) throw new Error('That code expired. Ask for a new one.');
      if (++row.attempts > 5) { delete state.emailCodes[key]; save(); throw new Error('Too many wrong codes. Ask for a new one.'); }
      if (!equalHash(hash(email + ':' + String(code || '').replace(/\D/g, '')), row.codeHash)) { save(); throw new Error('That code is not right. Check the email and try again.'); }
      delete state.emailCodes[key];
      if (row.linkOwner) {
        const taken = ownerByEmail(email);
        if (taken && taken !== row.linkOwner) { save(); throw new Error('That email already belongs to another account.'); }
        state.owners[row.linkOwner].email = email; save(); return row.linkOwner;
      }
      let ownerId = ownerByEmail(email);
      if (!ownerId) { ownerId = 'owner_' + random(); state.owners[ownerId] = {createdAt: now(), email}; }
      save(); return ownerId;
    },
    /** Permanently delete an owner, all passkeys, sessions, pending challenges, and email codes. */
    deleteOwner(ownerId, req = null, res = null) {
      if (!Object.hasOwn(state.owners, ownerId)) return false;
      delete state.owners[ownerId];
      for (const [id, cred] of Object.entries(state.credentials)) if (cred.ownerId === ownerId) delete state.credentials[id];
      for (const [id, sess] of Object.entries(state.sessions)) if (sess.ownerId === ownerId) delete state.sessions[id];
      for (const [id, ch] of Object.entries(state.challenges)) if (ch.ownerId === ownerId || ch.existingOwner === ownerId) delete state.challenges[id];
      for (const [id, ec] of Object.entries(state.emailCodes)) if (ec.linkOwner === ownerId) delete state.emailCodes[id];
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
      delete state.owners[ownerId].email; save(); return true;
    },
    signOut(req, res) { delete state.sessions[hash(cookieValue(req,sessionCookie))]; save(); setCookie(res,sessionCookie,'',0); },
    async options(req, res, mode, native = false) {
      prune();
      if (!['register','authenticate'].includes(mode) || Object.keys(state.challenges).length >= 1000) throw new Error('Cannot start sign-in. Try again shortly.');
      const existingOwner = native ? null : owner(req);
      if (native && !androidOrigins.length) throw new Error('Phone sign-in is not available on this server.');
      const ownerId = mode === 'register' ? existingOwner || 'owner_' + random() : null;
      const options = mode === 'register' ? await webauthn.generateRegistrationOptions({
        rpName:'Android Backstage', rpID, userName:'Backstage phone', userID:Buffer.from(ownerId), attestationType:'none',
        authenticatorSelection:{residentKey:'required',userVerification:'required'},
        excludeCredentials:Object.values(state.credentials).filter(c=>c.ownerId===ownerId).map(c=>({id:c.id,transports:c.transports}))
      }) : await webauthn.generateAuthenticationOptions({rpID,userVerification:'required'});
      const token = random();
      state.challenges[hash(token)] = {mode,ownerId,existingOwner,native,challenge:options.challenge,expires:now()+300000};
      save(); setCookie(res,challengeCookie,token,300); return options;
    },
    async verify(req, res, response, native = false) {
      const key = hash(cookieValue(req,challengeCookie)), pending = state.challenges[key];
      delete state.challenges[key]; save(); setCookie(res,challengeCookie,'',0);
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
