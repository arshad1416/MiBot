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

## Adding a machine

1. In the app: **Settings › Machines › Add a machine**. Give it a name and say which jobs it takes.
   The app shows two lines, `HUB_URL` and `WORKER_TOKEN`. The token is shown once.
2. On that machine: Node 22, the `hermes` CLI, and this repository with `npm ci` run in it.
3. Save the two lines as `~/.mibot/worker.env` (see `worker.env.example` for the other settings).
4. `npm run worker`. The machine shows as online within a few seconds.

To keep it running after a reboot, `scripts/service.sh worker` installs it as a service of your own
user (systemd on Linux, launchd on macOS). `scripts/service.sh hub` does the same for the server.

The worker has to be able to reach `HUB_URL`. On a Tailscale network that is the hub's
`https://<name>.<tailnet>.ts.net` address, or `http://<tailscale-ip>:8787` when `HOST` is set to it.

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
