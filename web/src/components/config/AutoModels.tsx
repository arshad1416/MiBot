import { useCallback, useEffect, useState } from 'react';
import type { AutoInfo, RecentChoice, RosterModel } from '@shared/types';
import { configApi, type RosterDraft } from '../../lib/configApi';
import { relativeShort } from '../../lib/time';
import { useVersion } from '../../configStore';
import { ConfirmButton, Segmented } from './ui';

const NO_DRAFT: RosterDraft = { label: '', provider: '', model: '', note: '', machines: [] };
/** How sure Jev has to be before its choice is used. */
const SURE = [0.3, 0.5, 0.7, 0.9].map((v) => ({ value: String(v), label: `${Math.round(v * 100)}%` }));

/** What a finished choice says under its job. */
function choiceLine(r: RecentChoice): string {
  const parts = [r.choice.by === 'jev' ? r.choice.label : 'The Bot’s own model', `${r.choice.weight} job`];
  if (r.machineName) parts.push(`on ${r.machineName}`);
  return `${parts.join(' · ')} · ${r.choice.why}`;
}

/** Settings › Models, the Auto part: the models a job's model is chosen from, and what was chosen lately. */
export function AutoModels() {
  // A job that starts or ends changes the list of choices.
  const version = useVersion('machines');
  const [data, setData] = useState<AutoInfo | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [draft, setDraft] = useState<RosterDraft>(NO_DRAFT);

  const load = useCallback(() => { configApi.autoModels().then(setData).catch((e) => setNote((e as Error).message)); }, []);
  useEffect(() => { load(); }, [load, version]);

  /** Run one change; every change answers with the new state of this part of the page. */
  const run = async (work: () => Promise<AutoInfo>): Promise<boolean> => {
    setBusy(true); setNote(null);
    try { setData(await work()); return true; }
    catch (e) { setNote((e as Error).message); return false; }
    finally { setBusy(false); }
  };
  const save = async () => { if (await run(() => configApi.saveRosterModel(draft))) setDraft(NO_DRAFT); };
  const edit = (r: RosterModel) => setDraft({ id: r.id, label: r.label, provider: r.provider, model: r.model, note: r.note, machines: r.machines });

  const providers = data?.providers ?? [];
  const machines = data?.machines ?? [];
  const nameOf = (provider: string) => providers.find((p) => p.id === provider)?.name ?? provider;
  const runsOn = (r: RosterModel) => {
    const names = machines.filter((m) => r.machines.includes(m.id)).map((m) => m.name);
    return names.length ? `runs on ${names.join(', ')}` : 'runs on any machine';
  };
  const pickProvider = (id: string) => {
    const next = providers.find((p) => p.id === id);
    // A model from another provider will not work here, so start from this provider's first suggestion.
    setDraft((d) => ({ ...d, provider: id, model: next?.models.length && !next.models.includes(d.model.trim()) ? next.models[0] : d.model }));
  };
  const toggleMachine = (id: string) => setDraft((d) => ({ ...d, machines: d.machines.includes(id) ? d.machines.filter((m) => m !== id) : [...d.machines, id] }));
  const editing = draft.id !== undefined;
  const suggestions = providers.find((p) => p.id === draft.provider)?.models ?? [];

  return (
    <>
      <section className="cfg-section">
        <div className="cfg-section__label">Auto</div>
        <div className="cfg-field__hint">
          A Bot with Auto switched on (in its panel, under Engine) gets a model for each job from this list. Jev, a small decision model reached through OpenRouter, reads the job and your notes and picks the model, the job’s size and a machine.
        </div>
        {note && <div className="cfg-error" role="alert">{note}</div>}
        {data?.blocked && <div className="cfg-note" role="status">{data.blocked}</div>}
        {(data?.roster.length ?? 0) > 0 && (
          <div className="cfg-card">
            {data!.roster.map((r) => (
              <div key={r.id} className="cfg-row model-row">
                <div className="cfg-row__text">
                  <div className="cfg-row__title">{r.label}</div>
                  <div className="cfg-row__sub">{nameOf(r.provider)} · <code className="cfg-code">{r.model}</code> · {runsOn(r)}</div>
                  {r.note && <div className="cfg-row__sub">{r.note}</div>}
                </div>
                <button type="button" className="cfg-btn cfg-btn--secondary" onClick={() => edit(r)}>Edit</button>
                <ConfirmButton confirmLabel="Remove" onConfirm={() => run(() => configApi.removeRosterModel(r.id))}>Remove</ConfirmButton>
              </div>
            ))}
          </div>
        )}
        <div className="model-add">
          <input className="text-input" value={draft.label} maxLength={60} placeholder="Name, for example Claude Opus" aria-label="Model name" onChange={(e) => setDraft({ ...draft, label: e.target.value })} />
          {providers.length > 0 ? (
            <select className="text-input" value={draft.provider} aria-label="Model provider" onChange={(e) => pickProvider(e.target.value)}>
              <option value="">Provider…</option>
              {draft.provider && !providers.some((p) => p.id === draft.provider) && <option value={draft.provider}>{draft.provider}</option>}
              {providers.map((p) => <option key={p.id} value={p.id}>{p.name}{p.connected ? '' : ' (not connected)'}</option>)}
            </select>
          ) : (
            <input className="text-input" value={draft.provider} spellCheck={false} placeholder="Provider id, for example openrouter" aria-label="Model provider" onChange={(e) => setDraft({ ...draft, provider: e.target.value })} />
          )}
          <input className="text-input model-add__wide" list="auto-model-ids" value={draft.model} spellCheck={false} autoComplete="off" placeholder={suggestions[0] ?? 'Model id, as the provider writes it'} aria-label="Model id" onChange={(e) => setDraft({ ...draft, model: e.target.value })} />
          <datalist id="auto-model-ids">{suggestions.map((m) => <option key={m} value={m} />)}</datalist>
          <textarea className="text-input cfg-textarea model-add__wide" rows={2} value={draft.note} maxLength={600} placeholder="What it is good and bad at, for example: best at long research and code, slow and costly" aria-label="Notes on the model" onChange={(e) => setDraft({ ...draft, note: e.target.value })} />
          {machines.length > 1 && (
            <div className="model-add__wide auto-machines" role="group" aria-label="Machines it runs on">
              <span>Runs on</span>
              {machines.map((m) => (
                <label key={m.id}>
                  <input type="checkbox" checked={draft.machines.includes(m.id)} onChange={() => toggleMachine(m.id)} /> {m.name}
                </label>
              ))}
              <span className="auto-machines__any">{draft.machines.length ? '' : 'Any machine'}</span>
            </div>
          )}
          <button type="button" className="cfg-btn cfg-btn--primary" disabled={!draft.label.trim() || !draft.provider.trim() || !draft.model.trim() || busy} onClick={save}>{busy ? 'Saving…' : editing ? 'Save changes' : 'Add model'}</button>
          {editing && <button type="button" className="cfg-btn cfg-btn--secondary" onClick={() => setDraft(NO_DRAFT)}>Cancel</button>}
        </div>
        <div className="cfg-field__hint">
          The notes are what Jev goes by, so say plainly what each model is for. Tick the machines a model runs on when it needs a sign-in that only some of them have; with none ticked it can run anywhere.
        </div>
      </section>

      {data && (
        <section className="cfg-section">
          <div className="cfg-section__label">How sure Jev has to be</div>
          <Segmented label="How sure Jev has to be" value={String(data.minConfidence)} options={SURE} onChange={(v) => run(() => configApi.saveAuto(Number(v)))} />
          <div className="cfg-field__hint">A choice Jev is less sure of than this is not used. The job then runs on the Bot’s own model and size.</div>
        </section>
      )}

      {(data?.recent.length ?? 0) > 0 && (
        <section className="cfg-section">
          <div className="cfg-section__label">Latest choices</div>
          <div className="cfg-card">
            {data!.recent.map((r) => (
              <div key={r.jobId} className="cfg-row">
                <div className="cfg-row__text">
                  <div className="cfg-row__title">{r.botName} · {r.title}</div>
                  <div className="cfg-row__sub">{choiceLine(r)}</div>
                </div>
                <span className="cfg-row__sub">{relativeShort(r.at)}</span>
              </div>
            ))}
          </div>
        </section>
      )}
    </>
  );
}
