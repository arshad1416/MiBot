# The Mac app

A small native app for answering your Bots' approval requests from a Mac. It signs in to your hub with the
same one-time code as the phone, lists every request that is waiting, shows the command in full, and
offers **Approve once** and **Deny**. It sends a macOS notification when a Bot asks; clicking the
notification opens that request (it never answers it). It keeps running in the menu bar when its window is
closed. The rest of the hub (chats, Settings) is in the window's **Hub** page.

## Build it

On the Mac, with Xcode or the Command Line Tools installed:

```sh
cd mac
swift test                  # the approval logic and the hub client
scripts/build-app.sh        # writes mac/build/MiBot.app
open build/MiBot.app
```

Copy `build/MiBot.app` to `/Applications` if you want to keep it. The first time it runs, macOS asks whether
MiBot may send notifications: allow it, or turn it on later in System Settings › Notifications › MiBot.

## Sign in

On the hub, open Settings › Machines › Add a device. In the app, type the hub's address (for example
`mac-mini:8787`) and the code, or paste the whole link. The app then shows up in Settings › Machines ›
Phones and browsers as "MiBot app on Mac", where it can be signed out.

## How it keeps in step

- Requests and answers come over the hub's live event stream (`/api/events`, event type `approval`).
- After a reconnect or a restart the app asks `/api/approvals?since=…` for what is waiting and what was
  decided while it was away. A request it still had as waiting that the hub no longer lists has run out.
- The hub decides which answer counts. If the phone answered first, the Mac's answer is refused (409) and
  the request shows "Approved on MiBot app on Android". A request runs at most once.
- Buttons disappear when a request is answered anywhere or its time is up (by the hub's clock).

The sign-in and the last list are kept in `~/Library/Application Support/MiBot/default/`, readable only by you.

## Trying it against a practice hub

```sh
APPROVAL_WAIT_MS=60000 node scripts/dev-hub.mjs --url http://10.0.2.2:18891   # one-minute expiry
node scripts/approval-drill.mjs code      # a code for the app
open mac/build/MiBot.app --args --profile test --hub http://127.0.0.1:18891 --code <CODE> --trace /tmp/mibot-mac.jsonl
node scripts/approval-drill.mjs ask       # a Bot asks; prints how it ended
```

`--profile test` keeps this sign-in apart from your everyday one. `--trace FILE` writes one line per thing
that happens to a request (notified, opened, decided, settled), for checking a run afterwards.
