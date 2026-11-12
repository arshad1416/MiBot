import { db, now } from './db.js';
import type { ModelAccess } from './gateway.js';
import { secretBox } from './secretbox.js';
import type { ModelEndpoint } from './types.js';

/**
 * Model providers a person adds in Settings: API keys for providers Hermes knows, and
 * OpenAI-compatible endpoints of their own (9Router, OmniRoute, LiteLLM, Ollama and the like).
 * Added once, they reach the agent of every Bot the person has, on this machine and on the others
 * (engine.ts lays them into each Hermes home; a job sent to another machine carries them).
 */

db.exec(`
CREATE TABLE IF NOT EXISTS model_access (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  slug TEXT NOT NULL,
  kind TEXT NOT NULL,
  name TEXT NOT NULL,
  base_url TEXT,
  models TEXT,
  secret TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, slug)
);
`);

/** Providers Hermes reaches with an API key, and the variable it reads each key from. */
export const KEY_PROVIDERS: { id: string; name: string; env: string }[] = [
  { id: 'openrouter', name: 'OpenRouter', env: 'OPENROUTER_API_KEY' },
  { id: 'anthropic', name: 'Claude (Anthropic API)', env: 'ANTHROPIC_API_KEY' },
  { id: 'openai-api', name: 'OpenAI API', env: 'OPENAI_API_KEY' },
  { id: 'xai', name: 'Grok (xAI API)', env: 'XAI_API_KEY' },
  { id: 'gemini', name: 'Google Gemini', env: 'GOOGLE_API_KEY' },
  { id: 'deepseek', name: 'DeepSeek', env: 'DEEPSEEK_API_KEY' },
  { id: 'fireworks', name: 'Fireworks AI', env: 'FIREWORKS_API_KEY' },
  { id: 'huggingface', name: 'Hugging Face', env: 'HF_TOKEN' },
  { id: 'nvidia', name: 'NVIDIA Build', env: 'NVIDIA_API_KEY' },
  { id: 'zai', name: 'z.ai (GLM)', env: 'GLM_API_KEY' },
  { id: 'kimi-coding', name: 'Kimi (Moonshot)', env: 'KIMI_API_KEY' },
  { id: 'minimax', name: 'MiniMax', env: 'MINIMAX_API_KEY' },
  { id: 'ollama-cloud', name: 'Ollama Cloud', env: 'OLLAMA_API_KEY' },
];

/** Subscriptions Hermes signs in to itself (`hermes auth add <id>`). */
export const SUBSCRIPTIONS: { id: string; name: string; note: string; args?: string }[] = [
  { id: 'openai-codex', name: 'ChatGPT (OpenAI)', note: 'A ChatGPT plan that includes Codex.' },
  // Without the flag Hermes asks for an API key.
  { id: 'anthropic', name: 'Claude', note: 'Needs a Claude Max plan with extra usage credits bought. Pro does not work.', args: '--type oauth' },
  { id: 'xai-oauth', name: 'Grok (xAI)', note: 'SuperGrok or X Premium+.' },
  { id: 'nous', name: 'Nous Portal', note: 'A Nous Portal account.' },
];

const box = secretBox<{ key?: string }>('model-access', () => ({}));
const rows = (userId: string) => db.prepare('SELECT * FROM model_access WHERE user_id=? ORDER BY created_at ASC').all(userId) as any[];
const row = (userId: string, slug: string) => db.prepare('SELECT * FROM model_access WHERE user_id=? AND slug=?').get(userId, slug) as any;

/** The variable an endpoint's key is kept in, in a Hermes home. */
const endpointEnv = (slug: string) => `APP_${slug.toUpperCase().replace(/[^A-Z0-9]/g, '_')}_API_KEY`;

/** A key is one line of printable characters; anything else would break the file it is written to. */
const cleanKey = (v: unknown): string | null => (typeof v === 'string' && /^[\x21-\x7e]{4,600}$/.test(v.trim()) ? v.trim() : null);

function upsert(userId: string, slug: string, kind: 'key' | 'endpoint', name: string, baseUrl: string | null, models: string[] | null, key: string | null) {
  const secret = key ? box.seal({ key }) : null;
  db.prepare(`INSERT INTO model_access (user_id, slug, kind, name, base_url, models, secret, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)
    ON CONFLICT(user_id, slug) DO UPDATE SET kind=excluded.kind, name=excluded.name, base_url=excluded.base_url, models=excluded.models, secret=excluded.secret, updated_at=excluded.updated_at`)
    .run(userId, slug, kind, name, baseUrl, models ? JSON.stringify(models) : null, secret, now(), now());
}

// ---------- API keys ----------

/** The ids of the providers this person has saved a key for. */
export const savedKeys = (userId: string): string[] => rows(userId).filter((r) => r.kind === 'key').map((r) => r.slug);

export function saveKey(userId: string, providerId: string, key: unknown) {
  const p = KEY_PROVIDERS.find((k) => k.id === providerId);
  if (!p) throw new Error('That provider is not one a key can be added for.');
  const clean = cleanKey(key);
  if (!clean) throw new Error('That does not look like an API key.');
  upsert(userId, p.id, 'key', p.name, null, null, clean);
}

// ---------- endpoints ----------

const toEndpoint = (r: any): ModelEndpoint => ({ id: r.slug, name: r.name, baseUrl: r.base_url, models: r.models ? JSON.parse(r.models) : [], hasKey: !!r.secret });

export const listEndpoints = (userId: string): ModelEndpoint[] => rows(userId).filter((r) => r.kind === 'endpoint').map(toEndpoint);

/** Add an endpoint, or change the one with this `id`. An empty key on a change keeps the key it has. */
export function saveEndpoint(userId: string, p: { id?: unknown; name?: unknown; baseUrl?: unknown; key?: unknown; models?: unknown }): ModelEndpoint {
  const name = typeof p.name === 'string' ? p.name.trim().slice(0, 60) : '';
  const existing = typeof p.id === 'string' ? row(userId, p.id) : null;
  if (p.id !== undefined && existing?.kind !== 'endpoint') throw new Error('That endpoint is gone.');
  const slug = existing?.slug ?? name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40);
  if (!name || !slug) throw new Error('Give the endpoint a name.');
  // Hermes would take a built-in provider's name to mean that provider.
  if (!existing && (KEY_PROVIDERS.some((k) => k.id === slug) || SUBSCRIPTIONS.some((s) => s.id === slug) || slug === 'custom')) throw new Error(`“${name}” is the name of a built-in provider. Choose another name.`);
  if (!existing && row(userId, slug)) throw new Error(`There is already an endpoint called “${name}”.`);

  let url: URL | null = null;
  try { url = new URL(String(p.baseUrl ?? '').trim()); } catch { /* reported below */ }
  if (!url || !/^https?:$/.test(url.protocol)) throw new Error('The address has to start with http:// or https://, for example http://localhost:20128/v1.');
  const key = p.key === undefined || p.key === '' ? null : cleanKey(p.key);
  if (p.key && !key) throw new Error('That does not look like an API key.');
  const models = (Array.isArray(p.models) ? p.models : String(p.models ?? '').split(/[\s,]+/))
    .map((m) => String(m).trim()).filter((m) => /^[\x21-\x7e]{1,120}$/.test(m)).slice(0, 40);

  upsert(userId, slug, 'endpoint', name, url.href.replace(/\/+$/, ''), models, key ?? (existing ? box.open(existing.secret).key ?? null : null));
  return toEndpoint(row(userId, slug));
}

/** Forget a key or an endpoint. False when there was none. */
export const removeAccess = (userId: string, slug: string, kind: 'key' | 'endpoint'): boolean =>
  db.prepare('DELETE FROM model_access WHERE user_id=? AND slug=? AND kind=?').run(userId, slug, kind).changes > 0;

// ---------- what a Hermes home gets ----------

export function accessFor(userId: string): ModelAccess {
  const access: ModelAccess = { env: {}, providers: {} };
  for (const r of rows(userId)) {
    const key = box.open(r.secret).key;
    if (r.kind === 'key') {
      const p = KEY_PROVIDERS.find((k) => k.id === r.slug);
      if (p && key) access.env[p.env] = key;
      continue;
    }
    const models: string[] = r.models ? JSON.parse(r.models) : [];
    if (key) access.env[endpointEnv(r.slug)] = key;
    access.providers[r.slug] = {
      name: r.name,
      api: r.base_url,
      ...(key ? { key_env: endpointEnv(r.slug) } : {}),
      // Hermes keeps an endpoint's models as a map from model id to its settings.
      ...(models.length ? { default_model: models[0], models: Object.fromEntries(models.map((m) => [m, {}])) } : {}),
    };
  }
  return access;
}
