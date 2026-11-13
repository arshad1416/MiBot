import { Hono, type Context } from 'hono';
import { getConnInfo } from '@hono/node-server/conninfo';
import { env } from './env.js';
import { createSession, listDevices, removeDevice, requireUser, sessionToken, type AuthVars } from './lib/auth.js';
import { describeDevice } from './lib/devices.js';
import { moveJob, stopJob } from './agent/loop.js';
import { getJob, openJobs } from './jobs.js';
import { checkIn, createMachine, deleteMachine, listMachines, machineByToken, takeEvents, updateMachine, type MachineRow } from './machines.js';
import { createPairCode, PairError, pairStatus, redeemPairCode } from './pairing.js';
import { getBotRow } from './store.js';

/**
 * Machines, signed-in devices and the jobs waiting on machines, for the app; the two calls a worker on another machine
 * makes to this server; and pairing, which brings a new device in with a code. Mounted in index.ts.
 */

// ---------- for the signed-in user ----------

export const machinesApi = new Hono<AuthVars>();
for (const p of ['/machines', '/machines/*', '/devices/*', '/jobs/*']) machinesApi.use(p, requireUser);

machinesApi.get('/machines', (c) => {
  const userId = c.get('user').id;
  return c.json({ machines: listMachines(userId), jobs: openJobs(userId), devices: listDevices(userId, sessionToken(c)) });
});

machinesApi.post('/machines', async (c) => {
  const p = await c.req.json<{ name?: string; takes?: string }>();
  if (!p.name?.trim()) return c.json({ error: 'Give the machine a name.' }, 400);
  return c.json(createMachine(c.get('user').id, p.name, p.takes ?? 'both'));
});

machinesApi.patch('/machines/:id', async (c) => {
  const machine = updateMachine(c.get('user').id, c.req.param('id'), await c.req.json());
  return machine ? c.json({ machine }) : c.json({ error: 'not found' }, 404);
});

machinesApi.delete('/machines/:id', (c) =>
  deleteMachine(c.get('user').id, c.req.param('id')) ? c.json({ ok: true }) : c.json({ error: 'not found' }, 404));

machinesApi.delete('/devices/:id', (c) =>
  removeDevice(c.get('user').id, c.req.param('id')) ? c.json({ ok: true }) : c.json({ error: 'not found' }, 404));

const ownJob = (userId: string, id: string) => {
  const job = getJob(id);
  return job && getBotRow(job.bot_id)?.user_id === userId ? job : null;
};

machinesApi.post('/jobs/:id/move', async (c) => {
  const userId = c.get('user').id;
  const job = ownJob(userId, c.req.param('id'));
  if (!job) return c.json({ error: 'not found' }, 404);
  const { machineId } = await c.req.json<{ machineId: string }>();
  if (!listMachines(userId).some((m) => m.id === machineId)) return c.json({ error: 'not found' }, 404);
  const why = moveJob(job.id, machineId);
  return why ? c.json({ error: why }, 409) : c.json({ ok: true });
});

machinesApi.post('/jobs/:id/stop', (c) => {
  const job = ownJob(c.get('user').id, c.req.param('id'));
  if (!job) return c.json({ error: 'not found' }, 404);
  stopJob(job.id);
  return c.json({ ok: true });
});

// ---------- for a worker ----------

export const workerApi = new Hono<{ Variables: { machine: MachineRow } }>();

workerApi.use('/*', async (c, next) => {
  const m = machineByToken((c.req.header('authorization') ?? '').replace(/^Bearer\s+/i, ''));
  if (!m) return c.json({ error: 'unknown machine' }, 401);
  if (!m.enabled) return c.json({ error: 'this machine is switched off in the app' }, 403);
  c.set('machine', m);
  await next();
});

workerApi.post('/checkin', async (c) => c.json(await checkIn(c.get('machine'), await c.req.json().catch(() => ({})))));

workerApi.post('/jobs/:id/events', async (c) => {
  const p = await c.req.json<{ attempt: number; from: number; events?: any[]; done?: any }>();
  const next = takeEvents(c.get('machine'), c.req.param('id'), Number(p.attempt), Number(p.from) || 0, Array.isArray(p.events) ? p.events : [], p.done);
  return next === null ? c.json({ error: 'gone' }, 410) : c.json({ next });
});

// ---------- pairing: a new device hands in a code ----------

export const pairApi = new Hono<AuthVars>();
for (const p of ['/codes', '/codes/*']) pairApi.use(p, requireUser);

const OWN_ORIGINS = new Set([env.appUrl, env.serverUrl].map((u) => new URL(u).origin));

// A page on another site, open in the owner's browser, must not be able to hand in codes in their name.
pairApi.use('/*', async (c, next) => {
  const origin = c.req.header('origin');
  if (c.req.method !== 'GET' && origin && !OWN_ORIGINS.has(origin)) return c.json({ error: 'not allowed from this page' }, 403);
  await next();
});

/** A request's JSON body as an object, whatever was sent. */
async function fields(c: Context): Promise<Record<string, unknown>> {
  const body = await c.req.json().catch(() => null);
  return body && typeof body === 'object' && !Array.isArray(body) ? body : {};
}
const text = (v: unknown) => (typeof v === 'string' ? v : '');

const LOOPBACK = /^(127\.|::1$|::ffff:127\.)/;

/**
 * Where a request comes from, for counting wrong codes. Behind a proxy on this machine (Tailscale
 * Serve, a tunnel) every request arrives from the machine itself, so the address the proxy passes
 * on is used; from anywhere else that header is whatever the sender likes and is ignored.
 */
function callerAddress(c: Context): string {
  const peer = getConnInfo(c).remote.address ?? 'unknown';
  const forwarded = c.req.header('x-forwarded-for')?.split(',')[0].trim();
  return LOOPBACK.test(peer) && forwarded ? forwarded.slice(0, 64) : peer;
}

pairApi.post('/codes', async (c) => {
  const p = await fields(c);
  const made = createPairCode(c.get('user').id, text(p.name), text(p.takes) || 'both');
  // The code rides in the link's fragment, which browsers keep to themselves.
  return c.json({ ...made, link: `${env.appUrl}/pair#${made.code}`, hubUrl: env.serverUrl });
});

pairApi.get('/codes/:id', (c) => {
  const status = pairStatus(c.get('user').id, c.req.param('id'));
  return status ? c.json(status) : c.json({ error: 'not found' }, 404);
});

/** A wrong, used or expired code is the caller's to fix; anything else is ours. */
const pairFailure = (e: unknown) => (e instanceof PairError ? { error: e.message, status: 400 as const } : { error: 'Could not add this device.', status: 500 as const });

// A worker with no settings yet: it becomes one of the code owner's machines and gets its token.
pairApi.post('/machine', async (c) => {
  const p = await fields(c);
  try {
    const made = redeemPairCode(text(p.code), 'machine', callerAddress(c), (code) => {
      const { machine, token } = createMachine(code.userId, code.name || text(p.name).trim() || 'Machine', code.takes);
      return { name: machine.name, token };
    });
    return c.json(made);
  } catch (e) {
    const f = pairFailure(e);
    return c.json({ error: f.error }, f.status);
  }
});

// A phone or another browser: it is signed in as the code's owner.
pairApi.post('/sign-in', async (c) => {
  const p = await fields(c);
  try {
    const made = redeemPairCode(text(p.code), 'browser', callerAddress(c), (code) => ({ name: describeDevice(c.req.header('user-agent') ?? '').phrase, userId: code.userId }));
    createSession(c, made.userId);
    return c.json({ ok: true });
  } catch (e) {
    const f = pairFailure(e);
    return c.json({ error: f.error }, f.status);
  }
});
