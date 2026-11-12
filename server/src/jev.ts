import { env } from './env.js';

/**
 * Jev, TypeSafe's decision model, reached through OpenRouter. It is given a situation (`state`) and
 * named questions, and answers each with one of the offered options and how sure it is. It writes
 * no text of its own, so everything it is to go by has to be in the state and the options.
 */

/** One question: pick an option. `criteria` maps each option's name to what it means. */
export interface JevQuestion {
  type: 'choice';
  instructions: string;
  criteria: Record<string, string | null>;
}

export interface JevAnswer {
  choice: string;
  /** 0 to 1. */
  confidence: number;
}

/** Longest a job waits for Jev before it goes ahead without it. */
const JEV_WAIT_MS = 10_000;

async function failure(res: Response): Promise<string> {
  const raw = await res.text().catch(() => '');
  try {
    const j = JSON.parse(raw);
    const m = j?.error?.message ?? j?.message ?? j?.error;
    if (typeof m === 'string' && m) return m.slice(0, 200);
  } catch { /* plain text */ }
  if (res.status === 401 || res.status === 403) return 'OpenRouter did not accept the key';
  return `OpenRouter answered ${res.status}`;
}

/**
 * Ask Jev the questions about `state`. The answer holds the questions it answered with one of the
 * options offered; anything else it sent back is left out. Rejects when it cannot be reached, says
 * no, or takes too long.
 */
export async function askJev(key: string, state: string, questions: Record<string, JevQuestion>, signal?: AbortSignal): Promise<Record<string, JevAnswer>> {
  const wait = AbortSignal.timeout(JEV_WAIT_MS);
  const res = await fetch(`${env.openRouterUrl}/systemone`, {
    method: 'POST',
    signal: signal ? AbortSignal.any([signal, wait]) : wait,
    headers: { 'content-type': 'application/json', authorization: `Bearer ${key}` },
    body: JSON.stringify({ model: env.jevModel, state, questions }),
  }).catch((e: any) => { throw new Error(wait.aborted ? 'it took too long to answer' : String(e?.cause?.message ?? e?.message ?? e)); });
  if (!res.ok) throw new Error(await failure(res));
  const body = await res.json().catch(() => null) as { answers?: Record<string, { choice?: unknown; confidence?: unknown }> } | null;
  const out: Record<string, JevAnswer> = {};
  for (const [name, q] of Object.entries(questions)) {
    const a = body?.answers?.[name];
    if (typeof a?.choice === 'string' && Object.hasOwn(q.criteria, a.choice) && typeof a.confidence === 'number' && Number.isFinite(a.confidence)) {
      out[name] = { choice: a.choice, confidence: Math.min(1, Math.max(0, a.confidence)) };
    }
  }
  return out;
}
