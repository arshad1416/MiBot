import { env } from '../env.js';
import { localProvider } from './local.js';
import { orgoProvider } from './orgo.js';
import type { ComputerProvider } from './types.js';

export type { BashResult, ComputerProvider, ProviderScreen, ProviderStatus, VncEndpoint } from './types.js';

const PROVIDERS: Record<string, ComputerProvider> = { orgo: orgoProvider, local: localProvider };

function pick(): ComputerProvider {
  const p = PROVIDERS[env.computerProvider];
  if (!p) throw new Error(`COMPUTER_PROVIDER=${env.computerProvider} is not known. Use one of: ${Object.keys(PROVIDERS).join(', ')}.`);
  return p;
}

/** The computer every bot works on, as chosen by COMPUTER_PROVIDER. */
export const provider = pick();
