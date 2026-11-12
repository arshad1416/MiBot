import { env } from '../../env.js';
import type { ConnectorTool } from '../store.js';

/**
 * Google Workspace, connected straight to Google's own APIs: Gmail, Calendar and Sheets.
 * Google has no hosted MCP server for these, so the tools are written here. Sign-in uses your own
 * Google Cloud OAuth client (GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET, type "Web application",
 * with this server's /api/apps/oauth/callback as an authorised redirect URI).
 */

export const GOOGLE_SLUG = 'google_workspace';

const SCOPES = [
  'openid', 'email',
  'https://www.googleapis.com/auth/gmail.modify',
  'https://www.googleapis.com/auth/calendar',
  'https://www.googleapis.com/auth/spreadsheets',
];

export interface GoogleTokens { access_token: string; refresh_token?: string; expires_at: number }

export const googleConfigured = () => !!(env.googleClientId && env.googleClientSecret);

export function googleAuthUrl(state: string, redirectUri: string): string {
  const u = new URL('https://accounts.google.com/o/oauth2/v2/auth');
  u.search = new URLSearchParams({
    client_id: env.googleClientId, redirect_uri: redirectUri, response_type: 'code', scope: SCOPES.join(' '),
    access_type: 'offline', prompt: 'consent', include_granted_scopes: 'true', state,
  }).toString();
  return u.toString();
}

async function tokenRequest(params: Record<string, string>): Promise<GoogleTokens> {
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: env.googleClientId, client_secret: env.googleClientSecret, ...params }),
  });
  const j: any = await res.json().catch(() => ({}));
  if (!res.ok || !j.access_token) throw new Error(`Google sign-in failed: ${j.error_description ?? j.error ?? res.status}`);
  return { access_token: j.access_token, refresh_token: j.refresh_token, expires_at: Date.now() + (Number(j.expires_in) || 3600) * 1000 };
}

export const googleExchange = (code: string, redirectUri: string) => tokenRequest({ grant_type: 'authorization_code', code, redirect_uri: redirectUri });

/** A token that is good for at least another minute; `save` is told when it had to be renewed. */
export async function googleFresh(tokens: GoogleTokens, save: (t: GoogleTokens) => void): Promise<string> {
  if (tokens.expires_at - Date.now() > 60_000) return tokens.access_token;
  if (!tokens.refresh_token) throw new Error('The Google sign-in has expired. Connect Google Workspace again.');
  const next = { ...(await tokenRequest({ grant_type: 'refresh_token', refresh_token: tokens.refresh_token })), refresh_token: tokens.refresh_token };
  save(next);
  return next.access_token;
}

// ---------------------------------------------------------------- the tools

const str = (description: string) => ({ type: 'string', description });
const obj = (properties: Record<string, unknown>, required: string[] = []) => ({ type: 'object', properties, required });
const tool = (name: string, description: string, inputSchema: unknown, readOnly: boolean): ConnectorTool => ({ name, description, inputSchema, readOnly });

const WHEN = 'RFC 3339 date-time with offset, e.g. 2026-03-01T09:00:00-05:00; or a date (2026-03-01) for an all-day event';
const VALUES = { type: 'array', items: { type: 'array', items: {} }, description: 'Rows of cells, e.g. [["Name","Total"],["Ada",12]]' };

export const GOOGLE_TOOLS: ConnectorTool[] = [
  tool('gmail_search', 'Search Gmail with Gmail search syntax (from:, to:, subject:, is:unread, newer_than:7d, has:attachment). Returns matching messages with sender, subject, date and a snippet.', obj({ query: str('Gmail search query'), max_results: { type: 'integer', description: '1 to 50, default 10' } }, ['query']), true),
  tool('gmail_read', 'Read one Gmail message in full: headers and the text of the body.', obj({ message_id: str('Message id from gmail_search') }, ['message_id']), true),
  tool('gmail_send', 'Send an email from the connected Gmail account. To reply inside a conversation, pass thread_id and reply_to_message_id.', obj({ to: str('Recipients, comma separated'), subject: str('Subject'), body: str('Plain-text body'), cc: str('Cc, comma separated'), bcc: str('Bcc, comma separated'), thread_id: str('Thread to reply in'), reply_to_message_id: str('Message being replied to') }, ['to', 'subject', 'body']), false),
  tool('gmail_create_draft', 'Save an email as a draft without sending it.', obj({ to: str('Recipients, comma separated'), subject: str('Subject'), body: str('Plain-text body'), cc: str('Cc, comma separated'), thread_id: str('Thread to reply in') }, ['to', 'subject', 'body']), false),
  tool('gmail_modify_labels', 'Add or remove labels on a message. Removing INBOX archives it; removing UNREAD marks it read; adding TRASH moves it to the bin.', obj({ message_id: str('Message id'), add: { type: 'array', items: { type: 'string' } }, remove: { type: 'array', items: { type: 'string' } } }, ['message_id']), false),
  tool('calendar_list_events', 'List events on a Google calendar between two times, soonest first.', obj({ time_min: str(WHEN), time_max: str(WHEN), query: str('Only events matching this text'), calendar_id: str('Calendar id, default "primary"'), max_results: { type: 'integer', description: '1 to 100, default 25' } }, ['time_min', 'time_max']), true),
  tool('calendar_create_event', 'Create a calendar event. Attendees are emailed an invitation.', obj({ summary: str('Title'), start: str(WHEN), end: str(WHEN), description: str('Details'), location: str('Where'), attendees: { type: 'array', items: { type: 'string' }, description: 'Email addresses to invite' }, calendar_id: str('Calendar id, default "primary"') }, ['summary', 'start', 'end']), false),
  tool('calendar_update_event', 'Change fields of an existing calendar event. Only the fields you pass are changed.', obj({ event_id: str('Event id from calendar_list_events'), summary: str('Title'), start: str(WHEN), end: str(WHEN), description: str('Details'), location: str('Where'), attendees: { type: 'array', items: { type: 'string' } }, calendar_id: str('Calendar id, default "primary"') }, ['event_id']), false),
  tool('calendar_delete_event', 'Delete a calendar event. Attendees are told it was cancelled.', obj({ event_id: str('Event id'), calendar_id: str('Calendar id, default "primary"') }, ['event_id']), false),
  tool('sheets_metadata', 'The title of a Google Sheet and the names and sizes of its tabs.', obj({ spreadsheet_id: str('The id in the sheet\'s URL') }, ['spreadsheet_id']), true),
  tool('sheets_get_values', 'Read a range of cells from a Google Sheet.', obj({ spreadsheet_id: str('The id in the sheet\'s URL'), range: str('A1 notation, e.g. Sheet1!A1:D20') }, ['spreadsheet_id', 'range']), true),
  tool('sheets_update_values', 'Overwrite a range of cells in a Google Sheet.', obj({ spreadsheet_id: str('The id in the sheet\'s URL'), range: str('A1 notation of the top-left cell or range'), values: VALUES }, ['spreadsheet_id', 'range', 'values']), false),
  tool('sheets_append_values', 'Add rows after the last row of a table in a Google Sheet.', obj({ spreadsheet_id: str('The id in the sheet\'s URL'), range: str('A1 notation of the table, e.g. Sheet1!A:D'), values: VALUES }, ['spreadsheet_id', 'range', 'values']), false),
  tool('sheets_create', 'Create a new, empty Google Sheet and return its id and link.', obj({ title: str('Name of the new sheet') }, ['title']), false),
];

async function api(token: string, method: string, url: string, body?: unknown): Promise<any> {
  const res = await fetch(url, {
    method,
    headers: { Authorization: `Bearer ${token}`, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (res.status === 204) return {};
  const j: any = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`Google answered ${res.status}: ${j?.error?.message ?? res.statusText}`);
  return j;
}

const GMAIL = 'https://gmail.googleapis.com/gmail/v1/users/me';
const CAL = 'https://www.googleapis.com/calendar/v3/calendars';
const SHEETS = 'https://sheets.googleapis.com/v4/spreadsheets';
const enc = encodeURIComponent;
const clamp = (v: unknown, lo: number, hi: number, d: number) => Math.min(hi, Math.max(lo, Number.isFinite(Number(v)) && v !== undefined && v !== null && v !== '' ? Math.floor(Number(v)) : d));

const header = (msg: any, name: string): string => (msg?.payload?.headers ?? []).find((h: any) => String(h.name).toLowerCase() === name.toLowerCase())?.value ?? '';

/** The readable text of a message: the first text/plain part, else text/html with the tags taken out. */
function bodyText(payload: any): string {
  const parts: any[] = [];
  const walk = (p: any) => { if (!p) return; parts.push(p); (p.parts ?? []).forEach(walk); };
  walk(payload);
  const decode = (p: any) => Buffer.from(String(p?.body?.data ?? ''), 'base64url').toString('utf8');
  const plain = parts.find((p) => p.mimeType === 'text/plain' && p.body?.data);
  if (plain) return decode(plain);
  const html = parts.find((p) => p.mimeType === 'text/html' && p.body?.data);
  return html ? decode(html).replace(/<(style|script)[\s\S]*?<\/\1>/gi, '').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/[ \t]+/g, ' ').replace(/\n\s*\n+/g, '\n\n').trim() : '';
}

/** A header value may not carry a line break, or a subject could smuggle in extra headers. */
const oneLine = (s: unknown) => String(s ?? '').replace(/[\r\n]+/g, ' ').trim();
const encodeHeader = (s: string) => (/^[\x20-\x7e]*$/.test(s) ? s : `=?UTF-8?B?${Buffer.from(s, 'utf8').toString('base64')}?=`);

function rawMessage(a: any, replyTo?: { messageId: string; references: string }): string {
  const lines = [
    `To: ${oneLine(a.to)}`,
    ...(a.cc ? [`Cc: ${oneLine(a.cc)}`] : []),
    ...(a.bcc ? [`Bcc: ${oneLine(a.bcc)}`] : []),
    `Subject: ${encodeHeader(oneLine(a.subject))}`,
    ...(replyTo?.messageId ? [`In-Reply-To: ${replyTo.messageId}`, `References: ${`${replyTo.references} ${replyTo.messageId}`.trim()}`] : []),
    'MIME-Version: 1.0',
    'Content-Type: text/plain; charset="UTF-8"',
    'Content-Transfer-Encoding: base64',
    '',
    Buffer.from(String(a.body ?? ''), 'utf8').toString('base64').replace(/(.{76})/g, '$1\r\n'),
  ];
  return Buffer.from(lines.join('\r\n'), 'utf8').toString('base64url');
}

const when = (v: unknown) => (/^\d{4}-\d{2}-\d{2}$/.test(String(v)) ? { date: String(v) } : { dateTime: String(v) });
const eventOut = (e: any) => ({ id: e.id, summary: e.summary, start: e.start?.dateTime ?? e.start?.date, end: e.end?.dateTime ?? e.end?.date, location: e.location, attendees: (e.attendees ?? []).map((x: any) => x.email), link: e.htmlLink, status: e.status });

function eventBody(a: any): Record<string, unknown> {
  return {
    ...(a.summary !== undefined ? { summary: String(a.summary) } : {}),
    ...(a.description !== undefined ? { description: String(a.description) } : {}),
    ...(a.location !== undefined ? { location: String(a.location) } : {}),
    ...(a.start !== undefined ? { start: when(a.start) } : {}),
    ...(a.end !== undefined ? { end: when(a.end) } : {}),
    ...(Array.isArray(a.attendees) ? { attendees: a.attendees.map((email: unknown) => ({ email: String(email) })) } : {}),
  };
}

/** Run one Google tool with a valid access token. The result is what the bot reads. */
export async function googleCall(token: string, name: string, a: any): Promise<unknown> {
  const cal = `${CAL}/${enc(String(a.calendar_id || 'primary'))}`;
  const sheet = `${SHEETS}/${enc(String(a.spreadsheet_id ?? ''))}`;
  switch (name) {
    case 'gmail_search': {
      const list = await api(token, 'GET', `${GMAIL}/messages?q=${enc(String(a.query ?? ''))}&maxResults=${clamp(a.max_results, 1, 50, 10)}`);
      const ids: string[] = (list.messages ?? []).map((m: any) => m.id);
      const messages = await Promise.all(ids.map((id) => api(token, 'GET', `${GMAIL}/messages/${id}?format=metadata&metadataHeaders=From&metadataHeaders=To&metadataHeaders=Subject&metadataHeaders=Date`)));
      return messages.map((m) => ({ id: m.id, thread_id: m.threadId, from: header(m, 'From'), to: header(m, 'To'), subject: header(m, 'Subject'), date: header(m, 'Date'), snippet: m.snippet, labels: m.labelIds }));
    }
    case 'gmail_read': {
      const m = await api(token, 'GET', `${GMAIL}/messages/${enc(String(a.message_id))}?format=full`);
      return { id: m.id, thread_id: m.threadId, from: header(m, 'From'), to: header(m, 'To'), cc: header(m, 'Cc'), subject: header(m, 'Subject'), date: header(m, 'Date'), labels: m.labelIds, body: bodyText(m.payload).slice(0, 50000) };
    }
    case 'gmail_send':
    case 'gmail_create_draft': {
      let replyTo: { messageId: string; references: string } | undefined;
      if (a.reply_to_message_id) {
        const orig = await api(token, 'GET', `${GMAIL}/messages/${enc(String(a.reply_to_message_id))}?format=metadata&metadataHeaders=Message-ID&metadataHeaders=References`);
        replyTo = { messageId: header(orig, 'Message-ID'), references: header(orig, 'References') };
      }
      const message = { raw: rawMessage(a, replyTo), ...(a.thread_id ? { threadId: String(a.thread_id) } : {}) };
      if (name === 'gmail_create_draft') {
        const d = await api(token, 'POST', `${GMAIL}/drafts`, { message });
        return { draft_id: d.id, message_id: d.message?.id };
      }
      const sent = await api(token, 'POST', `${GMAIL}/messages/send`, message);
      return { sent: true, message_id: sent.id, thread_id: sent.threadId };
    }
    case 'gmail_modify_labels': {
      const m = await api(token, 'POST', `${GMAIL}/messages/${enc(String(a.message_id))}/modify`, { addLabelIds: Array.isArray(a.add) ? a.add : [], removeLabelIds: Array.isArray(a.remove) ? a.remove : [] });
      return { id: m.id, labels: m.labelIds };
    }
    case 'calendar_list_events': {
      const q = new URLSearchParams({ timeMin: String(a.time_min), timeMax: String(a.time_max), singleEvents: 'true', orderBy: 'startTime', maxResults: String(clamp(a.max_results, 1, 100, 25)), ...(a.query ? { q: String(a.query) } : {}) });
      return ((await api(token, 'GET', `${cal}/events?${q}`)).items ?? []).map(eventOut);
    }
    case 'calendar_create_event':
      return eventOut(await api(token, 'POST', `${cal}/events?sendUpdates=all`, eventBody(a)));
    case 'calendar_update_event':
      return eventOut(await api(token, 'PATCH', `${cal}/events/${enc(String(a.event_id))}?sendUpdates=all`, eventBody(a)));
    case 'calendar_delete_event':
      await api(token, 'DELETE', `${cal}/events/${enc(String(a.event_id))}?sendUpdates=all`);
      return { deleted: true };
    case 'sheets_metadata': {
      const s = await api(token, 'GET', `${sheet}?fields=properties.title,spreadsheetUrl,sheets.properties(title,gridProperties)`);
      return { title: s.properties?.title, link: s.spreadsheetUrl, tabs: (s.sheets ?? []).map((t: any) => ({ name: t.properties?.title, rows: t.properties?.gridProperties?.rowCount, columns: t.properties?.gridProperties?.columnCount })) };
    }
    case 'sheets_get_values':
      return { range: a.range, values: (await api(token, 'GET', `${sheet}/values/${enc(String(a.range))}`)).values ?? [] };
    case 'sheets_update_values': {
      const r = await api(token, 'PUT', `${sheet}/values/${enc(String(a.range))}?valueInputOption=USER_ENTERED`, { values: a.values });
      return { updated_range: r.updatedRange, updated_cells: r.updatedCells };
    }
    case 'sheets_append_values': {
      const r = await api(token, 'POST', `${sheet}/values/${enc(String(a.range))}:append?valueInputOption=USER_ENTERED&insertDataOption=INSERT_ROWS`, { values: a.values });
      return { updated_range: r.updates?.updatedRange, updated_rows: r.updates?.updatedRows };
    }
    case 'sheets_create': {
      const s = await api(token, 'POST', SHEETS, { properties: { title: String(a.title) } });
      return { spreadsheet_id: s.spreadsheetId, link: s.spreadsheetUrl };
    }
    default:
      throw new Error(`Unknown Google tool ${name}`);
  }
}
