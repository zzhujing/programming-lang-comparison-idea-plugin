package dev.lazylittle.langcompare.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.lazylittle.langcompare.editor.TargetModel
import java.awt.BorderLayout
import java.awt.Font

/** Tool window content: mirrors the in-editor translation for easy copying. */
class TranslationsPanel : JBPanel<TranslationsPanel>(BorderLayout()) {

    private val headerLabel = JBLabel(" ")
    private val area = createArea()
    private var currentTarget: String? = null

    init {
        headerLabel.border = JBUI.Borders.empty(8, 12)
        add(headerLabel, BorderLayout.NORTH)
        add(JBScrollPane(area), BorderLayout.CENTER)
    }

    fun startSession(sourceLang: String, target: String) {
        currentTarget = target
        headerLabel.text = "Source: $sourceLang   →   $target"
        area.text = ""
    }

    fun updateTarget(model: TargetModel) {
        if (model.target != currentTarget) return
        area.text = when {
            model.error != null -> "ERROR: ${model.error}\n\n${model.displayText()}"
            model.running -> model.displayText().ifEmpty { "…" }
            else -> model.displayText()
        }
        area.caretPosition = 0
    }

    fun clearAll() {
        currentTarget = null
        headerLabel.text = " "
        area.text = ""
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
