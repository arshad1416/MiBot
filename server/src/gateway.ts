import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import YAML from 'yaml';

/**
 * Running a Hermes gateway out of a home folder of our own.
 *
 * Nothing here knows about the database or this server's settings, so the same code starts a bot's
 * agent on the hub (engine.ts) and on another machine (worker/main.ts).
 */

// ---------- small file helpers ----------

/** KEY=value lines of a dotenv file (quotes stripped). Missing file = {}. */
export function readDotenv(file: string): Record<string, string> {
  const out: Record<string, string> = {};
  let text = '';
  try { text = fs.readFileSync(file, 'utf8'); } catch { return out; }
  for (const line of text.split('\n')) {
    const m = /^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(line);
    if (!m) continue;
    let v = m[2].trim();
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) v = v.slice(1, -1);
    out[m[1]] = v;
  }
  return out;
}

export function readYaml(file: string): any {
  try { return YAML.parse(fs.readFileSync(file, 'utf8')) ?? {}; } catch { return {}; }
}

export function writePrivate(file: string, content: string) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, content, { mode: 0o600 });
  fs.chmodSync(file, 0o600);
}

// ---------- the home ----------

/** Model credentials a managed agent may inherit from the machine's own agent. Never messaging tokens. */
const SHARED_ENV = [
  'ANTHROPIC_API_KEY', 'ANTHROPIC_BASE_URL', 'ANTHROPIC_TOKEN', 'CLAUDE_CODE_OAUTH_TOKEN',
  'OPENAI_API_KEY', 'OPENAI_BASE_URL', 'OPENROUTER_API_KEY', 'XAI_API_KEY', 'GEMINI_API_KEY', 'GOOGLE_API_KEY', 'NOUS_API_KEY',
  'FIRECRAWL_API_KEY', 'TAVILY_API_KEY', 'EXA_API_KEY', 'PARALLEL_API_KEY',
];

/** Parts of the machine's own agent config a managed agent starts from, so it reaches the same models. */
const SHARED_CONFIG = ['model', 'providers', 'custom_providers', 'auxiliary', 'fallback_model', 'fallback_providers'];

export interface HomeSpec {
  home: string;
  /** The Hermes home that already exists on this machine; model settings and keys are inherited from it. */
  primaryHome: string;
  port: number;
  /** The persona file, written once. */
  soul: string;
  /** Hermes `mcp_servers` entries we own. Entries the user added to the config by hand are kept. */
  mcpServers: Record<string, any>;
  /** Where the agent's shell starts. */
  cwd: string;
  /** X display of the bot's screen, when the screen is on this machine. */
  display?: string | null;
  /** DevTools URL of the bot's Chrome, when it has one here. */
  cdp?: string;
}

/** Create or refresh the home. config.yaml is created once; after that only the keys we own are patched. Returns the API key. */
export function writeHermesHome(s: HomeSpec): string {
  fs.mkdirSync(path.join(s.home, 'logs'), { recursive: true });
  fs.mkdirSync(path.join(s.home, 'locks'), { recursive: true });
  fs.chmodSync(s.home, 0o700);
  fs.mkdirSync(s.cwd, { recursive: true });

  const cfgFile = path.join(s.home, 'config.yaml');
  const fresh = !fs.existsSync(cfgFile);
  const cfg = fresh ? {} : readYaml(cfgFile);
  if (fresh) {
    const primary = readYaml(path.join(s.primaryHome, 'config.yaml'));
    for (const k of SHARED_CONFIG) if (primary[k] !== undefined) cfg[k] = primary[k];
    cfg.agent = { reasoning_effort: 'medium' };
  }
  cfg.platform_toolsets = { ...(cfg.platform_toolsets ?? {}), api_server: ['hermes-api-server', ...(s.display ? ['computer_use'] : [])] };
  cfg.terminal = { ...(cfg.terminal ?? {}), cwd: s.cwd };
  if (s.cdp) cfg.browser = { ...(cfg.browser ?? {}), cdp_url: s.cdp };
  cfg.mcp_servers = { ...(cfg.mcp_servers ?? {}), ...s.mcpServers };
  writePrivate(cfgFile, YAML.stringify(cfg));

  if (!fs.existsSync(path.join(s.home, 'SOUL.md'))) fs.writeFileSync(path.join(s.home, 'SOUL.md'), s.soul);

  // Subscription sign-ins live in auth.json. Start from the machine's own; later sign-ins are this bot's.
  const auth = path.join(s.home, 'auth.json');
  if (!fs.existsSync(auth)) {
    try { writePrivate(auth, fs.readFileSync(path.join(s.primaryHome, 'auth.json'), 'utf8')); } catch { /* the machine's agent has none */ }
  }

  // .env is ours entirely: the API server plus inherited model keys. Keys the user added by hand are kept.
  const envFile = path.join(s.home, '.env');
  const mine = readDotenv(envFile);
  const key = mine.API_SERVER_KEY || crypto.randomBytes(24).toString('hex');
  const primaryEnv = readDotenv(path.join(s.primaryHome, '.env'));
  const vars: Record<string, string> = { ...mine };
  for (const k of SHARED_ENV) {
    const v = primaryEnv[k] ?? process.env[k];
    if (v && !vars[k]) vars[k] = v;
  }
  Object.assign(vars, { API_SERVER_ENABLED: 'true', API_SERVER_KEY: key, API_SERVER_PORT: String(s.port), API_SERVER_HOST: '127.0.0.1' });
  if (s.cdp) vars.BROWSER_CDP_URL = s.cdp; else delete vars.BROWSER_CDP_URL;
  writePrivate(envFile, Object.entries(vars).map(([k, v]) => `${k}=${v}`).join('\n') + '\n');
  return key;
}

/** The API key a home's gateway expects, or '' when the home has none yet. */
export const homeKey = (home: string) => readDotenv(path.join(home, '.env')).API_SERVER_KEY ?? '';

// ---------- the gateway process ----------

export async function healthy(url: string, timeoutMs = 2500): Promise<any | null> {
  try {
    const r = await fetch(`${url}/health`, { signal: AbortSignal.timeout(timeoutMs) });
    return r.ok ? await r.json() : null;
  } catch { return null; }
}

export async function keyAccepted(url: string, key: string): Promise<boolean> {
  try {
    const r = await fetch(`${url}/v1/models`, { headers: key ? { Authorization: `Bearer ${key}` } : {}, signal: AbortSignal.timeout(4000) });
    return r.ok;
  } catch { return false; }
}

/** The gateway's pid, but only if the pid file belongs to this home and the process is alive. */
export function gatewayPid(home: string): number | null {
  let raw = '';
  try { raw = fs.readFileSync(path.join(home, 'gateway.pid'), 'utf8').trim(); } catch { return null; }
  let pid = NaN;
  try {
    const j = JSON.parse(raw);
    if (typeof j === 'number') pid = j;
    else {
      if (j?.hermes_home && path.resolve(j.hermes_home) !== path.resolve(home)) return null;
      pid = Number(j?.pid);
    }
  } catch { pid = Number(raw); }
  if (!Number.isInteger(pid) || pid <= 1) return null;
  try { process.kill(pid, 0); } catch { return null; }
  try {
    if (!fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8').includes('gateway')) return null;
  } catch { /* no /proc: trust the pid file */ }
  return pid;
}

export async function stopGateway(home: string) {
  const pid = gatewayPid(home);
  if (!pid) return;
  const signal = (sig: NodeJS.Signals) => {
    try { process.kill(-pid, sig); } catch { try { process.kill(pid, sig); } catch { /* gone */ } }
  };
  signal('SIGTERM');
  for (let i = 0; i < 40; i++) {
    await new Promise((r) => setTimeout(r, 250));
    try { process.kill(pid, 0); } catch { return; }
  }
  signal('SIGKILL');
  await new Promise((r) => setTimeout(r, 300));
}

/** Run `hermes gateway run` for a home and wait until its API answers. The process outlives this one. */
export async function spawnGateway(o: { hermesBin: string; home: string; url: string; display?: string | null }): Promise<void> {
  const logFile = path.join(o.home, 'logs', 'gateway.out.log');
  const log = fs.openSync(logFile, 'a');
  const child = spawn(o.hermesBin, ['gateway', 'run', '--no-supervise'], {
    cwd: o.home,
    detached: true,
    stdio: ['ignore', log, log],
    // Only what the agent needs. None of this process's secrets, and nothing of the machine's own agent's messaging.
    env: {
      PATH: process.env.PATH ?? '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
      HOME: process.env.HOME ?? '/root',
      USER: process.env.USER ?? 'root',
      LANG: process.env.LANG ?? 'C.UTF-8',
      HERMES_HOME: o.home,
      HERMES_GATEWAY_LOCK_DIR: path.join(o.home, 'locks'),
      // Hermes' browser tool talks to a helper process it finds under this folder. With the default
      // (one folder per HOME) every agent on the machine would end up driving the same browser.
      BH_HOME: path.join(o.home, 'browser-harness'),
      ...(o.display ? { DISPLAY: o.display } : {}),
    },
  });
  fs.closeSync(log);
  let exited: string | null = null;
  child.once('error', (e) => { exited = `could not run ${o.hermesBin}: ${e.message}`; });
  child.once('exit', (code) => { exited = `the agent stopped while starting (exit ${code}). See ${logFile}`; });
  child.unref();

  for (let i = 0; i < 90; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    if (exited) throw new Error(exited);
    if (await healthy(o.url, 1500)) return;
  }
  throw new Error('The agent did not come up within 90 seconds.');
}
