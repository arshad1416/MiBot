// Handoff between machines, end to end on one computer: a hub, one worker and a stand-in for Hermes.
//
//   node tests/machines.mjs
//
// It checks that a heavy job goes to the worker, that a job is paused when the worker "sleeps"
// (SIGSTOP) and carries on when it wakes, and that a job whose worker died is started again with a
// note of what was already done. Needs nothing but `npm ci`; takes about a minute.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mibot-machines-'));
const HUB = 'http://127.0.0.1:18791';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const children = [];

// ---------- a stand-in for `hermes gateway run` ----------
// Each "tool call" takes `delay` ms; the last "steps=N delay=M" in the input sets the pace.
fs.writeFileSync(path.join(dir, 'stub-hermes.mjs'), `
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
const home = process.env.HERMES_HOME;
const env = Object.fromEntries(fs.readFileSync(path.join(home, '.env'), 'utf8').split('\\n').filter(Boolean).map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]));
fs.writeFileSync(path.join(home, 'gateway.pid'), JSON.stringify({ pid: process.pid, hermes_home: home }));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
http.createServer(async (req, res) => {
  if (req.url === '/health') return res.end(JSON.stringify({ status: 'ok', version: 'stub' }));
  if (req.headers.authorization !== 'Bearer ' + env.API_SERVER_KEY) { res.statusCode = 401; return res.end('{}'); }
  if (req.url !== '/v1/responses') return res.end('{"data":[]}');
  let raw = '';
  for await (const c of req) raw += c;
  const body = JSON.parse(raw);
  fs.appendFileSync(path.join(home, 'requests.log'), JSON.stringify({ input: body.input }) + '\\n');
  const last = (re) => [...body.input.matchAll(re)].pop()?.[1];
  const steps = Number(last(/steps=(\\d+)/g) ?? 2);
  const delay = Number(last(/delay=(\\d+)/g) ?? 100);
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  const send = (type, data) => res.write('event: ' + type + '\\ndata: ' + JSON.stringify({ type, ...data }) + '\\n\\n');
  for (let i = 1; i <= steps; i++) {
    const id = 'c' + Date.now() + i;
    send('response.output_item.added', { item: { type: 'function_call', call_id: id, name: 'terminal', arguments: JSON.stringify({ command: 'echo step ' + i }) } });
    await sleep(delay);
    send('response.output_item.done', { item: { type: 'function_call_output', call_id: id, output: 'step ' + i } });
  }
  send('response.output_text.delta', { delta: 'Finished ' + steps + ' steps.' });
  send('response.output_item.done', { item: { type: 'message', content: [{ text: 'x' }] } });
  res.end();
}).listen(Number(env.API_SERVER_PORT), '127.0.0.1');
`);
const hermes = path.join(dir, 'hermes');
fs.writeFileSync(hermes, `#!/bin/bash\nexec "${process.execPath}" "${path.join(dir, 'stub-hermes.mjs')}" "$@"\n`, { mode: 0o755 });

function start(name, cmd, args, env) {
  const log = fs.openSync(path.join(dir, `${name}.log`), 'a');
  const child = spawn(cmd, args, { cwd: path.join(repo, 'server'), env: { ...process.env, ...env }, stdio: ['ignore', log, log], detached: true });
  children.push(child);
  return child;
}
const stopGroup = (child, sig = 'SIGKILL') => { try { process.kill(-child.pid, sig); } catch { /* gone */ } };

async function until(what, test, ms = 60_000) {
  const end = Date.now() + ms;
  for (;;) {
    const v = await test().catch(() => null);
    if (v) return v;
    if (Date.now() > end) throw new Error(`timed out waiting for: ${what}`);
    await sleep(500);
  }
}

let cookie = '';
async function api(method, p, body) {
  const r = await fetch(`${HUB}/api${p}`, { method, headers: { 'content-type': 'application/json', cookie }, body: body ? JSON.stringify(body) : undefined });
  const set = r.headers.get('set-cookie');
  if (set) cookie = set.split(';')[0];
  return r.json();
}
const rows = async (botId) => (await api('GET', `/bots/${botId}/rows`)).rows.map((r) => r.body);
const said = (list, text) => list.some((b) => (b.type === 'message' || b.type === 'notice') && b.text.includes(text));

let failed = false;
function check(name, ok) {
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name}`);
  if (!ok) failed = true;
}

try {
  // The hub, and the agent it expects to find already running.
  const primary = path.join(dir, 'primary');
  fs.mkdirSync(primary);
  fs.writeFileSync(path.join(primary, '.env'), 'API_SERVER_PORT=18792\nAPI_SERVER_KEY=pk\n');
  start('primary', process.execPath, [path.join(dir, 'stub-hermes.mjs')], { HERMES_HOME: primary });
  const tsx = path.join(repo, 'node_modules', '.bin', 'tsx');
  start('hub', tsx, ['src/index.ts'], {
    PORT: '18791', HOST: '127.0.0.1', XAI_API_KEY: 'x', SESSION_SECRET: 'test-secret-test-secret', DEV_LOGIN: '1', COMPUTER_PROVIDER: 'local',
    DB_PATH: path.join(dir, 'hub.db'), ENGINES_DIR: path.join(dir, 'hub-engines'), HERMES_BIN: hermes, HERMES_HOME_PRIMARY: primary,
    HERMES_URL: 'http://127.0.0.1:18792', HERMES_KEY: 'pk', ENGINE_PORT_BASE: '18800', SERVER_URL: HUB, APP_URL: HUB,
    HQ_ROOT: path.join(dir, 'hq'), HQ_DATA: path.join(dir, 'data'), HQ_LOGS: path.join(dir, 'logs'), COMPOSIO_API_KEY: '',
  });
  await until('the hub to start', async () => (await fetch(`${HUB}/api/health`)).ok);
  await api('POST', '/auth/dev', { email: 'test@example.com' });
  const { bot } = await api('POST', '/bots', { name: 'Tester' });
  check('a new bot is heavy by default', bot.weight === 'heavy');

  // The hub takes light jobs only, so the heavy bot's jobs need the other machine.
  await api('PATCH', '/machines/hub', { takes: 'light' });
  const added = await api('POST', '/machines', { name: 'Laptop', takes: 'both' });
  const send = (text) => api('POST', `/bots/${bot.id}/messages`, { text });
  const jobs = async () => (await api('GET', '/machines')).jobs;

  await send('first steps=2');
  await until('the job to wait for the laptop', async () => (await jobs())[0]?.note?.includes('Waiting for Laptop'));
  check('a heavy job waits while no machine that takes it is awake', true);

  const workerEnv = { MIBOT_WORKER_ENV: path.join(dir, 'none.env'), HUB_URL: HUB, WORKER_TOKEN: added.token, WORKER_DIR: path.join(dir, 'laptop'), HERMES_BIN: hermes, HERMES_HOME_PRIMARY: path.join(dir, 'laptop-hermes'), WORKER_PORT_BASE: '18850' };
  let worker = start('worker', tsx, ['src/worker/main.ts'], workerEnv);
  await until('the first job to finish on the laptop', async () => said(await rows(bot.id), 'Finished 2 steps.'));
  check('it runs as soon as the machine checks in', true);
  const requestLog = path.join(dir, 'laptop', 'engines', bot.id, 'requests.log');
  const gatewayPid = () => JSON.parse(fs.readFileSync(path.join(dir, 'laptop', 'engines', bot.id, 'gateway.pid'), 'utf8')).pid;

  // Sleep: freeze the worker and its agent mid-job, then let them go on.
  await send('second steps=12 delay=1000');
  await until('the second job to get going', async () => (await rows(bot.id)).filter((b) => b.type === 'tool-call').length >= 5);
  process.kill(-worker.pid, 'SIGSTOP');
  process.kill(gatewayPid(), 'SIGSTOP');
  await until('the job to be paused', async () => (await jobs())[0]?.status === 'paused', 60_000);
  check('a job is paused when its machine stops answering', said(await rows(bot.id), 'This job is paused'));
  process.kill(gatewayPid(), 'SIGCONT');
  process.kill(-worker.pid, 'SIGCONT');
  await until('the second job to finish', async () => said(await rows(bot.id), 'Finished 12 steps.'));
  const afterSleep = await rows(bot.id);
  check('it carries on when the machine is back', said(afterSleep, 'Laptop is back'));
  check('without starting over', fs.readFileSync(requestLog, 'utf8').trim().split('\n').length === 2);

  // The worker dies mid-job and comes back: the job is started again and told what was done.
  await send('third steps=6 delay=1000');
  await until('the third job to get going', async () => (await rows(bot.id)).filter((b) => b.type === 'tool-call').length >= 12 + 2 + 3);
  stopGroup(worker);
  worker = start('worker', tsx, ['src/worker/main.ts'], workerEnv);
  await until('the third job to finish', async () => said(await rows(bot.id), 'Finished 6 steps.'), 90_000);
  const lastInput = JSON.parse(fs.readFileSync(requestLog, 'utf8').trim().split('\n').pop()).input;
  check('a job whose worker died is started again', lastInput.includes('was interrupted before it finished'));
  check('with the steps already taken', lastInput.includes('You used') && lastInput.includes('echo step 1'));
  check('no job is left open', (await jobs()).length === 0);
} catch (e) {
  console.error(`FAIL  ${e.message}`);
  failed = true;
} finally {
  for (const c of children) stopGroup(c);
  // The agents were started detached by the hub and the worker: stop them by their pid files.
  for (const engines of [path.join(dir, 'hub-engines'), path.join(dir, 'laptop', 'engines')]) {
    for (const id of fs.existsSync(engines) ? fs.readdirSync(engines) : []) {
      try { process.kill(JSON.parse(fs.readFileSync(path.join(engines, id, 'gateway.pid'), 'utf8')).pid, 'SIGKILL'); } catch { /* not running */ }
    }
  }
  if (failed) console.error(`Logs are in ${dir}`);
  else fs.rmSync(dir, { recursive: true, force: true });
}
process.exit(failed ? 1 : 0);
