import os from 'node:os';
import { db, now } from './db.js';
import { newId, sha256, token } from './lib/ids.js';
import { hub } from './lib/hub.js';
import type { StreamEvent } from './lib/sse.js';
import type { JobWeight, Machine } from './types.js';

/**
 * The machines that run bots' jobs.
 *
 * This server is the hub: it keeps the conversations, the queue and the schedules, and it can run jobs
 * itself. Any other machine runs the worker (worker/main.ts), which only ever calls the hub: it checks
 * in every few seconds to ask for work, runs a job on its own Hermes agent and posts the agent's event
 * stream back. A machine that stops checking in (asleep, off, off the network) is 'away'; a job it was
 * running is paused until it returns.
 *
 * This file holds the registry, the rule that picks a machine for a job, and the hub's end of a job
 * running elsewhere. agent/loop.ts drives it.
 */

export const HUB = 'hub';

/** A worker checks in at least every POLL_WAIT_MS; after this long without a word it counts as away. */
const AWAY_AFTER_MS = 30_000;
/** How long a check-in is held open when there is nothing to hand out. */
const POLL_WAIT_MS = 10_000;

export interface MachineRow {
  id: string; user_id: string; name: string; token_hash: string | null; takes: JobWeight | 'both';
  rank: number; enabled: number; info: string; last_seen_at: number | null; created_at: number;
}

/** What a worker says about itself each time it checks in. */
export interface WorkerInfo {
  platform?: string;
  memFreeMb?: number;
  memTotalMb?: number;
  /** Jobs it runs at once. */
  slots?: number;
  /** False while it will not start another job (low memory, quiet hours); `reason` says why. */
  accepting?: boolean;
  reason?: string;
}

// ---------- things changing ----------

const listeners = new Set<() => void>();

/** Something a waiting job cares about changed: a machine came or went, a slot freed, a setting moved. */
export function machinesChanged() {
  for (const fn of [...listeners]) fn();
  for (const u of db.prepare('SELECT id FROM users').all() as { id: string }[]) hub.emit(u.id, { type: 'machines' });
}

/** Resolves at the next change, or after `ms`. Rejects when the signal aborts. */
export function nextChange(signal: AbortSignal, ms: number): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(new Error('interrupted'));
    const done = (fn: () => void) => { listeners.delete(wake); clearTimeout(timer); signal.removeEventListener('abort', stop); fn(); };
    const wake = () => done(resolve);
    const stop = () => done(() => reject(new Error('interrupted')));
    const timer = setTimeout(wake, ms);
    listeners.add(wake);
    signal.addEventListener('abort', stop, { once: true });
  });
}

// ---------- the registry ----------

function ensureHub() {
  if (db.prepare('SELECT 1 FROM machines WHERE id=?').get(HUB)) return;
  db.prepare("INSERT INTO machines(id,user_id,name,takes,rank,created_at) VALUES (?,?,?,'both',0,?)").run(HUB, '', os.hostname() || 'This machine', now());
}
ensureHub();

export const machineRow = (id: string) => db.prepare('SELECT * FROM machines WHERE id=?').get(id) as MachineRow | undefined;

/** The hub and the user's own machines, in the order jobs are offered to them. */
function rowsFor(userId: string): MachineRow[] {
  return db.prepare("SELECT * FROM machines WHERE id=? OR user_id=? ORDER BY rank ASC, created_at ASC").all(HUB, userId) as MachineRow[];
}

const infoOf = (m: MachineRow): WorkerInfo => { try { return JSON.parse(m.info) ?? {}; } catch { return {}; } };

const isOnline = (m: MachineRow) => m.id === HUB || (m.last_seen_at !== null && now() - m.last_seen_at < AWAY_AFTER_MS);

const runningOn = (id: string) => (db.prepare("SELECT COUNT(*) n FROM jobs WHERE machine_id=? AND status='running'").get(id) as any).n as number;

/** Jobs a machine runs at once; 0 = no limit (the hub, unless HUB_SLOTS says otherwise). */
function slotsOf(m: MachineRow): number {
  if (m.id === HUB) return Math.max(0, Number(process.env.HUB_SLOTS ?? 0) || 0);
  return Math.max(1, Number(infoOf(m).slots ?? 1) || 1);
}

function toMachine(m: MachineRow): Machine {
  const info = infoOf(m);
  const online = isOnline(m);
  const hubMem = m.id === HUB ? { memFreeMb: Math.round(os.freemem() / 1048576), memTotalMb: Math.round(os.totalmem() / 1048576), platform: os.platform() } : null;
  return {
    id: m.id, name: m.name, isHub: m.id === HUB, takes: m.takes, rank: m.rank, enabled: !!m.enabled,
    state: m.id !== HUB && m.last_seen_at === null ? 'never' : !online ? 'away' : info.accepting === false ? 'busy' : 'online',
    reason: online && info.accepting === false ? info.reason ?? null : null,
    lastSeenAt: m.id === HUB ? now() : m.last_seen_at,
    platform: hubMem?.platform ?? info.platform ?? null,
    memFreeMb: hubMem?.memFreeMb ?? info.memFreeMb ?? null,
    memTotalMb: hubMem?.memTotalMb ?? info.memTotalMb ?? null,
    slots: slotsOf(m), running: runningOn(m.id),
  };
}

export const listMachines = (userId: string): Machine[] => rowsFor(userId).map(toMachine);

const TAKES = ['light', 'heavy', 'both'];

/** Register another machine. The token is shown once; only its hash is kept. */
export function createMachine(userId: string, name: string, takes: string): { machine: Machine; token: string } {
  const t = token();
  const id = newId('mch');
  const rank = ((db.prepare('SELECT MAX(rank) r FROM machines WHERE id=? OR user_id=?').get(HUB, userId) as any).r ?? 0) + 1;
  db.prepare('INSERT INTO machines(id,user_id,name,token_hash,takes,rank,created_at) VALUES (?,?,?,?,?,?,?)')
    .run(id, userId, name.trim().slice(0, 60) || 'Machine', sha256(t), TAKES.includes(takes) ? takes : 'both', rank, now());
  machinesChanged();
  return { machine: toMachine(machineRow(id)!), token: t };
}

const owns = (userId: string, m: MachineRow | undefined): m is MachineRow => !!m && (m.id === HUB || m.user_id === userId);

export function updateMachine(userId: string, id: string, p: { name?: string; takes?: string; rank?: number; enabled?: boolean }): Machine | null {
  const m = machineRow(id);
  if (!owns(userId, m)) return null;
  if (typeof p.name === 'string' && p.name.trim()) db.prepare('UPDATE machines SET name=? WHERE id=?').run(p.name.trim().slice(0, 60), id);
  if (typeof p.takes === 'string' && TAKES.includes(p.takes)) db.prepare('UPDATE machines SET takes=? WHERE id=?').run(p.takes, id);
  if (typeof p.rank === 'number' && Number.isFinite(p.rank)) db.prepare('UPDATE machines SET rank=? WHERE id=?').run(Math.round(p.rank), id);
  if (typeof p.enabled === 'boolean') db.prepare('UPDATE machines SET enabled=? WHERE id=?').run(p.enabled ? 1 : 0, id);
  machinesChanged();
  return toMachine(machineRow(id)!);
}

/** Forget a machine. A job it was running is picked up again elsewhere. The hub cannot be removed. */
export function deleteMachine(userId: string, id: string): boolean {
  const m = machineRow(id);
  if (!owns(userId, m) || id === HUB) return false;
  db.prepare('DELETE FROM machines WHERE id=?').run(id);
  db.prepare('DELETE FROM machine_threads WHERE machine_id=?').run(id);
  for (const run of runs.values()) if (run.machineId === id) push(run, { gone: 'moved' });
  machinesChanged();
  return true;
}

export function machineByToken(t: string): MachineRow | null {
  if (!t) return null;
  return (db.prepare('SELECT * FROM machines WHERE token_hash=?').get(sha256(t)) as MachineRow | undefined) ?? null;
}

// ---------- which machine takes a job ----------

export interface JobAsk {
  userId: string;
  weight: JobWeight;
  /** The job must run here: it started here, or the user moved it here. Ignored when that machine is gone or switched off. */
  pin: string | null;
  /** The bot is the machine's own Hermes agent, which lives on the hub. */
  hubOnly: boolean;
}

const names = (ms: MachineRow[]) => ms.map((m) => m.name).join(' or ');

/** The machine to run a job on now, or why it has to wait. */
export function place(ask: JobAsk): { machine: MachineRow } | { wait: string } {
  let able = rowsFor(ask.userId).filter((m) => m.enabled);
  const pinned = ask.pin ? able.find((m) => m.id === ask.pin) : undefined;
  if (ask.hubOnly) able = able.filter((m) => m.id === HUB);
  else if (pinned) able = [pinned];
  else able = able.filter((m) => m.takes === 'both' || m.takes === ask.weight);
  if (!able.length) return { wait: ask.hubOnly ? `${machineRow(HUB)!.name} is switched off for jobs` : `No machine is set to take ${ask.weight} jobs` };

  const online = able.filter(isOnline);
  if (!online.length) return { wait: `Waiting for ${names(able)} to wake up` };
  const open = online.filter((m) => infoOf(m).accepting !== false);
  if (!open.length) {
    const why = infoOf(online[0]).reason;
    return { wait: why ? `${online[0].name} is not taking jobs: ${why}` : `${names(online)} is not taking jobs right now` };
  }
  const free = open.find((m) => { const s = slotsOf(m); return s === 0 || runningOn(m.id) < s; });
  return free ? { machine: free } : { wait: `${names(open)} is busy with another job` };
}

// ---------- a job running on another machine ----------

/** Everything a worker needs to run one job. */
export interface JobSpec {
  jobId: string;
  attempt: number;
  botId: string;
  botName: string;
  /** The bot's persona, the hub's MCP endpoint for it (path and bearer token), and its memory files. */
  soul: string;
  mcp: { path: string; token: string };
  memory: Record<string, string | null>;
  /** Body for the agent's POST /v1/responses. */
  request: Record<string, unknown>;
}

export interface JobResult {
  ok: boolean;
  error?: string;
  /** The run broke because the machine slept or lost the network, not because the agent failed. */
  interrupted?: boolean;
  memory?: Record<string, string | null>;
}

export type RunItem =
  | { ev: StreamEvent }
  | { done: JobResult }
  /** The machine stopped checking in / is checking in again. */
  | { lost: true } | { back: true }
  /** The run is over on that machine: the worker no longer has it, or the job was moved away. */
  | { gone: 'dropped' | 'moved' };

interface RemoteRun {
  spec: JobSpec;
  machineId: string;
  delivered: boolean;
  /** Index of the next event we have not seen. */
  next: number;
  lost: boolean;
  ended: boolean;
  items: RunItem[];
  wake: (() => void) | null;
}

const runs = new Map<string, RemoteRun>();
/** Machines the list currently shows as away, so a change of state is announced once. */
const awayNow = new Set<string>();
const outboxWaiters = new Map<string, () => void>();

function push(run: RemoteRun, item: RunItem) {
  if (run.ended) return;
  if ('done' in item || 'gone' in item) run.ended = true;
  run.items.push(item);
  run.wake?.();
}

/** Hand a job to a machine. It goes out with the machine's next check-in. */
export function startRemote(machineId: string, spec: JobSpec): RemoteRun {
  const run: RemoteRun = { spec, machineId, delivered: false, next: 0, lost: false, ended: false, items: [], wake: null };
  runs.set(spec.jobId, run);
  outboxWaiters.get(machineId)?.();
  return run;
}

/** The next thing that happened to a remote run. Rejects when the signal aborts. */
export async function nextItem(run: RemoteRun, signal: AbortSignal): Promise<RunItem> {
  for (;;) {
    const item = run.items.shift();
    if (item) return item;
    await new Promise<void>((resolve, reject) => {
      if (signal.aborted) return reject(new Error('interrupted'));
      const stop = () => { run.wake = null; reject(new Error('interrupted')); };
      run.wake = () => { run.wake = null; signal.removeEventListener('abort', stop); resolve(); };
      signal.addEventListener('abort', stop, { once: true });
    });
  }
}

/** The hub is done with this run. If the worker still has it, its next check-in tells it to stop. */
export function endRemote(run: RemoteRun) {
  run.ended = true;
  if (runs.get(run.spec.jobId) === run) runs.delete(run.spec.jobId);
  outboxWaiters.get(run.machineId)?.();
}

/** Take a job away from the machine it is on (the user moved it). */
export function pullRemote(jobId: string) {
  const run = runs.get(jobId);
  if (run) push(run, { gone: 'moved' });
}

const runKey = (jobId: string, attempt: number) => `${jobId}@${attempt}`;

function seen(m: MachineRow, info?: WorkerInfo) {
  const wasOnline = isOnline(m);
  db.prepare('UPDATE machines SET last_seen_at=?, info=? WHERE id=?').run(now(), info ? JSON.stringify(info) : m.info, m.id);
  awayNow.delete(m.id);
  for (const run of runs.values()) {
    if (run.machineId === m.id && run.lost && !run.ended) { run.lost = false; push(run, { back: true }); }
  }
  const was = infoOf(m);
  if (!wasOnline || (info && (was.accepting !== info.accepting || was.reason !== info.reason || was.slots !== info.slots))) machinesChanged();
}

/**
 * A worker checks in: note that it is alive, tell it which of its jobs we no longer want, and hand it
 * new ones. Held open for a while when there is nothing to say.
 */
export async function checkIn(m: MachineRow, body: { info?: WorkerInfo; running?: string[] }): Promise<{ jobs: JobSpec[]; cancel: string[] }> {
  seen(m, body.info ?? {});
  const theirs = new Set(Array.isArray(body.running) ? body.running.map(String) : []);
  const mine = () => [...runs.values()].filter((r) => r.machineId === m.id && !r.ended);

  // A job we handed over that the worker no longer knows about was lost on its side (it restarted).
  for (const run of mine()) {
    if (run.delivered && !theirs.has(runKey(run.spec.jobId, run.spec.attempt))) push(run, { gone: 'dropped' });
  }
  const answer = () => {
    const live = new Set(mine().map((r) => runKey(r.spec.jobId, r.spec.attempt)));
    const jobs = mine().filter((r) => !r.delivered);
    for (const r of jobs) r.delivered = true;
    return { jobs: jobs.map((r) => r.spec), cancel: [...theirs].filter((k) => !live.has(k)) };
  };

  let out = answer();
  if (!out.jobs.length && !out.cancel.length) {
    await new Promise<void>((resolve) => {
      const done = () => { clearTimeout(timer); if (outboxWaiters.get(m.id) === done) outboxWaiters.delete(m.id); resolve(); };
      const timer = setTimeout(done, POLL_WAIT_MS);
      outboxWaiters.get(m.id)?.();
      outboxWaiters.set(m.id, done);
    });
    out = answer();
  }
  return out;
}

/**
 * A worker posts the next stretch of a job's event stream (and, at the end, how it went). Events
 * carry their index, so a stretch sent twice after a dropped connection is taken once.
 * Returns the index we expect next, or null when we no longer want this run.
 */
export function takeEvents(m: MachineRow, jobId: string, attempt: number, from: number, events: StreamEvent[], done?: JobResult): number | null {
  seen(m);
  const run = runs.get(jobId);
  if (!run || run.ended || run.machineId !== m.id || run.spec.attempt !== attempt) return null;
  events.forEach((ev, i) => {
    if (from + i !== run.next) return;
    run.next++;
    push(run, { ev });
  });
  if (done && from + events.length === run.next) push(run, { done });
  return run.next;
}

// A machine that has gone quiet does not say goodbye: watch the clock for the jobs it holds.
setInterval(() => {
  let changed = false;
  for (const run of runs.values()) {
    if (run.ended || run.lost) continue;
    const m = machineRow(run.machineId);
    if (m && isOnline(m)) continue;
    run.lost = true;
    push(run, { lost: true });
    changed = true;
  }
  // The same clock turns "online" into "away" for the people looking at the list.
  for (const m of db.prepare('SELECT * FROM machines WHERE id<>? AND last_seen_at IS NOT NULL').all(HUB) as MachineRow[]) {
    const away = !isOnline(m);
    if (away !== awayNow.has(m.id)) { away ? awayNow.add(m.id) : awayNow.delete(m.id); changed = true; }
  }
  if (changed) machinesChanged();
}, 5000).unref();
