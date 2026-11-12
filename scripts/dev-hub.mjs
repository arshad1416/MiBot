// A throwaway hub for trying an app (the Android app, a browser) without your real hub or a real agent.
//
//   node scripts/dev-hub.mjs                              on this computer only: http://127.0.0.1:18891
//   node scripts/dev-hub.mjs --url http://10.0.2.2:18891  as the Android emulator sees this computer
//   node scripts/dev-hub.mjs --host 0.0.0.0 --url http://<this computer's address>:18891   for a phone on the same network
//
// It prints the address and a one-time code to add a device with; press Enter for another code.
// Bots answer through a stand-in agent (tests/stub-hermes.mjs): write "steps=5 delay=2000" in a
// message to see five slow steps. Everything it stores is in a temporary folder that is removed
// when you stop it with Ctrl-C. Needs `npm ci` to have been run.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const arg = (name, fallback) => { const i = process.argv.indexOf(`--${name}`); return i > 0 ? process.argv[i + 1] : fallback; };
const port = Number(arg('port', 18891));
const host = arg('host', '127.0.0.1');
const local = `http://127.0.0.1:${port}`;
const url = arg('url', local);
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mibot-dev-hub-'));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// The agent the hub expects to find already running, and the one it starts for each new bot.
const stub = path.join(repo, 'tests', 'stub-hermes.mjs');
const primary = path.join(dir, 'primary');
fs.mkdirSync(primary);
fs.writeFileSync(path.join(primary, '.env'), `API_SERVER_PORT=${port + 1}\nAPI_SERVER_KEY=pk\n`);
// The hub leaves a bot's agent running when it stops itself, so each one notes its process id here.
const hermes = path.join(dir, 'hermes');
const agents = path.join(dir, 'agents');
fs.writeFileSync(hermes, `#!/bin/bash\necho $$ >> "${agents}"\nexec "${process.execPath}" "${stub}" "$@"\n`, { mode: 0o755 });
const log = fs.openSync(path.join(dir, 'hub.log'), 'a');
const children = [
  spawn(process.execPath, [stub], { env: { ...process.env, HERMES_HOME: primary }, stdio: ['ignore', log, log] }),
  spawn(path.join(repo, 'node_modules', '.bin', 'tsx'), ['src/index.ts'], {
    cwd: path.join(repo, 'server'), stdio: ['ignore', log, log],
    env: {
      ...process.env, PORT: String(port), HOST: host, SERVER_URL: url, APP_URL: url, DEV_LOGIN: '1', SESSION_SECRET: 'dev-hub-secret-dev-hub-secret', XAI_API_KEY: 'x', COMPOSIO_API_KEY: '',
      // No computer for the bots: an unusable Orgo key keeps the hub from starting screens on this machine.
      COMPUTER_PROVIDER: 'orgo', ORGO_API_KEY: 'none', GROKBOT_NO_SCHEDULER: '1',
      DB_PATH: path.join(dir, 'hub.db'), ENGINES_DIR: path.join(dir, 'engines'), HERMES_BIN: hermes, HERMES_HOME_PRIMARY: primary, HERMES_URL: `http://127.0.0.1:${port + 1}`, HERMES_KEY: 'pk',
      ENGINE_PORT_BASE: String(port + 10), HQ_ROOT: path.join(dir, 'hq'), HQ_DATA: path.join(dir, 'data'), HQ_LOGS: path.join(dir, 'logs'),
    },
  }),
];
function stop() {
  for (const c of children) c.kill();
  const started = fs.existsSync(agents) ? fs.readFileSync(agents, 'utf8').split('\n').filter(Boolean) : [];
  for (const pid of started) { try { process.kill(Number(pid)); } catch { /* already gone */ } }
  fs.rmSync(dir, { recursive: true, force: true });
  process.exit(0);
}
process.on('SIGINT', stop);
process.on('SIGTERM', stop);

for (let i = 0; ; i++) {
  if (await fetch(`${local}/api/health`).then((r) => r.ok, () => false)) break;
  if (i > 60) { console.error(`The hub did not start. Its log:\n${fs.readFileSync(path.join(dir, 'hub.log'), 'utf8').slice(-2000)}`); stop(); }
  await sleep(500);
}
let cookie = '';
async function api(method, p, body) {
  const r = await fetch(`${local}/api${p}`, { method, headers: { 'content-type': 'application/json', cookie }, body: body ? JSON.stringify(body) : undefined });
  const set = r.headers.get('set-cookie');
  if (set) cookie = set.split(';')[0];
  return r.json();
}
await api('POST', '/auth/dev', { email: 'dev@example.com' });
const newCode = async () => console.log(`Code: ${(await api('POST', '/pair/codes', {})).code}   (works once, for ten minutes; Enter makes another)`);
if (!fs.existsSync(path.join(repo, 'web', 'dist'))) console.log('The web app is not built here, so pages that open it will not load. `npm run build` builds it.');
console.log(`A dev hub is running. In the app, choose "Type the code instead".\nAddress: ${url}`);
await newCode();
process.stdin.on('data', newCode);
