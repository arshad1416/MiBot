import type { Context, MiddlewareHandler } from 'hono';
import crypto from 'node:crypto';
import { getCookie, setCookie, deleteCookie } from 'hono/cookie';
import { env } from '../env.js';
import { db, now } from '../db.js';
import { hub } from './hub.js';
import { newId, token } from './ids.js';
import { describeDevice } from './devices.js';
import type { Device, User } from '../types.js';

const COOKIE = 'grokbot_session';
const TTL_MS = 30 * 24 * 3600 * 1000;

export type AuthVars = { Variables: { user: User } };

/** A sign-in that is used gets its 30 days again, at most once a day, so a device in regular use is never signed out. */
const RENEW_AFTER_MS = 24 * 3600 * 1000;
/** How often a sign-in in use has its "last used" time written. */
const TOUCH_AFTER_MS = 60_000;
const MAX_USER_AGENT = 300;

const userAgentOf = (c: Context) => (c.req.header('user-agent') ?? '').slice(0, MAX_USER_AGENT);

const sendCookie = (c: Context, t: string) =>
  setCookie(c, COOKIE, t, { httpOnly: true, sameSite: 'Lax', secure: env.appUrl.startsWith('https:'), path: '/', maxAge: TTL_MS / 1000 });

export function createSession(c: Context, userId: string) {
  const t = token();
  db.prepare('INSERT INTO auth_sessions(token,user_id,created_at,expires_at,id,user_agent,last_used_at) VALUES (?,?,?,?,?,?,?)')
    .run(t, userId, now(), now() + TTL_MS, newId('dev'), userAgentOf(c), now());
  sendCookie(c, t);
  hub.emit(userId, { type: 'machines' });
}

export function destroySession(c: Context) {
  const t = getCookie(c, COOKIE);
  const owner = t ? (db.prepare('DELETE FROM auth_sessions WHERE token=? RETURNING user_id').get(t) as { user_id: string } | undefined) : undefined;
  deleteCookie(c, COOKIE, { path: '/' });
  // Wakes this user's open event streams; the one that belonged to this sign-in ends itself.
  if (owner) hub.emit(owner.user_id, { type: 'machines' });
}

export function userFromToken(t: string | undefined): User | null {
  if (!t) return null;
  const r = db
    .prepare('SELECT u.* FROM auth_sessions s JOIN users u ON u.id=s.user_id WHERE s.token=? AND s.expires_at>?')
    .get(t, now()) as any;
  return r ? { id: r.id, email: r.email, name: r.name, avatarUrl: r.avatar_url } : null;
}

export const sessionToken = (c: Context) => getCookie(c, COOKIE);

/** Every device signed in as this user, the one asking first. A sign-in's token never leaves the server; `id` stands in for it. */
export function listDevices(userId: string, currentToken: string | undefined): Device[] {
  const rows = db
    .prepare('SELECT id, token, user_agent, created_at, last_used_at FROM auth_sessions WHERE user_id=? AND expires_at>? ORDER BY COALESCE(last_used_at, created_at) DESC')
    .all(userId, now()) as { id: string; token: string; user_agent: string | null; created_at: number; last_used_at: number | null }[];
  return rows
    .map((r) => ({ id: r.id, ...deviceLabel(r.user_agent), signedInAt: r.created_at, lastUsedAt: r.last_used_at, current: r.token === currentToken }))
    .sort((a, b) => Number(b.current) - Number(a.current));
}

function deviceLabel(userAgent: string | null): Pick<Device, 'name' | 'kind'> {
  if (!userAgent) return { name: 'A device that has not been used since this list was added', kind: 'computer' };
  const { name, kind } = describeDevice(userAgent);
  return { name, kind };
}

/** Signs one device out. Its open pages stop getting news at their next event (see /api/events). */
export function removeDevice(userId: string, id: string): boolean {
  const gone = db.prepare('DELETE FROM auth_sessions WHERE user_id=? AND id=?').run(userId, id).changes > 0;
  if (gone) hub.emit(userId, { type: 'machines' });
  return gone;
}

export function userFromCookieHeader(cookieHeader: string | undefined): User | null {
  const m = cookieHeader?.split(/;\s*/).find((p) => p.startsWith(`${COOKIE}=`));
  if (!m) return null;
  // A cookie is whatever the sender likes; one that does not decode is nobody's.
  try { return userFromToken(decodeURIComponent(m.slice(COOKIE.length + 1))); } catch { return null; }
}

export const requireUser: MiddlewareHandler<AuthVars> = async (c, next) => {
  const t = getCookie(c, COOKIE);
  const u = userFromToken(t);
  if (!u || !t) return c.json({ error: 'unauthenticated' }, 401);
  const renewed = db.prepare('UPDATE auth_sessions SET expires_at=? WHERE token=? AND expires_at<?').run(now() + TTL_MS, t, now() + TTL_MS - RENEW_AFTER_MS);
  if (renewed.changes > 0) sendCookie(c, t);
  // Sign-ins from before devices were listed have no user agent yet; they get it the first time they are used.
  db.prepare('UPDATE auth_sessions SET last_used_at=?, user_agent=COALESCE(user_agent, ?) WHERE token=? AND (user_agent IS NULL OR COALESCE(last_used_at, 0)<?)')
    .run(now(), userAgentOf(c), t, now() - TOUCH_AFTER_MS);
  c.set('user', u);
  await next();
};

/**
 * Key for signing in from the machine the server runs on (POST /api/auth/owner). It is derived from
 * SESSION_SECRET, so only someone who can read the server's .env can make it:
 *   printf 'owner-login' | openssl dgst -sha256 -hmac "$SESSION_SECRET" -r | cut -d' ' -f1
 */
export function isOwnerKey(given: string): boolean {
  const want = crypto.createHmac('sha256', env.sessionSecret).update('owner-login').digest();
  const got = Buffer.from(given, 'hex');
  return got.length === want.length && crypto.timingSafeEqual(got, want);
}
