// Handoff between machines, end to end on one computer: a hub, one worker and a stand-in for Hermes.
//
//   node tests/machines.mjs
//
// It checks that a machine is added with a one-time code (from the command line and from the page a
// worker serves), that a heavy job goes to the worker, that a job is paused when the worker "sleeps"
// (SIGSTOP) and carries on when it wakes, that a job whose worker died is started again with a
// note of what was already done, that a new bot's model can be chosen while its agent is still
// starting, that a key, an endpoint or a sign-in added in Settings reaches every agent, and that a
// bot on Auto gets a model, a size and a machine for each job from Jev (a stand-in for it here).
// Needs nothing but `npm ci`; takes about a minute.
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mibot-machines-'));
const HUB = 'http://127.0.0.1:18791';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const children = [];
// The hub starts a screen for each bot, with a message bus of its own; the ones that appear during the test are stopped at the end.
const pids = (...find) => spawnSync('pgrep', find).stdout.toString().split('\n').filter(Boolean);
const screens = () => [...pids('-x', 'Xvfb'), ...pids('-f', 'dbus-daemon --session --fork --print-address')];
const screensBefore = new Set(screens());
// A screen that is killed leaves its lock file and socket behind.
const screenFiles = () => [...fs.readdirSync('/tmp').filter((f) => /^\.X\d+-lock$/.test(f)).map((f) => path.join('/tmp', f)), ...(fs.existsSync('/tmp/.X11-unix') ? fs.readdirSync('/tmp/.X11-unix').map((f) => path.join('/tmp/.X11-unix', f)) : [])];
const screenFilesBefore = new Set(screenFiles());

// ---------- a stand-in for Jev on OpenRouter ----------
// It picks the model whose notes name what the job says ("write code" or not), calls a job light when
// it says "small", always names the first machine, and is unsure of the model for "hard to say".
const jev = { asked: [] };
const jevServer = http.createServer(async (req, res) => {
  let raw = '';
  for await (const c of req) raw += c;
  const body = JSON.parse(raw);
  jev.asked.push({ url: req.url, auth: req.headers.authorization, body });
  const options = (name) => Object.entries(body.questions[name]?.criteria ?? {});
  const answers = {
    model: { type: 'choice', choice: options('model').find(([, about]) => about.includes(body.state.includes('write code') ? 'Coder' : 'Quick'))?.[0], confidence: body.state.includes('hard to say') ? 0.2 : 0.9 },
    size: { type: 'choice', choice: body.state.includes('small') ? 'light' : 'heavy', confidence: 0.9 },
    machine: { type: 'choice', choice: options('machine')[0]?.[0], confidence: 0.9 },
  };
  res.setHeader('content-type', 'application/json');
  res.end(JSON.stringify({ answers, usage: { input_tokens: 1, output_tokens: 0, cost: 0 } }));
}).listen(18796, '127.0.0.1');

// ---------- a stand-in for `hermes gateway run` (tests/stub-hermes.mjs) ----------
// While the file `slow-start` exists, a gateway that is starting does not answer yet.
const stub = path.join(repo, 'tests', 'stub-hermes.mjs');
const slowStart = path.join(dir, 'slow-start');
const hermes = path.join(dir, 'hermes');
fs.writeFileSync(hermes, `#!/bin/bash\nSTUB_SLOW_START="${slowStart}" exec "${process.execPath}" "${stub}" "$@"\n`, { mode: 0o755 });

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
  start('primary', process.execPath, [stub], { HERMES_HOME: primary });
  const tsx = path.join(repo, 'node_modules', '.bin', 'tsx');
  start('hub', tsx, ['src/index.ts'], {
    PORT: '18791', HOST: '127.0.0.1', XAI_API_KEY: 'x', SESSION_SECRET: 'test-secret-test-secret', DEV_LOGIN: '1', COMPUTER_PROVIDER: 'local',
    DB_PATH: path.join(dir, 'hub.db'), ENGINES_DIR: path.join(dir, 'hub-engines'), HERMES_BIN: hermes, HERMES_HOME_PRIMARY: primary,
    HERMES_URL: 'http://127.0.0.1:18792', HERMES_KEY: 'pk', ENGINE_PORT_BASE: '18800', SERVER_URL: HUB, APP_URL: HUB,
    HQ_ROOT: path.join(dir, 'hq'), HQ_DATA: path.join(dir, 'data'), HQ_LOGS: path.join(dir, 'logs'), COMPOSIO_API_KEY: '', OPENROUTER_URL: 'http://127.0.0.1:18796',
  });
  await until('the hub to start', async () => (await fetch(`${HUB}/api/health`)).ok);
  await api('POST', '/auth/dev', { email: 'test@example.com' });
  const { bot } = await api('POST', '/bots', { name: 'Tester' });
  check('a new bot is heavy by default', bot.weight === 'heavy');

  // The hub takes light jobs only, so the heavy bot's jobs need the other machine.
  await api('PATCH', '/machines/hub', { takes: 'light' });
  // The laptop joins with a one-time code, handed in from its command line.
  const laptopEnv = path.join(dir, 'laptop.env');
  const code = await api('POST', '/pair/codes', { name: 'Laptop', takes: 'both' });
  const paired = spawnSync(tsx, ['src/worker/main.ts', 'pair', code.link], { cwd: path.join(repo, 'server'), env: { ...process.env, MIBOT_WORKER_ENV: laptopEnv }, encoding: 'utf8' });
  check('a machine joins with the link from the app', paired.status === 0 && /^WORKER_TOKEN=.+/m.test(fs.readFileSync(laptopEnv, 'utf8')) && (await api('GET', `/pair/codes/${code.id}`)).deviceName === 'Laptop');
  check('its settings are readable by its user only', (fs.statSync(laptopEnv).mode & 0o777) === 0o600);
  const again = await fetch(`${HUB}/api/pair/machine`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ code: code.code }) });
  check('a code works once', again.status === 400);
  const send = (text) => api('POST', `/bots/${bot.id}/messages`, { text });
  const jobs = async () => (await api('GET', '/machines')).jobs;

  await send('first steps=2');
  await until('the job to wait for the laptop', async () => (await jobs())[0]?.note?.includes('Waiting for Laptop'));
  check('a heavy job waits while no machine that takes it is awake', true);

  const workerEnv = { MIBOT_WORKER_ENV: laptopEnv, WORKER_DIR: path.join(dir, 'laptop'), HERMES_BIN: hermes, HERMES_HOME_PRIMARY: path.join(dir, 'laptop-hermes'), WORKER_PORT_BASE: '18850' };
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

  // A second machine joins from the page its worker serves while it has no settings.
  const page = 'http://127.0.0.1:18795';
  start('desk', tsx, ['src/worker/main.ts'], { MIBOT_WORKER_ENV: path.join(dir, 'desk.env'), WORKER_PAIR_PORT: '18795', WORKER_DIR: path.join(dir, 'desk'), HERMES_BIN: hermes, WORKER_PORT_BASE: '18870' });
  await until('the setup page', async () => (await fetch(page)).ok);
  const hand = (body, origin = page) => fetch(`${page}/pair`, { method: 'POST', headers: { 'content-type': 'application/json', origin }, body: JSON.stringify(body) });
  const deskCode = await api('POST', '/pair/codes', { name: '', takes: 'light' });
  check('another site cannot point a worker at a hub', (await hand({ hub: HUB, code: deskCode.code }, 'https://elsewhere.example')).status === 403);
  check('a wrong code is turned down', (await hand({ hub: HUB, code: 'AAAA-AAAA' })).status === 400);
  const joined = await hand({ hub: HUB, code: deskCode.code.toLowerCase() });
  const desk = async () => (await api('GET', '/machines')).machines.find((m) => m.name === os.hostname().replace(/\.local$/, ''));
  check('a machine joins with the code typed on its own page', joined.status === 200 && (await desk())?.takes === 'light');
  await until('the new machine to come online', async () => (await desk())?.state === 'online');
  check('and starts working without being restarted', true);

  // The same kind of code signs another browser in.
  const phoneCode = await api('POST', '/pair/codes', {});
  const signIn = await fetch(`${HUB}/api/pair/sign-in`, { method: 'POST', headers: { 'content-type': 'application/json', 'user-agent': 'Mozilla/5.0 (iPhone)' }, body: JSON.stringify({ code: phoneCode.code }) });
  const phoneCookie = (signIn.headers.get('set-cookie') ?? '').split(';')[0];
  const me = await (await fetch(`${HUB}/api/auth/me`, { headers: { cookie: phoneCookie } })).json();
  check('a phone signs in with a code', signIn.ok && me.user?.email === 'test@example.com' && (await api('GET', `/pair/codes/${phoneCode.id}`)).deviceName === 'an iPhone');

  // A new bot's agent can take minutes to start; its model list is there meanwhile, from the agent that is up.
  fs.writeFileSync(slowStart, '');
  const { bot: newcomer } = await api('POST', '/bots', { name: 'Newcomer' });
  const engine = async () => (await api('GET', `/bots/${newcomer.id}/engine`)).engine;
  const early = await engine();
  check('a new bot has its model list while its agent starts', early.status === 'starting' && early.providers[0]?.models.includes('stub-small'));
  await api('PATCH', `/bots/${newcomer.id}/engine`, { provider: 'openai-codex', model: 'stub-small' });
  fs.rmSync(slowStart);
  const ready = await until('the new agent to start', async () => { const e = await engine(); return e.status === 'ready' && e; });
  check('and keeps the model chosen meanwhile', ready.model === 'stub-small' && ready.providers.length === 1);

  // What is added in Settings › Models reaches every agent: the one on the hub at once, the one on another machine with its next job.
  const hubHome = path.join(dir, 'hub-engines', newcomer.id);
  const laptopHome = path.join(dir, 'laptop', 'engines', bot.id);
  const read = (home, file) => fs.readFileSync(path.join(home, file), 'utf8');
  fs.appendFileSync(path.join(hubHome, '.env'), 'MY_OWN=kept\n');
  await api('POST', '/models/endpoints', { name: '9Router', baseUrl: 'http://localhost:20128/v1/', key: 'sk-router-secret', models: 'cx/gpt-6-luna, cc/claude' });
  await api('PUT', '/models/keys/openrouter', { key: 'sk-or-secret' });
  check('a key added in Settings reaches a bot’s agent', read(hubHome, '.env').includes('OPENROUTER_API_KEY=sk-or-secret'));
  check('so does an endpoint', read(hubHome, 'config.yaml').includes('api: http://localhost:20128/v1\n') && read(hubHome, 'config.yaml').includes('key_env: APP_9ROUTER_API_KEY') && read(hubHome, 'config.yaml').includes('cx/gpt-6-luna') && read(hubHome, '.env').includes('APP_9ROUTER_API_KEY=sk-router-secret'));
  const models = await api('GET', '/models');
  check('the app does not give a key back', models.endpoints[0]?.hasKey === true && models.keys.find((k) => k.id === 'openrouter').saved && !JSON.stringify(models).includes('secret'));
  check('an endpoint named like a built-in provider is turned down', !!(await api('POST', '/models/endpoints', { name: 'OpenRouter', baseUrl: 'http://localhost:1/v1' })).error);
  check('so is a key that would add a line to the file', !!(await api('PUT', '/models/keys/openrouter', { key: 'k\nINJECTED=1' })).error && !read(hubHome, '.env').includes('INJECTED'));
  await send('fourth steps=1');
  await until('the fourth job to finish', async () => said(await rows(bot.id), 'Finished 1 steps.'));
  check('a job on another machine carries them', read(laptopHome, '.env').includes('APP_9ROUTER_API_KEY=sk-router-secret') && read(laptopHome, 'config.yaml').includes('key_env: APP_9ROUTER_API_KEY'));
  await api('DELETE', '/models/endpoints/9router');
  check('taking one away leaves the rest', !read(hubHome, '.env').includes('APP_9ROUTER') && !read(hubHome, 'config.yaml').includes('9router') && read(hubHome, '.env').includes('MY_OWN=kept') && read(hubHome, '.env').includes('OPENROUTER_API_KEY=sk-or-secret'));

  // A subscription is signed in to once, in a window on a bot's screen, and handed on to the agents.
  check('only a known subscription can be signed in to', !!(await api('POST', '/models/sign-in', { provider: 'openrouter; reboot' })).error);
  const signedIn = await api('POST', '/models/sign-in', { provider: 'openai-codex' });
  if (signedIn.error) console.log(`skip  a sign-in from Settings (${signedIn.error})`); // this computer has no terminal program
  else {
    await until('the sign-in to reach the agent', async () => JSON.parse(read(hubHome, 'auth.json')).providers['openai-codex']?.token === 'signed-in', 30_000);
    check('a sign-in from Settings reaches a bot’s agent', true);
  }

  // Auto: Jev chooses a model, a size and a machine for each job of a bot that is switched to it.
  const laptop = (await api('GET', '/machines')).machines.find((m) => m.name === 'Laptop');
  await api('POST', '/models/roster', { label: 'Coder', provider: 'openai-codex', model: 'stub-large', note: 'Best at writing code and long research.', machines: [laptop.id, 'mch_gone'] });
  const auto = await api('POST', '/models/roster', { label: 'Quick', provider: 'openrouter', model: 'stub-small', note: 'Fast and cheap, for short answers and mail.' });
  check('a model is listed with the machines it runs on', auto.roster.length === 2 && auto.roster[0].machines.join() === laptop.id && auto.blocked === null);
  check('two models cannot share a name', !!(await api('POST', '/models/roster', { label: 'quick', provider: 'openrouter', model: 'other' })).error);
  await api('PATCH', `/bots/${bot.id}`, { autoModel: true });
  const finished = async () => (await rows(bot.id)).filter((b) => b.type === 'message' && b.text.includes('Finished 1 steps.')).length;
  const run = async (text) => {
    const before = await finished();
    await send(`${text} steps=1`);
    await until(`“${text}” to finish`, async () => (await finished()) > before);
    return (await api('GET', '/models/auto')).recent[0];
  };
  const lastRequest = (home) => JSON.parse(read(home, 'requests.log').trim().split('\n').pop());

  const coded = await run('please write code');
  const asked = jev.asked[0];
  check('Jev is asked through OpenRouter with the key from Settings', asked?.url === '/systemone' && asked.auth === 'Bearer sk-or-secret' && asked.body.model === 'typesafe/jev-1.14' && asked.body.state.includes('please write code') && Object.values(asked.body.questions.model.criteria).some((c) => c.includes('long research')));
  check('a job runs on the model Jev picks', lastRequest(laptopHome).model === 'stub-large' && lastRequest(laptopHome).provider === 'openai-codex' && coded.choice.by === 'jev' && coded.choice.label === 'Coder');
  check('the choice is shown on the job', said(await rows(bot.id), 'Auto chose Coder for this, as a heavy job on Laptop. Jev was 90% sure.'));

  const quick = await run('a small note');
  const onHub = lastRequest(path.join(dir, 'hub-engines', bot.id));
  check('a job Jev calls light goes to the machine it names', onHub.model === 'stub-small' && onHub.provider === 'openrouter' && quick.choice.weight === 'light' && quick.machineName !== 'Laptop');

  const small = await run('write code, a small one');
  check('a model only runs on the machines it is set for', small.machineName === 'Laptop' && small.choice.weight === 'light' && lastRequest(laptopHome).model === 'stub-large');

  const unsure = await run('hard to say');
  check('a choice Jev is unsure of falls back to the bot’s own model', unsure.choice.by === 'bot' && lastRequest(laptopHome).model === 'hermes-agent' && !lastRequest(laptopHome).provider && said(await rows(bot.id), 'Jev was only 20% sure of a model.'));

  jevServer.close();
  jevServer.closeAllConnections();
  const alone = await run('after hours');
  check('a job goes ahead when Jev cannot be reached', alone.choice.by === 'bot' && said(await rows(bot.id), 'Jev could not be asked'));

  // Guessing closes pairing for a while, the open codes included.
  const openCode = await api('POST', '/pair/codes', {});
  const guess = (code) => fetch(`${HUB}/api/pair/sign-in`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ code }) });
  for (let i = 0; i < 10; i++) await guess('ZZZZZZZ' + (i % 8 + 2));
  check('too many wrong codes close pairing', (await guess(openCode.code)).status === 400 && (await api('GET', `/pair/codes/${openCode.id}`)).state === 'expired');
} catch (e) {
  console.error(`FAIL  ${e.message}`);
  failed = true;
} finally {
  for (const c of children) stopGroup(c);
  // The agents were started detached by the hub and the worker: stop them by their pid files.
  for (const engines of [path.join(dir, 'hub-engines'), path.join(dir, 'laptop', 'engines'), path.join(dir, 'desk', 'engines')]) {
    for (const id of fs.existsSync(engines) ? fs.readdirSync(engines) : []) {
      try { process.kill(JSON.parse(fs.readFileSync(path.join(engines, id, 'gateway.pid'), 'utf8')).pid, 'SIGKILL'); } catch { /* not running */ }
    }
  }
  spawnSync('pkill', ['-KILL', '-f', dir]); // what the screens were running
  for (const pid of screens()) if (!screensBefore.has(pid)) { try { process.kill(Number(pid), 'SIGKILL'); } catch { /* gone */ } }
  for (const file of screenFiles()) if (!screenFilesBefore.has(file)) fs.rmSync(file, { force: true });
  jevServer.close();
  jevServer.closeAllConnections();
  if (failed) console.error(`Logs are in ${dir}`);
  else fs.rmSync(dir, { recursive: true, force: true });
}
process.exit(failed ? 1 : 0);
