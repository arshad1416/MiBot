// Shared API contract between server and web (web imports these as types only).

export type AvatarShape =
  | 'blob' | 'pebble' | 'bean' | 'egg' | 'squircle' | 'tablet' | 'capsule' | 'cylinder' | 'hex'
  | 'gem' | 'crystal' | 'wedge' | 'shield' | 'dome' | 'arch' | 'cloud' | 'teardrop' | 'leaf'
  | 'square' | 'sparkle' | 'clover' | 'flower' | 'heart' | 'house' | 'star';

export type AvatarColor =
  | 'black' | 'brown' | 'red' | 'orange' | 'yellow' | 'green' | 'cyan' | 'blue' | 'violet' | 'magenta' | 'gray';

export interface User { id: string; email: string; name: string; avatarUrl: string | null }

export interface Bot {
  id: string;
  name: string;
  label: string;
  description: string;
  instructions: string;
  avatarShape: AvatarShape;
  avatarColor: AvatarColor;
  mode: 'personal' | 'teammate';
  isPrimary: boolean;
  /** 'room' = group chat (members answer instead of the bot itself). */
  kind?: 'agent' | 'room';
  pinned: boolean;
  effort: 'low' | 'medium' | 'high' | 'xhigh';
  /** Voice chat settings: xAI voice id, speed multiplier (0.7–1.5), BCP-47 language or 'auto'. */
  voice: string;
  voiceSpeed: number;
  voiceLanguage: string;
  /** Hermes engine: model id and provider for this bot; null = the engine's default. */
  model: string | null;
  provider: string | null;
  /** Auto: a model from the person's list (Settings › Models) is chosen for each of this bot's jobs; `model` and `provider` are what it falls back to. */
  autoModel: boolean;
  /** How big this bot's jobs are: a 'light' job may run on a small machine, a 'heavy' one only where heavy jobs are taken. */
  weight: JobWeight;
  generation: number;
  unread: number;
  lastPreview: string | null;
  lastActivityAt: number;
  createdAt: number;
}

/** A transcript row. `seq` orders rows; `updatedSeq` is a per-bot revision counter (rows are rewritten while streaming). */
export interface Row {
  botId: string;
  generation: number;
  seq: number;
  updatedSeq: number;
  kind: RowKind;
  body: RowBody;
  createdAt: number;
}

export type RowKind = 'message' | 'tool-call' | 'card' | 'event' | 'notice';

export type RowBody =
  | MessageBody
  | ToolCallBody
  | CardBody
  | EventBody
  | NoticeBody;

export interface MessageBody {
  type: 'message';
  role: 'user' | 'assistant';
  text: string;
  streaming?: boolean;
  /** Set when a message came from another bot, a routine or a voice call rather than the user typing. */
  from?: { kind: 'bot' | 'routine' | 'voice'; id: string; name: string };
  citations?: { url: string; title?: string }[];
  /** The earlier message this one answers, quoted as it was when the reply was sent. */
  replyTo?: MessageQuote;
}

/** A short quote of an earlier message, for a reply. */
export interface MessageQuote {
  seq: number;
  role: 'user' | 'assistant';
  /** Who said it, when it was not the person or this Bot (another Bot, a routine). */
  name?: string;
  text: string;
}

export interface ToolCallBody {
  type: 'tool-call';
  callId: string;
  name: string;
  /** Human phrase, e.g. "Clicking on screen". */
  label: string;
  status: 'running' | 'done' | 'error' | 'awaiting-approval' | 'denied';
  args?: unknown;
  summary?: string;
  /** Data URL thumbnail for computer actions. */
  screenshot?: string;
}

export type CardBody =
  | {
      type: 'card';
      card: 'approval';
      approvalId: string;
      title: string;
      detail: string;
      tool: string;
      toolkit?: string;
      toolkitName?: string;
      logo?: string;
      args: unknown;
      status: 'pending' | 'allowed' | 'always' | 'denied' | 'expired';
    }
  | {
      type: 'card';
      card: 'question';
      question: string;
      options: string[];
      status: 'pending' | 'answered' | 'dismissed';
      answer?: string;
    }
  | {
      type: 'card';
      card: 'connect';
      toolkit: string;
      toolkitName: string;
      logo?: string;
      reason: string;
      status: 'pending' | 'connected' | 'skipped';
    }
  | {
      type: 'card';
      card: 'draft';
      channel: 'email' | 'slack' | 'message';
      to: string;
      subject?: string;
      text: string;
      approvalId: string;
      status: 'pending' | 'sent' | 'discarded';
    };

export interface EventBody {
  type: 'event';
  event: 'routine-created' | 'routine-updated' | 'routine-run' | 'task-changed' | 'bot-message' | 'conversation-cleared' | 'computer' | 'voice-call';
  text: string;
  ref?: string;
  /** voice-call only: the call record (set on the end-of-call row). */
  call?: VoiceCallRecord;
}

export interface VoiceCallRecord {
  id: string;
  phase: 'started' | 'ended';
  durationMs?: number;
  summary?: string;
  transcript?: { role: 'user' | 'assistant'; text: string }[];
  tasks?: string[];
}

export interface VoiceOption { id: string; name: string; gender?: string }

export interface NoticeBody { type: 'notice'; text: string; tone?: 'info' | 'warn' | 'error' }

export type ActivityKind =
  | 'thinking' | 'working' | 'searching_web' | 'reading_web' | 'browsing_web' | 'on_its_computer'
  | 'running_commands' | 'waiting_on_command' | 'messaging_named' | 'connecting_service'
  | 'waiting_for_you' | 'drafting_file' | 'remembering'
  /** No machine can take the job yet / the machine running it went away mid-job. `detail` says which and why. */
  | 'waiting_for_machine' | 'paused';

export interface LiveState {
  botId: string;
  isRunning: boolean;
  activity: { kind: ActivityKind; detail?: string; since: number } | null;
  awaiting: { reason: string; since: number } | null;
  startedAt: number | null;
  /** Short line from the model's live reasoning, shown next to "Thinking". */
  caption?: string | null;
  /** Tool calls made so far in the current turn. */
  steps?: number;
}

export interface Routine {
  id: string; botId: string; name: string; prompt: string; schedule: string; timezone: string;
  enabled: boolean; nextRunAt: number | null; lastRunAt: number | null; description: string;
}

export interface Task { id: string; botId: string; title: string; state: 'working' | 'needs_input' | 'done'; note: string; updatedAt: number }

export interface Memory { id: string; botId: string; text: string; createdAt: number; source?: 'chat' | 'learned' }

export interface Skill { id: string; botId: string; name: string; description: string; body: string; createdAt: number; updatedAt: number }

export type JobWeight = 'light' | 'heavy';

/** A phone, tablet or browser that is signed in. It runs no jobs. */
export interface Device {
  id: string;
  /** What it said it is, such as "iPhone · Safari". */
  name: string;
  kind: 'phone' | 'tablet' | 'computer';
  signedInAt: number;
  /** Null for a sign-in that has not been used since devices were first listed. */
  lastUsedAt: number | null;
  /** The device that asked. */
  current: boolean;
}

/** A machine that can run bots' jobs: the hub (where this server runs) or another one running the worker. */
export interface Machine {
  id: string;
  name: string;
  isHub: boolean;
  /** Which jobs it takes. */
  takes: JobWeight | 'both';
  /** Lower runs first when several machines could take a job. */
  rank: number;
  enabled: boolean;
  /** 'away' = it stopped checking in (asleep, off or offline); 'busy' = checking in but not taking jobs; 'never' = not seen yet. */
  state: 'online' | 'busy' | 'away' | 'never';
  /** Why it is not taking jobs, when it said. */
  reason: string | null;
  lastSeenAt: number | null;
  platform: string | null;
  memFreeMb: number | null;
  memTotalMb: number | null;
  /** Jobs it runs at once (0 = no limit) and how many it is running now. */
  slots: number;
  running: number;
}

/** A job that has not finished: waiting for a machine, running, or paused because its machine went away. */
export interface JobInfo {
  id: string;
  botId: string;
  botName: string;
  title: string;
  weight: JobWeight;
  status: 'queued' | 'waiting' | 'running' | 'paused';
  machineId: string | null;
  note: string | null;
  /** What Auto chose for it, when its bot is on Auto. */
  choice: JobChoice | null;
  createdAt: number;
}

/** A model Auto can choose for a job, with the notes the choice is made from. */
export interface RosterModel {
  id: string;
  label: string;
  provider: string;
  model: string;
  /** What it is good and bad at, in the person's own words. */
  note: string;
  /** The machines that can run it, by id; empty = any machine. */
  machines: string[];
}

/** What Auto decided for one job. */
export interface JobChoice {
  /** 'jev' = Jev chose the model; 'bot' = the Bot's own model is used, because Jev was unsure or could not be asked. */
  by: 'jev' | 'bot';
  /** The chosen model; all null when the Bot's own is used. */
  label: string | null;
  provider: string | null;
  model: string | null;
  weight: JobWeight;
  /** The machine Jev would run it on, when it named one that can. */
  machineId: string | null;
  /** The machines the chosen model runs on; empty = any. */
  runsOn: string[];
  /** How sure Jev was of the model, 0 to 1. */
  confidence: number | null;
  /** One line for the person. */
  why: string;
}

/** A job Auto chose for, newest first in Settings › Models. */
export interface RecentChoice { jobId: string; botName: string; title: string; at: number; machineName: string | null; choice: JobChoice }

/** Settings › Models, the Auto part: the models to choose from and how the choosing has gone. */
export interface AutoInfo {
  roster: RosterModel[];
  /** How sure Jev has to be of a choice (0 to 1) before it is used. */
  minConfidence: number;
  /** Why Jev cannot be asked right now, or null. */
  blocked: string | null;
  /** The providers a model can come from, as the Bots' agents report them. Empty while no agent is up. */
  providers: EngineProvider[];
  machines: { id: string; name: string }[];
  recent: RecentChoice[];
}

export interface LibraryFile { name: string; size: number; modifiedAt: number; isDir: boolean }

export interface ComputerState {
  status: 'absent' | 'creating' | 'starting' | 'running' | 'suspending' | 'suspended' | 'resuming' | 'error' | 'stopped';
  /** Which computer provider the screens come from (`orgo`, `local`). */
  provider?: string;
  /** The computer's id at that provider. */
  orgoId: string | null;
  error: string | null;
  /** One screen per bot on the shared computer. width/height are the screen's pixel size. */
  screens: { botId: string; screenId: string; width?: number; height?: number }[];
  /** How many bots can have their own screen at once. */
  maxScreens?: number;
}

/** A model provider Hermes can use (subscription sign-in or API key). */
export interface EngineProvider {
  id: string;
  name: string;
  kind: 'subscription' | 'api_key';
  connected: boolean;
  /** Suggested model ids for this provider. */
  models: string[];
}

/** An OpenAI-compatible endpoint of the person's own (9Router, OmniRoute, LiteLLM, Ollama…), added in Settings. */
export interface ModelEndpoint {
  id: string;
  name: string;
  baseUrl: string;
  models: string[];
  hasKey: boolean;
}

/** Settings › Models: what the person's Bots can reach, and what can be added. */
export interface ModelAccessInfo {
  /** Providers a key can be added for. `saved`: a key was added here. `connected`: the Bots' agents can reach it (a key from here or one the computer already had). */
  keys: { id: string; name: string; saved: boolean; connected: boolean }[];
  subscriptions: { id: string; name: string; note: string; connected: boolean }[];
  endpoints: ModelEndpoint[];
  /** Why a subscription cannot be signed in to from here, or null. */
  signInBlocked: string | null;
  /** False while no agent has answered, so `connected` is not known yet. */
  checked: boolean;
}

/** The Hermes agent that runs one bot. */
export interface EngineInfo {
  botId: string;
  /** Hermes profile name ('default' for the primary bot). */
  profile: string;
  status: 'ready' | 'starting' | 'stopped' | 'error';
  error: string | null;
  model: string | null;
  provider: string | null;
  version: string | null;
  providers: EngineProvider[];
  /** Hermes is running a private screen of its own for this agent, so its computer work is not on the screen the app shows. */
  ownScreen: boolean;
}

export interface Toolkit {
  slug: string; name: string; logo?: string; description?: string; categories: string[];
  managedAuth: boolean; noAuth: boolean; connected: boolean;
  /** `direct` = the app's own MCP server, signed in to here; `composio` = through Composio. */
  source?: 'direct' | 'composio';
  /** Direct apps: how you sign in. `api_key` asks for a key instead of opening a sign-in page. */
  auth?: 'oauth' | 'api_key' | 'none';
  /** Direct apps: an MCP server the person added themselves. */
  custom?: boolean;
  /** Connected direct apps: the bots it is switched on for. */
  bots?: string[];
}

/**
 * A request a Bot made that waits for the person: everything a phone or the Mac app needs to show
 * it and answer it, without the conversation it sits in.
 */
export interface Approval {
  id: string;
  botId: string;
  botName: string;
  /** "Allow Scout to send an email?" */
  title: string;
  /** What it will do, a line per argument. */
  detail: string;
  /** The tool's name, such as gmail_send_email. */
  tool: string;
  toolkitName?: string;
  args: unknown;
  /** The row in the conversation that shows it. */
  rowSeq: number;
  status: 'pending' | 'allowed' | 'always' | 'denied' | 'expired';
  createdAt: number;
  /** When it stops waiting; after that it is expired whatever happens. */
  expiresAt: number;
  resolvedAt: number | null;
  /** The device that answered it, such as "MiBot app on Android (CPH2655)". */
  resolvedBy: string | null;
}

/** Server-sent events on GET /api/events */
export type ServerEvent =
  | { type: 'hello'; serverTime: number }
  | { type: 'rows'; botId: string; generation: number; rows: Row[] }
  | { type: 'cleared'; botId: string; generation: number }
  | { type: 'live'; state: LiveState }
  | { type: 'roster' }
  | { type: 'bot'; bot: Bot }
  | { type: 'computer'; state: ComputerState }
  | { type: 'routines'; botId: string }
  | { type: 'tasks'; botId: string }
  | { type: 'memories'; botId: string }
  | { type: 'connections' }
  | { type: 'machines' }
  /** One approval changed: it was asked for, answered or ran out. Each device updates its copy. */
  | { type: 'approval'; approval: Approval }
  | { type: 'ping' };
