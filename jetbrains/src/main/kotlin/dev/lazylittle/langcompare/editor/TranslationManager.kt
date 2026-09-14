package dev.lazylittle.langcompare.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.Alarm
import dev.lazylittle.langcompare.llm.LlmClient
import dev.lazylittle.langcompare.settings.LlmSettings
import dev.lazylittle.langcompare.ui.TranslationsPanel

/** Per-project orchestrator: selection -> LLM translation -> in-editor block inlays + tool window. */
@Service(Service.Level.PROJECT)
class TranslationManager(private val project: Project) : Disposable {

    class EditorState {
        val inlays = mutableListOf<Inlay<*>>()
        val cancellables = mutableListOf<LlmClient.Cancellable>()

        fun reset() {
            cancellables.forEach { runCatching { it.cancel() } }
            cancellables.clear()
            inlays.forEach { runCatching { it.dispose() } }
            inlays.clear()
        }

        companion object {
            val KEY: Key<EditorState> = Key.create("langcompare.editorState")
        }
    }

    var panel: TranslationsPanel? = null
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD)

    fun onSelectionChanged(editor: Editor) {
        val text = editor.selectionModel.selectedText?.trim().orEmpty()
        if (text.isEmpty()) {
            clearTranslations(editor)
            return
        }
        val settings = LlmSettings.instance
        if (!settings.state.autoTranslate) return
        if (settings.state.baseUrl.isBlank() || settings.apiKey.isNullOrBlank()) return
        alarm.cancelAllRequests()
        alarm.addRequest(
            Runnable { runTranslation(editor) },
            settings.state.debounceMillis.coerceIn(200, 10_000).toLong()
        )
    }

    fun runManual(editor: Editor) {
        alarm.cancelAllRequests()
        runTranslation(editor)
    }

    fun clearTranslations(editor: Editor) {
        stateFor(editor).reset()
        panel?.clearAll()
    }

    fun onDocumentChanged(editor: Editor) {
        alarm.cancelAllRequests()
        stateFor(editor).reset()
    }

    private fun stateFor(editor: Editor): EditorState =
        editor.getUserData(EditorState.KEY) ?: EditorState().also {
            editor.putUserData(EditorState.KEY, it)
        }

    private fun runTranslation(editor: Editor) {
        if (editor.isDisposed || project.isDisposed) return
        val text = editor.selectionModel.selectedText?.trim().orEmpty()
        if (text.isEmpty()) return
        val settings = LlmSettings.instance
        val baseUrl = settings.state.baseUrl.trim()
        val apiKey = settings.apiKey
        val state = stateFor(editor)
        state.reset()
        if (baseUrl.isEmpty() || apiKey.isNullOrBlank()) {
            addNoteInlay(
                editor, state, "settings",
                "LLM is not configured — open Settings → Tools → Language Comparison"
            )
            return
        }
        val source = settings.state.sourceLanguage.trim().ifEmpty { detectSourceLanguage(editor) }
        val target = settings.targetLanguage()
        val insertOffset = endOfSelectionLine(editor)
        panel?.startSession(source, target)

        val maxInputTokens = settings.state.maxInputTokens
        if (maxInputTokens > 0) {
            val estimated = estimateTokens(systemPrompt(source, target) + userPrompt(source, target, text))
            if (estimated > maxInputTokens) {
                addNoteInlay(
                    editor, state, target,
                    "Selection too large: ~$estimated input tokens (limit $maxInputTokens). " +
                        "Select less code or raise 'Max input tokens' in settings."
                )
                return
            }
        }

        val modelName = settings.state.model.trim().ifEmpty { "gpt-4o-mini" }
        val model = TargetModel(target)
        val renderer = TranslationInlayRenderer(editor, model)
        val inlay = editor.inlayModel.addBlockElement(insertOffset, false, false, 0, renderer)
        if (inlay != null) state.inlays.add(inlay)
        model.onUpdate = { scheduleRefresh(model, inlay) }

        val cacheKey = "$modelName|$source|$target|${text.length}|${text.hashCode()}"
        val cached = cacheGet(cacheKey)
        if (cached != null) {
            model.complete(cached)
            return
        }
        state.cancellables.add(
            LlmClient.chat(
                baseUrl = baseUrl,
                apiKey = apiKey,
                proxy = settings.state.proxy,
                model = modelName,
                systemPrompt = systemPrompt(source, target),
                userPrompt = userPrompt(source, target, text),
                streaming = settings.state.streaming,
                disableThinking = settings.state.disableThinking,
                onDelta = model::append,
                onSuccess = { full ->
                    if (full.isNotBlank()) cachePut(cacheKey, full)
                    model.complete(full)
                },
                onError = model::fail,
            )
        )
    }

    private fun scheduleRefresh(model: TargetModel, inlay: Inlay<*>?) {
        val finished = !model.running
        // Throttle streaming repaints (~UI_REFRESH_INTERVAL_MS); the final result always repaints.
        if (!finished && System.currentTimeMillis() - model.lastPaintAt < UI_REFRESH_INTERVAL_MS) return
        if (!model.refreshPending.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            model.lastPaintAt = System.currentTimeMillis()
            model.refreshPending.set(false)
            if (inlay != null && inlay.isValid) inlay.update()
            panel?.updateTarget(model)
        }, ModalityState.any())
    }

    private val resultCache = LinkedHashMap<String, String>()

    private fun cacheGet(key: String): String? = synchronized(resultCache) { resultCache[key] }

    private fun cachePut(key: String, value: String) {
        synchronized(resultCache) {
            if (resultCache.size >= CACHE_MAX_ENTRIES) {
                val it = resultCache.entries.iterator()
                it.next()
                it.remove()
            }
            resultCache[key] = value
        }
    }

    private fun addNoteInlay(editor: Editor, state: EditorState, target: String, message: String) {
        val model = TargetModel(target)
        model.fail(message)
        val inlay = editor.inlayModel.addBlockElement(
            endOfSelectionLine(editor), false, false, 0, TranslationInlayRenderer(editor, model)
        )
        if (inlay != null) state.inlays.add(inlay)
        panel?.updateTarget(model)
    }

    /** ASCII text is ~4 chars/token; CJK ~1 token/char. Good enough for a pre-flight size cap. */
    private fun estimateTokens(text: String): Int {
        var ascii = 0
        var nonAscii = 0
        for (c in text) if (c.code < 128) ascii++ else nonAscii++
        return (ascii / 4 + nonAscii).coerceAtLeast(1)
    }

    private fun detectSourceLanguage(editor: Editor): String {
        val psi = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        val name = psi?.language?.displayName ?: psi?.fileType?.displayName
        return name?.takeIf { it.isNotBlank() } ?: "the current"
    }

    private fun endOfSelectionLine(editor: Editor): Int {
        val document = editor.document
        val end = editor.selectionModel.selectionEnd.coerceAtMost(document.textLength)
        return document.getLineEndOffset(document.getLineNumber(end))
    }

    private fun systemPrompt(source: String, target: String): String =
        "You are an expert polyglot programmer. Translate the user's $source code into idiomatic $target code. " +
            "Preserve names, structure and intent as closely as the target language allows. " +
            "Do NOT include any comments in the output: drop all comments from the source code and emit pure code only. " +
            "Reply with ONLY one fenced code block containing the translated code, without any explanations."

    private fun userPrompt(source: String, target: String, code: String): String =
        "Translate the following $source code to $target.\n\n```$source\n$code\n```"

    override fun dispose() {
        runCatching { alarm.dispose() }
    }

    private companion object {
        const val UI_REFRESH_INTERVAL_MS = 120L
        const val CACHE_MAX_ENTRIES = 64
    }
}
