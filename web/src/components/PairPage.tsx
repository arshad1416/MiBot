import { useState } from 'react';
import { BotAvatar } from '../avatar/BotAvatar';
import { configApi } from '../lib/configApi';
import { setupPageLink } from '../lib/pairing';
import { useStore } from '../store';

/**
 * Where the QR code under Settings › Machines leads (/pair#CODE). On a phone or another browser it
 * signs that device in with the code. A computer that should run jobs instead is sent to its own
 * setup page with the code and this hub's address filled in.
 */
export function PairPage() {
  const signedIn = useStore((s) => !!s.user);
  // From the QR code's link, when that is how this page was reached.
  const [given] = useState(() => decodeURIComponent(location.hash.slice(1)).toUpperCase().replace(/[^A-Z0-9]/g, ''));
  const [code, setCode] = useState(given.length === 8 ? `${given.slice(0, 4)}-${given.slice(4)}` : given);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // A phone has no worker to set up.
  const computer = !window.matchMedia('(pointer: coarse)').matches;
  const typed = code.replace(/[^A-Za-z0-9]/g, '');

  const signIn = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try { await configApi.pairSignIn(typed); location.replace('/'); } catch (err) { setError((err as Error).message); setBusy(false); }
  };

  return (
    <div className="signin">
      <div className="signin__card">
        <div className="signin__hero"><BotAvatar shape="blob" color="black" size={84} /></div>
        <h1 className="signin__title">Add this device</h1>
        {signedIn ? (
          <p className="signin__sub">This browser is already signed in. <a href="/">Open the app</a></p>
        ) : (
          <form className="signin__dev pair-page__form" onSubmit={signIn}>
            <p className="signin__sub">{given ? 'This signs this device in to your Bots, with the code from your other device.' : 'Type the code shown under Settings › Machines › Add on a device that is already signed in.'}</p>
            <input className="text-input pair-page__code" value={code} maxLength={12} placeholder="ABCD-EFGH" aria-label="Code" autoCapitalize="characters" autoComplete="off" spellCheck={false} onChange={(e) => setCode(e.target.value.toUpperCase())} />
            <button type="submit" className="btn btn--primary" disabled={busy || typed.length < 8}>{busy ? 'Signing in…' : 'Sign in on this device'}</button>
          </form>
        )}
        {error && <div className="card__error signin__error">{error}</div>}
        {computer && (
          <p className="signin__foot pair-page__foot">
            Should this computer run jobs instead? <a href={setupPageLink(location.origin, typed)}>Open its setup page</a>
          </p>
        )}
      </div>
    </div>
  );
}
