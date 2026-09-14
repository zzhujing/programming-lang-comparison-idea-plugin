package dev.lazylittle.langcompare.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import dev.lazylittle.langcompare.editor.TargetFileTypes
import dev.lazylittle.langcompare.editor.TargetModel
import java.awt.BorderLayout

/** Tool window content: mirrors the in-editor translation (syntax-highlighted for the target language) for easy copying. */
class TranslationsPanel(private val project: Project) : JBPanel<TranslationsPanel>(BorderLayout()), Disposable {

    private val headerLabel = JBLabel(" ")
    private val document = EditorFactory.getInstance().createDocument("")
    private val editor: EditorEx = EditorFactory.getInstance().createViewer(document, project) as EditorEx
    private var currentTarget: String? = null

    init {
        headerLabel.border = JBUI.Borders.empty(8, 12)
        editor.settings.apply {
            isLineNumbersShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isUseSoftWraps = true
        }
        Disposer.register(this) { EditorFactory.getInstance().releaseEditor(editor) }
        add(headerLabel, BorderLayout.NORTH)
        add(editor.component, BorderLayout.CENTER)
    }

    fun startSession(sourceLang: String, target: String) {
        currentTarget = target
        headerLabel.text = "Source: $sourceLang   →   $target"
        val file = TargetFileTypes.resolveVirtualFile(target)
            ?: LightVirtualFile("snippet.txt", PlainTextFileType.INSTANCE, "")
        editor.setHighlighter(
            EditorHighlighterFactory.getInstance().createEditorHighlighter(file, editor.colorsScheme, project)
        )
        setText("")
    }

    fun updateTarget(model: TargetModel) {
        if (model.target != currentTarget) return
        setText(
            when {
                model.error != null -> "ERROR: ${model.error}\n\n${model.displayText()}"
                model.running -> model.displayText().ifEmpty { "…" }
                else -> model.displayText()
            }
        )
    }

    fun clearAll() {
        currentTarget = null
        headerLabel.text = " "
        setText("")
    }

    private fun setText(text: String) {
        ApplicationManager.getApplication().runWriteAction {
            document.setText(text)
        }
        editor.caretModel.moveToOffset(0)
        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
    }

    override fun dispose() {
        // The editor is a Disposer child (registered in init), so it is released with this panel.
    }
}
