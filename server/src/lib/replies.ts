import type { MessageBody, MessageQuote } from '../types.js';

const QUOTE_CHARS = 280;
const AGENT_QUOTE_CHARS = 1200;

const clip = (s: string, n: number) => {
  const t = s.replace(/\s+/g, ' ').trim();
  return t.length > n ? `${t.slice(0, n - 1)}…` : t;
};

/** What a reply keeps of the message it answers. */
export function quoteOf(seq: number, m: MessageBody): MessageQuote {
  return { seq, role: m.role, ...(m.from && m.from.kind !== 'voice' ? { name: m.from.name } : {}), text: clip(m.text, QUOTE_CHARS) };
}

/**
 * The person's words as the agent gets them, with the message they answer named first. `full` is the
 * whole earlier message; the stored quote is shortened for the chat.
 */
export function withQuote(text: string, q: MessageQuote, full = q.text): string {
  const whose = q.name ? `${q.name}'s message` : q.role === 'assistant' ? 'your earlier message' : 'my earlier message';
  return `[Replying to ${whose}: "${clip(full, AGENT_QUOTE_CHARS)}"]\n\n${text}`;
}
