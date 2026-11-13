import crypto from 'node:crypto';
import { now } from './db.js';
import { newId } from './lib/ids.js';
import { hub } from './lib/hub.js';

/**
 * One-time codes that bring another device in without a terminal.
 *
 * Someone signed in asks for a code (Settings › Machines). The app shows it as eight letters and as a
 * QR code of a link to /pair. Within ten minutes the code can be used once, either way:
 *   - a machine's worker hands it in and becomes a machine that runs jobs (it gets its own token);
 *   - a phone or another browser hands it in and is signed in as the person who made it.
 *
 * Codes live in memory only: a restart drops the ones still open, which is what a short-lived code
 * should do. Wrong guesses are counted against the address they come from: a handful shuts that
 * address out for a while and nobody else, so a stranger cannot stop the owner adding a phone.
 */

/** Letters and digits that are not mistaken for each other when read off a screen. */
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const LENGTH = 8;
const TTL_MS = 10 * 60_000;
/** A used or expired code is remembered this long, so the screen that showed it can say what happened. */
const KEEP_MS = 10 * 60_000;
const MAX_WRONG = 10;
const LOCK_MS = 10 * 60_000;
/** Addresses remembered for their wrong guesses; past this the oldest are forgotten. */
const MAX_GUESSERS = 5000;

export type PairUse = 'machine' | 'browser';

interface PairCode {
  id: string; code: string; userId: string;
  /** For a machine: the name and job sizes chosen when the code was made. */
  name: string; takes: string;
  expiresAt: number;
  used: { as: PairUse; name: string } | null;
}

/** `expiresInMs` rather than a time of day: the browser's clock need not agree with this machine's. */
export interface PairStatus { state: 'open' | 'used' | 'expired'; expiresInMs: number; usedAs: PairUse | null; deviceName: string | null }

export class PairError extends Error {}

const byCode = new Map<string, PairCode>();
/** Per address: when its wrong guesses were made, and until when it is shut out. */
const guessers = new Map<string, { wrong: number[]; lockedUntil: number }>();

/** What someone typed, as the code it stands for: case and the dash in the middle do not matter. */
export const normalizeCode = (text: string) => text.toUpperCase().replace(/[^A-Z0-9]/g, '');

const WELL_FORMED = new RegExp(`^[${ALPHABET}]{${LENGTH}}$`);

function prune() {
  const t = now();
  for (const [code, c] of byCode) if (c.expiresAt + KEEP_MS < t) byCode.delete(code);
  for (const [from, g] of guessers) if (g.lockedUntil < t && !g.wrong.some((at) => t - at < LOCK_MS)) guessers.delete(from);
  // A Map keeps the order things were added in, so the first keys are the oldest.
  for (const from of guessers.keys()) { if (guessers.size <= MAX_GUESSERS) break; guessers.delete(from); }
}

/** Counts a wrong guess from `from`; the tenth within ten minutes shuts that address out for ten more. */
function countWrong(from: string, t: number) {
  const g = guessers.get(from) ?? { wrong: [], lockedUntil: 0 };
  g.wrong = [...g.wrong.filter((at) => t - at < LOCK_MS), t];
  if (g.wrong.length >= MAX_WRONG) { g.lockedUntil = t + LOCK_MS; g.wrong = []; }
  guessers.set(from, g);
}

export function createPairCode(userId: string, name: string, takes: string): { id: string; code: string; expiresInMs: number } {
  prune();
  let code = '';
  do {
    code = Array.from(crypto.randomBytes(LENGTH), (b) => ALPHABET[b % ALPHABET.length]).join('');
  } while (byCode.has(code));
  const c: PairCode = { id: newId('pair'), code, userId, name: name.trim().slice(0, 60), takes, expiresAt: now() + TTL_MS, used: null };
  byCode.set(code, c);
  return { id: c.id, code, expiresInMs: TTL_MS };
}

export function pairStatus(userId: string, id: string): PairStatus | null {
  const c = [...byCode.values()].find((x) => x.id === id && x.userId === userId);
  if (!c) return null;
  const left = c.expiresAt - now();
  return { state: c.used ? 'used' : left <= 0 ? 'expired' : 'open', expiresInMs: Math.max(0, left), usedAs: c.used?.as ?? null, deviceName: c.used?.name ?? null };
}

/**
 * Use a code once. `from` is the address asking. `make` does what the code is for and returns the
 * device's name; when it throws, the code stays open.
 */
export function redeemPairCode<T extends { name: string }>(text: string, as: PairUse, from: string, make: (c: { userId: string; name: string; takes: string }) => T): T {
  const t = now();
  prune();
  if (t < (guessers.get(from)?.lockedUntil ?? 0)) throw new PairError('Too many wrong codes from this device. Wait ten minutes, then try again.');
  const typed = normalizeCode(text);
  // Something that cannot be a code is a slip, not a guess.
  if (!WELL_FORMED.test(typed)) throw new PairError('That code is not right. Check it and try again.');
  const c = byCode.get(typed);
  if (!c) {
    countWrong(from, t);
    throw new PairError('That code is not right. Check it and try again.');
  }
  if (c.used) throw new PairError('This code was already used. Make a new one.');
  if (c.expiresAt < t) throw new PairError('This code has expired. Make a new one.');
  const made = make({ userId: c.userId, name: c.name, takes: c.takes });
  c.used = { as, name: made.name };
  // The screen that shows the code listens for this.
  hub.emit(c.userId, { type: 'machines' });
  return made;
}
