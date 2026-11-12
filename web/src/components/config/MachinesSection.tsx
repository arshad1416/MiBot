import { useCallback, useEffect, useState } from 'react';
import type { JobInfo, Machine } from '@shared/types';
import { configApi, type MachinePatch, type MachinesBundle } from '../../lib/configApi';
import { relativeShort } from '../../lib/time';
import { useVersion } from '../../configStore';
import { Icon } from '../Icon';
import { ConfirmButton, PageHead, Segmented, Switch } from './ui';

const TAKES: { value: Machine['takes']; label: string }[] = [
  { value: 'light', label: 'Light' },
  { value: 'heavy', label: 'Heavy' },
  { value: 'both', label: 'Both' },
];

const gb = (mb: number) => (mb >= 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${mb} MB`);

/** One line under a machine's name: is it there, and what is it doing. */
function machineLine(m: Machine): string {
  if (!m.enabled) return 'Switched off. It gets no jobs.';
  if (m.state === 'never') return 'Not connected yet. Start the worker on it to bring it in.';
  if (m.state === 'away') return `Asleep or offline${m.lastSeenAt ? ` · last seen ${relativeShort(m.lastSeenAt)}${/^\d/.test(relativeShort(m.lastSeenAt)) ? ' ago' : ''}` : ''}`;
  const parts = [m.state === 'busy' ? `Not taking jobs${m.reason ? `: ${m.reason}` : ''}` : 'Online'];
  if (m.running) parts.push(`${m.running} job${m.running === 1 ? '' : 's'} running`);
  if (m.memFreeMb !== null) parts.push(`${gb(m.memFreeMb)} free`);
  return parts.join(' · ');
}

const JOB_STATUS: Record<JobInfo['status'], string> = { queued: 'In line', waiting: 'Waiting', running: 'Running', paused: 'Paused' };

/** Settings for a worker that was just added. The token is only ever shown here. */
function NewMachineCard({ name, hubUrl, token, onDone }: { name: string; hubUrl: string; token: string; onDone: () => void }) {
  const [copied, setCopied] = useState(false);
  const text = `HUB_URL=${hubUrl}\nWORKER_TOKEN=${token}\n`;
  const copy = async () => { try { await navigator.clipboard.writeText(text); setCopied(true); setTimeout(() => setCopied(false), 1500); } catch { /* select and copy by hand */ } };
  return (
    <div className="machine-new" role="status">
      <div className="machine-new__title">Finish on {name}</div>
      <ol className="machine-new__steps">
        <li>Put the MiBot folder on that machine and run <code className="cfg-code">npm ci</code> in it.</li>
        <li>Save these two lines there as <code className="cfg-code">~/.mibot/worker.env</code>. The token is shown only this once.</li>
      </ol>
      <pre className="machine-new__env">{text}</pre>
      <ol className="machine-new__steps" start={3}>
        <li>Run <code className="cfg-code">npm run worker</code> in the MiBot folder. The machine shows as online here within a few seconds.</li>
      </ol>
      <div className="machine-new__actions">
        <button type="button" className="cfg-btn cfg-btn--secondary" onClick={copy}><Icon name={copied ? 'check' : 'copy'} size={12} opacity={0.7} /> {copied ? 'Copied' : 'Copy the two lines'}</button>
        <button type="button" className="cfg-btn cfg-btn--primary" onClick={onDone}>Done</button>
      </div>
    </div>
  );
}

export function MachinesSection() {
  const version = useVersion('machines');
  const [data, setData] = useState<MachinesBundle | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [takes, setTakes] = useState<Machine['takes']>('both');
  const [adding, setAdding] = useState(false);
  const [added, setAdded] = useState<{ name: string; token: string } | null>(null);
  const load = useCallback(() => { configApi.machines().then(setData).catch((e) => setNote((e as Error).message)); }, []);
  useEffect(() => { load(); }, [load, version]);

  const run = async (work: () => Promise<unknown>) => {
    setNote(null);
    try { await work(); } catch (e) { setNote((e as Error).message); }
    load();
  };
  const patch = (id: string, p: MachinePatch) => run(() => configApi.updateMachine(id, p));
  const machines = data?.machines ?? [];
  /** Swap two neighbours in the order jobs are offered. */
  const move = (i: number, by: -1 | 1) => run(async () => {
    const order = [...machines];
    [order[i], order[i + by]] = [order[i + by], order[i]];
    await Promise.all(order.map((m, rank) => (m.rank === rank ? null : configApi.updateMachine(m.id, { rank }))));
  });
  const add = async () => {
    if (!name.trim()) return;
    setAdding(true);
    await run(async () => {
      const r = await configApi.addMachine(name.trim(), takes);
      setAdded({ name: r.machine.name, token: r.token });
      setName('');
    });
    setAdding(false);
  };
  const byId = new Map(machines.map((m) => [m.id, m]));
  const jobs = (data?.jobs ?? []).filter((j) => j.status !== 'running' || j.machineId !== 'hub' || machines.length > 1);

  return (
    <>
      <PageHead title="Machines" sub="Where your Bots’ jobs run. A job goes to the first machine in this list that takes its size and is awake. If the machine running a job falls asleep, the job waits for it and carries on when it is back." />

      <section className="cfg-section">
        <div className="cfg-card">
          {!data ? <div className="cfg-empty cfg-empty--flat">Loading…</div> : machines.map((m, i) => (
            <div key={m.id} className={`cfg-row machine-row${m.enabled ? '' : ' is-off'}`}>
              <span className={`computer-status-dot is-${!m.enabled || m.state === 'away' || m.state === 'never' ? 'off' : m.state === 'busy' ? 'busy' : 'on'}`} aria-hidden />
              <div className="cfg-row__text">
                <div className="cfg-row__title">{m.name}{m.isHub && <span className="cfg-chip cfg-chip--muted machine-row__chip">This server</span>}</div>
                <div className="cfg-row__sub">{machineLine(m)}</div>
              </div>
              <Segmented label={`Jobs ${m.name} takes`} value={m.takes} options={TAKES} onChange={(v) => patch(m.id, { takes: v })} />
              <div className="machine-row__order">
                <button type="button" className="icon-btn" disabled={i === 0} onClick={() => move(i, -1)} aria-label={`Move ${m.name} up`} title="Offer jobs to it sooner">↑</button>
                <button type="button" className="icon-btn" disabled={i === machines.length - 1} onClick={() => move(i, 1)} aria-label={`Move ${m.name} down`} title="Offer jobs to it later">↓</button>
              </div>
              <Switch on={m.enabled} label={`${m.name} takes jobs`} onChange={(v) => patch(m.id, { enabled: v })} />
              {m.isHub ? <span className="machine-row__spacer" /> : (
                <ConfirmButton confirmLabel="Remove" onConfirm={() => run(() => configApi.removeMachine(m.id))} title="Forget this machine. A job it holds is picked up elsewhere.">Remove</ConfirmButton>
              )}
            </div>
          ))}
        </div>
        {note && <div className="cfg-error">{note}</div>}
        <div className="cfg-field__hint">Light and Heavy say which jobs a machine takes. Each Bot’s job size is in its own settings.</div>
      </section>

      <section className="cfg-section">
        <div className="cfg-section__label">Add a machine</div>
        {added && data ? <NewMachineCard name={added.name} hubUrl={data.hubUrl} token={added.token} onDone={() => setAdded(null)} /> : (
          <div className="machine-add">
            <input className="text-input" value={name} maxLength={60} placeholder="Name, like MacBook" aria-label="Machine name" onChange={(e) => setName(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') void add(); }} />
            <Segmented label="Jobs it takes" value={takes} options={TAKES} onChange={setTakes} />
            <button type="button" className="cfg-btn cfg-btn--primary" disabled={!name.trim() || adding} onClick={add}>{adding ? 'Adding…' : 'Add'}</button>
          </div>
        )}
      </section>

      <section className="cfg-section">
        <div className="cfg-section__label">Jobs in progress</div>
        <div className="cfg-card">
          {!data ? <div className="cfg-empty cfg-empty--flat">Loading…</div> : jobs.length === 0 ? (
            <div className="cfg-empty cfg-empty--flat">Nothing is waiting for a machine.</div>
          ) : jobs.map((j) => {
            const others = machines.filter((m) => m.enabled && m.id !== j.machineId);
            return (
              <div key={j.id} className="cfg-row">
                <div className="cfg-row__text">
                  <div className="cfg-row__title">{j.botName} · {j.title}</div>
                  <div className="cfg-row__sub">
                    {j.note ?? `${JOB_STATUS[j.status]}${j.status === 'running' && j.machineId ? ` on ${byId.get(j.machineId)?.name ?? 'a machine'}` : ''}`} · {j.weight} job
                  </div>
                </div>
                {others.length > 0 && !(j.status === 'running' && j.machineId === 'hub') && (
                  <select className="text-input machine-move" value="" aria-label={`Run ${j.botName}'s job on another machine`} onChange={(e) => { const to = e.target.value; if (to) void run(() => configApi.moveJob(j.id, to)); }}>
                    <option value="">Run on…</option>
                    {others.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
                  </select>
                )}
                <ConfirmButton confirmLabel="Stop job" onConfirm={() => run(() => configApi.stopJob(j.id))}>Stop</ConfirmButton>
              </div>
            );
          })}
        </div>
      </section>
    </>
  );
}
