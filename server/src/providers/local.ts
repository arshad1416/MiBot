import { execFile, spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import { env } from '../env.js';
import type { ComputerProvider, ProviderScreen } from './types.js';

/**
 * The local box: the machine this server runs on (a Mac Mini, a Raspberry Pi, any Linux box) is the
 * bots' computer. A screen is a virtual X display (Xvfb) that we start on demand; the default
 * screen is :99, the others :100 and up, the same numbering Orgo uses so the rest of the app does
 * not care which provider it has. Watching a screen goes through an x11vnc bound to localhost.
 *
 * Needs on the machine: Xvfb, x11vnc and ImageMagick's `import`.
 */

const DEFAULT_DISPLAY = 99;
const VNC_PORT_BASE = 15900;
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

const displayOf = (screenId: string) => (screenId === 'default' ? DEFAULT_DISPLAY : Number(screenId.replace('screen-', '')));
const idOf = (display: number) => (display === DEFAULT_DISPLAY ? 'default' : `screen-${display}`);
const socketOf = (display: number) => `/tmp/.X11-unix/X${display}`;
const isUp = (display: number) => fs.existsSync(socketOf(display));

function size(): { width: number; height: number } {
  const m = /^(\d{3,5})x(\d{3,5})$/.exec(env.localScreenSize);
  return m ? { width: Number(m[1]), height: Number(m[2]) } : { width: 1440, height: 900 };
}

const screenOf = (display: number): ProviderScreen => ({ id: idOf(display), display: `:${display}`, ...size(), default: display === DEFAULT_DISPLAY });

/** Start a long-lived helper that outlives this server (a restart must not blank the bots' screens). */
function launch(bin: string, args: string[]) {
  const child = spawn(bin, args, { detached: true, stdio: 'ignore' });
  child.once('error', (e) => console.warn(`[local] could not run ${bin}: ${e.message}`));
  child.unref();
}

async function startDisplay(display: number): Promise<void> {
  if (isUp(display)) return;
  const { width, height } = size();
  launch('Xvfb', [`:${display}`, '-screen', '0', `${width}x${height}x24`, '-nolisten', 'tcp']);
  for (let i = 0; i < 50; i++) {
    if (isUp(display)) return;
    await sleep(100);
  }
  throw new Error(`Could not start a screen on display :${display}. Is Xvfb installed on this machine?`);
}

const portOpen = (port: number) =>
  new Promise<boolean>((resolve) => {
    const s = net.connect({ host: '127.0.0.1', port });
    const done = (ok: boolean) => { s.destroy(); resolve(ok); };
    s.setTimeout(500, () => done(false));
    s.once('connect', () => done(true));
    s.once('error', () => done(false));
  });

async function startVnc(display: number): Promise<number> {
  const port = VNC_PORT_BASE + display;
  if (await portOpen(port)) return port;
  // No password: it listens on localhost only, and the app's own sign-in guards the relay.
  launch('x11vnc', ['-display', `:${display}`, '-rfbport', String(port), '-localhost', '-forever', '-shared', '-nopw', '-quiet']);
  for (let i = 0; i < 50; i++) {
    if (await portOpen(port)) return port;
    await sleep(100);
  }
  throw new Error(`Could not start the screen viewer for display :${display}. Is x11vnc installed on this machine?`);
}

export const localProvider: ComputerProvider = {
  name: 'local',
  get maxScreens() { return env.maxBots; },
  local: true,
  missingMessage: '',

  id: () => 'local',

  status: async () => 'running',

  async wake(report) {
    await startDisplay(DEFAULT_DISPLAY);
    report('running');
  },

  async listScreens() {
    const out: ProviderScreen[] = [];
    for (let d = DEFAULT_DISPLAY; d < DEFAULT_DISPLAY + this.maxScreens; d++) if (isUp(d)) out.push(screenOf(d));
    return out;
  },

  async createScreen() {
    for (let d = DEFAULT_DISPLAY + 1; d < DEFAULT_DISPLAY + this.maxScreens; d++) {
      if (isUp(d)) continue;
      await startDisplay(d);
      return screenOf(d);
    }
    throw new Error('Every screen on this machine is in use.');
  },

  async getScreen(screenId) {
    const d = displayOf(screenId);
    if (!isUp(d)) throw new Error(`Screen ${screenId} is gone`);
    return screenOf(d);
  },

  screenshot: (screenId) =>
    new Promise((resolve, reject) => {
      const args = ['-display', `:${displayOf(screenId)}`, '-window', 'root', '-quality', '75', 'jpeg:-'];
      execFile('import', args, { encoding: 'buffer', timeout: 15_000, maxBuffer: 32 * 1024 * 1024 }, (err, stdout) =>
        err ? reject(err) : resolve({ data: stdout, mime: 'image/jpeg' }),
      );
    }),

  bash: async () => { throw new Error('The local box runs commands directly'); },

  async vnc(screenId) {
    const d = displayOf(screenId);
    await startDisplay(d);
    return { kind: 'tcp', host: '127.0.0.1', port: await startVnc(d), password: '' };
  },

  exportFile: async () => null,

  explain: (e) => String((e as any)?.message ?? e),
};
