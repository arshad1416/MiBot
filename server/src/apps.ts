import { catalog as composioCatalog, connectedToolkits as composioConnected } from './composio.js';
import { connectorCatalog, type ConnectorEntry } from './connectors/catalog.js';
import { botConnections, botsWith, listConnections, type Connection } from './connectors/store.js';
import type { Toolkit } from './types.js';

/**
 * Every app a bot can be connected to, from both places they come from: direct connectors (the
 * app's own MCP server, signed in to once in this app and switched on per bot) and, when a
 * Composio key is set, Composio's catalog. A direct connector wins over a Composio app of the same
 * name.
 */

export type CatalogItem = Omit<Toolkit, 'connected'>;

const logoFor = (hosts: string[], url: string) => {
  let host = hosts[0];
  if (!host) { try { host = new URL(url).hostname.split('.').slice(-2).join('.'); } catch { return undefined; } }
  return `https://www.google.com/s2/favicons?domain=${encodeURIComponent(host)}&sz=128`;
};

const fromEntry = (e: ConnectorEntry): CatalogItem => ({
  slug: e.slug, name: e.name, logo: logoFor(e.hosts, e.url), description: e.description, categories: ['direct'],
  managedAuth: true, noAuth: false, source: 'direct', auth: e.auth,
});

const fromCustom = (c: Connection): CatalogItem => ({
  slug: c.slug, name: c.name, logo: logoFor([], c.url), description: c.url, categories: ['direct'],
  managedAuth: true, noAuth: false, source: 'direct', auth: c.auth, custom: true,
});

/** The whole catalog as one person sees it (their own custom servers included). */
export async function appCatalog(userId?: string): Promise<CatalogItem[]> {
  const direct = connectorCatalog().map(fromEntry);
  const custom = userId ? listConnections(userId).filter((c) => c.custom).map(fromCustom) : [];
  const taken = new Set([...direct, ...custom].map((a) => a.slug));
  const brokered = (await composioCatalog().catch(() => [])).filter((a) => !taken.has(a.slug)).map((a) => ({ ...a, source: 'composio' as const }));
  return [...custom, ...direct, ...brokered];
}

export async function appInfo(slug: string, userId?: string): Promise<CatalogItem | undefined> {
  return (await appCatalog(userId)).find((a) => a.slug === slug);
}

/** Apps this person has connected, from either source. */
export async function connectedApps(userId: string): Promise<Set<string>> {
  const out = new Set(listConnections(userId).filter((c) => c.status === 'connected').map((c) => c.slug));
  for (const slug of await composioConnected(userId).catch(() => new Set<string>())) out.add(slug);
  return out;
}

/** Apps one bot can use: the direct connections switched on for it, plus everything connected through Composio. */
export async function appsForBot(userId: string, botId: string): Promise<string[]> {
  const out = new Set(botConnections(userId, botId).map((c) => c.name));
  for (const slug of await composioConnected(userId).catch(() => new Set<string>())) out.add(slug);
  return [...out].sort();
}

/** A connected direct app with the bots it is switched on for, as the Connect Apps page shows it. */
export function withBots(userId: string, app: CatalogItem): Toolkit {
  return { ...app, connected: true, ...(app.source === 'direct' ? { bots: botsWith(userId, app.slug) } : {}) };
}
