// A stand-in for `hermes gateway run`, for tests and for trying an app without a real agent.
//
// It answers the calls the hub makes to an agent. A job is a run of "tool calls" and a one-line
// reply: the last "steps=N delay=M" in what was asked sets how many steps and how long each takes
// (2 steps of 100 ms when there is none). `hermes auth add <provider>` signs its home in at once.
// While the file named by STUB_SLOW_START exists, a gateway that is starting does not answer yet.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const home = process.env.HERMES_HOME;
if (process.argv[2] === 'auth') {
  fs.writeFileSync(path.join(home, 'auth.json'), JSON.stringify({ version: 1, providers: { [process.argv[4]]: { token: 'signed-in' } } }));
  process.exit(0);
}
const env = Object.fromEntries(fs.readFileSync(path.join(home, '.env'), 'utf8').split('\n').filter(Boolean).map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]));
fs.writeFileSync(path.join(home, 'gateway.pid'), JSON.stringify({ pid: process.pid, hermes_home: home }));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
while (process.env.STUB_SLOW_START && fs.existsSync(process.env.STUB_SLOW_START)) await sleep(200);

http.createServer(async (req, res) => {
  if (req.url === '/health') return res.end(JSON.stringify({ status: 'ok', version: 'stub' }));
  if (req.headers.authorization !== 'Bearer ' + env.API_SERVER_KEY) { res.statusCode = 401; return res.end('{}'); }
  if (req.url === '/api/model/options') return res.end(JSON.stringify({ provider: 'openai-codex', model: 'stub-large', providers: [{ slug: 'openai-codex', name: 'ChatGPT', auth_type: 'oauth', authenticated: true, featured_models: ['stub-large', 'stub-small'] }] }));
  if (req.url !== '/v1/responses') return res.end('{"data":[]}');
  let raw = '';
  for await (const c of req) raw += c;
  const body = JSON.parse(raw);
  fs.appendFileSync(path.join(home, 'requests.log'), JSON.stringify({ input: body.input, model: body.model, provider: body.provider }) + '\n');
  const last = (re) => [...body.input.matchAll(re)].pop()?.[1];
  const steps = Number(last(/steps=(\d+)/g) ?? 2);
  const delay = Number(last(/delay=(\d+)/g) ?? 100);
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  const send = (type, data) => res.write('event: ' + type + '\ndata: ' + JSON.stringify({ type, ...data }) + '\n\n');
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
