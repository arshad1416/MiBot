import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { readDotenv } from '../gateway.js';

/**
 * Connecting this machine to a hub with a one-time code, so that nobody has to write a settings file.
 *
 * The hub shows the code under Settings › Machines, as eight letters and as a link (the QR code).
 * A worker with no settings serves a small page on this machine only; typing the code there, or
 * running `npm run pair -- <link>`, hands the code to the hub and saves what the hub gives back.
 */

/** A link from the hub (https://hub/pair#CODE) as its two parts, or null when the text is not one. */
export function parsePairLink(text: string): { hub: string; code: string } | null {
  try {
    const u = new URL(text.trim());
    const code = u.hash.replace(/^#/, '');
    return /^https?:$/.test(u.protocol) && code ? { hub: u.origin, code } : null;
  } catch { return null; }
}

/** A hub address as typed, with or without https:// in front, as an origin. */
function hubOrigin(text: string): string {
  const t = text.trim();
  try {
    const u = new URL(/^https?:\/\//i.test(t) ? t : `https://${t}`);
    return u.origin;
  } catch { throw new Error('That hub address does not look right. It is the address you open the app at.'); }
}

/** Keep HUB_URL and WORKER_TOKEN in the settings file, leaving its other lines as they are. */
function saveSettings(file: string, hubUrl: string, token: string) {
  let rest: string[] = [];
  try { rest = fs.readFileSync(file, 'utf8').split('\n').filter((l) => !/^\s*(HUB_URL|WORKER_TOKEN)\s*=/.test(l)); } catch { /* a new file */ }
  while (rest.length && !rest[rest.length - 1].trim()) rest.pop();
  fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
  fs.writeFileSync(file, [`HUB_URL=${hubUrl}`, `WORKER_TOKEN=${token}`, ...rest, ''].join('\n'), { mode: 0o600 });
  fs.chmodSync(file, 0o600);
}

/** Hand the code to the hub and save this machine's settings. Returns the name the hub gave it. */
export async function pairWithHub(file: string, hub: string, code: string): Promise<string> {
  const origin = hubOrigin(hub);
  let r: Response;
  try {
    r = await fetch(`${origin}/api/pair/machine`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code, name: os.hostname().replace(/\.local$/, '') }),
      signal: AbortSignal.timeout(20_000),
    });
  } catch { throw new Error(`Could not reach the hub at ${origin}. Check the address, and that this machine is on the same network.`); }
  const json = (await r.json().catch(() => ({}))) as { token?: string; name?: string; error?: string };
  if (!r.ok || !json.token) throw new Error(json.error ?? `The hub at ${origin} did not accept the code (${r.status}).`);
  saveSettings(file, origin, json.token);
  return json.name ?? 'this machine';
}

const hasSettings = (file: string) => { const v = readDotenv(file); return !!v.HUB_URL && !!v.WORKER_TOKEN; };

const PAGE = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Connect this machine</title>
<style>
  :root { color-scheme: light dark; --bg: #f5f5f4; --card: #fff; --text: #1c1c1c; --dim: #6b6b6b; --line: #d9d9d6; --btn: #1c1c1c; --btn-text: #fff; --bad: #b3261e; }
  @media (prefers-color-scheme: dark) { :root { --bg: #111; --card: #1c1c1c; --text: #f2f2f2; --dim: #a3a3a3; --line: #333; --btn: #f2f2f2; --btn-text: #111; --bad: #ff8a80; } }
  * { box-sizing: border-box; }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center; padding: 16px; background: var(--bg); color: var(--text); font: 15px/22px system-ui, sans-serif; }
  main { width: 100%; max-width: 400px; padding: 28px; border-radius: 20px; background: var(--card); border: 1px solid var(--line); }
  h1 { margin: 0 0 6px; font-size: 20px; line-height: 26px; }
  p { margin: 0 0 16px; color: var(--dim); }
  label { display: block; margin: 0 0 4px; font-size: 13px; color: var(--dim); }
  input { width: 100%; margin: 0 0 14px; padding: 10px 12px; border-radius: 10px; border: 1px solid var(--line); background: transparent; color: inherit; font: inherit; }
  #code { font-size: 20px; letter-spacing: 3px; text-transform: uppercase; }
  button { width: 100%; padding: 11px; border: 0; border-radius: 10px; background: var(--btn); color: var(--btn-text); font: inherit; font-weight: 600; cursor: pointer; }
  button:disabled { opacity: 0.5; cursor: default; }
  #note { margin: 14px 0 0; min-height: 22px; }
  #note.bad { color: var(--bad); }
</style></head><body><main>
  <h1 id="title">Connect this machine</h1>
  <p id="lead">In the app, open Settings › Machines and choose Add. Type the code it shows here.</p>
  <form id="form">
    <label for="code">Code</label>
    <input id="code" autocomplete="off" autocapitalize="characters" spellcheck="false" placeholder="ABCD-EFGH" required>
    <label for="hub">Hub address</label>
    <input id="hub" autocomplete="off" spellcheck="false" placeholder="name.tailnet.ts.net" required>
    <button id="go">Connect</button>
  </form>
  <p id="note" role="status"></p>
</main><script>
  const $ = (id) => document.getElementById(id);
  // The app can send someone here with both fields filled in. Nothing is sent until Connect is pressed.
  const given = new URLSearchParams(location.hash.slice(1));
  if (given.get('code')) $('code').value = given.get('code');
  if (given.get('hub')) { $('hub').value = given.get('hub'); $('lead').textContent = 'Check that the hub address below is yours, then connect.'; }
  history.replaceState(null, '', location.pathname);
  $('form').addEventListener('submit', async (e) => {
    e.preventDefault();
    $('go').disabled = true; $('note').className = ''; $('note').textContent = 'Connecting…';
    try {
      const r = await fetch('/pair', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ hub: $('hub').value, code: $('code').value }) });
      const j = await r.json();
      if (!r.ok) throw new Error(j.error || 'Could not connect.');
      $('form').remove(); $('note').remove();
      $('title').textContent = 'This machine is connected';
      $('lead').textContent = 'It is in the app under Settings › Machines as ' + j.name + '. This page can be closed.';
    } catch (err) { $('note').className = 'bad'; $('note').textContent = err.message; $('go').disabled = false; }
  });
</script></body></html>`;

/**
 * Wait until this machine has its settings: serve the page that takes the code, and watch the file
 * in case the code is handed in from the command line meanwhile.
 */
export function waitForPairing(file: string, port: number): Promise<void> {
  return new Promise((resolve) => {
    const own = new Set([`127.0.0.1:${port}`, `localhost:${port}`]);
    const server = http.createServer(async (req, res) => {
      const send = (status: number, type: string, body: string) => {
        // No other page may frame this one or read its answers.
        res.writeHead(status, { 'content-type': type, 'cache-control': 'no-store', 'x-frame-options': 'DENY', 'content-security-policy': "frame-ancestors 'none'" });
        res.end(body);
      };
      // Only this machine's own browser, on this page: another site must not be able to point the worker at a hub.
      if (!own.has(req.headers.host ?? '')) return send(403, 'text/plain', 'Open this page as http://127.0.0.1:' + port);
      if (req.method === 'GET' && req.url?.split('?')[0] === '/') return send(200, 'text/html; charset=utf-8', PAGE);
      if (req.method === 'POST' && req.url === '/pair') {
        const origin = req.headers.origin ?? '';
        if (!own.has(origin.replace(/^http:\/\//, '')) || !/^application\/json\b/.test(req.headers['content-type'] ?? '')) return send(403, 'application/json', '{"error":"Not allowed from here."}');
        let raw = '';
        for await (const chunk of req) { raw += chunk; if (raw.length > 4096) return send(413, 'application/json', '{"error":"Too long."}'); }
        try {
          const p = JSON.parse(raw) as { hub?: string; code?: string };
          if (!p.hub?.trim() || !p.code?.trim()) throw new Error('Type the code and the hub address.');
          const name = await pairWithHub(file, p.hub, p.code);
          send(200, 'application/json', JSON.stringify({ name }));
        } catch (e: any) { send(400, 'application/json', JSON.stringify({ error: e?.message ?? 'Could not connect.' })); }
        return;
      }
      send(404, 'text/plain', 'Not found');
    });
    server.on('error', (e) => console.warn(`[worker] the setup page could not start on port ${port} (${e.message}). Connect with: npm run pair -- '<link from the app>'`));
    server.listen(port, '127.0.0.1', () => {
      console.log(`[worker] This machine is not connected to a hub yet. In the app, open Settings › Machines and choose Add, then either`);
      console.log(`[worker]   open http://127.0.0.1:${port} on this machine and type the code, or`);
      console.log(`[worker]   run: npm run pair -- '<link from the app>'`);
    });
    const watch = setInterval(() => {
      if (!hasSettings(file)) return;
      clearInterval(watch);
      server.close();
      server.closeAllConnections();
      resolve();
    }, 1000);
  });
}
