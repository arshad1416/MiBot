/** One event of a Hermes agent's /v1/responses stream. */
export interface StreamEvent { type: string; data: any }

/** Parse a server-sent event stream into its JSON events. Keepalives and "[DONE]" are skipped. */
export async function* sseEvents(body: ReadableStream<Uint8Array>): AsyncGenerator<StreamEvent> {
  const dec = new TextDecoder();
  let buf = '';
  for await (const chunk of body as any as AsyncIterable<Uint8Array>) {
    buf = (buf + dec.decode(chunk, { stream: true })).replace(/\r\n/g, '\n');
    let end: number;
    while ((end = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, end);
      buf = buf.slice(end + 2);
      let event = '';
      const data: string[] = [];
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (!data.length) continue; // a keepalive comment
      const raw = data.join('\n');
      if (raw === '[DONE]') continue;
      try {
        const json = JSON.parse(raw);
        yield { type: event || json?.type || '', data: json };
      } catch { /* not ours to understand */ }
    }
  }
}
