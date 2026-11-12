import { Hono } from 'hono';
import { db } from './db.js';
import { env } from './env.js';
import { requireUser, type AuthVars } from './lib/auth.js';
import { box } from './box.js';
import { readyScreen } from './computer.js';
import { openTerminal } from './desk.js';
import { applyAccess, knownProviders, signInCommand, signInsChangedAt, spreadSignIn } from './engine.js';
import { recentChoices } from './jobs.js';
import { listMachines } from './machines.js';
import { KEY_PROVIDERS, listEndpoints, removeAccess, saveEndpoint, savedKeys, saveKey, SUBSCRIPTIONS } from './modelAccess.js';
import { autoBlocked, listRoster, minConfidence, removeRosterModel, saveRosterModel, setMinConfidence } from './routing.js';
import type { AutoInfo, ModelAccessInfo } from './types.js';

/**
 * Settings › Models: API keys, endpoints of the person's own and subscription sign-ins, added once
 * for all of their Bots, and the models Auto chooses from for each job (routing.ts). Mounted at
 * /api/models in index.ts.
 */
export const modelsApi = new Hono<AuthVars>();
modelsApi.use('*', requireUser);

/** Why a subscription cannot be signed in to from this server, or null when it can. */
const signInBlocked = (): string | null =>
  box.local ? null : 'Signing in opens a window on the Bots’ computer, which works once this app runs on that computer.';

/** How long a sign-in started from Settings is waited for. */
const SIGN_IN_MS = 30 * 60_000;
const signingIn = new Map<string, NodeJS.Timeout>();

/**
 * Hermes writes a finished sign-in to the machine's own home. Until the wait runs out, each time
 * that file changes from how it was at `seen`, the sign-in is handed on to the person's Bots.
 */
function handOnSignIn(userId: string, provider: string, seen: number) {
  clearInterval(signingIn.get(userId));
  const until = Date.now() + SIGN_IN_MS;
  const watch = setInterval(() => {
    const changed = signInsChangedAt();
    if (changed > seen) {
      seen = changed;
      spreadSignIn(userId, provider);
    }
    if (Date.now() > until) {
      clearInterval(watch);
      signingIn.delete(userId);
    }
  }, 3000);
  watch.unref();
  signingIn.set(userId, watch);
}

async function info(userId: string): Promise<ModelAccessInfo> {
  const known = await knownProviders(userId);
  const reached = new Set(known.filter((p) => p.connected).map((p) => p.id));
  const saved = new Set(savedKeys(userId));
  return {
    keys: KEY_PROVIDERS.map((p) => ({ id: p.id, name: p.name, saved: saved.has(p.id), connected: reached.has(p.id) })),
    // A saved key also makes Hermes report the provider as reachable; that says nothing about the subscription.
    subscriptions: SUBSCRIPTIONS.map((s) => ({ id: s.id, name: s.name, note: s.note, connected: reached.has(s.id) && !saved.has(s.id) })),
    endpoints: listEndpoints(userId),
    signInBlocked: signInBlocked(),
    checked: known.length > 0,
  };
}

const fail = (e: unknown) => String((e as any)?.message ?? e).slice(0, 300);

modelsApi.get('/', async (c) => c.json(await info(c.get('user').id)));

modelsApi.put('/keys/:id', async (c) => {
  const userId = c.get('user').id;
  const { key } = await c.req.json<{ key?: string }>().catch(() => ({} as { key?: string }));
  try { saveKey(userId, c.req.param('id'), key); } catch (e) { return c.json({ error: fail(e) }, 400); }
  applyAccess(userId);
  return c.json(await info(userId));
});

modelsApi.delete('/keys/:id', async (c) => {
  const userId = c.get('user').id;
  if (!removeAccess(userId, c.req.param('id'), 'key')) return c.json({ error: 'not found' }, 404);
  applyAccess(userId);
  return c.json(await info(userId));
});

modelsApi.post('/endpoints', async (c) => {
  const userId = c.get('user').id;
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({} as Record<string, unknown>));
  try { saveEndpoint(userId, body); } catch (e) { return c.json({ error: fail(e) }, 400); }
  applyAccess(userId);
  return c.json(await info(userId));
});

modelsApi.delete('/endpoints/:id', async (c) => {
  const userId = c.get('user').id;
  if (!removeAccess(userId, c.req.param('id'), 'endpoint')) return c.json({ error: 'not found' }, 404);
  applyAccess(userId);
  return c.json(await info(userId));
});

/**
 * Sign in to a subscription for all of the person's Bots. Hermes does the sign-in in a terminal on
 * one Bot's screen, into the machine's own Hermes home (the one new Bots start from); the answer
 * names that Bot so the app can show its screen.
 */
modelsApi.post('/sign-in', async (c) => {
  const userId = c.get('user').id;
  const blocked = signInBlocked();
  if (blocked) return c.json({ error: blocked }, 409);
  const { provider } = await c.req.json<{ provider?: string }>().catch(() => ({} as { provider?: string }));
  const sub = SUBSCRIPTIONS.find((s) => s.id === provider);
  if (!sub) return c.json({ error: 'That is not a subscription this app can sign in to.' }, 400);
  const bot = db.prepare("SELECT id FROM bots WHERE user_id=? AND COALESCE(kind,'agent') <> 'room' AND COALESCE(hidden,0)=0 ORDER BY is_primary DESC, created_at ASC LIMIT 1").get(userId) as { id: string } | undefined;
  if (!bot) return c.json({ error: 'Make a Bot first: the sign-in window opens on a Bot’s screen.' }, 409);
  const before = signInsChangedAt();
  try {
    const s = await readyScreen(userId, bot.id);
    await openTerminal(s.screenId, signInCommand(env.hermesHome, sub.id), `Sign in: ${sub.name}`);
  } catch (e) {
    return c.json({ error: fail(e) }, 502);
  }
  handOnSignIn(userId, sub.id, before);
  return c.json({ botId: bot.id });
});

// ---------- Auto: the models a job's model is chosen from ----------

async function autoInfo(userId: string): Promise<AutoInfo> {
  return {
    roster: listRoster(userId),
    minConfidence: minConfidence(userId),
    blocked: autoBlocked(userId),
    providers: await knownProviders(userId),
    machines: listMachines(userId).map((m) => ({ id: m.id, name: m.name })),
    recent: recentChoices(userId),
  };
}

modelsApi.get('/auto', async (c) => c.json(await autoInfo(c.get('user').id)));

modelsApi.put('/auto', async (c) => {
  const userId = c.get('user').id;
  const { minConfidence: value } = await c.req.json<{ minConfidence?: unknown }>().catch(() => ({} as { minConfidence?: unknown }));
  try { setMinConfidence(userId, value); } catch (e) { return c.json({ error: fail(e) }, 400); }
  return c.json(await autoInfo(userId));
});

modelsApi.post('/roster', async (c) => {
  const userId = c.get('user').id;
  const body = await c.req.json<Record<string, unknown>>().catch(() => ({} as Record<string, unknown>));
  try { saveRosterModel(userId, body); } catch (e) { return c.json({ error: fail(e) }, 400); }
  return c.json(await autoInfo(userId));
});

modelsApi.delete('/roster/:id', async (c) => {
  const userId = c.get('user').id;
  if (!removeRosterModel(userId, c.req.param('id'))) return c.json({ error: 'not found' }, 404);
  return c.json(await autoInfo(userId));
});
