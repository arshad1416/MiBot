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
 * should do. A handful of wrong guesses closes pairing for a while and cancels the open codes.
 */

/** Letters and digits that are not mistaken for each other when read off a screen. */
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const LENGTH = 8;
const TTL_MS = 10 * 60_000;
/** A used or expired code is remembered this long, so the screen that showed it can say what happened. */
const KEEP_MS = 10 * 60_000;
const MAX_WRONG = 10;
const LOCK_MS = 10 * 60_000;

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
let wrong: number[] = [];
let lockedUntil = 0;

/** What someone typed, as the code it stands for: case and the dash in the middle do not matter. */
export const normalizeCode = (text: string) => text.toUpperCase().replace(/[^A-Z0-9]/g, '');

function prune() {
  const t = now();
  for (const [code, c] of byCode) if (c.expiresAt + KEEP_MS < t) byCode.delete(code);
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
 * Use a code once. `make` does what the code is for and returns the device's name; when it throws,
 * the code stays open.
 */
export function redeemPairCode<T extends { name: string }>(text: string, as: PairUse, make: (c: { userId: string; name: string; takes: string }) => T): T {
  const t = now();
  if (t < lockedUntil) throw new PairError('Too many wrong codes. Wait a few minutes, then make a new code.');
  const c = byCode.get(normalizeCode(text));
  if (!c || c.used || c.expiresAt < t) {
    wrong = [...wrong.filter((at) => t - at < LOCK_MS), t];
    if (wrong.length >= MAX_WRONG) {
      lockedUntil = t + LOCK_MS;
      wrong = [];
      for (const open of byCode.values()) if (!open.used) open.expiresAt = Math.min(open.expiresAt, t - 1);
    }
    throw new PairError(c?.used ? 'This code was already used. Make a new one.' : c ? 'This code has expired. Make a new one.' : 'That code is not right. Check it and try again.');
  }
  const made = make({ userId: c.userId, name: c.name, takes: c.takes });
  c.used = { as, name: made.name };
  // The screen that shows the code listens for this.
  hub.emit(c.userId, { type: 'machines' });
  return made;
}
