import { execFile } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { gatewayPid, healthy, homeKey, keyAccepted, readDotenv, spawnGateway, stopGateway, writeHermesHome } from '../gateway.js';
import { sseEvents, type StreamEvent } from '../lib/sse.js';
import type { JobResult, JobSpec, WorkerInfo } from '../machines.js';
import { pairWithHub, parsePairLink, waitForPairing } from './pairing.js';

/**
 * The worker: lets another machine run bots' jobs for the hub.
 *
 *   npm run worker                  (settings in ~/.mibot/worker.env, see docs/machines.md)
 *   npm run pair -- '<link>'        connect this machine with the link the app shows (worker/pairing.ts)
 *
 * With no settings yet it waits to be connected: it serves a page on this machine that takes the
 * code from the app, and starts working once it has been given one.
 *
 * It only ever calls the hub, so it needs no open port. It checks in every few seconds to ask for
 * work. For a job it starts the bot's own Hermes agent here (a home under WORKER_DIR/engines, with
 * this machine's model sign-ins), runs the turn and posts the agent's event stream to the hub as it
 * comes. When this machine sleeps the check-ins stop, which is how the hub knows; after it wakes, a
 * run that survived carries on and one that did not is reported as interrupted so the hub can have it
 * picked up again.
 */

// ---------- settings ----------

const settingsFile = process.env.MIBOT_WORKER_ENV ?? path.join(os.homedir(), '.mibot', 'worker.env');
let fileVars = readDotenv(settingsFile);
const setting = (name: string, fallback = '') => process.env[name] ?? fileVars[name] ?? fallback;

if (process.argv[2] === 'pair') {
  // `pair <link>` or `pair <hub address> <code>`
  const given = process.argv[4] ? { hub: process.argv[3], code: process.argv[4] } : parsePairLink(process.argv[3] ?? '');
  if (!given) {
    console.error("usage: npm run pair -- '<link from Settings › Machines › Add>'   (or: npm run pair -- <hub address> <code>)");
    process.exit(2);
  }
  try {
    console.log(`Connected to ${given.hub} as ${await pairWithHub(settingsFile, given.hub, given.code)}. Settings are in ${settingsFile}.`);
    process.exit(0);
  } catch (e: any) {
    console.error(e?.message ?? e);
    process.exit(1);
  }
}

if (!setting('HUB_URL') || !setting('WORKER_TOKEN')) {
  await waitForPairing(settingsFile, Number(setting('WORKER_PAIR_PORT', '8765')));
  fileVars = readDotenv(settingsFile);
}
const hubUrl = setting('HUB_URL').replace(/\/+$/, '');
const workerToken = setting('WORKER_TOKEN');

const cfg = {
  dir: setting('WORKER_DIR', path.join(os.homedir(), '.mibot', 'worker')),
  hermesBin: setting('HERMES_BIN', 'hermes'),
  /** This machine's own Hermes home: the agents started here inherit its model settings and sign-ins. */
  primaryHome: setting('HERMES_HOME_PRIMARY', path.join(os.homedir(), '.hermes')),
  portBase: Number(setting('WORKER_PORT_BASE', '8750')),
  /** Jobs run at once. */
  slots: Math.max(1, Number(setting('WORKER_SLOTS', '1')) || 1),
  /** Take no new job while less memory than this is available (0 = do not check). */
  minFreeMb: Number(setting('WORKER_MIN_FREE_MB', '0')) || 0,
  /** Take no new job while the agents started here use more than this together (0 = do not check). */
  maxAgentsMb: Number(setting('WORKER_MAX_AGENTS_MB', '0')) || 0,
  /** Take no new job between these local times, e.g. 06:30-07:50. */
  quietHours: setting('WORKER_QUIET_HOURS'),
  /** Stop a bot's agent after this many idle minutes to give its memory back (0 = leave it running). */
  idleStopMin: Number(setting('WORKER_IDLE_STOP_MIN', '15')) || 0,
};

const enginesDir = path.join(cfg.dir, 'engines');
fs.mkdirSync(enginesDir, { recursive: true });

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

// ---------- talking to the hub ----------

async function hubPost(pathname: string, body: unknown, timeoutMs: number): Promise<{ status: number; json: any }> {
  const r = await fetch(`${hubUrl}${pathname}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${workerToken}` },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs),
  });
  return { status: r.status, json: await r.json().catch(() => ({})) };
}

// ---------- is this machine free to take a job ----------

function freeMb(): number {
  try {
    const m = /MemAvailable:\s+(\d+) kB/.exec(fs.readFileSync('/proc/meminfo', 'utf8'));
    if (m) return Math.round(Number(m[1]) / 1024);
  } catch { /* not Linux */ }
  return Math.round(os.freemem() / 1048576);
}

/** Minutes since midnight for "HH:MM", or null. */
function minutes(hhmm: string): number | null {
  const m = /^(\d{1,2}):(\d{2})$/.exec(hhmm.trim());
  return m && Number(m[1]) < 24 && Number(m[2]) < 60 ? Number(m[1]) * 60 + Number(m[2]) : null;
}

function quietUntil(): string | null {
  const [a, b] = cfg.quietHours.split('-');
  const from = minutes(a ?? '');
  const to = minutes(b ?? '');
  if (from === null || to === null || from === to) return null;
  const d = new Date();
  const t = d.getHours() * 60 + d.getMinutes();
  const inside = from < to ? t >= from && t < to : t >= from || t < to;
  return inside ? b.trim() : null;
}

/** Memory in MB of each process and its children, from one `ps`. */
function processTree(): Promise<Map<number, { ppid: number; rssMb: number }>> {
  return new Promise((resolve) => {
    execFile('ps', ['-eo', 'pid=,ppid=,rss='], { timeout: 5000 }, (err, out) => {
      const map = new Map<number, { ppid: number; rssMb: number }>();
      if (!err) for (const line of out.split('\n')) {
        const [pid, ppid, rss] = line.trim().split(/\s+/).map(Number);
        if (pid) map.set(pid, { ppid, rssMb: (rss || 0) / 1024 });
      }
      resolve(map);
    });
  });
}

let agentsMb = 0;
async function measureAgents() {
  const roots = fs.readdirSync(enginesDir).map((id) => gatewayPid(path.join(enginesDir, id))).filter((p): p is number => !!p);
  if (!roots.length) { agentsMb = 0; return; }
  const tree = await processTree();
  const mine = new Set(roots);
  // Children are listed after their parents often enough, but not always: sweep until nothing is added.
  for (let grew = true; grew;) {
    grew = false;
    for (const [pid, p] of tree) if (!mine.has(pid) && mine.has(p.ppid)) { mine.add(pid); grew = true; }
  }
  agentsMb = Math.round([...mine].reduce((sum, pid) => sum + (tree.get(pid)?.rssMb ?? 0), 0));
}

function info(): WorkerInfo {
  const base: WorkerInfo = { platform: os.platform(), memFreeMb: freeMb(), memTotalMb: Math.round(os.totalmem() / 1048576), slots: cfg.slots, accepting: true };
  const quiet = quietUntil();
  if (quiet) return { ...base, accepting: false, reason: `quiet hours until ${quiet}` };
  if (cfg.minFreeMb && base.memFreeMb! < cfg.minFreeMb) return { ...base, accepting: false, reason: `low on memory (${base.memFreeMb} MB free)` };
  if (cfg.maxAgentsMb && agentsMb > cfg.maxAgentsMb) return { ...base, accepting: false, reason: `its agents are using ${agentsMb} MB` };
  return base;
}

// ---------- a bot's agent on this machine ----------

const portsFile = path.join(cfg.dir, 'ports.json');
function portFor(botId: string): number {
  let ports: Record<string, number> = {};
  try { ports = JSON.parse(fs.readFileSync(portsFile, 'utf8')); } catch { /* first one */ }
  if (!ports[botId]) {
    const used = new Set(Object.values(ports));
    let p = cfg.portBase;
    while (used.has(p)) p++;
    ports[botId] = p;
    fs.writeFileSync(portsFile, JSON.stringify(ports, null, 2));
  }
  return ports[botId];
}

const MEMORY_FILES = ['MEMORY.md', 'USER.md'];

function readMemory(home: string): Record<string, string | null> {
  const out: Record<string, string | null> = {};
  for (const f of MEMORY_FILES) {
    try { out[f] = fs.readFileSync(path.join(home, 'memories', f), 'utf8'); } catch { out[f] = null; }
  }
  return out;
}

/** Before a job the hub's copy of the bot's memory replaces whatever an earlier job left here. */
function writeMemory(home: string, files: Record<string, string | null>) {
  fs.mkdirSync(path.join(home, 'memories'), { recursive: true });
  for (const f of MEMORY_FILES) {
    const file = path.join(home, 'memories', f);
    if (typeof files[f] === 'string') fs.writeFileSync(file, files[f]);
    else fs.rmSync(file, { force: true });
  }
}

const starting = new Map<string, Promise<{ url: string; key: string; home: string }>>();
const lastUsed = new Map<string, number>();

async function startEngine(spec: JobSpec): Promise<{ url: string; key: string; home: string }> {
  const home = path.join(enginesDir, spec.botId);
  const url = `http://127.0.0.1:${portFor(spec.botId)}`;
  fs.mkdirSync(home, { recursive: true });
  writeMemory(home, spec.memory);
  fs.writeFileSync(path.join(home, 'SOUL.md'), spec.soul);

  if ((await healthy(url)) && homeKey(home) && (await keyAccepted(url, homeKey(home)))) return { url, key: homeKey(home), home };
  if (await healthy(url)) {
    if (gatewayPid(home)) await stopGateway(home);
    else throw new Error(`Port ${portFor(spec.botId)} is taken by something else.`);
  }
  const key = writeHermesHome({
    home, primaryHome: cfg.primaryHome, port: portFor(spec.botId), soul: spec.soul, cwd: path.join(home, 'workspace'),
    mcpServers: { hq: { url: `${hubUrl}${spec.mcp.path}`, headers: { Authorization: `Bearer ${spec.mcp.token}` }, timeout: 1800, connect_timeout: 30 } },
  });
  if (gatewayPid(home)) await stopGateway(home);
  await spawnGateway({ hermesBin: cfg.hermesBin, home, url });
  console.log(`[worker] ${spec.botName}: agent up on ${url}`);
  return { url, key, home };
}

/** Start (or adopt) the bot's agent. Concurrent calls share one start. */
function engineFor(spec: JobSpec) {
  let p = starting.get(spec.botId);
  if (!p) {
    p = startEngine(spec).finally(() => starting.delete(spec.botId));
    starting.set(spec.botId, p);
  }
  return p;
}

// ---------- a job ----------

interface Job {
  key: string;
  spec: JobSpec;
  ctrl: AbortController;
  /** Events not yet taken by the hub; `base` is the index of the first one. */
  events: StreamEvent[];
  base: number;
  result: JobResult | null;
  /** The hub does not want this run any more. */
  dropped: boolean;
  /** This machine slept while the job ran, and when it woke. */
  wokeAt: number;
  lastEventAt: number;
  wake: (() => void) | null;
}

const jobs = new Map<string, Job>();

/** A one-second clock that fires this late means the machine was asleep (or stopped) in between. */
const SLEEP_GAP_MS = 15_000;
let lastTick = Date.now();
const keyOf = (spec: JobSpec) => `${spec.jobId}@${spec.attempt}`;

/** This machine slept while the job ran. The second test catches a break noticed before the clock below has ticked. */
const slept = (job: Job) => !!job.wokeAt || Date.now() - lastTick > SLEEP_GAP_MS;

async function runAgent(job: Job) {
  const { spec } = job;
  let home = path.join(enginesDir, spec.botId);
  try {
    const engine = await engineFor(spec);
    home = engine.home;
    const res = await fetch(`${engine.url}/v1/responses`, {
      method: 'POST',
      signal: job.ctrl.signal,
      headers: { 'content-type': 'application/json', accept: 'text/event-stream', ...(engine.key ? { authorization: `Bearer ${engine.key}` } : {}) },
      body: JSON.stringify(spec.request),
    });
    if (!res.ok || !res.body) throw new Error((await res.text().catch(() => '')).slice(0, 300) || `the agent answered ${res.status}`);
    for await (const ev of sseEvents(res.body)) {
      // After a sleep the agent's own connections are often dead. That is an interruption, not the agent failing.
      if (slept(job) && (ev.type === 'response.failed' || ev.type === 'error')) throw new Error('the run broke when this machine slept');
      job.events.push(ev);
      job.lastEventAt = Date.now();
      job.wake?.();
    }
    job.result = { ok: true, memory: readMemory(home) };
  } catch (e: any) {
    job.result = { ok: false, error: String(e?.message ?? e), interrupted: slept(job), memory: readMemory(home) };
  }
  lastUsed.set(spec.botId, Date.now());
  job.wake?.();
}

/** Post the job's events to the hub as they come, in order, until its result has been taken. */
async function sendEvents(job: Job) {
  let wait = 500;
  while (!job.dropped) {
    if (!job.events.length && !job.result) {
      await new Promise<void>((r) => { job.wake = () => { job.wake = null; r(); }; });
      await sleep(120); // let a burst gather into one post
      continue;
    }
    const batch = job.events.slice(0, 200);
    const last = !!job.result && batch.length === job.events.length;
    try {
      const { status, json } = await hubPost(`/api/worker/jobs/${job.spec.jobId}/events`, { attempt: job.spec.attempt, from: job.base, events: batch, ...(last ? { done: job.result } : {}) }, 30_000);
      if (status === 410) break;
      if (status !== 200 || typeof json.next !== 'number') throw new Error(`the hub answered ${status}`);
      const taken = Math.max(0, Math.min(json.next - job.base, job.events.length));
      job.events.splice(0, taken);
      job.base += taken;
      wait = 500;
      if (last && taken === batch.length) break;
    } catch {
      // The hub is out of reach (often: this machine just woke and the network is not back). Keep everything and try again.
      await sleep(wait);
      wait = Math.min(wait * 2, 10_000);
    }
  }
  job.dropped = true;
  job.ctrl.abort();
  jobs.delete(job.key);
}

function startJob(spec: JobSpec) {
  const key = keyOf(spec);
  if (jobs.has(key)) return;
  const job: Job = { key, spec, ctrl: new AbortController(), events: [], base: 0, result: null, dropped: false, wokeAt: 0, lastEventAt: Date.now(), wake: null };
  jobs.set(key, job);
  lastUsed.set(spec.botId, Date.now());
  console.log(`[worker] ${spec.botName}: job ${key}`);
  void runAgent(job);
  void sendEvents(job);
}

function dropJob(key: string) {
  const job = jobs.get(key);
  if (!job) return;
  job.dropped = true;
  job.ctrl.abort();
  job.wake?.();
}

// ---------- the clock: sleep, idle agents ----------

/** How long after waking a silent run is given before it is called broken. */
const WAKE_GRACE_MS = 45_000;

setInterval(() => {
  const t = Date.now();
  if (t - lastTick > SLEEP_GAP_MS) {
    console.log(`[worker] awake again after ${Math.round((t - lastTick) / 1000)} s`);
    for (const job of jobs.values()) if (!job.result) job.wokeAt = t;
  }
  lastTick = t;
  for (const job of jobs.values()) {
    if (job.wokeAt && !job.result && t - job.wokeAt > WAKE_GRACE_MS && job.lastEventAt < job.wokeAt) job.ctrl.abort();
  }
}, 1000);

setInterval(() => {
  void measureAgents();
  if (!cfg.idleStopMin) return;
  const busy = new Set([...jobs.values()].map((j) => j.spec.botId));
  for (const [botId, at] of lastUsed) {
    if (busy.has(botId) || starting.has(botId) || Date.now() - at < cfg.idleStopMin * 60_000) continue;
    lastUsed.delete(botId);
    console.log(`[worker] stopping idle agent ${botId}`);
    void stopGateway(path.join(enginesDir, botId));
  }
}, 60_000);

// ---------- the loop ----------

async function main() {
  console.log(`[worker] ${os.hostname()} working for ${hubUrl}`);
  await measureAgents();
  let quiet = false;
  let wait = 2000;
  for (;;) {
    try {
      const { status, json } = await hubPost('/api/worker/checkin', { info: info(), running: [...jobs.keys()] }, 40_000);
      if (status === 401 || status === 403) {
        if (!quiet) console.error(`[worker] the hub turned this machine away: ${json?.error ?? status}`);
        quiet = true;
        await sleep(30_000);
        continue;
      }
      if (status !== 200) throw new Error(`the hub answered ${status}`);
      if (quiet) console.log('[worker] connected to the hub again');
      quiet = false;
      wait = 2000;
      for (const key of json.cancel ?? []) dropJob(String(key));
      for (const spec of json.jobs ?? []) startJob(spec as JobSpec);
    } catch (e: any) {
      if (!quiet) console.warn(`[worker] cannot reach the hub (${e?.message ?? e}); trying again`);
      quiet = true;
      await sleep(wait);
      wait = Math.min(wait * 2, 15_000);
    }
  }
}

void main();
