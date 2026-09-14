package dev.lazylittle.langcompare.editor

import com.intellij.lang.Language
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the free-form target language name from the settings into something the platform can
 * lex: an IDE-native FileType when the IDE supports the language, otherwise the TextMate engine
 * with a grammar claiming the extension (e.g. Java/Go/Kotlin in PyCharm). Returns null when
 * nothing yields colors; callers then fall back to plain text.
 */
object TargetFileTypes {

    private val LOG = Logger.getInstance("langcompare")

    // Short LLM-style names that do not equal any registered file type or language name.
    private val ALIASES = mapOf(
        "js" to "JavaScript",
        "jsx" to "JavaScript",
        "ts" to "TypeScript",
        "tsx" to "TypeScript",
        "py" to "Python",
        "rb" to "Ruby",
        "kt" to "Kotlin",
        "kts" to "Kotlin",
        "rs" to "Rust",
        "golang" to "Go",
        "cs" to "C#",
        "csharp" to "C#",
        "cpp" to "C++",
        "sh" to "Shell Script",
        "bash" to "Shell Script",
        "zsh" to "Shell Script",
        "yml" to "YAML",
    )

    // Canonical language name -> file extension used to claim a lexer (native or TextMate bundle).
    private val EXTENSIONS = mapOf(
        "Java" to "java",
        "Kotlin" to "kt",
        "Go" to "go",
        "C#" to "cs",
        "C++" to "cpp",
        "Rust" to "rs",
        "Ruby" to "rb",
        "Python" to "py",
        "JavaScript" to "js",
        "TypeScript" to "ts",
        "Swift" to "swift",
        "Objective-C" to "m",
        "Scala" to "scala",
        "Dart" to "dart",
        "Lua" to "lua",
        "Perl" to "pl",
        "Erlang" to "erl",
        "Shell Script" to "sh",
        "YAML" to "yml",
    )

    // Positive results are cached for the session; misses are retried after a short delay because
    // TextMate bundles register asynchronously during IDE startup.
    private val MISS_RETRY_MS = 5_000L
    private val highlighterCache = ConcurrentHashMap<String, CacheEntry>()
    private class CacheEntry(val highlighter: SyntaxHighlighter?, val at: Long)

    fun resolve(target: String): FileType? {
        val name = target.trim()
        if (name.isEmpty()) return null
        val manager = FileTypeManager.getInstance()
        val primary = ALIASES[name.lowercase()] ?: name
        val byName = runCatching { manager.findFileTypeByName(primary) }.getOrNull()
        if (byName != null && !isPlainText(byName)) return byName
        val language = findLanguage(name) ?: return null
        val byLanguage = runCatching { manager.findFileTypeByLanguage(language) }.getOrNull()
        return byLanguage?.takeIf { !isPlainText(it) }
    }

    /**
     * A light in-memory file carrying the target language, for callers that need a FileType-backed
     * editor (the tool window). Null when nothing claims the language.
     */
    fun resolveVirtualFile(target: String): VirtualFile? {
        val name = target.trim()
        if (name.isEmpty()) return null
        val byName = resolve(name)
        if (byName != null) return LightVirtualFile("snippet." + byName.defaultExtension, byName, "")
        val ext = extensionFor(name) ?: return null
        val file = LightVirtualFile("snippet.$ext")
        // TextMate bundles claim extensions via a FileTypeDetector, which only runs in the
        // file-based lookup; fall back to the single registered "textmate" file type.
        val claimed = runCatching { FileTypeManager.getInstance().getFileTypeByFile(file) }.getOrNull()
        if (claimed != null && !isPlainText(claimed)) {
            file.setFileType(claimed)
            return file
        }
        val textmateType = runCatching { FileTypeManager.getInstance().findFileTypeByName("textmate") }.getOrNull()
        if (textmateType != null && !isPlainText(textmateType)) {
            file.setFileType(textmateType)
            return file
        }
        return null
    }

    /**
     * Lexer-level highlighter for coloring translated code outside a real editor. [sampleText] is
     * the code about to be painted; a highlighter only counts when it yields at least one color on
     * it, so unknown languages degrade to the plain-text path instead of silent monochrome.
     */
    fun syntaxHighlighterFor(target: String, project: Project?, sampleText: String): SyntaxHighlighter? {
        val name = target.trim()
        if (name.isEmpty()) return null
        val now = System.currentTimeMillis()
        val cached = highlighterCache[name]
        if (cached != null && (cached.highlighter != null || now - cached.at < MISS_RETRY_MS)) {
            return cached.highlighter
        }
        val resolved = resolveByNameOrNull(name, project, sampleText) ?: resolveViaTextmateOrNull(name, project, sampleText)
        highlighterCache[name] = CacheEntry(resolved, now)
        return resolved
    }

    // Route 1: IDE-native (or otherwise registered) file type looked up by language name.
    private fun resolveByNameOrNull(name: String, project: Project?, sampleText: String): SyntaxHighlighter? {
        val fileType = resolve(name) ?: return null
        val file = LightVirtualFile("snippet." + fileType.defaultExtension, fileType, "")
        val highlighter = runCatching {
            SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, project, file)
        }.getOrNull()
        return highlighter?.takeIf { producesColors(it, sampleText) } ?: logMiss(name, "native:${fileType.name}")
    }

    // Route 2: the bundled TextMate engine; it resolves the grammar from the file name extension,
    // so no file type claim is needed. Try the language key, then the "textmate" file type.
    private fun resolveViaTextmateOrNull(name: String, project: Project?, sampleText: String): SyntaxHighlighter? {
        val ext = extensionFor(name) ?: return logMiss(name, "no-extension")
        val file = LightVirtualFile("snippet.$ext")
        val textmateType = runCatching { FileTypeManager.getInstance().findFileTypeByName("textmate") }.getOrNull()
        if (textmateType == null || isPlainText(textmateType)) return logMiss(name, "no-textmate-engine")
        val highlighter = runCatching {
            val language = runCatching { Language.findLanguageByID("textmate") }.getOrNull()
            if (language != null) {
                SyntaxHighlighterFactory.getSyntaxHighlighter(language, project, file)
            } else {
                SyntaxHighlighterFactory.getSyntaxHighlighter(textmateType, project, file)
            }
        }.getOrNull()
        return highlighter?.takeIf { producesColors(it, sampleText) } ?: logMiss(name, "textmate-no-colors")
    }

    private fun extensionFor(name: String): String? {
        val canonical = ALIASES[name.lowercase()] ?: name
        val ext = EXTENSIONS[canonical] ?: Regex("[^a-z0-9]").replace(canonical.lowercase(), "")
        return ext.ifEmpty { null }
    }

    /** The highlighter counts only when the color scheme resolves a foreground for some token. */
    private fun producesColors(highlighter: SyntaxHighlighter, sampleText: String): Boolean {
        if (sampleText.isBlank()) return true
        val scheme = schemeOrNull() ?: return true
        val lexer = runCatching { highlighter.highlightingLexer }.getOrNull() ?: return false
        return runCatching {
            lexer.start(sampleText)
            var checked = 0
            while (lexer.tokenType != null && checked < 200) {
                for (key in highlighter.getTokenHighlights(lexer.tokenType)) {
                    val attributes = scheme.getAttributes(key) ?: continue
                    if (attributes.foregroundColor != null || attributes.errorStripeColor != null) return true
                }
                checked++
                lexer.advance()
            }
            false
        }.getOrDefault(false)
    }

    private fun schemeOrNull(): EditorColorsScheme? =
        runCatching {
            com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme
        }.getOrNull()

    private fun logMiss(target: String, reason: String): SyntaxHighlighter? {
        if (logged.add("miss:$target:$reason")) LOG.info("no highlighting for '$target': $reason")
        return null
    }

    private val logged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Language IDs are case-sensitive ("JAVA", "kotlin", "go"), so try the common spellings.
    private fun findLanguage(name: String): Language? =
        listOf(name, name.lowercase(), name.uppercase()).firstNotNullOfOrNull { candidate ->
            runCatching { Language.findLanguageByID(candidate) }.getOrNull()
        }

    // findFileTypeByName() falls back to the plain-text file type on a miss; treat that as "unknown".
    private fun isPlainText(fileType: FileType): Boolean =
        fileType === PlainTextFileType.INSTANCE || fileType.name.equals("PLAIN_TEXT", ignoreCase = true)
}
