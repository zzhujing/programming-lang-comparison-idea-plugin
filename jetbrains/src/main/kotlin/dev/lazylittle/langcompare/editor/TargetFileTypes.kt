package dev.lazylittle.langcompare.editor

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project

/**
 * Resolves the free-form target language name from the settings into a FileType,
 * so translated code can be highlighted with that language's real lexer.
 * Returns null when no known language matches; callers then fall back to plain text.
 */
object TargetFileTypes {

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

    /** Lexer-level highlighter for coloring translated code that lives outside a real editor. */
    fun syntaxHighlighterFor(target: String, project: Project?): SyntaxHighlighter? {
        val fileType = resolve(target) ?: return null
        return runCatching { SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, project, null) }.getOrNull()
    }

    // Language IDs are case-sensitive ("JAVA", "kotlin", "go"), so try the common spellings.
    private fun findLanguage(name: String): Language? =
        listOf(name, name.lowercase(), name.uppercase()).firstNotNullOfOrNull { candidate ->
            runCatching { Language.findLanguageByID(candidate) }.getOrNull()
        }

    // findFileTypeByName() falls back to the plain-text file type on a miss; treat that as "unknown".
    private fun isPlainText(fileType: FileType): Boolean =
        fileType === PlainTextFileType.INSTANCE || fileType.name.equals("PLAIN_TEXT", ignoreCase = true)
}
