package dev.lazylittle.langcompare.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.lazylittle.langcompare.editor.TranslationManager

class TranslateSelectionAction : AnAction() {
    init {
        templatePresentation.text = "Compare to Other Languages"
        templatePresentation.description =
            "Translate the selected code into the configured target languages using an LLM"
        templatePresentation.icon = AllIcons.Actions.Preview
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && editor.selectionModel.hasSelection()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        project.getService(TranslationManager::class.java).runManual(editor)
    }
}

class ClearTranslationsAction : AnAction() {
    init {
        templatePresentation.text = "Clear Language Comparison"
        templatePresentation.description = "Remove in-editor language comparison blocks"
        templatePresentation.icon = AllIcons.Actions.Cancel
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        project.getService(TranslationManager::class.java).clearTranslations(editor)
    }
}
