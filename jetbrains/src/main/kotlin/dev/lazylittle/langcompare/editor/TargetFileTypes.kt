package dev.lazylittle.langcompare.editor

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile

/**
 * Resolves the free-form target language name from the settings into something the platform can
 * lex: an IDE-native FileType when the IDE supports the language, otherwise a TextMate bundle
 * claiming the extension (e.g. Java/Go/Kotlin in PyCharm). Returns null when nothing claims the
 * language; callers then fall back to plain text.
 */
object TargetFileTypes {

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance("langcompare")

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
     * A light in-memory file named like "snippet.<ext>", so the platform resolves a lexer for the
     * target language from its extension — native support first, TextMate bundles second.
     */
    fun resolveVirtualFile(target: String): VirtualFile? {
        val name = target.trim()
        if (name.isEmpty()) return null
        val byName = resolve(name)
        if (byName != null) return LightVirtualFile("snippet." + byName.defaultExtension, byName, "")
        val canonical = ALIASES[name.lowercase()] ?: name
        val ext = EXTENSIONS[canonical] ?: Regex("[^a-z0-9]").replace(canonical.lowercase(), "")
        if (ext.isEmpty()) return null
        val file = LightVirtualFile("snippet.$ext")
        // TextMate bundles claim extensions via a FileTypeDetector, which only runs in the
        // file-based lookup (getFileTypeByFile), not in the by-name association lookup.
        val claimed = FileTypeManager.getInstance().getFileTypeByFile(file)
        if (isPlainText(claimed)) return null
        file.setFileType(claimed)
        return file
    }

    /** Lexer-level highlighter for coloring translated code that lives outside a real editor. */
    fun syntaxHighlighterFor(target: String, project: Project?): SyntaxHighlighter? {
        val file = resolveVirtualFile(target)
        if (file == null) {
            logOnce("no lexer claims target '$target'")
            return null
        }
        val highlighter = runCatching {
            SyntaxHighlighterFactory.getSyntaxHighlighter(file.fileType, project, file)
        }.getOrNull()
        logOnce("highlighter for '$target': file=${file.name} type=${file.fileType.name} hl=${highlighter?.javaClass?.name}")
        return highlighter
    }

    private val logged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun logOnce(message: String) {
        if (logged.add(message)) LOG.info(message)
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
