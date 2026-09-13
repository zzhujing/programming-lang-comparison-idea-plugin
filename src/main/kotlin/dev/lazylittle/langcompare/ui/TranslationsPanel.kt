package dev.lazylittle.langcompare.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.lazylittle.langcompare.editor.TargetModel
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JTabbedPane

/** Tool window content: one tab per target language, mirroring the in-editor results for easy copying. */
class TranslationsPanel : JBPanel<TranslationsPanel>(BorderLayout()) {

    private val headerLabel = JBLabel(" ")
    private val tabs = JTabbedPane()
    private val areas = LinkedHashMap<String, JBTextArea>()

    init {
        headerLabel.border = JBUI.Borders.empty(8, 12)
        add(headerLabel, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
    }

    fun startSession(sourceLang: String, targets: List<String>) {
        areas.clear()
        tabs.removeAll()
        headerLabel.text = "Source: $sourceLang   →   ${targets.joinToString(", ")}"
        for (target in targets) {
            val area = createArea()
            areas[target] = area
            tabs.addTab(target, JBScrollPane(area))
        }
    }

    fun updateTarget(model: TargetModel) {
        val area = areas[model.target] ?: return
        area.text = when {
            model.error != null -> "ERROR: ${model.error}\n\n${model.displayText()}"
            model.running -> model.displayText().ifEmpty { "…" }
            else -> model.displayText()
        }
        area.caretPosition = 0
    }

    fun clearAll() {
        areas.clear()
        tabs.removeAll()
        headerLabel.text = " "
    }

    private fun createArea(): JBTextArea {
        val area = JBTextArea()
        area.isEditable = false
        area.lineWrap = true
        area.wrapStyleWord = false
        area.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        return area
    }
}
