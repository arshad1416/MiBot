import { env } from '../env.js';
import { orgo, OrgoError } from '../orgo.js';
import type { ComputerProvider, ProviderStatus } from './types.js';

function mapStatus(s: string): ProviderStatus {
  if (s === 'running' || s === 'creating' || s === 'suspended' || s === 'suspending' || s === 'resuming' || s === 'error') return s;
  if (s === 'frozen' || s === 'stopped') return 'stopped';
  return 'starting';
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function id(): string {
  if (!env.orgoComputerId) throw new Error(orgoProvider.missingMessage);
  return env.orgoComputerId;
}

async function waitRunning(timeoutMs = 240_000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const c = await orgo.getComputer(id());
    if (c.status === 'running' && !c.asleep) return;
    await sleep(3000);
  }
  throw new Error('Timed out waiting for the computer to start');
}

/** One Orgo cloud computer (ORGO_COMPUTER_ID). It is never created, suspended or deleted from here. */
export const orgoProvider: ComputerProvider = {
  name: 'orgo',
  /** Orgo allows this many screens on one Linux computer. */
  maxScreens: 4,
  local: env.localBox,
  missingMessage: 'ORGO_COMPUTER_ID is not set, so there is no computer for the bots to use.',

  id: () => env.orgoComputerId || null,

  async status() {
    const c = await orgo.getComputer(id());
    return mapStatus(c.asleep ? 'suspended' : c.status);
  },

  async wake(report) {
    const c = await orgo.getComputer(id());
    if (c.status === 'running' && !c.asleep) return report('running');
    if (c.status === 'suspended' || c.status === 'suspending' || c.asleep) {
      console.log(`[computer] waking ${id()} (was ${c.status})`);
      report('resuming');
      for (let i = 0; i < 40 && (await orgo.getComputer(id())).status === 'suspending'; i++) await sleep(3000);
      await orgo.resume(id());
    } else if (c.status === 'frozen' || c.status === 'stopped' || c.status === 'error') {
      report('starting');
      await orgo.start(id());
    } else {
      report(mapStatus(c.status));
    }
    await waitRunning();
    report('running');
  },

  listScreens: () => orgo.listScreens(id()),
  createScreen: () => orgo.createScreen(id()),
  getScreen: (screenId) => orgo.getScreen(id(), screenId),

  async screenshot(screenId) {
    const s = await orgo.screenshot(id(), screenId);
    return { data: Buffer.from(s.image.replace(/^data:[^,]+,/, ''), 'base64'), mime: s.mime_type ?? 'image/jpeg' };
  },

  async bash(command, timeoutSec, screenId) {
    const r = await orgo.bash(id(), screenId ?? undefined, command, timeoutSec);
    return { output: r.output ?? '', exit_code: r.exit_code ?? (r.success ? 0 : 1) };
  },

  async vnc(screenId) {
    const { password } = await orgo.vncPassword(id());
    const screen = screenId !== 'default' ? `&screen=${encodeURIComponent(screenId)}` : '';
    return { kind: 'ws', url: `wss://www.orgo.ai/desktops/${id()}/ws/websockify?token=${encodeURIComponent(password)}${screen}`, password };
  },

  async exportFile(path) {
    const r: any = await orgo.exportFile(id(), path);
    return r?.url ?? r?.file?.url ?? r?.download_url ?? null;
  },

  explain: (e) => (e instanceof OrgoError && e.status === 404 ? 'The computer set in ORGO_COMPUTER_ID was not found with this Orgo key.' : String((e as any)?.message ?? e)),
};
