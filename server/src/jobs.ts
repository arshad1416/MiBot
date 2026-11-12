import { db, now } from './db.js';
import { newId } from './lib/ids.js';
import { HUB } from './machines.js';
import type { JobInfo, JobWeight, RowBody } from './types.js';

/**
 * The queue, on disk.
 *
 * Every turn a bot is asked to take is a job: what set it off, how big it is, which machine has it
 * and how far it got. The rows a job writes into the conversation are its checkpoints: when a job has
 * to be picked up again (its machine slept, the user moved it, this server restarted), the agent that
 * continues is told what the conversation holds that it has not seen on its own machine.
 */

export type JobStatus = 'queued' | 'waiting' | 'running' | 'paused' | 'done' | 'failed' | 'stopped';

export interface JobRow {
  id: string; bot_id: string; trigger: string; weight: JobWeight; status: JobStatus;
  machine_id: string | null; move_to: string | null; attempts: number;
  /** The conversation row that set this job off (the user's message), when it has one. */
  row_seq: number | null;
  /** First row this job wrote. */
  start_seq: number | null;
  generation: number; note: string | null; created_at: number; started_at: number | null; finished_at: number | null;
}

const OPEN = "('queued','waiting','running','paused')";

export const getJob = (id: string) => db.prepare('SELECT * FROM jobs WHERE id=?').get(id) as JobRow | undefined;

export function createJob(botId: string, trigger: { type: string }): JobRow {
  const b = db.prepare('SELECT weight, generation, next_seq FROM bots WHERE id=?').get(botId) as any;
  const weight: JobWeight = b.weight === 'light' ? 'light' : 'heavy';
  // A message from the user or another bot was written into the conversation just before this.
  const rowSeq = trigger.type === 'user' || trigger.type === 'bot' ? b.next_seq - 1 : null;
  const id = newId('job');
  db.prepare("INSERT INTO jobs(id,bot_id,trigger,weight,status,row_seq,generation,created_at) VALUES (?,?,?,?,'queued',?,?,?)")
    .run(id, botId, JSON.stringify(trigger), weight, rowSeq, b.generation, now());
  return getJob(id)!;
}

export function setJob(id: string, patch: Partial<Pick<JobRow, 'status' | 'machine_id' | 'move_to' | 'attempts' | 'start_seq' | 'note' | 'started_at' | 'finished_at'>>) {
  const keys = Object.keys(patch) as (keyof typeof patch)[];
  if (!keys.length) return;
  db.prepare(`UPDATE jobs SET ${keys.map((k) => `${k}=?`).join(', ')} WHERE id=?`).run(...keys.map((k) => patch[k] as any), id);
}

/** Jobs the last run of this server left unfinished, oldest first. Ones too old to matter are closed. */
export function unfinishedJobs(maxAgeMs: number): JobRow[] {
  db.prepare(`UPDATE jobs SET status='failed', note='Left over from before a restart', finished_at=? WHERE status IN ${OPEN} AND created_at < ?`).run(now(), now() - maxAgeMs);
  return db.prepare(`SELECT * FROM jobs WHERE status IN ${OPEN} ORDER BY created_at ASC`).all() as JobRow[];
}

/** Finished jobs are only kept for a while. */
export function pruneJobs() {
  db.prepare(`DELETE FROM jobs WHERE status NOT IN ${OPEN} AND created_at < ?`).run(now() - 14 * 86400_000);
}

function titleOf(trigger: string): string {
  try {
    const t = JSON.parse(trigger);
    const text = t.type === 'routine' ? `Routine: ${t.name}` : String(t.text ?? '');
    return text.replace(/\s+/g, ' ').trim().slice(0, 90) || 'Job';
  } catch { return 'Job'; }
}

export function openJobs(userId: string): JobInfo[] {
  const rows = db.prepare(`SELECT j.*, b.name bot_name FROM jobs j JOIN bots b ON b.id=j.bot_id WHERE b.user_id=? AND j.status IN ${OPEN} ORDER BY j.created_at ASC`).all(userId) as (JobRow & { bot_name: string })[];
  return rows.map((j) => ({
    id: j.id, botId: j.bot_id, botName: j.bot_name, title: titleOf(j.trigger), weight: j.weight,
    status: j.status as JobInfo['status'], machineId: j.machine_id, note: j.note, createdAt: j.created_at,
  }));
}

// ---------- what a machine's agent has seen of a conversation ----------

/** Each machine's agent keeps its own thread of a conversation. This is how far each one has read. */
function seenSeq(botId: string, generation: number, machineId: string): number {
  const r = db.prepare('SELECT seen_seq FROM machine_threads WHERE bot_id=? AND generation=? AND machine_id=?').get(botId, generation, machineId) as any;
  return r?.seen_seq ?? 0;
}

function setSeen(botId: string, generation: number, machineId: string, seq: number) {
  db.prepare('INSERT INTO machine_threads(bot_id,generation,machine_id,seen_seq) VALUES (?,?,?,?) ON CONFLICT(bot_id,generation,machine_id) DO UPDATE SET seen_seq=excluded.seen_seq')
    .run(botId, generation, machineId, seq);
}

/**
 * Before the first job of a conversation we kept no record. Until now everything ran on the hub, so
 * the hub's agent has read all of it up to this job.
 */
export function noteHubHistory(job: JobRow) {
  if (db.prepare('SELECT 1 FROM machine_threads WHERE bot_id=? AND generation=?').get(job.bot_id, job.generation)) return;
  const next = (db.prepare('SELECT next_seq FROM bots WHERE id=?').get(job.bot_id) as any).next_seq as number;
  setSeen(job.bot_id, job.generation, HUB, (job.row_seq ?? next) - 1);
}

/** The machine's agent finished a turn: it has now read the conversation up to here. */
export function markSeen(job: JobRow, machineId: string) {
  const next = (db.prepare('SELECT next_seq FROM bots WHERE id=?').get(job.bot_id) as any).next_seq as number;
  setSeen(job.bot_id, job.generation, machineId, next - 1);
}

const DIGEST_CHARS = 6000;

function lineFor(body: RowBody): string | null {
  if (body.type === 'message') {
    const text = body.text.trim();
    if (!text) return null;
    if (body.role === 'assistant') return `You: ${text}`;
    const from = (body as any).from;
    return `${from?.kind === 'bot' ? `${from.name} (another Bot)` : 'User'}: ${text}`;
  }
  if (body.type === 'tool-call') {
    const how = body.status === 'done' ? 'done' : body.status === 'error' ? `failed${body.summary ? ` (${body.summary})` : ''}` : body.status === 'denied' ? 'not allowed' : 'not finished';
    return `You used ${body.label || body.name}${argHint(body.args)}: ${how}`;
  }
  return null;
}

/** The one argument that says what a tool call was about, when there is an obvious one. */
function argHint(args: unknown): string {
  if (!args || typeof args !== 'object') return '';
  for (const k of ['command', 'url', 'query', 'path', 'file', 'title', 'name', 'text']) {
    const v = (args as Record<string, unknown>)[k];
    if (typeof v === 'string' && v.trim()) return ` (${v.replace(/\s+/g, ' ').trim().slice(0, 80)})`;
  }
  return '';
}

/** The same line many times over says it once, with a count. */
function collapse(lines: string[]): string[] {
  const out: { line: string; n: number }[] = [];
  for (const line of lines) {
    const last = out[out.length - 1];
    if (last?.line === line) last.n++;
    else out.push({ line, n: 1 });
  }
  return out.map((o) => (o.n > 1 ? `${o.line} (${o.n} times)` : o.line));
}

/**
 * What happened in the conversation that this machine's agent has not read: turns that ran on another
 * machine, and the part of this job done before it was interrupted. Messages still waiting their own
 * turn are left out. Empty when there is nothing to catch up on.
 */
export function unseenDigest(job: JobRow, machineId: string): string {
  const waiting = new Set((db.prepare(`SELECT row_seq FROM jobs WHERE bot_id=? AND generation=? AND row_seq IS NOT NULL AND status IN ${OPEN}`).all(job.bot_id, job.generation) as any[]).map((r) => r.row_seq as number));
  const rows = db.prepare("SELECT seq, body FROM rows WHERE bot_id=? AND generation=? AND seq>? AND kind IN ('message','tool-call') ORDER BY seq ASC")
    .all(job.bot_id, job.generation, seenSeq(job.bot_id, job.generation, machineId)) as { seq: number; body: string }[];
  const lines = rows.filter((r) => !waiting.has(r.seq)).map((r) => lineFor(JSON.parse(r.body))).filter((l): l is string => !!l);
  let text = collapse(lines).join('\n');
  if (text.length > DIGEST_CHARS) text = `(earlier part left out)\n${text.slice(-DIGEST_CHARS)}`;
  return text;
}
