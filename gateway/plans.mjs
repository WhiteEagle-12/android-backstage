/**
 * Hosted plan tiers and limits — single source of truth enforced across
 * device enrollment, assistant authorization, parallel backstage screens,
 * recording, webhooks, file transfers, and daily fair-use action caps.
 */
export const PLAN_NAMES = ['free', 'pro', 'team'];

export const PLANS = Object.freeze({
  free: Object.freeze({
    id: 'free',
    name: 'Personal',
    maxDevices: 1,
    maxAssistants: 1,
    maxParallelScreens: 1,
    dailyActions: 500,
    screenRecording: false,
    webhooks: false,
    maxFileBytes: 15 * 1024 * 1024,
  }),
  pro: Object.freeze({
    id: 'pro',
    name: 'Pro',
    maxDevices: 5,
    maxAssistants: 50,
    maxParallelScreens: 20,
    dailyActions: 10_000,
    screenRecording: true,
    webhooks: true,
    maxFileBytes: 128 * 1024 * 1024,
  }),
  team: Object.freeze({
    id: 'team',
    name: 'Team',
    maxDevices: 25,
    maxAssistants: 200,
    maxParallelScreens: 50,
    dailyActions: 50_000,
    screenRecording: true,
    webhooks: true,
    maxFileBytes: 128 * 1024 * 1024,
  }),
});

export function normalizePlan(value) {
  const key = String(value || 'free').trim().toLowerCase();
  return Object.hasOwn(PLANS, key) ? key : 'free';
}

export function planLimits(plan) {
  return PLANS[normalizePlan(plan)];
}
