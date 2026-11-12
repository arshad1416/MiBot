import { useCallback, useEffect, useState } from 'react';
import type { JobInfo, Machine } from '@shared/types';
import { configApi, type MachinePatch, type MachinesBundle, type PairCode, type PairStatus } from '../../lib/configApi';
import { SETUP_PAGE, setupPageLink } from '../../lib/pairing';
import { relativeShort } from '../../lib/time';
import { useVersion } from '../../configStore';
import { Icon } from '../Icon';
import { QrCode } from '../QrCode';
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

const spaced = (code: string) => `${code.slice(0, 4)}-${code.slice(4)}`;
const clock = (ms: number) => `${Math.floor(ms / 60_000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`;

/** A one-time code for a new device, as a QR code and as letters, until it is used or runs out. */
function PairCard({ pair, version, onUsed, onAgain, onDone }: { pair: PairCode; version: number; onUsed: () => void; onAgain: () => void; onDone: () => void }) {
  const [status, setStatus] = useState<PairStatus>({ state: 'open', expiresInMs: pair.expiresInMs, usedAs: null, deviceName: null });
  const [left, setLeft] = useState(pair.expiresInMs);
  const [copied, setCopied] = useState(false);
  const open = status.state === 'open';

  // Ask when something changed on the server, and every few seconds in case that word was missed.
  useEffect(() => {
    if (!open) return;
    let alive = true;
    const ask = () => configApi.pairStatus(pair.id).then((s) => {
      if (!alive) return;
      setStatus(s);
      setLeft(s.expiresInMs);
      if (s.state === 'used') onUsed();
    }).catch(() => {});
    ask();
    const poll = setInterval(ask, 4000);
    return () => { alive = false; clearInterval(poll); };
  }, [pair.id, open, version, onUsed]);
  useEffect(() => {
    if (!open) return;
    const tick = setInterval(() => setLeft((ms) => Math.max(0, ms - 1000)), 1000);
    return () => clearInterval(tick);
  }, [open]);

  const copy = async () => { try { await navigator.clipboard.writeText(pair.link); setCopied(true); setTimeout(() => setCopied(false), 1500); } catch { /* select and copy by hand */ } };

  if (!open || left <= 0) {
    const used = status.state === 'used';
    return (
      <div className="pair-card pair-card--closed" role="status">
        <div className="pair-card__title">
          {used ? (status.usedAs === 'machine' ? `${status.deviceName} is connected. It is in the list above.` : `Signed in on ${status.deviceName}.`) : 'That code ran out before it was used.'}
        </div>
        <div className="pair-card__actions">
          <button type="button" className="cfg-btn cfg-btn--secondary" onClick={onAgain}>{used ? 'Add another' : 'Make a new code'}</button>
          <button type="button" className="cfg-btn cfg-btn--primary" onClick={onDone}>Done</button>
        </div>
      </div>
    );
  }

  const hub = pair.hubUrl.replace(/^https?:\/\//, '');
  return (
    <div className="pair-card" role="status">
      <QrCode text={pair.link} size={168} label="QR code that adds a device" />
      <div className="pair-card__body">
        <div className="pair-card__code" aria-label={`Code ${pair.code.split('').join(' ')}`}>{spaced(pair.code)}</div>
        <div className="pair-card__meta">Works once, for the next {clock(left)}</div>
        <ul className="pair-card__steps">
          <li><b>A computer that should run jobs:</b> open <code className="cfg-code">{SETUP_PAGE}</code> on it and type the code. Its hub address is <code className="cfg-code">{hub}</code>.</li>
          <li><b>A phone or another browser:</b> scan the QR code, in the MiBot app or with the camera, to sign in there.</li>
        </ul>
        <div className="pair-card__actions">
          <a className="cfg-btn cfg-btn--secondary" href={setupPageLink(pair.hubUrl, pair.code)} target="_blank" rel="noreferrer" title="Opens this computer’s own setup page with the code filled in. Its worker has to be running.">Add this computer</a>
          <button type="button" className="cfg-btn cfg-btn--secondary" onClick={copy}><Icon name={copied ? 'check' : 'copy'} size={12} opacity={0.7} /> {copied ? 'Copied' : 'Copy link'}</button>
          <button type="button" className="cfg-btn cfg-btn--primary" onClick={onDone}>Done</button>
        </div>
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
  const [pair, setPair] = useState<PairCode | null>(null);
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
    setAdding(true);
    await run(async () => {
      setPair(await configApi.pairCode(name.trim(), takes));
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
        <div className="cfg-section__label">Add a device</div>
        {pair ? <PairCard key={pair.id} pair={pair} version={version} onUsed={load} onAgain={() => { setPair(null); void add(); }} onDone={() => setPair(null)} /> : (
          <>
            <div className="machine-add">
              <input className="text-input" value={name} maxLength={60} placeholder="Name (optional)" aria-label="Machine name" onChange={(e) => setName(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') void add(); }} />
              <Segmented label="Jobs it takes" value={takes} options={TAKES} onChange={setTakes} />
              <button type="button" className="cfg-btn cfg-btn--primary" disabled={adding} onClick={add}>{adding ? 'Adding…' : 'Add'}</button>
            </div>
            <div className="cfg-field__hint">Add shows a QR code and a short code. Use it on a computer to have it run jobs, or on a phone to sign in there. The name and job sizes are for a computer; without a name it is called after itself.</div>
          </>
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
                    {j.note ?? `${JOB_STATUS[j.status]}${j.status === 'running' && j.machineId ? ` on ${byId.get(j.machineId)?.name ?? 'a machine'}` : ''}`} · {j.weight} job{j.choice?.label ? ` · ${j.choice.label}` : ''}
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
