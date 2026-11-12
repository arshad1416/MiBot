import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { parse } from 'yaml';
import { env } from '../env.js';

/**
 * The direct connector catalog: MCP servers the app talks to itself, with no broker in between.
 * Entries come from the Hermes MCP catalog on this machine (optional-mcps/<name>/manifest.yaml, the
 * Nous-approved list that `hermes mcp catalog` shows). Only vendor-hosted HTTP servers are taken;
 * entries that run a local process stay with Hermes itself.
 */

export type ConnectorAuth = 'oauth' | 'api_key' | 'none';

export interface ConnectorEntry {
  slug: string;
  name: string;
  description: string;
  url: string;
  auth: ConnectorAuth;
  /** Sites this app is about (for a logo and for matching a request to an app). */
  hosts: string[];
  /** Tools the catalog hides by default. */
  excludedTools: string[];
  /** A pre-registered OAuth client, for vendors without dynamic client registration. */
  oauthClient?: { clientId: string; clientSecret?: string; scope?: string };
  /** What the person is asked for when auth is `api_key`. */
  keyPrompt?: string;
}

const slugOf = (name: string) => name.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
const titleOf = (name: string) => name.split(/[-_]/).map((w) => (w ? w[0].toUpperCase() + w.slice(1) : w)).join(' ');
const envRef = (v: unknown) => (typeof v === 'string' ? v.replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)\}/g, (_m, k) => process.env[k] ?? '') : '');

function catalogDirs(): string[] {
  const home = os.homedir();
  return [
    env.connectorCatalogDir,
    process.env.HERMES_OPTIONAL_MCPS ?? '',
    path.join(env.hermesHome, 'hermes-agent', 'optional-mcps'),
    path.join(home, 'hermes-agent', 'optional-mcps'),
  ].filter(Boolean);
}

function readEntry(file: string): ConnectorEntry | null {
  let m: any;
  try { m = parse(fs.readFileSync(file, 'utf8')); } catch { return null; }
  if (!m || typeof m.name !== 'string' || m.transport?.type !== 'http' || typeof m.transport.url !== 'string') return null;
  const authType = m.auth?.type;
  const auth: ConnectorAuth = authType === 'oauth' ? 'oauth' : authType === 'api_key' ? 'api_key' : 'none';
  const pinned = m.auth?.oauth;
  const clientId = envRef(pinned?.client_id);
  return {
    slug: slugOf(m.name),
    name: titleOf(m.name),
    description: String(m.description ?? '').trim(),
    url: m.transport.url,
    auth,
    hosts: Array.isArray(m.suggest?.hosts) ? m.suggest.hosts.filter((h: unknown) => typeof h === 'string') : [],
    excludedTools: Array.isArray(m.tools?.default_excluded) ? m.tools.default_excluded.map(String) : [],
    ...(clientId ? { oauthClient: { clientId, clientSecret: envRef(pinned?.client_secret) || undefined, scope: envRef(pinned?.scope) || undefined } } : {}),
    ...(auth === 'api_key' ? { keyPrompt: String(m.auth?.env?.[0]?.prompt ?? `${titleOf(m.name)} API key`) } : {}),
  };
}

let cache: { at: number; items: ConnectorEntry[] } | null = null;

/** Every direct connector found on this machine, by name. Re-read every few minutes. */
export function connectorCatalog(): ConnectorEntry[] {
  if (cache && Date.now() - cache.at < 300_000) return cache.items;
  const bySlug = new Map<string, ConnectorEntry>();
  for (const dir of catalogDirs()) {
    let names: string[] = [];
    try { names = fs.readdirSync(dir); } catch { continue; }
    for (const name of names) {
      const entry = readEntry(path.join(dir, name, 'manifest.yaml'));
      if (entry && !bySlug.has(entry.slug)) bySlug.set(entry.slug, entry);
    }
  }
  cache = { at: Date.now(), items: [...bySlug.values()].sort((a, b) => a.name.localeCompare(b.name)) };
  return cache.items;
}

export const connectorEntry = (slug: string) => connectorCatalog().find((e) => e.slug === slug);
export { slugOf };
