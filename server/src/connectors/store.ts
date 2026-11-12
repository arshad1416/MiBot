import { db, now } from '../db.js';
import { secretBox } from '../secretbox.js';
import { connectorEntry, type ConnectorAuth, type ConnectorEntry } from './catalog.js';

/**
 * Where direct connections live: one row per person per app (the sign-in, done once), and one row
 * per bot per app saying whether that bot may use it. Tokens and keys are encrypted at rest with a
 * key derived from SESSION_SECRET.
 */

db.exec(`
CREATE TABLE IF NOT EXISTS connections (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  slug TEXT NOT NULL,
  name TEXT NOT NULL,
  url TEXT NOT NULL,
  auth TEXT NOT NULL,
  custom INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'pending',
  state TEXT,
  secret TEXT,
  tools TEXT,
  error TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, slug)
);
CREATE TABLE IF NOT EXISTS bot_connections (
  bot_id TEXT NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
  slug TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (bot_id, slug)
);
`);

/** What we keep secret for one connection. */
export interface ConnectionSecret {
  tokens?: unknown;
  clientInfo?: unknown;
  codeVerifier?: string;
  apiKey?: string;
}

export interface ConnectorTool { name: string; description: string; inputSchema: unknown; readOnly: boolean }

export interface Connection {
  userId: string;
  slug: string;
  name: string;
  url: string;
  auth: ConnectorAuth;
  custom: boolean;
  status: 'pending' | 'connected' | 'error';
  error: string | null;
  tools: ConnectorTool[];
}

// A SESSION_SECRET that changed opens as nothing: the person signs in again.
const { seal, open } = secretBox<ConnectionSecret>('connections', () => ({}));

const toConnection = (r: any): Connection => ({
  userId: r.user_id, slug: r.slug, name: r.name, url: r.url, auth: r.auth, custom: !!r.custom,
  status: r.status, error: r.error, tools: r.tools ? JSON.parse(r.tools) : [],
});

const row = (userId: string, slug: string) => db.prepare('SELECT * FROM connections WHERE user_id=? AND slug=?').get(userId, slug) as any;

export const getConnection = (userId: string, slug: string): Connection | null => { const r = row(userId, slug); return r ? toConnection(r) : null; };
export const listConnections = (userId: string): Connection[] => (db.prepare('SELECT * FROM connections WHERE user_id=? ORDER BY name').all(userId) as any[]).map(toConnection);
export const connectionByState = (state: string): Connection | null => { const r = db.prepare('SELECT * FROM connections WHERE state=?').get(state) as any; return r ? toConnection(r) : null; };

/** Start (or restart) a connection. Its old sign-in is kept so a reconnect can reuse the registered client. */
export function upsertConnection(userId: string, e: Pick<ConnectorEntry, 'slug' | 'name' | 'url' | 'auth'>, custom = false) {
  const t = now();
  db.prepare(`INSERT INTO connections(user_id,slug,name,url,auth,custom,status,created_at,updated_at) VALUES (?,?,?,?,?,?, 'pending', ?, ?)
              ON CONFLICT(user_id,slug) DO UPDATE SET name=excluded.name, url=excluded.url, auth=excluded.auth, error=NULL, updated_at=excluded.updated_at`)
    .run(userId, e.slug, e.name, e.url, e.auth, custom ? 1 : 0, t, t);
}

export function setConnection(userId: string, slug: string, patch: { status?: Connection['status']; error?: string | null; state?: string | null; tools?: ConnectorTool[] }) {
  const r = row(userId, slug);
  if (!r) return;
  db.prepare('UPDATE connections SET status=?, error=?, state=?, tools=?, updated_at=? WHERE user_id=? AND slug=?').run(
    patch.status ?? r.status,
    patch.error === undefined ? r.error : patch.error,
    patch.state === undefined ? r.state : patch.state,
    patch.tools ? JSON.stringify(patch.tools) : r.tools,
    now(), userId, slug,
  );
}

export const readSecret = (userId: string, slug: string): ConnectionSecret => open(row(userId, slug)?.secret ?? null);
export function patchSecret(userId: string, slug: string, patch: Partial<ConnectionSecret>) {
  const next = { ...readSecret(userId, slug), ...patch };
  db.prepare('UPDATE connections SET secret=?, updated_at=? WHERE user_id=? AND slug=?').run(seal(next), now(), userId, slug);
}

export function deleteConnection(userId: string, slug: string) {
  db.prepare('DELETE FROM connections WHERE user_id=? AND slug=?').run(userId, slug);
  db.prepare('DELETE FROM bot_connections WHERE slug=? AND bot_id IN (SELECT id FROM bots WHERE user_id=?)').run(slug, userId);
}

// ---------- which bots may use a connection ----------

/** A connection is off for a bot until it is switched on for that bot. */
export const botHas = (botId: string, slug: string) => !!(db.prepare('SELECT enabled FROM bot_connections WHERE bot_id=? AND slug=?').get(botId, slug) as any)?.enabled;

export function setBotConnection(botId: string, slug: string, enabled: boolean) {
  db.prepare('INSERT INTO bot_connections(bot_id,slug,enabled) VALUES (?,?,?) ON CONFLICT(bot_id,slug) DO UPDATE SET enabled=excluded.enabled').run(botId, slug, enabled ? 1 : 0);
}

export const botsWith = (userId: string, slug: string): string[] =>
  (db.prepare('SELECT bc.bot_id FROM bot_connections bc JOIN bots b ON b.id=bc.bot_id WHERE b.user_id=? AND bc.slug=? AND bc.enabled=1').all(userId, slug) as any[]).map((r) => r.bot_id);

/** The connected apps this bot is allowed to use. */
export function botConnections(userId: string, botId: string): Connection[] {
  return listConnections(userId).filter((c) => c.status === 'connected' && botHas(botId, c.slug));
}

/** Catalog details for a connection: the catalog's when it is a catalog app, its own row when custom. */
export function entryFor(c: Connection): ConnectorEntry {
  const known = c.custom ? undefined : connectorEntry(c.slug);
  return known ?? { slug: c.slug, name: c.name, description: '', url: c.url, auth: c.auth, hosts: [], excludedTools: [] };
}
