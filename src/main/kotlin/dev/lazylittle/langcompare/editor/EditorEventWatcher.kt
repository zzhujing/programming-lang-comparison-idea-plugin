package dev.lazylittle.langcompare.editor

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener

/** Application-level listener (registered in plugin.xml) that routes editor events to per-project managers. */
class EditorEventWatcher : SelectionListener, DocumentListener, EditorFactoryListener {

    override fun selectionChanged(event: SelectionEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        if (editor.isOneLineMode) return
        project.getService(TranslationManager::class.java).onSelectionChanged(editor)
    }

    override fun documentChanged(event: DocumentEvent) {
        for (editor in EditorFactory.getInstance().getEditors(event.document)) {
            val project = editor.project ?: continue
            project.getServiceIfCreated(TranslationManager::class.java)?.onDocumentChanged(editor)
        }
    }

    override fun editorCreated(event: EditorFactoryEvent) = Unit

    override fun editorReleased(event: EditorFactoryEvent) {
        // Editor is about to be disposed: cancel pending LLM requests attached to it.
        event.editor.getUserData(TranslationManager.EditorState.KEY)?.reset()
    }
}
