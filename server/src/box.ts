import { execFile } from 'node:child_process';
import fs from 'node:fs/promises';
import path from 'node:path';
import { env } from './env.js';
import { provider, type BashResult } from './providers/index.js';

/**
 * The box: the one computer every bot works on. When this server runs on that computer (the local
 * provider, or HQ_LOCAL=1 on an Orgo computer) commands and files go straight to the local shell
 * and disk; otherwise they go through the provider's API.
 */

export type { BashResult };

/** Screen id -> X display. The default screen is :99, "screen-100" is :100. */
export const displayFor = (screenId?: string | null) => (!screenId || screenId === 'default' ? ':99' : `:${screenId.replace('screen-', '')}`);

/** Only what a desktop command needs; none of this server's secrets. */
function shellEnv(screenId?: string | null): NodeJS.ProcessEnv {
  return {
    PATH: process.env.PATH ?? '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
    HOME: process.env.HOME ?? '/root',
    USER: process.env.USER ?? 'root',
    LANG: process.env.LANG ?? 'C.UTF-8',
    DISPLAY: displayFor(screenId),
    HQ_ROOT: env.boxRoot,
    HQ_DATA: env.boxData,
    HQ_LOGS: env.boxLogs,
  };
}

function localBash(command: string, timeoutSec: number, screenId?: string | null): Promise<BashResult> {
  return new Promise((resolve) => {
    execFile('bash', ['-c', command], { timeout: timeoutSec * 1000, maxBuffer: 32 * 1024 * 1024, env: shellEnv(screenId) }, (err, stdout, stderr) => {
      const code = err ? (typeof (err as any).code === 'number' ? (err as any).code : 1) : 0;
      resolve({ output: `${stdout ?? ''}${stderr ?? ''}`, exit_code: code });
    });
  });
}

/** A path safe to put inside single quotes in a shell command. */
const q = (s: string) => `'${s.replace(/'/g, `'\\''`)}'`;

export const box = {
  local: provider.local,

  /** Run a bash command on the box. `screenId` picks which screen's DISPLAY it sees. */
  async bash(command: string, timeoutSec = 60, screenId?: string | null): Promise<BashResult> {
    if (provider.local) return localBash(command, timeoutSec, screenId);
    const vars = `DISPLAY=${displayFor(screenId)} HQ_ROOT=${q(env.boxRoot)} HQ_DATA=${q(env.boxData)} HQ_LOGS=${q(env.boxLogs)}`;
    return provider.bash(`export ${vars}; ${command}`, timeoutSec, screenId);
  },

  async writeFile(file: string, content: string | Buffer, mode = '0644'): Promise<void> {
    const buf = typeof content === 'string' ? Buffer.from(content, 'utf8') : content;
    if (provider.local) {
      await fs.mkdir(path.dirname(file), { recursive: true });
      await fs.writeFile(file, buf);
      await fs.chmod(file, parseInt(mode, 8));
      return;
    }
    // A remote API drops very long commands, so the file goes over in base64 pieces.
    const b64 = buf.toString('base64');
    const tmp = `${file}.b64`;
    const first = await box.bash(`mkdir -p ${q(path.dirname(file))} && : > ${q(tmp)} && echo ok`, 30);
    if (!first.output.includes('ok')) throw new Error(`could not write ${file}: ${first.output.slice(0, 200)}`);
    for (let i = 0; i < b64.length; i += 60000) await box.bash(`printf '%s' '${b64.slice(i, i + 60000)}' >> ${q(tmp)}`, 30);
    const done = await box.bash(`base64 -d ${q(tmp)} > ${q(file)} && rm -f ${q(tmp)} && chmod ${mode} ${q(file)} && echo ok`, 30);
    if (!done.output.includes('ok')) throw new Error(`could not write ${file}: ${done.output.slice(0, 200)}`);
  },

  /** Read a file from the box, or null if it isn't there. Meant for small files (under ~4 MB). */
  async readFile(file: string): Promise<Buffer | null> {
    if (provider.local) {
      try { return await fs.readFile(file); } catch { return null; }
    }
    const r = await box.bash(`[ -f ${q(file)} ] && base64 -w0 ${q(file)} || echo __missing__`, 60);
    const out = r.output.trim();
    if (!out || out.endsWith('__missing__')) return null;
    return Buffer.from(out, 'base64');
  },

  quote: q,
};
