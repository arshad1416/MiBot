import { useCallback, useEffect, useState } from 'react';
import type { ModelAccessInfo, ModelEndpoint } from '@shared/types';
import { configApi, type EndpointDraft } from '../../lib/configApi';
import { useConfigUI } from '../../configStore';
import { useStore } from '../../store';
import { ConfirmButton, PageHead } from './ui';

/** Routers and local servers that speak the OpenAI API, with the address each listens on when left as installed. */
const ENDPOINT_KINDS: { name: string; baseUrl: string }[] = [
  { name: '9Router', baseUrl: 'http://localhost:20128/v1' },
  { name: 'OmniRoute', baseUrl: 'http://localhost:20128/v1' },
  { name: 'LiteLLM', baseUrl: 'http://localhost:4000/v1' },
  { name: 'Ollama', baseUrl: 'http://localhost:11434/v1' },
  { name: 'LM Studio', baseUrl: 'http://localhost:1234/v1' },
];
const NO_DRAFT: EndpointDraft = { name: '', baseUrl: '', key: '', models: '' };

/** Settings › Models: subscriptions, API keys and the person's own endpoints, added once for every Bot. */
export function ModelsSection() {
  const [data, setData] = useState<ModelAccessInfo | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [keyFor, setKeyFor] = useState('');
  const [key, setKey] = useState('');
  const [draft, setDraft] = useState<EndpointDraft>(NO_DRAFT);

  const load = useCallback(() => { configApi.models().then(setData).catch((e) => setNote((e as Error).message)); }, []);
  useEffect(() => { load(); }, [load]);

  /** Run one change; every change answers with the new state of the page. */
  const run = async (what: string, work: () => Promise<ModelAccessInfo>): Promise<boolean> => {
    setBusy(what); setNote(null);
    try { setData(await work()); return true; }
    catch (e) { setNote((e as Error).message); return false; }
    finally { setBusy(null); }
  };

  const signIn = async (id: string, name: string) => {
    setBusy(`sub:${id}`); setNote(null);
    try {
      const { botId } = await configApi.modelSignIn(id);
      // The sign-in runs in a window on that Bot's screen, so take the person there with the mouse and keyboard.
      useStore.getState().selectBot(botId);
      useStore.getState().set({ viewerOpen: true, viewerMode: 'control', viewerHint: `Finish signing in to ${name}, then hand back. Every Bot can use it afterwards.` });
      useConfigUI.getState().closeAppSettings();
    } catch (e) { setNote((e as Error).message); }
    finally { setBusy(null); }
  };

  const saveKey = async () => {
    if (await run('key', () => configApi.saveModelKey(keyFor, key.trim()))) { setKey(''); setKeyFor(''); }
  };
  const saveEndpoint = async () => {
    if (await run('endpoint', () => configApi.saveModelEndpoint(draft))) setDraft(NO_DRAFT);
  };
  const edit = (e: ModelEndpoint) => setDraft({ id: e.id, name: e.name, baseUrl: e.baseUrl, key: '', models: e.models.join(', ') });
  const pickKind = (name: string) => {
    const kind = ENDPOINT_KINDS.find((k) => k.name === name);
    setDraft((d) => ({ ...d, name: kind?.name ?? '', baseUrl: kind?.baseUrl ?? '' }));
  };

  const keys = data?.keys ?? [];
  const shownKeys = keys.filter((k) => k.saved || k.connected);
  const editing = draft.id !== undefined;

  return (
    <>
      <PageHead title="Models" sub="Where your Bots get their models. Add a subscription, a key or an endpoint here once and every Bot can use it. Each Bot’s own provider and model are chosen in its panel, under Engine." />
      {note && <div className="cfg-error" role="alert">{note}</div>}

      <section className="cfg-section">
        <div className="cfg-section__label">Subscriptions</div>
        <div className="cfg-card">
          {!data ? <div className="cfg-empty cfg-empty--flat">Loading…</div> : data.subscriptions.map((s) => (
            <div key={s.id} className="cfg-row model-row">
              <div className="cfg-row__text">
                <div className="cfg-row__title">{s.name}{s.connected && <span className="cfg-chip machine-row__chip">Signed in</span>}</div>
                <div className="cfg-row__sub">{s.note}</div>
              </div>
              <button type="button" className={`cfg-btn ${s.connected ? 'cfg-btn--secondary' : 'cfg-btn--primary'}`} disabled={!!data.signInBlocked || busy === `sub:${s.id}`} onClick={() => signIn(s.id, s.name)}>
                {busy === `sub:${s.id}` ? 'Opening…' : s.connected ? 'Sign in again' : 'Sign in'}
              </button>
            </div>
          ))}
        </div>
        <div className="cfg-field__hint">
          {data?.signInBlocked ?? 'Sign in opens the provider’s own sign-in in a window on a Bot’s screen, and shows you that screen. You type your account details there yourself.'}
          {data && !data.checked && ' No Bot’s agent is running yet, so this page cannot tell what is already signed in.'}
        </div>
      </section>

      <section className="cfg-section">
        <div className="cfg-section__label">API keys</div>
        {shownKeys.length > 0 && (
          <div className="cfg-card">
            {shownKeys.map((k) => (
              <div key={k.id} className="cfg-row model-row">
                <div className="cfg-row__text">
                  <div className="cfg-row__title">{k.name}</div>
                  <div className="cfg-row__sub">{k.saved ? 'Key added here' : 'Already set up on the computer'}</div>
                </div>
                <button type="button" className="cfg-btn cfg-btn--secondary" onClick={() => { setKeyFor(k.id); setKey(''); }}>{k.saved ? 'Replace' : 'Use another key'}</button>
                {k.saved && <ConfirmButton confirmLabel="Remove key" onConfirm={() => run('key', () => configApi.removeModelKey(k.id))}>Remove</ConfirmButton>}
              </div>
            ))}
          </div>
        )}
        <div className="machine-add">
          <select className="text-input model-add__pick" value={keyFor} aria-label="Provider" onChange={(e) => setKeyFor(e.target.value)}>
            <option value="">Provider…</option>
            {keys.map((k) => <option key={k.id} value={k.id}>{k.name}</option>)}
          </select>
          <input className="text-input" type="password" autoComplete="off" spellCheck={false} value={key} placeholder="Paste the API key" aria-label="API key" onChange={(e) => setKey(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter' && keyFor && key.trim()) void saveKey(); }} />
          <button type="button" className="cfg-btn cfg-btn--primary" disabled={!keyFor || !key.trim() || busy === 'key'} onClick={saveKey}>{busy === 'key' ? 'Saving…' : 'Save key'}</button>
        </div>
        <div className="cfg-field__hint">A key is kept encrypted on this server and is never shown again. Bots use it from their next message.</div>
      </section>

      <section className="cfg-section">
        <div className="cfg-section__label">Your own endpoints</div>
        {(data?.endpoints.length ?? 0) > 0 && (
          <div className="cfg-card">
            {data!.endpoints.map((e) => (
              <div key={e.id} className="cfg-row model-row">
                <div className="cfg-row__text">
                  <div className="cfg-row__title">{e.name}</div>
                  <div className="cfg-row__sub"><code className="cfg-code">{e.baseUrl}</code> · {e.models.length ? e.models.join(', ') : 'models as the endpoint lists them'} · {e.hasKey ? 'key added' : 'no key'}</div>
                </div>
                <button type="button" className="cfg-btn cfg-btn--secondary" onClick={() => edit(e)}>Edit</button>
                <ConfirmButton confirmLabel="Remove" onConfirm={() => run('endpoint', () => configApi.removeModelEndpoint(e.id))}>Remove</ConfirmButton>
              </div>
            ))}
          </div>
        )}
        <div className="model-add">
          {!editing && (
            <select className="text-input model-add__pick" value={ENDPOINT_KINDS.some((k) => k.name === draft.name) ? draft.name : ''} aria-label="Kind of endpoint" onChange={(e) => pickKind(e.target.value)}>
              <option value="">Other…</option>
              {ENDPOINT_KINDS.map((k) => <option key={k.name} value={k.name}>{k.name}</option>)}
            </select>
          )}
          <input className={`text-input${editing ? ' model-add__wide' : ''}`} value={draft.name} maxLength={60} placeholder="Name" aria-label="Endpoint name" disabled={editing} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
          <input className="text-input model-add__wide" value={draft.baseUrl} spellCheck={false} placeholder="Address, for example http://localhost:20128/v1" aria-label="Endpoint address" onChange={(e) => setDraft({ ...draft, baseUrl: e.target.value })} />
          <input className="text-input model-add__wide" value={draft.models} spellCheck={false} placeholder="Model ids, separated by commas (optional)" aria-label="Model ids" onChange={(e) => setDraft({ ...draft, models: e.target.value })} />
          <input className="text-input model-add__wide" type="password" autoComplete="off" spellCheck={false} value={draft.key} placeholder={editing ? 'API key (empty keeps the one it has)' : 'API key (if it needs one)'} aria-label="Endpoint API key" onChange={(e) => setDraft({ ...draft, key: e.target.value })} />
          <button type="button" className="cfg-btn cfg-btn--primary" disabled={!draft.name.trim() || !draft.baseUrl.trim() || busy === 'endpoint'} onClick={saveEndpoint}>{busy === 'endpoint' ? 'Saving…' : editing ? 'Save changes' : 'Add endpoint'}</button>
          {editing && <button type="button" className="cfg-btn cfg-btn--secondary" onClick={() => setDraft(NO_DRAFT)}>Cancel</button>}
        </div>
        <div className="cfg-field__hint">
          For 9Router, OmniRoute and anything else that speaks the OpenAI API. An address with <code className="cfg-code">localhost</code> means the machine a Bot’s job runs on, so use the machine’s network name when the router runs on one machine only. Model ids are the names the endpoint uses; the first is the one a Bot starts with.
        </div>
      </section>
    </>
  );
}
