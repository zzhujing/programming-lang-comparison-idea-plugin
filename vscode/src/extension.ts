import * as vscode from 'vscode';
import { chat, estimateTokens, extractCodeBlock, hash } from './llm';

const CONFIG_SECTION = 'langcompare';
const API_KEY_SECRET = 'langcompare.apiKey';
const UI_REFRESH_INTERVAL_MS = 120;
const CACHE_MAX_ENTRIES = 64;
const THREAD_CONTEXT = 'langcompareThread';

const LANGUAGE_NAMES: Record<string, string> = {
  python: 'Python', java: 'Java', go: 'Go', kotlin: 'Kotlin', typescript: 'TypeScript',
  javascript: 'JavaScript', c: 'C', cpp: 'C++', csharp: 'C#', rust: 'Rust', ruby: 'Ruby',
  php: 'PHP', swift: 'Swift', scala: 'Scala', dart: 'Dart', shellscript: 'Shell',
  sql: 'SQL', html: 'HTML', css: 'CSS', xml: 'XML', yaml: 'YAML', json: 'JSON',
};

function detectSourceLanguage(editor: vscode.TextEditor): string {
  const id = editor.document.languageId;
  return LANGUAGE_NAMES[id] ?? id ?? 'the current';
}

interface Config {
  baseUrl: string;
  model: string;
  targetLanguage: string;
  sourceLanguage: string;
  autoTranslate: boolean;
  debounceMillis: number;
  streaming: boolean;
  disableThinking: boolean;
  maxInputTokens: number;
}

function readConfig(): Config {
  const c = vscode.workspace.getConfiguration(CONFIG_SECTION);
  return {
    baseUrl: (c.get<string>('baseUrl') ?? '').trim(),
    model: (c.get<string>('model') ?? '').trim() || 'gpt-4o-mini',
    targetLanguage: (c.get<string>('targetLanguage') ?? '').trim() || 'Java',
    sourceLanguage: (c.get<string>('sourceLanguage') ?? '').trim(),
    autoTranslate: c.get<boolean>('autoTranslate') ?? true,
    debounceMillis: c.get<number>('debounceMillis') ?? 500,
    streaming: c.get<boolean>('streaming') ?? true,
    disableThinking: c.get<boolean>('disableThinking') ?? true,
    maxInputTokens: c.get<number>('maxInputTokens') ?? 4000,
  };
}

function systemPrompt(source: string, target: string): string {
  return 'You are an expert polyglot programmer. Translate the user\'s ' + source + ' code into idiomatic ' + target +
    ' code. Preserve names, structure and intent as closely as the target language allows. ' +
    'Do NOT include any comments in the output: drop all comments from the source code and emit pure code only. ' +
    'Reply with ONLY one fenced code block containing the translated code, without any explanations.';
}

function userPrompt(source: string, target: string, code: string): string {
  return 'Translate the following ' + source + ' code to ' + target + '.\n\n```' + source + '\n' + code + '\n```';
}

function describeError(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/** Port of the JetBrains plugin's TranslationManager orchestration.
 * Results render as an expanded comment thread anchored below the selection's last line
 * (VS Code has no block inlays, and multiline decoration content is unsupported). */
export function activate(context: vscode.ExtensionContext): void {
  const controller = new TranslationController(context);
  context.subscriptions.push(
    vscode.commands.registerCommand('langcompare.translateSelection', () => controller.translateManual()),
    vscode.commands.registerCommand('langcompare.clear', () => controller.clear()),
    vscode.commands.registerCommand('langcompare.setApiKey', () => controller.setApiKey()),
    vscode.commands.registerCommand('langcompare.openSettings', () => {
      void vscode.commands.executeCommand('workbench.action.openSettings', '@ext:' + context.extension.id);
    }),
    vscode.commands.registerCommand('langcompare.copyTranslation', () => controller.copyResult()),
    vscode.window.onDidChangeTextEditorSelection((event) => controller.onSelectionChanged(event)),
    controller,
  );
}

export function deactivate(): void {
  // Nothing to do: subscriptions dispose the controller, which aborts in-flight requests.
}

class TranslationController implements vscode.Disposable {
  private readonly comments = vscode.comments.createCommentController('langcompare', 'LangCompare');
  private thread: vscode.CommentThread | undefined;
  private debounceTimer: NodeJS.Timeout | undefined;
  private abort: AbortController | undefined;
  private readonly cache = new Map<string, string>();
  private lastResultText = '';

  constructor(private readonly context: vscode.ExtensionContext) {}

  dispose(): void {
    if (this.debounceTimer) clearTimeout(this.debounceTimer);
    this.abort?.abort();
    this.comments.dispose();
  }

  async setApiKey(): Promise<void> {
    const current = (await this.context.secrets.get(API_KEY_SECRET)) ?? '';
    const value = await vscode.window.showInputBox({
      prompt: 'API key for the OpenAI-format service (stored in VS Code SecretStorage, never in settings.json)',
      password: true,
      value: current,
    });
    if (value === undefined) return; // cancelled
    if (value.trim()) {
      await this.context.secrets.store(API_KEY_SECRET, value.trim());
    } else {
      await this.context.secrets.delete(API_KEY_SECRET);
    }
  }

  copyResult(): void {
    if (!this.lastResultText) {
      void vscode.window.showInformationMessage('No translation to copy yet.');
      return;
    }
    void vscode.env.clipboard.writeText(this.lastResultText);
    void vscode.window.showInformationMessage('Translation copied to clipboard.');
  }

  onSelectionChanged(event: vscode.TextEditorSelectionChangeEvent): void {
    const editor = event.textEditor;
    const scheme = editor.document.uri.scheme;
    if (scheme !== 'file' && scheme !== 'untitled') return;
    const cfg = readConfig();
    if (!cfg.autoTranslate) return;
    if (editor.selection.isEmpty) {
      this.clear();
      return;
    }
    if (this.debounceTimer) clearTimeout(this.debounceTimer);
    this.debounceTimer = setTimeout(() => {
      this.debounceTimer = undefined;
      void this.translate(editor);
    }, Math.min(Math.max(cfg.debounceMillis, 200), 10_000));
  }

  translateManual(): void {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.selection.isEmpty) {
      void vscode.window.showInformationMessage('Select some code first.');
      return;
    }
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer);
      this.debounceTimer = undefined;
    }
    void this.translate(editor);
  }

  clear(): void {
    this.abort?.abort();
    this.abort = undefined;
    this.disposeThread();
    this.lastResultText = '';
  }

  private disposeThread(): void {
    this.thread?.dispose();
    this.thread = undefined;
  }

  private async translate(editor: vscode.TextEditor): Promise<void> {
    const text = editor.document.getText(editor.selection).trim();
    if (!text) return;
    const cfg = readConfig();
    const apiKey = (await this.context.secrets.get(API_KEY_SECRET)) ?? '';

    // A new translation always supersedes the previous one.
    this.abort?.abort();
    const abort = new AbortController();
    this.abort = abort;

    const source = cfg.sourceLanguage || detectSourceLanguage(editor);
    const target = cfg.targetLanguage;

    this.disposeThread();
    this.thread = this.createThread(editor, target);
    this.paint('', true, null, target);

    if (!cfg.baseUrl || !apiKey) {
      this.paint('', false,
        'LLM is not configured — run "LangCompare: Open Settings" for Base URL/Model, and "LangCompare: Set API Key" for the key.', target);
      return;
    }

    if (cfg.maxInputTokens > 0) {
      const estimated = estimateTokens(systemPrompt(source, target) + userPrompt(source, target, text));
      if (estimated > cfg.maxInputTokens) {
        this.paint('', false,
          'Selection too large: ~' + estimated + ' input tokens (limit ' + cfg.maxInputTokens + '). ' +
          'Select less code or raise langcompare.maxInputTokens.', target);
        return;
      }
    }

    const cacheKey = cfg.model + '|' + source + '|' + target + '|' + text.length + '|' + hash(text);
    const cached = this.cache.get(cacheKey);
    if (cached !== undefined) {
      this.paint(extractCodeBlock(cached), false, null, target);
      return;
    }

    let buffer = '';
    let lastPaint = 0;
    try {
      const full = await chat({
        baseUrl: cfg.baseUrl,
        apiKey,
        model: cfg.model,
        systemPrompt: systemPrompt(source, target),
        userPrompt: userPrompt(source, target, text),
        streaming: cfg.streaming,
        disableThinking: cfg.disableThinking,
        signal: abort.signal,
        onDelta: (delta) => {
          buffer += delta;
          const now = Date.now();
          if (now - lastPaint < UI_REFRESH_INTERVAL_MS) return;
          lastPaint = now;
          this.paint(extractCodeBlock(buffer), true, null, target);
        },
      });
      if (abort.signal.aborted) return;
      if (full) this.putCache(cacheKey, full);
      this.paint(extractCodeBlock(full || buffer), false, null, target);
    } catch (err) {
      if (abort.signal.aborted) return;
      this.paint(extractCodeBlock(buffer), false, describeError(err), target);
    }
  }

  private createThread(editor: vscode.TextEditor, target: string): vscode.CommentThread {
    const line = Math.min(editor.selection.end.line, editor.document.lineCount - 1);
    const range = new vscode.Range(line, 0, line, editor.document.lineAt(line).text.length);
    const thread = this.comments.createCommentThread(editor.document.uri, range, []);
    thread.canReply = false;
    thread.collapsibleState = vscode.CommentThreadCollapsibleState.Expanded;
    thread.contextValue = THREAD_CONTEXT;
    thread.label = '→ ' + target;
    return thread;
  }

  private paint(text: string, running: boolean, error: string | null, target: string): void {
    const thread = this.thread;
    if (!thread) return;
    const status = error ? 'error' : running ? 'translating…' : '';
    thread.label = '→ ' + target + (status ? '  (' + status + ')' : '');

    const body = new vscode.MarkdownString();
    if (error) {
      body.appendText('ERROR: ' + error);
    } else {
      // Always emit a closed fence; streamed partials would otherwise leave one open.
      body.appendCodeblock(text || (running ? '…' : '(empty response)'), target.toLowerCase());
    }
    if (!error && text) this.lastResultText = text;
    // vscode.Comment is an interface — a plain object satisfies it (mode/author are required fields).
    thread.comments = [{
      body,
      mode: vscode.CommentMode.Preview,
      author: { name: 'LangCompare' },
    }];
  }

  private putCache(key: string, value: string): void {
    if (this.cache.size >= CACHE_MAX_ENTRIES) {
      const oldest = this.cache.keys().next().value;
      if (oldest !== undefined) this.cache.delete(oldest);
    }
    this.cache.set(key, value);
  }
}
