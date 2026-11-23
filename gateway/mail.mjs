import fs from 'node:fs';

function assertSingleLine(value, label) {
  const s = String(value ?? '');
  if (/[\r\n\x00]/.test(s)) throw new Error(`Invalid ${label}.`);
  return s;
}

/**
 * Sends account email (sign-in codes).
 *
 * Preferred for production: Resend transactional email API via
 * BACKSTAGE_RESEND_API_KEY + BACKSTAGE_MAIL_FROM (default:
 * "Android Backstage <login@android-backstage.com>").
 *
 * Fallback for single-owner personal deployments: Gmail API OAuth via
 * BACKSTAGE_GMAIL_CLIENT and BACKSTAGE_GMAIL_TOKEN.
 */
export function createMailer({
  resendApiKey = process.env.BACKSTAGE_RESEND_API_KEY,
  resendEndpoint = process.env.BACKSTAGE_RESEND_URL || 'https://api.resend.com/emails',
  clientFile = process.env.BACKSTAGE_GMAIL_CLIENT,
  tokenFile = process.env.BACKSTAGE_GMAIL_TOKEN,
  fetchImpl = fetch,
  from = process.env.BACKSTAGE_MAIL_FROM || '',
} = {}) {
  const resendConfigured = () => Boolean(String(resendApiKey || '').trim());
  const gmailConfigured = () => Boolean(clientFile && tokenFile && fs.existsSync(clientFile) && fs.existsSync(tokenFile));
  const configured = () => resendConfigured() || gmailConfigured();

  let access = null;
  async function token() {
    if (access && access.expires > Date.now() + 60000) return access.value;
    const client = JSON.parse(fs.readFileSync(clientFile, 'utf8'));
    const saved = JSON.parse(fs.readFileSync(tokenFile, 'utf8'));
    const response = await fetchImpl('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        client_id: client.client_id,
        client_secret: client.client_secret,
        refresh_token: saved.refresh_token,
        grant_type: 'refresh_token',
      }),
      signal: AbortSignal.timeout(15000),
    });
    const body = await response.json();
    if (!response.ok || !body.access_token) throw new Error('Email is unavailable right now.');
    access = { value: body.access_token, expires: Date.now() + (body.expires_in || 3600) * 1000 };
    return access.value;
  }

  let address = null;
  async function gmailSender() {
    const label = assertSingleLine(from || 'Android Backstage', 'sender');
    if (address === null) {
      try {
        const r = await fetchImpl('https://gmail.googleapis.com/gmail/v1/users/me/profile', {
          headers: { authorization: 'Bearer ' + (await token()) },
          signal: AbortSignal.timeout(15000),
        });
        address = r.ok ? (await r.json()).emailAddress || '' : '';
      } catch {
        address = '';
      }
    }
    return address ? `${label} <${assertSingleLine(address, 'sender address')}>` : label;
  }

  return {
    configured,
    provider: () => (resendConfigured() ? 'resend' : gmailConfigured() ? 'gmail' : 'none'),
    async send(to, subject, text) {
      if (!configured()) throw new Error('Email sign-in is not set up on this server.');
      const safeTo = assertSingleLine(to, 'recipient').trim();
      const safeSubject = assertSingleLine(subject, 'subject').trim();
      const bodyText = String(text ?? '');

      if (resendConfigured()) {
        const safeFrom = assertSingleLine(from || 'Android Backstage <login@android-backstage.com>', 'sender').trim();
        const response = await fetchImpl(resendEndpoint, {
          method: 'POST',
          headers: {
            authorization: 'Bearer ' + String(resendApiKey).trim(),
            'content-type': 'application/json',
          },
          body: JSON.stringify({ from: safeFrom, to: [safeTo], subject: safeSubject, text: bodyText }),
          signal: AbortSignal.timeout(15000),
        });
        if (!response.ok) throw new Error('The sign-in email could not be sent. Try again.');
        return;
      }

      const raw = [
        `From: ${await gmailSender()}`,
        `To: ${safeTo}`,
        `Subject: ${safeSubject}`,
        'MIME-Version: 1.0',
        'Content-Type: text/plain; charset=UTF-8',
        '',
        bodyText,
      ].join('\r\n');
      const response = await fetchImpl('https://gmail.googleapis.com/gmail/v1/users/me/messages/send', {
        method: 'POST',
        headers: { authorization: 'Bearer ' + (await token()), 'content-type': 'application/json' },
        body: JSON.stringify({ raw: Buffer.from(raw).toString('base64url') }),
        signal: AbortSignal.timeout(15000),
      });
      if (!response.ok) throw new Error('The sign-in email could not be sent. Try again.');
    },
  };
}
