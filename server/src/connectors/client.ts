import crypto from 'node:crypto';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { auth as runAuth, type OAuthClientProvider } from '@modelcontextprotocol/sdk/client/auth.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { env } from '../env.js';
import { GOOGLE_TOOLS, googleAuthUrl, googleCall, googleConfigured, googleExchange, googleFresh, type GoogleTokens } from './builtin/google.js';
import { connectorEntry, slugOf, type ConnectorAuth, type ConnectorEntry } from './catalog.js';
import {
  connectionByState, deleteConnection, entryFor, getConnection, patchSecret, readSecret, setConnection, upsertConnection,
  type Connection, type ConnectorTool,
} from './store.js';

/**
 * Talking to a connected app: this server is the MCP client, holds the sign-in, and hands the app's
 * tools on to the bots through its own MCP endpoint (see mcp.ts). One sign-in serves every bot that
 * is allowed to use the app.
 */

export const OAUTH_CALLBACK = `${env.serverUrl}/api/apps/oauth/callback`;

const READ_NAME = /(^|[_-])(get|list|search|fetch|find|read|retrieve|query|lookup|check|describe|view|count|show|status|whoami)([_-]|$)/i;

/** Our side of the OAuth dance for one connection; everything it remembers goes in the store. */
class StoredAuth implements OAuthClientProvider {
  /** Set when the app wants the person to sign in. */
  authorizationUrl: URL | null = null;

  constructor(private userId: string, private slug: string, private entry: ConnectorEntry) {}

  get redirectUrl() { return OAUTH_CALLBACK; }

  get clientMetadata() {
    return {
      client_name: 'MiBot',
      redirect_uris: [OAUTH_CALLBACK],
      grant_types: ['authorization_code', 'refresh_token'],
      response_types: ['code'],
      token_endpoint_auth_method: this.entry.oauthClient?.clientSecret ? 'client_secret_post' : 'none',
      ...(this.entry.oauthClient?.scope ? { scope: this.entry.oauthClient.scope } : {}),
    };
  }

  state() {
    const state = crypto.randomBytes(24).toString('base64url');
    setConnection(this.userId, this.slug, { state });
    return state;
  }

  clientInformation() {
    const pinned = this.entry.oauthClient;
    if (pinned) return { client_id: pinned.clientId, ...(pinned.clientSecret ? { client_secret: pinned.clientSecret } : {}) };
    return readSecret(this.userId, this.slug).clientInfo as any;
  }
  saveClientInformation(clientInfo: unknown) { patchSecret(this.userId, this.slug, { clientInfo }); }

  tokens() { return readSecret(this.userId, this.slug).tokens as any; }
  saveTokens(tokens: unknown) { patchSecret(this.userId, this.slug, { tokens }); }

  redirectToAuthorization(url: URL) { this.authorizationUrl = url; }

  saveCodeVerifier(codeVerifier: string) { patchSecret(this.userId, this.slug, { codeVerifier }); }
  codeVerifier() { return readSecret(this.userId, this.slug).codeVerifier ?? ''; }

  invalidateCredentials(scope: 'all' | 'client' | 'tokens' | 'verifier' | 'discovery') {
    if (scope === 'all' || scope === 'tokens') patchSecret(this.userId, this.slug, { tokens: undefined });
    if (scope === 'all' || scope === 'client') patchSecret(this.userId, this.slug, { clientInfo: undefined });
    if (scope === 'all' || scope === 'verifier') patchSecret(this.userId, this.slug, { codeVerifier: undefined });
  }
}

/** A Google access token for this connection, renewed and saved when it has run out. */
function googleToken(c: Connection): Promise<string> {
  const tokens = readSecret(c.userId, c.slug).tokens as GoogleTokens | undefined;
  if (!tokens?.access_token) throw new Error('Google Workspace is not signed in. Connect it again.');
  return googleFresh(tokens, (next) => patchSecret(c.userId, c.slug, { tokens: next }));
}

// ---------- live sessions, kept for a few minutes between calls ----------

interface Session { client: Client; timer: NodeJS.Timeout }
const sessions = new Map<string, Session>();
const sessionKey = (userId: string, slug: string) => `${userId}\0${slug}`;

function drop(userId: string, slug: string) {
  const k = sessionKey(userId, slug);
  const s = sessions.get(k);
  if (!s) return;
  clearTimeout(s.timer);
  sessions.delete(k);
  void s.client.close().catch(() => {});
}

async function session(c: Connection): Promise<Client> {
  const k = sessionKey(c.userId, c.slug);
  const live = sessions.get(k);
  if (live) {
    live.timer.refresh();
    return live.client;
  }
  const entry = entryFor(c);
  const apiKey = c.auth === 'api_key' ? readSecret(c.userId, c.slug).apiKey : undefined;
  const transport = new StreamableHTTPClientTransport(new URL(c.url), {
    ...(c.auth === 'oauth' ? { authProvider: new StoredAuth(c.userId, c.slug, entry) } : {}),
    ...(apiKey ? { requestInit: { headers: { Authorization: `Bearer ${apiKey}` } } } : {}),
  });
  const client = new Client({ name: 'mibot', version: '1.0.0' });
  await client.connect(transport);
  const timer = setTimeout(() => drop(c.userId, c.slug), 300_000);
  timer.unref();
  sessions.set(k, { client, timer });
  return client;
}

async function loadTools(c: Connection): Promise<ConnectorTool[]> {
  if (entryFor(c).builtin === 'google') {
    await googleToken(c); // proves the sign-in works
    return GOOGLE_TOOLS;
  }
  const hidden = new Set(entryFor(c).excludedTools);
  const client = await session(c);
  const out: ConnectorTool[] = [];
  let cursor: string | undefined;
  do {
    const page = await client.listTools(cursor ? { cursor } : undefined);
    for (const t of page.tools) {
      if (hidden.has(t.name)) continue;
      const hint = t.annotations?.readOnlyHint;
      out.push({ name: t.name, description: t.description ?? '', inputSchema: t.inputSchema, readOnly: hint ?? READ_NAME.test(t.name) });
    }
    cursor = page.nextCursor;
  } while (cursor);
  return out;
}

/** The sign-in is done (or was never needed): check the app answers and remember its tools. */
async function finish(userId: string, slug: string): Promise<Connection> {
  const c = getConnection(userId, slug)!;
  try {
    const tools = await loadTools(c);
    setConnection(userId, slug, { status: 'connected', error: null, state: null, tools });
  } catch (e: any) {
    drop(userId, slug);
    setConnection(userId, slug, { status: 'error', error: String(e?.message ?? e).slice(0, 300) });
  }
  return getConnection(userId, slug)!;
}

export interface ConnectStart { status: 'connected' | 'redirect' | 'needs_key' | 'error'; redirectUrl?: string; keyPrompt?: string; error?: string }

/** Begin connecting an app. OAuth apps answer with the page to send the person to. */
export async function startConnect(userId: string, slug: string, opts: { apiKey?: string } = {}): Promise<ConnectStart> {
  const existing = getConnection(userId, slug);
  const entry = existing?.custom ? entryFor(existing) : connectorEntry(slug);
  if (!entry) return { status: 'error', error: `No app called ${slug}` };
  if (entry.auth === 'api_key' && !opts.apiKey && !readSecret(userId, slug).apiKey) return { status: 'needs_key', keyPrompt: entry.keyPrompt ?? `${entry.name} API key` };

  upsertConnection(userId, entry, !!existing?.custom);
  drop(userId, slug);
  if (opts.apiKey) patchSecret(userId, slug, { apiKey: opts.apiKey });

  if (entry.builtin === 'google') {
    if (!googleConfigured()) {
      const error = 'Google Workspace needs your own Google Cloud OAuth client. Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET on the server, then connect again.';
      setConnection(userId, slug, { status: 'error', error });
      return { status: 'error', error };
    }
    const state = crypto.randomBytes(24).toString('base64url');
    setConnection(userId, slug, { state });
    return { status: 'redirect', redirectUrl: googleAuthUrl(state, OAUTH_CALLBACK) };
  }

  if (entry.auth === 'oauth') {
    const provider = new StoredAuth(userId, slug, entry);
    try {
      const result = await runAuth(provider, { serverUrl: entry.url });
      if (result === 'REDIRECT' && provider.authorizationUrl) return { status: 'redirect', redirectUrl: provider.authorizationUrl.toString() };
    } catch (e: any) {
      const error = String(e?.message ?? e).slice(0, 300);
      setConnection(userId, slug, { status: 'error', error });
      return { status: 'error', error };
    }
  }
  const done = await finish(userId, slug);
  return done.status === 'connected' ? { status: 'connected' } : { status: 'error', error: done.error ?? 'The app did not answer' };
}

/** The person came back from the app's sign-in page. Returns the connection it belonged to. */
export async function completeOAuth(state: string, code: string): Promise<Connection | null> {
  const c = connectionByState(state);
  if (!c) return null;
  try {
    if (entryFor(c).builtin === 'google') patchSecret(c.userId, c.slug, { tokens: await googleExchange(code, OAUTH_CALLBACK) });
    else await runAuth(new StoredAuth(c.userId, c.slug, entryFor(c)), { serverUrl: c.url, authorizationCode: code });
  } catch (e: any) {
    setConnection(c.userId, c.slug, { status: 'error', error: String(e?.message ?? e).slice(0, 300), state: null });
    return getConnection(c.userId, c.slug);
  }
  return finish(c.userId, c.slug);
}

/** Add an MCP server that is not in the catalog. */
export function addCustom(userId: string, name: string, url: string, auth: ConnectorAuth): string {
  const u = new URL(url);
  if (u.protocol !== 'https:' && u.hostname !== 'localhost' && u.hostname !== '127.0.0.1') throw new Error('The address must start with https://');
  const slug = slugOf(name);
  if (!slug) throw new Error('Give the app a name');
  if (connectorEntry(slug)) throw new Error(`${name} is already in the catalog`);
  upsertConnection(userId, { slug, name: name.trim(), url: u.toString(), auth }, true);
  return slug;
}

export function disconnect(userId: string, slug: string) {
  drop(userId, slug);
  deleteConnection(userId, slug);
}

// ---------- the tools, as the bots see them ----------

/** `notion` + `search` -> `notion__search`: one flat list of names across every connected app. */
export const toolName = (slug: string, tool: string) => `${slug}__${tool}`;

export function splitToolName(name: string): { slug: string; tool: string } | null {
  const i = name.indexOf('__');
  return i > 0 ? { slug: name.slice(0, i), tool: name.slice(i + 2) } : null;
}

export async function callTool(c: Connection, tool: string, args: Record<string, unknown>): Promise<{ output: string; ok: boolean }> {
  if (entryFor(c).builtin === 'google') {
    const out = JSON.stringify(await googleCall(await googleToken(c), tool, args));
    return { output: out.length > 60000 ? `${out.slice(0, 60000)}…(truncated)` : out, ok: true };
  }
  let result: any;
  try {
    result = await (await session(c)).callTool({ name: tool, arguments: args });
  } catch (e) {
    drop(c.userId, c.slug); // a stale session is the usual cause; the next call starts a fresh one
    throw e;
  }
  const parts = Array.isArray(result.content) ? result.content : [];
  let output = parts.map((p: any) => (p?.type === 'text' ? String(p.text) : `[${p?.type ?? 'content'}]`)).join('\n');
  if (!output && result.structuredContent) output = JSON.stringify(result.structuredContent);
  if (output.length > 60000) output = `${output.slice(0, 60000)}…(truncated)`;
  return { output, ok: !result.isError };
}
