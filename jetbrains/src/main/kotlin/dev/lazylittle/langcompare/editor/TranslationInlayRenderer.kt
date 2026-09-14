package dev.lazylittle.langcompare.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import dev.lazylittle.langcompare.llm.LlmClient
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Mutable result state of one target-language translation (fed by the streaming LLM client). */
class TargetModel(val target: String) {
    val buffer = StringBuilder()
    @Volatile var running: Boolean = true
    @Volatile var error: String? = null
    @Volatile var lastPaintAt: Long = 0L
    val refreshPending = AtomicBoolean(false)
    var onUpdate: (() -> Unit)? = null

    fun append(delta: String) {
        synchronized(buffer) { buffer.append(delta) }
        onUpdate?.invoke()
    }

    fun complete(full: String?) {
        if (full != null && full.isNotEmpty()) {
            synchronized(buffer) {
                buffer.setLength(0)
                buffer.append(full)
            }
        }
        running = false
        onUpdate?.invoke()
    }

    fun fail(message: String) {
        error = message
        running = false
        onUpdate?.invoke()
    }

    fun displayText(): String = synchronized(buffer) { LlmClient.extractCodeBlock(buffer.toString()) }

    fun statusText(): String = when {
        error != null -> "error"
        running -> "translating…"
        else -> ""
    }
}

/** Draws a rounded panel below the selection: a "→ Target" header plus the translated code lines. */
class TranslationInlayRenderer(private val editor: Editor, private val model: TargetModel) :
    com.intellij.openapi.editor.EditorCustomElementRenderer {

    private fun headerText(): String {
        val status = model.statusText()
        return if (status.isEmpty()) "→ ${model.target}" else "→ ${model.target}  ($status)"
    }

    private fun contentLines(): List<String> {
        val error = model.error
        if (error != null) {
            return error.lines().flatMap { wrap(it) }.ifEmpty { listOf("unknown error") }
        }
        val text = model.displayText()
        if (text.isBlank()) return listOf(if (model.running) "…" else "(empty response)")
        return text.split('\n')
    }

    private fun wrap(line: String): List<String> =
        if (line.length <= MAX_LINE_CHARS) listOf(line) else line.chunked(MAX_LINE_CHARS)

    private fun codeFont(): Font = editor.colorsScheme.getFont(EditorFontType.PLAIN)

    private fun headerFont(): Font = codeFont().deriveFont(Font.BOLD, max(11f, codeFont().size2D - 1.5f))

    private fun metrics(font: Font): FontMetrics = editor.contentComponent.getFontMetrics(font)

    private fun availableWidth(): Int =
        maxOf(320, editor.contentComponent.visibleRect.width - 24)

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val fmh = metrics(headerFont())
        val fmc = metrics(codeFont())
        var w = fmh.stringWidth(headerText())
        for (line in contentLines()) w = maxOf(w, fmc.stringWidth(line))
        return (w + 2 * PAD_H).coerceAtMost(availableWidth())
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val fmh = metrics(headerFont())
        val fmc = metrics(codeFont())
        return PAD_V + fmh.height + 6 + contentLines().size * fmc.height + PAD_V
    }

    override fun paint(inlay: Inlay<*>, g: Graphics2D, targetRegion: Rectangle2D, attributes: TextAttributes) {
        val scheme = editor.colorsScheme
        val bg = scheme.getColor(EditorColors.CARET_ROW_COLOR) ?: scheme.defaultBackground
        val fg = scheme.defaultForeground
        val border = ColorUtil.mix(bg, fg, 0.18)
        val headerColor = if (JBColor.isBright()) Color(0x067D17) else Color(0x4EC9B0)
        val errorColor = if (JBColor.isBright()) Color(0xC62828) else Color(0xF14C4C)
        val headerF = headerFont()
        val codeF = codeFont()
        val fmh = metrics(headerF)
        val fmc = metrics(codeF)

        val gg = g.create() as Graphics2D
        try {
            gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            gg.translate(targetRegion.x, targetRegion.y)
            val w = targetRegion.width
            val h = targetRegion.height
            val shape = RoundRectangle2D.Double(0.5, 0.5, w - 1.5, h - 1.5, 12.0, 12.0)
            gg.color = bg
            gg.fill(shape)
            gg.color = border
            gg.stroke = BasicStroke(1f)
            gg.draw(shape)
            gg.clip = Rectangle2D.Double(PAD_H - 4.0, 0.0, w - 2 * PAD_H + 8.0, h)

            var y = PAD_V.toFloat()
            gg.font = headerF
            gg.color = headerColor
            gg.drawString(headerText(), PAD_H.toFloat(), y + fmh.ascent)
            y += fmh.height + 4f

            gg.font = codeF
            gg.color = if (model.error != null) errorColor else fg
            for (line in contentLines()) {
                gg.drawString(line, PAD_H.toFloat(), y + fmc.ascent)
                y += fmc.height
            }
        } finally {
            gg.dispose()
        }
    }

    companion object {
        private const val PAD_H = 14
        private const val PAD_V = 8
        private const val MAX_LINE_CHARS = 110
    }
}
