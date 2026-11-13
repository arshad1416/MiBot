// Approvals answered from more than one device, end to end against a practice hub (scripts/dev-hub.mjs).
//
//   node tests/approvals.mjs
//
// A Bot asks to run code that reaches connected apps, which always needs the person's OK. Two devices
// are signed in: "the Mac" and "the phone". The test checks that both are told, that both can list
// what is pending (as after a reconnect), that an answer from one reaches the other, that a request
// answered from both at once goes ahead exactly once, that a denial stops the call, that a request
// that runs out can no longer be answered, and that a device that was signed out cannot answer.
// Approvals wait 4 seconds here instead of 25 minutes. Needs nothing but `npm ci`; takes about 30 s.
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PORT = 19500;
const HUB = `http://127.0.0.1:${PORT}`;
const SECRET = 'dev-hub-secret-dev-hub-secret'; // the practice hub's, see scripts/dev-hub.mjs
const MAC = 'MiBot-Mac/0.1.0 (Macintosh; macOS 15)';
const PHONE = 'MiBot-Android/0.1.0 (Android 16; CPH2655) Mobile';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let failed = 0;
const check = (what, ok, extra = '') => { console.log(`${ok ? 'ok  ' : 'FAIL'}  ${what}${ok || !extra ? '' : `  (${extra})`}`); if (!ok) failed++; };
async function until(what, test, ms = 15_000) {
  const end = Date.now() + ms;
  for (;;) {
    const v = await test().catch(() => null);
    if (v) return v;
    if (Date.now() > end) throw new Error(`timed out waiting for: ${what}`);
    await sleep(150);
  }
}

const hubProc = spawn(process.execPath, [path.join(repo, 'scripts', 'dev-hub.mjs'), '--port', String(PORT)], { env: { ...process.env, APPROVAL_WAIT_MS: '4000' }, stdio: ['pipe', 'ignore', 'ignore'] });
const streams = [];

/** A signed-in device: its cookie, its calls, and the approval events its live stream has seen. */
function device(cookie, userAgent) {
  const call = async (method, p, body) => {
    const res = await fetch(`${HUB}/api${p}`, { method, headers: { cookie, 'user-agent': userAgent, 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
    return { status: res.status, body: await res.json().catch(() => null) };
  };
  const seen = [];
  const ctrl = new AbortController();
  streams.push(ctrl);
  fetch(`${HUB}/api/events`, { headers: { cookie, 'user-agent': userAgent }, signal: ctrl.signal }).then(async (res) => {
    let buf = '';
    for await (const chunk of res.body) {
      buf += Buffer.from(chunk).toString();
      for (let i; (i = buf.indexOf('\n\n')) >= 0; buf = buf.slice(i + 2)) {
        const data = buf.slice(0, i).split('\n').find((l) => l.startsWith('data: '));
        const ev = data ? JSON.parse(data.slice(6)) : null;
        if (ev?.type === 'approval') seen.push(ev.approval);
      }
    }
  }).catch(() => {});
  const latest = (id) => seen.filter((a) => a.id === id).pop();
  return { call, seen, latest, answer: (id, decision) => call('POST', `/approvals/${id}`, { decision }) };
}

/** The Bot's agent calls a tool that needs an OK, the way Hermes does over MCP. Resolves with the tool's answer. */
async function botAsks(botId, n) {
  const token = crypto.createHmac('sha256', SECRET).update(`mcp:${botId}`).digest('hex');
  const res = await fetch(`${HUB}/mcp/${botId}`, {
    method: 'POST', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', accept: 'application/json, text/event-stream' },
    body: JSON.stringify({ jsonrpc: '2.0', id: n, method: 'tools/call', params: { name: 'COMPOSIO_REMOTE_WORKBENCH', arguments: { code: `run_composio_tool("GMAIL_SEND_EMAIL", {"to": "test${n}@example.com"})` } } }),
  });
  return JSON.stringify(await res.json());
}

try {
  await until('the practice hub', async () => (await fetch(`${HUB}/api/health`)).ok, 60_000);
  const signIn = await fetch(`${HUB}/api/auth/dev`, { method: 'POST', headers: { 'content-type': 'application/json', 'user-agent': MAC }, body: JSON.stringify({ email: 'dev@example.com' }) });
  const mac = device(signIn.headers.get('set-cookie').split(';')[0], MAC);
  const code = (await mac.call('POST', '/pair/codes', {})).body.code;
  const paired = await fetch(`${HUB}/api/pair/sign-in`, { method: 'POST', headers: { 'content-type': 'application/json', 'user-agent': PHONE }, body: JSON.stringify({ code }) });
  const phoneCookie = paired.headers.get('set-cookie').split(';')[0];
  const phone = device(phoneCookie, PHONE);
  const botId = (await mac.call('GET', '/bots')).body.bots.find((b) => b.isPrimary).id;
  await sleep(500); // both live streams open

  // 1. Asked, seen everywhere, approved on the phone, the Mac is told.
  const first = botAsks(botId, 1);
  const pending = await until('the first request', async () => (await mac.call('GET', '/approvals')).body.approvals[0]);
  check('a request is listed for each device, with what it will do', pending.status === 'pending' && pending.detail.includes('GMAIL_SEND_EMAIL') && (await phone.call('GET', '/approvals')).body.approvals[0]?.id === pending.id && pending.expiresAt > Date.now());
  await until('both devices to be told', async () => mac.latest(pending.id) && phone.latest(pending.id));
  check('both devices are told at once', mac.latest(pending.id).status === 'pending' && phone.latest(pending.id).status === 'pending');
  const yes = await phone.answer(pending.id, 'once');
  check('approved once on the phone', yes.status === 200 && yes.body.approval.status === 'allowed' && yes.body.approval.resolvedBy === 'MiBot app on Android (CPH2655)');
  await until('the Mac to hear it', async () => mac.latest(pending.id)?.status === 'allowed');
  check('the Mac hears it was approved, and where', mac.latest(pending.id).resolvedBy.startsWith('MiBot app on Android'));
  const ran = await first;
  check('the call went ahead', !/not allowed|skipped/i.test(ran), ran.slice(0, 120));
  check('it is no longer pending anywhere', (await mac.call('GET', '/approvals')).body.approvals.length === 0);
  const late = await mac.answer(pending.id, 'deny');
  check('the Mac can no longer deny it, and is told why', late.status === 409 && late.body.approval.status === 'allowed' && /already approved on MiBot app on Android/.test(late.body.error));

  // 2. Answered from both at once: one wins, the call happens once.
  const second = botAsks(botId, 2);
  const two = await until('the second request', async () => (await mac.call('GET', '/approvals')).body.approvals[0]);
  const [a, b] = await Promise.all([mac.answer(two.id, 'once'), phone.answer(two.id, 'deny')]);
  const winners = [a, b].filter((r) => r.status === 200);
  check('two answers at once: exactly one counts', winners.length === 1 && [a, b].some((r) => r.status === 409));
  const outcome = winners[0].body.approval.status;
  const said = await second;
  check('and the call follows the one that counted', outcome === 'allowed' ? !/declined|not allowed/i.test(said) : /declin|not allowed|did not allow/i.test(said), `${outcome}: ${said.slice(0, 120)}`);
  await until('both to agree', async () => mac.latest(two.id)?.status === outcome && phone.latest(two.id)?.status === outcome);
  check('both devices end up showing the same answer', true);

  // 3. Denied on the Mac: the call is stopped and the phone is told.
  const third = botAsks(botId, 3);
  const three = await until('the third request', async () => (await phone.call('GET', '/approvals')).body.approvals[0]);
  check('denied on the Mac', (await mac.answer(three.id, 'deny')).status === 200);
  check('the call is stopped', /declined this action/.test(await third));
  await until('the phone to hear it', async () => phone.latest(three.id)?.status === 'denied');
  check('the phone hears it was denied', true);

  // 4. Nobody answers: it runs out, and then cannot be answered.
  const fourth = botAsks(botId, 4);
  const four = await until('the fourth request', async () => (await mac.call('GET', '/approvals')).body.approvals[0]);
  await until('it to run out', async () => phone.latest(four.id)?.status === 'expired', 10_000);
  check('a request that runs out is expired on every device', mac.latest(four.id)?.status === 'expired');
  const tooLate = await phone.answer(four.id, 'once');
  check('and cannot be approved any more', tooLate.status === 409 && tooLate.body.approval.status === 'expired' && /expired/.test(tooLate.body.error));
  check('the call was skipped', /skip|no answer|did not answer|timed out|expired/i.test(await fourth));

  // 5. A device that was away catches up on what was decided meanwhile.
  const since = Date.now() - 60_000;
  const caughtUp = (await phone.call('GET', `/approvals?since=${since}`)).body.approvals;
  check('a device that was away gets what was decided meanwhile', caughtUp.length === 4 && caughtUp.every((x) => x.status !== 'pending'));

  // 6. Odd answers and signed-out devices.
  check('an answer that is not once, always or deny is refused', (await mac.answer(four.id, 'maybe')).status === 400);
  check('an unknown request is not found', (await mac.answer('apr_nope', 'once')).status === 404);
  const fifth = botAsks(botId, 5);
  const five = await until('the fifth request', async () => (await mac.call('GET', '/approvals')).body.approvals[0]);
  const phoneId = (await mac.call('GET', '/machines')).body.devices.find((d) => d.name.startsWith('MiBot app on Android')).id;
  await mac.call('DELETE', `/devices/${phoneId}`);
  check('a signed-out phone cannot answer', (await phone.answer(five.id, 'once')).status === 401 && (await mac.call('GET', '/approvals')).body.approvals[0]?.status === 'pending');
  await mac.answer(five.id, 'deny');
  await fifth;
} catch (e) {
  console.error(`FAIL  ${e.message}`);
  failed++;
} finally {
  for (const s of streams) s.abort();
  hubProc.kill('SIGINT');
  await new Promise((r) => hubProc.once('exit', r));
}
console.log(failed ? `${failed} failed` : 'all ok');
process.exit(failed ? 1 : 0);
