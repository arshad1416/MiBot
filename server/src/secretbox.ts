import crypto from 'node:crypto';
import { env } from './env.js';

/**
 * Secrets kept in the database are encrypted with a key made from SESSION_SECRET and a name for
 * what is being kept, so one kind of secret can never be opened as another.
 */
export function secretBox<T>(purpose: string, empty: () => T) {
  const key = crypto.createHash('sha256').update(`${purpose}:${env.sessionSecret}`).digest();
  return {
    seal(value: T): string {
      const iv = crypto.randomBytes(12);
      const c = crypto.createCipheriv('aes-256-gcm', key, iv);
      const body = Buffer.concat([c.update(JSON.stringify(value), 'utf8'), c.final()]);
      return Buffer.concat([iv, c.getAuthTag(), body]).toString('base64');
    },
    /** `empty()` when there is nothing, or when SESSION_SECRET changed since it was sealed. */
    open(sealed: string | null): T {
      if (!sealed) return empty();
      try {
        const raw = Buffer.from(sealed, 'base64');
        const d = crypto.createDecipheriv('aes-256-gcm', key, raw.subarray(0, 12));
        d.setAuthTag(raw.subarray(12, 28));
        return JSON.parse(Buffer.concat([d.update(raw.subarray(28)), d.final()]).toString('utf8'));
      } catch {
        return empty();
      }
    },
  };
}
