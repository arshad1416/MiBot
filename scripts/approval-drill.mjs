// Drives approvals on a practice hub (scripts/dev-hub.mjs) so the Mac and Android apps can be tried by hand.
//
//   node scripts/dev-hub.mjs --url http://10.0.2.2:18891     (start it with APPROVAL_WAIT_MS=60000 for one-minute expiry)
//   node scripts/approval-drill.mjs code         a one-time code to sign an app in with
//   node scripts/approval-drill.mjs ask [label]  a Bot asks for an OK; prints the request, then how it ended
//   node scripts/approval-drill.mjs list         every request from the last hour, and who answered it
//   node scripts/approval-drill.mjs answer ID once|deny   answer as a third device (for simultaneous answers)
//
// Options: --hub http://127.0.0.1:18891 (default). Nothing here touches a real hub: it signs in with the
// practice hub's dev login and its fixed secret.
import crypto from 'node:crypto';

const arg = (name, fallback) => { const i = process.argv.indexOf(`--${name}`); return i > 0 ? process.argv[i + 1] : fallback; };
const HUB = arg('hub', 'http://127.0.0.1:18891');
const SECRET = 'dev-hub-secret-dev-hub-secret'; // the practice hub's, see scripts/dev-hub.mjs
const [cmd, ...rest] = process.argv.slice(2).filter((a, i, all) => !a.startsWith('--') && !(all[i - 1] ?? '').startsWith('--'));

async function signIn() {
  const res = await fetch(`${HUB}/api/auth/dev`, { method: 'POST', headers: { 'content-type': 'application/json', 'user-agent': 'approval-drill' }, body: JSON.stringify({ email: 'dev@example.com' }) });
  if (!res.ok) throw new Error(`dev sign-in failed (${res.status}); is this a practice hub?`);
  const cookie = res.headers.get('set-cookie').split(';')[0];
  return async (method, p, body) => {
    const r = await fetch(`${HUB}/api${p}`, { method, headers: { cookie, 'user-agent': 'approval-drill', 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
    return { status: r.status, body: await r.json().catch(() => null) };
  };
}

const call = await signIn();
const show = (a) => `${a.id}  ${a.status.padEnd(8)} ${a.resolvedBy ? `by ${a.resolvedBy}` : ''}  ${a.title}  (expires ${new Date(a.expiresAt).toLocaleTimeString()})`;

if (cmd === 'code') {
  console.log((await call('POST', '/pair/codes', {})).body.code);
} else if (cmd === 'list') {
  const { approvals } = (await call('GET', `/approvals?since=${Date.now() - 3600_000}`)).body;
  for (const a of approvals) console.log(show(a));
  if (!approvals.length) console.log('(none)');
} else if (cmd === 'answer') {
  const r = await call('POST', `/approvals/${rest[0]}`, { decision: rest[1] ?? 'once' });
  console.log(r.status, r.body?.error ?? '', r.body?.approval ? show(r.body.approval) : '');
} else if (cmd === 'ask') {
  const botId = (await call('GET', '/bots')).body.bots.find((b) => b.isPrimary).id;
  const label = rest[0] ?? `drill-${Date.now() % 100000}`;
  const before = new Set((await call('GET', '/approvals')).body.approvals.map((a) => a.id));
  const token = crypto.createHmac('sha256', SECRET).update(`mcp:${botId}`).digest('hex');
  // The way a Bot's agent asks over MCP: this returns only once the request is answered or runs out.
  const asked = fetch(`${HUB}/mcp/${botId}`, {
    method: 'POST', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', accept: 'application/json, text/event-stream' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'COMPOSIO_REMOTE_WORKBENCH', arguments: { code: `run_composio_tool("GMAIL_SEND_EMAIL", {"to": "${label}@example.com", "subject": "${label}"})` } } }),
  }).then((r) => r.text());
  let mine;
  for (let i = 0; i < 100 && !mine; i++) {
    mine = (await call('GET', '/approvals')).body.approvals.find((a) => !before.has(a.id));
    if (!mine) await new Promise((r) => setTimeout(r, 100));
  }
  console.log(mine ? `asked  ${show(mine)}` : 'asked, but no request showed up');
  const said = await asked;
  const end = mine && (await call('GET', `/approvals?since=${Date.now() - 3600_000}`)).body.approvals.find((a) => a.id === mine.id);
  console.log(`ended  ${end ? show(end) : '?'}`);
  console.log(`the Bot was told: ${said.replace(/\s+/g, ' ').slice(0, 200)}`);
} else {
  console.log('usage: node scripts/approval-drill.mjs code | ask [label] | list | answer ID once|deny');
  process.exit(1);
}
