/**
 * A computer provider: where the bots' screens live. Orgo (a cloud computer) is one; the local
 * provider (this machine's own X displays) is another. Everything above this file talks to the
 * provider chosen by COMPUTER_PROVIDER and never to a vendor API directly, so adding another
 * hosted-desktop service means adding one file here.
 */

export type ProviderStatus = 'running' | 'creating' | 'starting' | 'suspending' | 'suspended' | 'resuming' | 'stopped' | 'error';

export interface ProviderScreen { id: string; display: string; width: number; height: number; default: boolean }

export interface BashResult { output: string; exit_code: number }

/** Where the VNC relay finds a screen's RFB stream. An empty password means no VNC authentication. */
export type VncEndpoint =
  | { kind: 'ws'; url: string; password: string }
  | { kind: 'tcp'; host: string; port: number; password: string };

export interface ComputerProvider {
  readonly name: string;
  /** How many screens one computer can hold, the default one included. */
  readonly maxScreens: number;
  /** True when this server runs on the computer itself, so shell commands and files are local. */
  readonly local: boolean;

  /** The computer's id, or null when none is configured. */
  id(): string | null;
  /** Why id() is null, in words for the person setting the app up. */
  readonly missingMessage: string;

  status(): Promise<ProviderStatus>;
  /** Bring the computer up if it sleeps. `report` is told each phase it passes through. */
  wake(report: (status: ProviderStatus) => void): Promise<void>;

  listScreens(): Promise<ProviderScreen[]>;
  createScreen(): Promise<ProviderScreen>;
  /** Throws when the screen no longer exists. */
  getScreen(screenId: string): Promise<ProviderScreen>;

  screenshot(screenId: string): Promise<{ data: Buffer; mime: string }>;
  /** Run a command on the computer through the provider. Only called when `local` is false. */
  bash(command: string, timeoutSec: number, screenId?: string | null): Promise<BashResult>;
  vnc(screenId: string): Promise<VncEndpoint>;
  /** A link the browser can download a file on the computer from, or null when there is none. */
  exportFile(path: string): Promise<string | null>;

  /** A message for the person when an error from this provider has a known cause. */
  explain(e: unknown): string;
}
