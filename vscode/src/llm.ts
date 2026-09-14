/**
 * Minimal OpenAI-format chat-completions client with optional SSE streaming.
 * Port of the JetBrains plugin's dev.lazylittle.langcompare.llm.LlmClient.
 */

interface ChatMessage {
  role: 'system' | 'user';
  content: string;
}

interface ChatChunk {
  error?: { message?: string } | string;
  choices?: Array<{
    delta?: { content?: string | null };
    text?: string | null;
    message?: { content?: string | null };
  }>;
}

export interface ChatOptions {
  baseUrl: string;
  apiKey: string;
  model: string;
  systemPrompt: string;
  userPrompt: string;
  streaming: boolean;
  disableThinking: boolean;
  signal: AbortSignal;
  onDelta: (delta: string) => void;
}

export async function chat(options: ChatOptions): Promise<string> {
  const url = options.baseUrl.trim().replace(/\/+$/, '') + '/chat/completions';
  const body: Record<string, unknown> = {
    model: options.model,
    stream: options.streaming,
    messages: [
      { role: 'system', content: options.systemPrompt },
      { role: 'user', content: options.userPrompt },
    ] satisfies ChatMessage[],
  };
  // "thinking" is passed directly in the OpenAI-format body; only sent when enabled
  // because strict providers reject unknown fields.
  if (options.disableThinking) {
    body.thinking = { type: 'disabled' };
  }

  const response = await fetch(url, {
    method: 'POST',
    signal: options.signal,
    headers: {
      'Content-Type': 'application/json',
      ...(options.apiKey.trim() ? { Authorization: `Bearer ${options.apiKey.trim()}` } : {}),
      ...(options.streaming ? { Accept: 'text/event-stream' } : {}),
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(`HTTP ${response.status}: ${text.slice(0, 500)}`);
  }

  if (!options.streaming) {
    const root = (await response.json()) as ChatChunk;
    throwIfApiError(root);
    const content = root.choices?.[0]?.message?.content;
    return typeof content === 'string' ? content : '';
  }

  if (!response.body) {
    throw new Error('Empty response body');
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let full = '';
  let finished = false;
  while (!finished) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let newlineIndex: number;
    while ((newlineIndex = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, newlineIndex).replace(/\r$/, '');
      buffer = buffer.slice(newlineIndex + 1);
      if (!line.startsWith('data:')) continue;
      const payload = line.slice(5).trim();
      if (!payload) continue;
      if (payload === '[DONE]') {
        finished = true;
        break;
      }
      let chunk: ChatChunk;
      try {
        chunk = JSON.parse(payload) as ChatChunk;
      } catch {
        continue;
      }
      throwIfApiError(chunk);
      const choice = chunk.choices?.[0];
      if (!choice) continue;
      const content = choice.delta?.content ?? choice.text ?? '';
      if (content) {
        full += content;
        options.onDelta(content);
      }
    }
  }
  return full;
}

function throwIfApiError(root: ChatChunk): void {
  if (!root.error) return;
  const message = typeof root.error === 'string' ? root.error : root.error.message;
  throw new Error(`API error: ${message ?? JSON.stringify(root.error).slice(0, 300)}`);
}

/** Strips a markdown code fence ("```lang ... ```") if present; returns raw text otherwise. */
export function extractCodeBlock(text: string): string {
  const t = text.trimStart();
  if (!t.startsWith('```')) return t.trimEnd();
  const firstNewline = t.indexOf('\n');
  if (firstNewline < 0) return '';
  let body = t.slice(firstNewline + 1);
  const trimmed = body.trimEnd();
  if (trimmed.endsWith('```')) {
    body = trimmed.slice(0, -3).trimEnd();
  }
  return body;
}

/** ASCII text is ~4 chars/token; CJK ~1 token/char. Good enough for a pre-flight size cap. */
export function estimateTokens(text: string): number {
  let ascii = 0;
  let nonAscii = 0;
  for (const ch of text) {
    if ((ch.codePointAt(0) ?? 0) < 128) ascii++;
    else nonAscii++;
  }
  return Math.max(1, Math.floor(ascii / 4) + nonAscii);
}

/** djb2 string hash (base36) — part of the cache key, same idea as Kotlin's text.hashCode(). */
export function hash(text: string): string {
  let h = 5381;
  for (let i = 0; i < text.length; i++) {
    h = ((h << 5) + h + text.charCodeAt(i)) | 0;
  }
  return (h >>> 0).toString(36);
}
