# Running jobs on more than one machine

One machine is the **hub**: it runs this server, keeps every conversation, the queue and the
schedules, and holds the Bots' screens. Any other machine can lend a hand by running the **worker**.
A worker has no open port. It calls the hub every few seconds, asks for work, runs the job on a
Hermes agent of its own and posts the agent's event stream back.

Each turn a Bot takes is a job. A job is `light` or `heavy` (set per Bot, in its settings; new Bots
are `heavy`). Each machine says which jobs it takes. A job goes to the first machine in the list
under **Settings › Machines** that takes its size, is awake and has a free slot. If none is, the job
waits and the conversation says what it is waiting for.

## What happens when a machine goes away

A worker that stops calling is asleep, off or off the network. After 30 seconds the hub marks it
away, and a job it was running is paused. Nothing is lost:

- When the machine is back and its agent kept going, the stream carries on from where the hub
  stopped hearing it.
- When the run broke (the agent's own connections died, the worker restarted), the job is started
  again on the same machine. The agent is told what was already done, taken from the conversation
  rows the hub recorded, and asked to carry on.
- A paused or waiting job can be sent to another machine from **Settings › Machines**.
- The hub restarting does the same for its own jobs: what was running or in line is picked up
  again, oldest first. Jobs older than 12 hours are dropped.

A job that is interrupted five times is given up on.

## What moves with a job, and what does not

- **The conversation.** Each machine's agent keeps its own thread. When a Bot's turn lands on a
  machine that missed earlier turns, the hub puts a catch-up of what happened in front of the
  request (user and Bot messages, and one line per tool call).
- **Memory.** The Bot's `MEMORY.md` and `USER.md` travel to the worker with the job and come back
  after it. The hub's copy is the one that counts.
- **Connected apps and the app's own tools.** They live on the hub and are reached through its MCP
  endpoint, so they work the same from any machine. Approval cards too.
- **Not the screen.** A Bot's screen and Library folder are on the hub's computer. On another
  machine the agent uses that machine's shell, files and browser, and is told so. Nobody can watch
  that work in the app.
- **Not the first Bot.** It is the Hermes agent that already runs on the hub, so its jobs always
  run there. On a hub with no agent of its own to keep (`HERMES_URL=` left empty in `.env`), the
  first Bot is like the others and can run anywhere.
- **Model sign-ins are per machine.** An agent started by a worker inherits model settings and keys
  from that machine's own `~/.hermes`, the same way the hub's extra Bots do.

## Setting up the hub on a machine of your own

On a machine that stays on (Linux here), with Node 22, the `hermes` CLI, Xvfb, x11vnc and
ImageMagick installed, and this repository with `npm ci` run in it. A window manager (xfwm4,
openbox, marco, metacity or icewm) is started on each screen when the machine has one, which gives
windows there a title bar; without one a sign-in window still takes the keyboard:

1. Choose a model for Hermes once (`hermes model`). Each Bot's agent starts from those settings.
2. `scripts/hub-env.sh https://<name>.<tailnet>.ts.net you@example.com` writes a first `.env`: this
   machine's own screens, no Hermes agent that already runs there, listening on `127.0.0.1` only.
   The address is the one `tailscale serve --bg 8787` gives the machine.
3. `scripts/service.sh hub` builds the app and keeps it running.
4. `bash scripts/sign-in-link.sh you@example.com` prints a link that signs your browser in.

## Adding a machine

1. On that machine: Node 22, the `hermes` CLI, and this repository. `scripts/service.sh worker` in it
   installs what it needs, builds it and keeps the worker running as a service of your own user
   (systemd on Linux, launchd on macOS). With no settings yet, the worker waits to be connected.
2. In the app: **Settings › Machines › Add**. Choose which jobs the machine takes, and a name if you
   want one (without it the machine is called after itself). The app shows a QR code and an
   eight-letter code. The code works once, for ten minutes.
3. Hand the code to the machine, whichever way is nearest:
   - **In the app on that machine**, press **Add this computer**. Its own setup page opens with the
     code and the hub's address filled in. Check the address and press Connect.
   - **On that machine's setup page**, `http://127.0.0.1:8765`, type the code and the hub's address.
   - **On a machine without a screen**, run `npm run pair -- '<link>'` in the checkout, with the link
     from **Copy link** (keep the quotes), or `npm run pair -- <hub address> <code>`.

The machine shows as online within a few seconds. Nothing has to be restarted, and no file has to be
written by hand: the worker saves `HUB_URL` and its own key in `~/.mibot/worker.env` (readable by
your user only). Other settings go in the same file, see `worker.env.example`.

The setup page only answers on the machine itself, only while the machine is not connected, and
only sends the code when you press Connect, so another site cannot point your worker at a hub.

To run the worker without the service, use `npm run worker`. `scripts/service.sh hub` keeps the
server running the same way.

The worker has to be able to reach the hub. On a Tailscale network that is the hub's
`https://<name>.<tailnet>.ts.net` address, or `http://<tailscale-ip>:8787` when `HOST` is set to it.

## Signing in on a phone or another browser

The same code signs another device in. With **Settings › Machines › Add** open on a device that is
already signed in, either scan the QR code with the phone's camera and press **Sign in on this
device**, or open the app's address on the other device, choose **Sign in with a code from it** and
type the code. `scripts/sign-in-link.sh` is still there for the very first sign-in.

A handful of wrong codes closes pairing for ten minutes and cancels the codes that were open.

## Keeping a small machine small

Settings in `worker.env` for a machine like a Raspberry Pi:

| Setting | What it does |
|---|---|
| `WORKER_SLOTS=1` | Jobs it runs at once |
| `WORKER_MIN_FREE_MB=400` | Takes no new job while less memory than this is available |
| `WORKER_MAX_AGENTS_MB=300` | Takes no new job while the agents it started use more than this together |
| `WORKER_QUIET_HOURS=06:30-07:50` | Takes no new job between these local times |
| `WORKER_IDLE_STOP_MIN=15` | Stops a Bot's agent after this many idle minutes, which gives its memory back |

A job already running is never cut off by these. For a hard ceiling on Linux, install the service
with `MEMORY_MAX=300M scripts/service.sh worker`: systemd then limits the worker and every agent it
starts to that much memory.

On the hub, `HUB_SLOTS` limits how many jobs the hub itself runs at once (unset = no limit).
