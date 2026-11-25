import crypto from 'node:crypto';

/**
 * Zero-dependency Stripe Billing adapter for Android Backstage.
 * Supports:
 *  - Pro Monthly with 50% lifetime launch promo ($4.50/mo while subscribed)
 *  - Pro Yearly ($84/yr)
 *  - Team Monthly ($39/mo)
 *  - Stripe Customer Portal + HMAC-SHA256 webhook verification
 */
export function createBilling({
  secretKey = process.env.STRIPE_SECRET_KEY || '',
  webhookSecret = process.env.STRIPE_WEBHOOK_SECRET || '',
  priceProMonthly = process.env.STRIPE_PRICE_PRO_MONTHLY || '',
  priceProMonthlyPromo = process.env.STRIPE_PRICE_PRO_MONTHLY_PROMO || '',
  couponPro50 = process.env.STRIPE_COUPON_PRO_50_LIFETIME || '',
  priceProYearly = process.env.STRIPE_PRICE_PRO_YEARLY || '',
  priceProYearlyPromo = process.env.STRIPE_PRICE_PRO_YEARLY_PROMO || '',
  priceTeamMonthly = process.env.STRIPE_PRICE_TEAM_MONTHLY || '',
  apiBase = process.env.STRIPE_API_BASE || 'https://api.stripe.com',
  fetchImpl = fetch,
  now = Date.now,
} = {}) {
  const key = String(secretKey || '').trim();
  const whSecret = String(webhookSecret || '').trim();
  const proMonthlyPrice = String(priceProMonthlyPromo || priceProMonthly || '').trim();
  const useCouponMonthly = !String(priceProMonthlyPromo || '').trim() && Boolean(String(couponPro50 || '').trim());
  const proYearlyPrice = String(priceProYearlyPromo || priceProYearly || '').trim();
  const useCouponYearly = !String(priceProYearlyPromo || '').trim() && Boolean(String(couponPro50 || '').trim());
  const teamPrice = String(priceTeamMonthly || '').trim();

  const configured = () => Boolean(key && (proMonthlyPrice || proYearlyPrice || teamPrice));
  const webhookConfigured = () => Boolean(whSecret);

  const priceIdToPlan = (priceId) => {
    if (!priceId) return null;
    if (priceId === teamPrice) return 'team';
    if (priceId === proMonthlyPrice || priceId === String(priceProMonthly || '').trim() || priceId === proYearlyPrice || priceId === String(priceProYearly || '').trim()) return 'pro';
    return null;
  };

  async function stripePost(endpoint, params) {
    if (!key) throw new Error('Online billing is not enabled on this server yet.');
    const body = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null && v !== '') body.append(k, String(v));
    }
    const r = await fetchImpl(`${apiBase}${endpoint}`, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${key}`,
        'content-type': 'application/x-www-form-urlencoded',
      },
      body: body.toString(),
      signal: AbortSignal.timeout(15000),
    });
    const data = await r.json().catch(() => ({}));
    if (!r.ok) {
      throw new Error(data?.error?.message || 'Could not reach billing provider. Try again shortly.');
    }
    return data;
  }

  function verifyWebhookSignature(rawBody, signatureHeader) {
    if (!whSecret) throw new Error('Billing webhook is not configured.');
    const raw = Buffer.isBuffer(rawBody) ? rawBody : Buffer.from(String(rawBody || ''), 'utf8');
    const parts = String(signatureHeader || '').split(',').map((p) => p.trim());
    let timestamp = null;
    const v1s = [];
    for (const p of parts) {
      const idx = p.indexOf('=');
      if (idx <= 0) continue;
      const k = p.slice(0, idx), v = p.slice(idx + 1);
      if (k === 't') timestamp = Number(v);
      else if (k === 'v1') v1s.push(v);
    }
    if (!Number.isFinite(timestamp) || !v1s.length) throw new Error('Invalid Stripe signature header.');
    if (Math.abs(Math.floor(now() / 1000) - timestamp) > 300) throw new Error('Expired Stripe webhook timestamp.');
    const signedPayload = `${timestamp}.${raw.toString('utf8')}`;
    const expected = crypto.createHmac('sha256', whSecret).update(signedPayload).digest('hex');
    const expBuf = Buffer.from(expected, 'utf8');
    const matched = v1s.some((sig) => {
      const sigBuf = Buffer.from(sig, 'utf8');
      return sigBuf.length === expBuf.length && crypto.timingSafeEqual(sigBuf, expBuf);
    });
    if (!matched) throw new Error('Stripe signature verification failed.');
    return JSON.parse(raw.toString('utf8'));
  }

  return {
    configured,
    webhookConfigured,
    offers: () => ({
      configured: configured(),
      proMonthlyPromo: Boolean(key && proMonthlyPrice),
      proYearly: Boolean(key && proYearlyPrice),
      teamMonthly: Boolean(key && teamPrice),
    }),
    async createCheckoutSession({ ownerId, email, customerId, offer = 'pro_monthly_promo', successUrl, cancelUrl }) {
      let priceId = '', targetPlan = 'pro', promo = '', applyCoupon = false;
      if (offer === 'pro_monthly_promo') {
        priceId = proMonthlyPrice;
        targetPlan = 'pro';
        promo = 'launch50_lifetime';
        applyCoupon = useCouponMonthly;
      } else if (offer === 'pro_yearly') {
        priceId = proYearlyPrice;
        targetPlan = 'pro';
        promo = 'launch50_lifetime';
        applyCoupon = useCouponYearly;
      } else if (offer === 'team_monthly') {
        priceId = teamPrice;
        targetPlan = 'team';
      } else {
        throw new Error('Choose a valid plan option.');
      }
      if (!key || !priceId) {
        throw new Error('Online checkout is being finalized. Contact support@android-backstage.com to lock in the 50% off Pro launch offer.');
      }
      const params = {
        mode: 'subscription',
        success_url: successUrl,
        cancel_url: cancelUrl,
        client_reference_id: ownerId,
        'line_items[0][price]': priceId,
        'line_items[0][quantity]': 1,
        'metadata[ownerId]': ownerId,
        'metadata[plan]': targetPlan,
        'subscription_data[metadata][ownerId]': ownerId,
        'subscription_data[metadata][plan]': targetPlan,
      };
      if (promo) {
        params['metadata[promo]'] = promo;
        params['subscription_data[metadata][promo]'] = promo;
        if (applyCoupon) params['discounts[0][coupon]'] = String(couponPro50).trim();
      }
      if (customerId) params.customer = customerId;
      else if (email) params.customer_email = email;
      const session = await stripePost('/v1/checkout/sessions', params);
      if (!session?.url) throw new Error('Billing session did not return a checkout URL.');
      return { id: session.id, url: session.url };
    },
    async createPortalSession({ customerId, returnUrl }) {
      if (!customerId) throw new Error('No active billing account found.');
      const session = await stripePost('/v1/billing_portal/sessions', {
        customer: customerId,
        return_url: returnUrl,
      });
      if (!session?.url) throw new Error('Could not open billing portal.');
      return { url: session.url };
    },
    verifyWebhookSignature,
    handleWebhookEvent(event, owners) {
      const type = event?.type || '';
      const obj = event?.data?.object || {};
      if (type === 'checkout.session.completed' && obj.mode === 'subscription') {
        const ownerId = obj.metadata?.ownerId || obj.client_reference_id || owners.ownerByStripeCustomer?.(obj.customer);
        if (!ownerId || !owners.exists(ownerId)) return { handled: false, reason: 'unknown_owner' };
        const plan = obj.metadata?.plan === 'team' ? 'team' : 'pro';
        const promoLocked = obj.metadata?.promo === 'launch50_lifetime';
        owners.applySubscription(ownerId, {
          plan,
          status: 'active',
          customerId: obj.customer || null,
          subscriptionId: obj.subscription || null,
          promoLocked,
        });
        return { handled: true, ownerId, plan, promoLocked };
      }
      if (type === 'customer.subscription.updated' || type === 'customer.subscription.deleted') {
        const ownerId = obj.metadata?.ownerId || owners.ownerByStripeCustomer?.(obj.customer);
        if (!ownerId || !owners.exists(ownerId)) return { handled: false, reason: 'unknown_owner' };
        const status = type === 'customer.subscription.deleted' ? 'canceled' : String(obj.status || 'active');
        const active = ['active', 'trialing', 'past_due'].includes(status);
        const priceId = obj.items?.data?.[0]?.price?.id || '';
        const mappedPlan = obj.metadata?.plan === 'team' ? 'team' : (priceIdToPlan(priceId) || 'pro');
        const promoLocked = active && (obj.metadata?.promo === 'launch50_lifetime' || priceId === String(priceProMonthlyPromo || '').trim() || priceId === String(priceProYearlyPromo || '').trim());
        owners.applySubscription(ownerId, {
          plan: active ? mappedPlan : 'free',
          status,
          customerId: obj.customer || null,
          subscriptionId: obj.id || null,
          promoLocked,
          cancelAtPeriodEnd: Boolean(obj.cancel_at_period_end),
          currentPeriodEnd: obj.current_period_end ? obj.current_period_end * 1000 : null,
        });
        return { handled: true, ownerId, plan: active ? mappedPlan : 'free', status };
      }
      if (type === 'invoice.payment_failed') {
        const ownerId = owners.ownerByStripeCustomer?.(obj.customer);
        if (!ownerId || !owners.exists(ownerId)) return { handled: false, reason: 'unknown_owner' };
        owners.markBillingStatus?.(ownerId, 'past_due');
        return { handled: true, ownerId, status: 'past_due' };
      }
      return { handled: false, reason: 'ignored_event' };
    },
  };
}
