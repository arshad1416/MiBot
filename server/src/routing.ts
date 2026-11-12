import path from 'node:path';
import { db, now } from './db.js';
import { env } from './env.js';
import { readDotenv } from './gateway.js';
import { askJev, type JevAnswer, type JevQuestion } from './jev.js';
import type { JobRow } from './jobs.js';
import { newId } from './lib/ids.js';
import { listMachines } from './machines.js';
import { savedKey } from './modelAccess.js';
import { getBotRow } from './store.js';
import type { JobChoice, JobWeight, Machine, RosterModel } from './types.js';

/**
 * Auto: which model does a job.
 *
 * A person keeps a list of models with a note on what each is good and bad at, and on which machines
 * it runs (sign-ins differ from machine to machine). For each new job of a Bot that is on Auto, Jev
 * (jev.ts) is asked three things: which of those models, whether the job is light or heavy, and
 * which machine. An answer it is sure enough of is used; the rest stays as the Bot has it. The
 * choice and the reason are written on the job. agent/loop.ts asks, machines.ts places the job.
 */

db.exec(`
CREATE TABLE IF NOT EXISTS model_roster (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  label TEXT NOT NULL,
  provider TEXT NOT NULL,
  model TEXT NOT NULL,
  note TEXT NOT NULL DEFAULT '',
  machines TEXT NOT NULL DEFAULT '[]',
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS routing_settings (
  user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  min_confidence REAL NOT NULL
);
`);

// ---------- the models to choose from ----------

const MAX_MODELS = 40;

const toModel = (r: any): RosterModel => ({ id: r.id, label: r.label, provider: r.provider, model: r.model, note: r.note, machines: JSON.parse(r.machines) });

export const listRoster = (userId: string): RosterModel[] =>
  (db.prepare('SELECT * FROM model_roster WHERE user_id=? ORDER BY created_at ASC').all(userId) as any[]).map(toModel);

/** Add a model to the list, or change the one with this `id`. */
export function saveRosterModel(userId: string, p: { id?: unknown; label?: unknown; provider?: unknown; model?: unknown; note?: unknown; machines?: unknown }): RosterModel {
  const existing = typeof p.id === 'string' ? db.prepare('SELECT id FROM model_roster WHERE id=? AND user_id=?').get(p.id, userId) as { id: string } | undefined : undefined;
  if (p.id !== undefined && !existing) throw new Error('That model is no longer on the list.');
  const label = typeof p.label === 'string' ? p.label.replace(/\s+/g, ' ').trim().slice(0, 60) : '';
  const provider = typeof p.provider === 'string' ? p.provider.trim() : '';
  const model = typeof p.model === 'string' ? p.model.trim() : '';
  if (!label) throw new Error('Give the model a name.');
  if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,60}$/.test(provider)) throw new Error('Choose the provider the model comes from.');
  if (!/^[\x21-\x7e]{1,120}$/.test(model)) throw new Error('Give the model’s id as the provider writes it, for example claude-opus-5-5.');
  if (listRoster(userId).some((r) => r.id !== existing?.id && r.label.toLowerCase() === label.toLowerCase())) throw new Error(`There is already a model called “${label}”.`);
  if (!existing && listRoster(userId).length >= MAX_MODELS) throw new Error(`The list holds ${MAX_MODELS} models at most.`);
  const note = typeof p.note === 'string' ? p.note.trim().slice(0, 600) : '';
  const own = new Set(listMachines(userId).map((m) => m.id));
  const machines = JSON.stringify(Array.isArray(p.machines) ? [...new Set(p.machines.filter((id): id is string => typeof id === 'string' && own.has(id)))] : []);

  const id = existing?.id ?? newId('mdl');
  if (existing) db.prepare('UPDATE model_roster SET label=?, provider=?, model=?, note=?, machines=? WHERE id=?').run(label, provider, model, note, machines, id);
  else db.prepare('INSERT INTO model_roster(id,user_id,label,provider,model,note,machines,created_at) VALUES (?,?,?,?,?,?,?,?)').run(id, userId, label, provider, model, note, machines, now());
  return toModel(db.prepare('SELECT * FROM model_roster WHERE id=?').get(id));
}

export const removeRosterModel = (userId: string, id: string): boolean =>
  db.prepare('DELETE FROM model_roster WHERE id=? AND user_id=?').run(id, userId).changes > 0;

// ---------- how sure Jev has to be ----------

/** Jev's makers give no figure. Half is where one option of several stops being a toss-up. */
const DEFAULT_MIN_CONFIDENCE = 0.5;

export function minConfidence(userId: string): number {
  const r = db.prepare('SELECT min_confidence FROM routing_settings WHERE user_id=?').get(userId) as { min_confidence: number } | undefined;
  return r?.min_confidence ?? DEFAULT_MIN_CONFIDENCE;
}

export function setMinConfidence(userId: string, value: unknown) {
  if (typeof value !== 'number' || !(value >= 0 && value <= 1)) throw new Error('That has to be between 0 and 100%.');
  db.prepare('INSERT INTO routing_settings(user_id,min_confidence) VALUES (?,?) ON CONFLICT(user_id) DO UPDATE SET min_confidence=excluded.min_confidence').run(userId, value);
}

// ---------- asking ----------

/** The OpenRouter key Jev is asked with: the one added in Settings, else the one this machine's own Hermes has. */
const jevKey = (userId: string): string | null =>
  savedKey(userId, 'openrouter') ?? (readDotenv(path.join(env.hermesHome, '.env')).OPENROUTER_API_KEY || null);

/** Why Jev cannot be asked for this person's jobs, or null when it can. */
export const autoBlocked = (userId: string): string | null =>
  jevKey(userId) ? null : 'Auto asks Jev through OpenRouter. Add an OpenRouter key under API keys first.';

const SIZES: Record<JobWeight, string> = {
  light: 'The job only reads or writes mail, a calendar, sheets, documents or other connected apps, or only needs an answer from the model. No web browser, no screen, no large files.',
  heavy: 'The job needs a web browser or a screen, long research, large files, code to be run, or a lot of memory.',
};

const gb = (mb: number) => `${(mb / 1024).toFixed(1)} GB`;

function describeMachine(m: Machine): string {
  const parts = [m.takes === 'both' ? 'Takes light and heavy jobs.' : `Takes ${m.takes} jobs only.`];
  if (m.platform) parts.push(`Runs ${m.platform}.`);
  if (m.memTotalMb) parts.push(`${gb(m.memTotalMb)} of memory${m.memFreeMb !== null ? `, ${gb(m.memFreeMb)} free` : ''}.`);
  parts.push(m.state === 'online' ? (m.running ? `Running ${m.running} job${m.running === 1 ? '' : 's'} now.` : 'Idle now.') : m.state === 'busy' ? 'Not taking jobs right now.' : 'Asleep or offline right now.');
  return parts.join(' ');
}

/** Give each thing a short name Jev can answer with, made from its own name. */
function named<T>(things: T[], nameOf: (t: T) => string): Map<string, T> {
  const out = new Map<string, T>();
  for (const t of things) {
    const base = nameOf(t).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'option';
    let name = base;
    for (let n = 2; out.has(name); n++) name = `${base}-${n}`;
    out.set(name, t);
  }
  return out;
}

const percent = (n: number) => `${Math.round(n * 100)}%`;

/**
 * Choose for a new job of a Bot on Auto and write the choice on the job (its size changes with it).
 * Null when the Bot is not on Auto. `hubOnly`: the Bot's agent lives on the hub, so there is no
 * machine or size to choose. Rejects only when `signal` aborts.
 */
export async function chooseFor(job: JobRow, request: string, hubOnly: boolean, signal: AbortSignal): Promise<JobChoice | null> {
  const bot = getBotRow(job.bot_id);
  if (!bot?.auto_model) return null;
  const userId: string = bot.user_id;
  const keep = (c: JobChoice) => { db.prepare('UPDATE jobs SET weight=?, choice=? WHERE id=?').run(c.weight, JSON.stringify(c), job.id); return c; };
  const own = (why: string): JobChoice => keep({ by: 'bot', label: null, provider: null, model: null, weight: job.weight, machineId: null, runsOn: [], confidence: null, why });

  const roster = listRoster(userId);
  if (!roster.length) return own('There are no models to choose from yet. Add some in Settings › Models.');

  const machines = hubOnly ? [] : listMachines(userId).filter((m) => m.enabled);
  const models = named(roster, (r) => r.label);
  const boxes = named(machines, (m) => m.name);
  const questions: Record<string, JevQuestion> = {};
  if (models.size > 1) {
    questions.model = {
      type: 'choice',
      instructions: 'Which model should do this job? Go by what each one is good and bad at.',
      criteria: Object.fromEntries([...models].map(([name, r]) => [name, `${r.label}. ${r.note || 'No notes on this one.'}`])),
    };
  }
  if (!hubOnly) questions.size = { type: 'choice', instructions: 'How big is this job?', criteria: SIZES };
  if (boxes.size > 1) {
    questions.machine = {
      type: 'choice',
      instructions: 'Which machine should run this job? A machine has to take jobs of this size, and one that is awake and idle is better.',
      criteria: Object.fromEntries([...boxes].map(([name, m]) => [name, `${m.name}. ${describeMachine(m)}`])),
    };
  }

  let answers: Record<string, JevAnswer> = {};
  if (Object.keys(questions).length) {
    const key = jevKey(userId);
    if (!key) return own('Auto needs an OpenRouter key. Add one in Settings › Models.');
    const state = [
      `A job for ${bot.name}, an AI assistant${bot.label ? ` (${bot.label})` : ''}.${bot.description ? ` About it: ${bot.description}` : ''}`,
      'The job:',
      request.slice(0, 6000),
    ].join('\n');
    try { answers = await askJev(key, state, questions, signal); }
    catch (e: any) {
      if (signal.aborted) throw e;
      return own(`Jev could not be asked: ${String(e?.message ?? e).slice(0, 160)}.`);
    }
  }

  const min = minConfidence(userId);
  const sure = (name: string) => (answers[name] && answers[name].confidence >= min ? answers[name].choice : null);
  const picked = models.size === 1 ? roster[0] : models.get(sure('model') ?? '') ?? null;
  const weight = (sure('size') as JobWeight | null) ?? job.weight;
  // A machine that has since been removed no longer limits where the model runs.
  const runsOn = picked ? picked.machines.filter((id) => machines.some((m) => m.id === id)) : [];
  const box = boxes.get(sure('machine') ?? '');
  const machineId = box && (box.takes === 'both' || box.takes === weight) && (!runsOn.length || runsOn.includes(box.id)) ? box.id : null;
  const asked = answers.model;

  if (!picked) {
    return keep({
      by: 'bot', label: null, provider: null, model: null, weight, machineId, runsOn: [], confidence: asked?.confidence ?? null,
      why: asked ? `Jev was only ${percent(asked.confidence)} sure of a model.` : 'Jev gave no answer for the model.',
    });
  }
  return keep({
    by: 'jev', label: picked.label, provider: picked.provider, model: picked.model, weight, machineId, runsOn, confidence: asked?.confidence ?? null,
    why: asked ? `Jev was ${percent(asked.confidence)} sure.` : 'It is the only model on the list.',
  });
}

/** The line a job's conversation gets about its choice, once a machine has taken the job. */
export function choiceLine(c: JobChoice, botName: string, machineName: string): string {
  if (c.by === 'bot') return `Auto kept ${botName}’s own model for this. ${c.why}`;
  return `Auto chose ${c.label} for this, as a ${c.weight} job on ${machineName}. ${c.why}`;
}
