import { Hono } from 'hono';
import { env } from './env.js';
import { requireUser, type AuthVars } from './lib/auth.js';
import { moveJob, stopJob } from './agent/loop.js';
import { getJob, openJobs } from './jobs.js';
import { checkIn, createMachine, deleteMachine, listMachines, machineByToken, takeEvents, updateMachine, type MachineRow } from './machines.js';
import { getBotRow } from './store.js';

/**
 * Machines and the jobs waiting on them, for the app; and the two calls a worker on another machine
 * makes to this server. Mounted in index.ts.
 */

// ---------- for the signed-in user ----------

export const machinesApi = new Hono<AuthVars>();
for (const p of ['/machines', '/machines/*', '/jobs/*']) machinesApi.use(p, requireUser);

machinesApi.get('/machines', (c) => {
  const userId = c.get('user').id;
  return c.json({ machines: listMachines(userId), jobs: openJobs(userId), hubUrl: env.serverUrl });
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
