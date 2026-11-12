import type { Device } from '../types.js';

/** The browser a user agent names, for telling two computers apart. Order matters: Edge and Chrome both say Safari. */
function browserName(userAgent: string): string | null {
  if (/Edg\//.test(userAgent)) return 'Edge';
  if (/Firefox\//.test(userAgent)) return 'Firefox';
  if (/Chrome\//.test(userAgent)) return 'Chrome';
  if (/Safari\//.test(userAgent)) return 'Safari';
  return null;
}

/**
 * What kind of device a user agent is: `phrase` for a sentence ("Signed in on an iPhone."),
 * `name` for a list. Nothing here identifies a device for sure; it is what the device said.
 */
export function describeDevice(userAgent: string): Pick<Device, 'name' | 'kind'> & { phrase: string } {
  const app = /^MiBot-Android\/\S+ \([^;]*; ([^)]{1,40})\)/.exec(userAgent);
  if (/^MiBot-Android/.test(userAgent)) return { phrase: 'the MiBot app on an Android phone', name: `MiBot app on Android${app ? ` (${app[1].trim()})` : ''}`, kind: 'phone' };
  const browser = browserName(userAgent);
  const named = (phrase: string, name: string, kind: Device['kind']) => ({ phrase, name: browser ? `${name} · ${browser}` : name, kind });
  if (/iPhone/.test(userAgent)) return named('an iPhone', 'iPhone', 'phone');
  if (/iPad/.test(userAgent)) return named('an iPad', 'iPad', 'tablet');
  if (/Android/.test(userAgent)) return /Mobile/.test(userAgent) ? named('an Android phone', 'Android phone', 'phone') : named('an Android tablet', 'Android tablet', 'tablet');
  if (/Macintosh/.test(userAgent)) return named('a Mac', 'Mac', 'computer');
  if (/Windows/.test(userAgent)) return named('a Windows PC', 'Windows PC', 'computer');
  if (/Linux|X11/.test(userAgent)) return named('a Linux computer', 'Linux computer', 'computer');
  return { phrase: 'another browser', name: browser ?? 'A browser', kind: 'computer' };
}
