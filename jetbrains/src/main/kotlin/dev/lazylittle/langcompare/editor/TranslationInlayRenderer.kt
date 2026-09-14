package dev.lazylittle.langcompare.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.colors.TextAttributesKey
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

/**
 * Draws a rounded panel below the selection: a "→ Target" header plus the translated code lines,
 * syntax-highlighted with the target language's own lexer when the IDE knows that language.
 */
class TranslationInlayRenderer(private val editor: Editor, private val model: TargetModel) :
    com.intellij.openapi.editor.EditorCustomElementRenderer {

    private fun headerText(): String {
        val status = model.statusText()
        val base = if (status.isEmpty()) "→ ${model.target}" else "→ ${model.target}  ($status)"
        // Finished result, but no lexer in this IDE yields colors for the target language (e.g. Java in PyCharm).
        val hint = if (status.isEmpty() && model.displayText().isNotBlank() &&
            TargetFileTypes.syntaxHighlighterFor(model.target, editor.project, model.displayText()) == null
        ) "  (no syntax highlighting for this language in this IDE)" else ""
        return base + hint
    }

    private fun contentLines(): List<String> {
        val error = model.error
        if (error != null) {
            return error.lines().flatMap { wrap(it) }.ifEmpty { listOf("unknown error") }
        }
        val text = model.displayText()
        if (text.isBlank()) return listOf(if (model.running) "…" else "(empty response)")
        // Tabs render as missing glyphs in drawString and break column math; expand for both lexing and painting.
        return text.split('\n').map { it.replace("\t", "    ") }
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
        val lines = contentLines()
        val spans = spansFor(lines)

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
            for ((index, line) in lines.withIndex()) {
                val rowSpans = if (model.error != null) null else spans.getOrNull(index)
                if (rowSpans.isNullOrEmpty()) {
                    gg.color = if (model.error != null) errorColor else fg
                    gg.drawString(line, PAD_H.toFloat(), y + fmc.ascent)
                } else {
                    var x = PAD_H.toFloat()
                    var cursor = 0
                    for (span in rowSpans) {
                        if (span.start > cursor) {
                            gg.color = fg
                            val gap = line.substring(cursor, span.start)
                            gg.drawString(gap, x, y + fmc.ascent)
                            x += fmc.stringWidth(gap)
                        }
                        gg.color = span.color ?: fg
                        val text = line.substring(span.start, span.end)
                        gg.drawString(text, x, y + fmc.ascent)
                        x += fmc.stringWidth(text)
                        cursor = span.end
                    }
                    if (cursor < line.length) {
                        gg.color = fg
                        gg.drawString(line.substring(cursor), x, y + fmc.ascent)
                    }
                }
                y += fmc.height
            }
        } finally {
            gg.dispose()
        }
    }

    /** A run of characters on one line sharing one resolved color; null color means the default foreground. */
    private class Span(val start: Int, val end: Int, val color: Color?)

    private var spansKey: Pair<List<String>, EditorColorsScheme>? = null
    private var spansCache: List<List<Span>> = emptyList()

    /** Re-lexes only when the text or the color scheme changed; repaints of unchanged text reuse cached spans. */
    private fun spansFor(lines: List<String>): List<List<Span>> {
        if (model.error != null) return emptyList()
        val key = lines to editor.colorsScheme
        if (spansKey == key) return spansCache
        val computed = runCatching { computeSpans(lines) }.getOrDefault(emptyList())
        // Don't cache empty results: a highlighter may become available once TextMate bundles finish loading.
        if (computed.any { it.isNotEmpty() }) {
            spansKey = key
            spansCache = computed
        }
        return computed
    }

    private fun computeSpans(lines: List<String>): List<List<Span>> {
        val text = lines.joinToString("\n")
        val highlighter = TargetFileTypes.syntaxHighlighterFor(model.target, editor.project, text) ?: return emptyList()
        val lexer = highlighter.highlightingLexer
        val lineStarts = IntArray(lines.size)
        var offset = 0
        for (i in lines.indices) {
            lineStarts[i] = offset
            offset += lines[i].length + 1
        }
        val spans = List(lines.size) { mutableListOf<Span>() }
        lexer.start(text)
        while (true) {
            val tokenType = lexer.tokenType ?: break
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            if (end > start) {
                val color = foregroundColor(highlighter.getTokenHighlights(tokenType), editor.colorsScheme)
                var line = 0
                while (line < lines.lastIndex && start >= lineStarts[line] + lines[line].length + 1) line++
                var pos = start
                while (pos < end && line < lines.size) {
                    val lineEnd = lineStarts[line] + lines[line].length
                    if (pos >= lineEnd) {
                        line++ // token continues past the newline (multi-line string/comment); step over '\n'
                        pos++
                        continue
                    }
                    val segEnd = minOf(end, lineEnd)
                    spans[line].add(Span(pos - lineStarts[line], segEnd - lineStarts[line], color))
                    pos = segEnd
                }
            }
            lexer.advance()
        }
        return spans
    }

    /** Later keys in the array win, mirroring how the editor layers attribute keys onto one token. */
    private fun foregroundColor(keys: Array<TextAttributesKey>, scheme: EditorColorsScheme): Color? {
        var color: Color? = null
        for (key in keys) {
            val attrs = scheme.getAttributes(key) ?: continue
            val next = attrs.foregroundColor ?: attrs.errorStripeColor ?: continue
            color = next
        }
        return color
    }

    companion object {
        private const val PAD_H = 14
        private const val PAD_V = 8
        private const val MAX_LINE_CHARS = 110
    }
}
