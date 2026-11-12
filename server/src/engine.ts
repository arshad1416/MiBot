import { execFile } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { box, displayFor } from './box.js';
import { cdpUrlFor, screenFor } from './computer.js';
import { db } from './db.js';
import { env } from './env.js';
import { copySignIn, gatewayPid, healthy, homeKey, keyAccepted, readDotenv, spawnGateway, stopGateway, writeHermesHome, writeModelAccess } from './gateway.js';
import { accessFor, SUBSCRIPTIONS } from './modelAccess.js';
import type { EngineInfo, EngineProvider } from './types.js';

/**
 * Every bot is run by a Hermes agent.
 *
 * - The 'primary' engine is a Hermes gateway that already runs on the computer (HERMES_URL, home
 *   HERMES_HOME_PRIMARY). It has its own life: messaging, memory, model. We only talk to its API
 *   server; we never write its config, restart it or stop it. With HERMES_URL set to nothing there
 *   is no such agent, and every bot is a managed one.
 * - A 'managed' engine is a Hermes home of our own under ENGINES_DIR/<botId> with its own
 *   `hermes gateway run`, started here and left running when this server exits. Its home sits
 *   outside ~/.hermes, so Hermes treats it as a separate tenant beside the primary gateway.
 *
 * A managed bot can also be given a second home on another machine (see machines.ts); this file is
 * only about the agents on this one.
 */

export type EngineKind = 'primary' | 'managed';
export interface Engine { kind: EngineKind; url: string; key: string; home: string }

const botRow = (botId: string) => db.prepare('SELECT * FROM bots WHERE id=?').get(botId) as any;

// ---------- identity ----------

/** Token a bot's Hermes agent presents to this server's MCP endpoint. `primary` names the primary bot. */
export function mcpToken(botId: string): string {
  return crypto.createHmac('sha256', env.sessionSecret).update(`mcp:${botId}`).digest('hex');
}

export function primaryBotId(): string | null {
  return (db.prepare("SELECT id FROM bots WHERE engine='primary' LIMIT 1").get() as any)?.id ?? null;
}

export function engineKind(botId: string): EngineKind | null {
  const e = botRow(botId)?.engine;
  return e === 'primary' || e === 'managed' ? e : null;
}

const homeFor = (botId: string) => path.join(env.enginesDir, botId);

let cachedPrimaryKey: string | null = null;
function primaryKey(): string {
  if (env.hermesKey) return env.hermesKey;
  if (cachedPrimaryKey === null) cachedPrimaryKey = readDotenv(path.join(env.hermesHome, '.env')).API_SERVER_KEY ?? '';
  return cachedPrimaryKey;
}

// ---------- assignment ----------

function freePort(): number {
  const used = new Set((db.prepare('SELECT engine_port FROM bots WHERE engine_port IS NOT NULL').all() as any[]).map((r) => r.engine_port as number));
  let p = env.enginePortBase;
  while (used.has(p)) p++;
  return p;
}

/** Give a new agent bot its engine: the existing agent if there is one and nobody has it yet, otherwise one of our own. */
export function assignEngine(botId: string): EngineKind {
  const b = botRow(botId);
  if (!b) throw new Error('bot not found');
  if (b.engine === 'primary' || b.engine === 'managed') return b.engine;
  if (env.hermesUrl && !primaryBotId()) {
    db.prepare("UPDATE bots SET engine='primary', engine_port=NULL WHERE id=?").run(botId);
    return 'primary';
  }
  db.prepare("UPDATE bots SET engine='managed', engine_port=? WHERE id=?").run(freePort(), botId);
  return 'managed';
}

// ---------- a managed bot's Hermes home ----------

/** The persona file of a managed bot. */
export function soulFor(botId: string): string {
  const b = botRow(botId);
  const owner = (db.prepare('SELECT name FROM users WHERE id=?').get(b.user_id) as any)?.name ?? 'the user';
  const role = b.label ? `, ${b.label}` : '';
  return [
    `You are ${b.name}${role}. You work for ${owner} as one of a small team of AI agents, each with its own screen on a shared computer.`,
    b.description ? `\n${b.description}` : '',
    '\nYou are direct, warm and brief. You do the work rather than describe it, and you say plainly what you did and what you could not do.',
    '',
  ].join('\n');
}

/** Rewrite the persona file after a rename or a new description. The primary agent keeps its own. */
export function syncPersona(botId: string) {
  const b = botRow(botId);
  if (!b || b.engine !== 'managed') return;
  const home = homeFor(botId);
  if (!fs.existsSync(home)) return;
  fs.writeFileSync(path.join(home, 'SOUL.md'), soulFor(botId));
}

/** Extra MCP servers every managed bot gets (EXTRA_MCP_SERVERS). */
export function extraMcpServers(): Record<string, any> {
  if (!env.extraMcpServers) return {};
  try {
    const v = JSON.parse(env.extraMcpServers);
    return v && typeof v === 'object' && !Array.isArray(v) ? v : {};
  } catch {
    console.warn('[engine] EXTRA_MCP_SERVERS is not valid JSON; ignored');
    return {};
  }
}

/** The Hermes `mcp_servers` entry that gives a bot's agent this app's tools, reached at `baseUrl`. */
export const hqMcpServer = (botId: string, baseUrl: string) => ({
  url: `${baseUrl}/mcp/${botId}`, headers: { Authorization: `Bearer ${mcpToken(botId)}` }, timeout: 1800, connect_timeout: 30,
});

/** Create or refresh the home. */
async function writeHome(botId: string): Promise<{ home: string; key: string; display: string | null }> {
  const b = botRow(botId);
  const home = homeFor(botId);

  // Its screen decides which display and which Chrome it drives. Only meaningful on the computer itself.
  let display: string | null = null;
  let cdp = '';
  if (env.localBox) {
    try {
      const s = await screenFor(b.user_id, botId);
      display = displayFor(s.screenId);
      cdp = cdpUrlFor(botId, s.screenId);
    } catch (e: any) {
      console.warn(`[engine] no screen for ${b.name} yet: ${e?.message ?? e}`);
    }
  }

  const key = writeHermesHome({
    home,
    primaryHome: env.hermesHome,
    port: b.engine_port,
    soul: soulFor(botId),
    mcpServers: { ...extraMcpServers(), hq: hqMcpServer(botId, env.mcpBaseUrl) },
    cwd: env.localBox ? `${env.boxData}/files/${botId}` : path.join(home, 'workspace'),
    display,
    cdp,
    access: accessFor(b.user_id),
  });
  return { home, key, display };
}

// ---------- a managed bot's gateway ----------

const starting = new Map<string, Promise<void>>();
const failures = new Map<string, string>();
const keys = new Map<string, string>();

function managedUrl(botId: string): string {
  return `http://127.0.0.1:${botRow(botId).engine_port}`;
}

function managedKey(botId: string): string {
  let k = keys.get(botId);
  if (!k) {
    k = homeKey(homeFor(botId));
    if (k) keys.set(botId, k);
  }
  return k;
}

async function startGateway(botId: string): Promise<void> {
  const b = botRow(botId);
  if (!b || b.engine !== 'managed') throw new Error('not a managed engine');
  if (!b.engine_port) db.prepare('UPDATE bots SET engine_port=? WHERE id=?').run(freePort(), botId);
  const url = managedUrl(botId);

  // A gateway from an earlier run of this server is still good: use it as it is.
  if ((await healthy(url)) && managedKey(botId) && (await keyAccepted(url, managedKey(botId)))) return;
  if (await healthy(url)) {
    if (gatewayPid(homeFor(botId))) await stopGateway(homeFor(botId));
    else throw new Error(`Port ${botRow(botId).engine_port} is taken by something else.`);
  }

  const { home, key, display } = await writeHome(botId);
  keys.set(botId, key);
  if (gatewayPid(home)) await stopGateway(home);
  await spawnGateway({ hermesBin: env.hermesBin, home, url, display });
  console.log(`[engine] ${b.name}: gateway up on ${url}`);
}

/** Start (or adopt) the bot's gateway. Safe to call often; concurrent calls share one start. */
function ensureManaged(botId: string): Promise<void> {
  let p = starting.get(botId);
  if (!p) {
    p = startGateway(botId)
      .then(() => { failures.delete(botId); })
      .catch((e: any) => {
        const msg = String(e?.message ?? e);
        failures.set(botId, msg);
        console.warn(`[engine] ${botId}: ${msg}`);
        throw e;
      })
      .finally(() => starting.delete(botId));
    starting.set(botId, p);
  }
  return p;
}

/** Where to send this bot's turns. Starts its gateway if it is not running. */
export async function engineFor(botId: string): Promise<Engine> {
  let kind = engineKind(botId);
  if (!kind) {
    const b = botRow(botId);
    if (!b) throw new Error('bot not found');
    if (b.kind === 'room') throw new Error('A group chat has no agent of its own.');
    kind = assignEngine(botId);
  }
  if (kind === 'primary') return { kind, url: env.hermesUrl, key: primaryKey(), home: env.hermesHome };
  const url = managedUrl(botId);
  if (!(await healthy(url, 1500)) || !managedKey(botId)) await ensureManaged(botId);
  return { kind, url, key: managedKey(botId), home: homeFor(botId) };
}

/** The bot's Hermes home, without starting anything (memory files, sign-in commands). */
export function engineHome(botId: string): string | null {
  const kind = engineKind(botId);
  return kind === 'primary' ? env.hermesHome : kind === 'managed' ? homeFor(botId) : null;
}

/**
 * Hermes can run a private screen of its own for a profile (its "Bot Desktop": an Xvnc display with
 * state in <home>/bot-desktop). While that screen is up, the agent's computer-use tool acts there and
 * not on the screen this app shows (so does its browser tool, unless the config pins it to a Chrome
 * with `browser.cdp_url`), so the user could be watching an idle desktop.
 */
export function ownScreenOn(botId: string): boolean {
  const home = engineHome(botId);
  if (!home) return false;
  const dir = path.join(home, 'bot-desktop');
  try {
    // launcher.pid holds "<pid> <create time>"; the env file appears once the screen is usable.
    const pid = Number(fs.readFileSync(path.join(dir, 'launcher.pid'), 'utf8').trim().split(/\s+/)[0]);
    if (!Number.isInteger(pid) || pid <= 1 || !fs.existsSync(path.join(dir, 'env'))) return false;
    process.kill(pid, 0);
    return true;
  } catch (e: any) {
    return e?.code === 'EPERM';
  }
}

/** Ask Hermes to stop that private screen. Hermes refuses while a person is using it; the refusal is passed on. */
export function stopOwnScreen(botId: string): Promise<void> {
  const home = engineHome(botId);
  if (!home) return Promise.reject(new Error('This bot has no agent.'));
  return new Promise((resolve, reject) => {
    execFile(env.hermesBin, ['computer-use', 'screen', 'stop'], { env: { ...process.env, HERMES_HOME: home }, timeout: 60_000 }, (err, stdout, stderr) => {
      if (!ownScreenOn(botId)) return resolve();
      const said = `${stderr || ''}\n${stdout || ''}`.trim().split('\n').filter(Boolean).pop();
      reject(new Error(said || (err ? String(err.message) : 'The screen is still running.')));
    });
  });
}

/** Start the gateway in the background (after creating a bot, or on server start). */
export function provisionEngine(botId: string) {
  if (engineKind(botId) === 'managed') ensureManaged(botId).catch(() => {});
}

export async function restartEngine(botId: string): Promise<void> {
  if (engineKind(botId) !== 'managed') return;
  await starting.get(botId)?.catch(() => {});
  await stopGateway(homeFor(botId));
  optionsCache.delete(botId);
  provisionEngine(botId);
}

/** The bot is gone: stop its gateway and delete its home. The primary agent is left alone. */
export async function removeEngine(botId: string): Promise<void> {
  const home = homeFor(botId);
  if (!fs.existsSync(home)) return;
  await starting.get(botId)?.catch(() => {});
  await stopGateway(homeFor(botId));
  keys.delete(botId);
  failures.delete(botId);
  optionsCache.delete(botId);
  if (path.resolve(home).startsWith(path.resolve(env.enginesDir) + path.sep)) fs.rmSync(home, { recursive: true, force: true });
}

/** On server start: give older bots an engine, then bring the managed gateways up. */
export function initEngines() {
  fs.mkdirSync(env.enginesDir, { recursive: true });
  const bots = db.prepare("SELECT id, is_primary FROM bots WHERE engine IS NULL AND COALESCE(kind,'agent') <> 'room' AND COALESCE(hidden,0)=0 ORDER BY is_primary DESC, created_at ASC").all() as any[];
  for (const b of bots) assignEngine(b.id);
  for (const b of db.prepare("SELECT id FROM bots WHERE engine='managed'").all() as any[]) provisionEngine(b.id);
}

// ---------- what the settings panel shows ----------

/** Providers worth showing even when not signed in. */
const WELL_KNOWN = ['anthropic', 'openai-codex', 'xai-oauth', 'xai', 'openrouter', 'nous', 'gemini', 'openai-api'];

/** What each agent last said it offers. Asked again after 15 s; the old answer stays for a panel whose agent is down. */
const optionsCache = new Map<string, { at: number; data: any }>();

async function modelOptions(botId: string, e: { url: string; key: string }): Promise<any | null> {
  const hit = optionsCache.get(botId);
  if (hit && Date.now() - hit.at < 15_000) return hit.data;
  try {
    const r = await fetch(`${e.url}/api/model/options`, { headers: e.key ? { Authorization: `Bearer ${e.key}` } : {}, signal: AbortSignal.timeout(8000) });
    if (!r.ok) return null;
    const data = await r.json();
    optionsCache.set(botId, { at: Date.now(), data });
    return data;
  } catch { return null; }
}

function toProviders(data: any): EngineProvider[] {
  const list: any[] = Array.isArray(data?.providers) ? data.providers : [];
  const out = list
    .filter((p) => p?.slug && (p.authenticated || WELL_KNOWN.includes(p.slug)))
    .map((p): EngineProvider => {
      const featured = Array.isArray(p.featured_models) && p.featured_models.length ? p.featured_models : Array.isArray(p.models) ? p.models : [];
      return {
        id: String(p.slug),
        name: p.slug === 'anthropic' ? 'Claude (Anthropic)' : String(p.name ?? p.slug),
        // Hermes lists Anthropic as an API-key provider, yet it can also sign in to a Claude subscription. Offer that until a key is in place.
        kind: String(p.auth_type ?? '').includes('oauth') || (p.slug === 'anthropic' && !p.authenticated) ? 'subscription' : 'api_key',
        connected: !!p.authenticated,
        models: featured.filter((m: unknown) => typeof m === 'string').slice(0, 12),
      };
    });
  const rank = (p: EngineProvider) => (p.connected ? 0 : 1) * 100 + (WELL_KNOWN.indexOf(p.id) < 0 ? 50 : WELL_KNOWN.indexOf(p.id));
  return out.sort((a, b) => rank(a) - rank(b));
}

/**
 * The list for a managed bot whose own agent is not answering yet: what it gave last, else what
 * another of the same person's agents gives. A new agent starts from the same sign-ins, keys and
 * default model as the others on this computer, so the list is right until someone signs this bot
 * in to something else, and the bot's own agent replaces it as soon as it is up.
 */
async function standInOptions(botId: string, userId: string): Promise<any | null> {
  const own = optionsCache.get(botId)?.data;
  if (own) return own;
  const others = db.prepare("SELECT id, engine, engine_port FROM bots WHERE user_id=? AND id<>? AND engine IN ('primary','managed') ORDER BY (engine='primary') DESC, created_at ASC").all(userId, botId) as any[];
  for (const o of others) {
    const known = optionsCache.get(o.id)?.data;
    if (known) return known;
  }
  for (const o of others) {
    if (starting.has(o.id) || (o.engine === 'managed' && !o.engine_port)) continue;
    const e = o.engine === 'primary' ? { url: env.hermesUrl, key: primaryKey() } : { url: managedUrl(o.id), key: managedKey(o.id) };
    if (!(await healthy(e.url, 800))) continue;
    const data = await modelOptions(o.id, e);
    if (data) return data;
  }
  return null;
}

export async function engineInfo(botId: string): Promise<EngineInfo> {
  const b = botRow(botId);
  if (!b) throw new Error('bot not found');
  const kind = engineKind(botId);
  const base: EngineInfo = { botId, profile: kind === 'primary' ? 'default' : botId, status: 'stopped', error: null, model: b.model ?? null, provider: b.provider ?? null, version: null, providers: [], ownScreen: kind ? ownScreenOn(botId) : false };
  if (!kind) return { ...base, status: 'error', error: 'This bot has no agent.' };
  const choices = (data: any) => ({ model: b.model ?? data?.model ?? null, provider: b.provider ?? data?.provider ?? null, providers: toProviders(data) });

  let e: { url: string; key: string };
  if (kind === 'primary') e = { url: env.hermesUrl, key: primaryKey() };
  else {
    if (!b.engine_port) return { ...base, status: 'error', error: 'No port assigned.' };
    e = { url: managedUrl(botId), key: managedKey(botId) };
  }

  const health = await healthy(e.url);
  if (!health && kind === 'primary') return { ...base, status: 'error', error: `The agent at ${env.hermesUrl} is not answering.` };
  if (!health || starting.has(botId)) {
    // The model can be chosen before the agent is up: the choice is kept here and sent with each turn.
    const failed = !health && !starting.has(botId) ? failures.get(botId) : undefined;
    // Joins the start that is under way, when there is one.
    if (!failed) provisionEngine(botId);
    return { ...base, ...choices(await standInOptions(botId, b.user_id)), ...(failed ? { status: 'error', error: failed } : { status: 'starting' }) };
  }

  return {
    ...base,
    ...choices(await modelOptions(botId, e)),
    status: 'ready',
    version: typeof health.version === 'string' && health.version !== 'unknown' ? health.version : null,
  };
}

/** The command that signs a Hermes home in to a provider: a subscription where it has one, else its API key. */
export function signInCommand(home: string, provider: string): string {
  const args = SUBSCRIPTIONS.find((s) => s.id === provider)?.args;
  return `HERMES_HOME=${box.quote(home)} ${box.quote(env.hermesBin)} auth add ${provider}${args ? ` ${args}` : ''}`;
}

/** After a sign-in the provider list changes; drop what we remembered. */
export function forgetEngineOptions(botId: string) { optionsCache.delete(botId); }

// ---------- what Settings changes for all of a person's bots ----------

/** The homes of this person's managed agents that exist on this machine. */
function managedHomes(userId: string): { botId: string; home: string }[] {
  return (db.prepare("SELECT id FROM bots WHERE user_id=? AND engine='managed'").all(userId) as { id: string }[])
    .map((b) => ({ botId: b.id, home: homeFor(b.id) }))
    .filter((h) => fs.existsSync(path.join(h.home, 'config.yaml')));
}

/** Lay the person's keys and endpoints into each of their agents. An agent reads them again for its next turn. */
export function applyAccess(userId: string) {
  const access = accessFor(userId);
  for (const { botId, home } of managedHomes(userId)) {
    writeModelAccess(home, access);
    optionsCache.delete(botId);
  }
}

/** Give each of the person's agents the sign-in the machine's own Hermes home holds for `provider`. False when it holds none. */
export function spreadSignIn(userId: string, provider: string): boolean {
  let found = false;
  for (const { botId, home } of managedHomes(userId)) {
    if (!copySignIn(env.hermesHome, home, provider)) continue;
    found = true;
    optionsCache.delete(botId);
  }
  return found;
}

/** When the machine's own Hermes home last had a sign-in written to it (0 = never). */
export function signInsChangedAt(): number {
  try { return fs.statSync(path.join(env.hermesHome, 'auth.json')).mtimeMs; } catch { return 0; }
}

/** Which providers the person's agents can reach, as one of them reports it. Empty while none is up. */
export async function knownProviders(userId: string): Promise<EngineProvider[]> {
  return toProviders(await standInOptions('', userId));
}
