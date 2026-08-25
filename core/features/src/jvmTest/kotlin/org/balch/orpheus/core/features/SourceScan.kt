package org.balch.orpheus.core.features

import java.io.File
import kotlin.test.fail

/**
 * Shared repo-tree scan for the startup guards. Read once, comment-stripped up front, cached for
 * the JVM's lifetime, so a guard is a filter over a list rather than a walk.
 *
 * [stripComments] is a regex, not a lexer: a comment-opening sequence inside a string literal eats
 * the rest of that line. Fine for matching Metro annotations, not for freer-form text.
 */
internal object SourceScan {

    class Source(val file: File, val text: String) {
        val name: String get() = file.name
        val path: String get() = file.path

        /** Comments removed, so a commented-out annotation cannot read as a live one. */
        val code: String by lazy { stripComments(text) }

        /** [code] without imports, so naming a type in an import is not a usage. */
        val codeWithoutImports: String by lazy {
            code.lineSequence().filterNot { it.trim().startsWith("import") }.joinToString("\n")
        }
    }

    private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
    private val LINE_COMMENT = Regex("""//[^\n]*""")

    fun stripComments(text: String): String =
        text.replace(BLOCK_COMMENT, " ").replace(LINE_COMMENT, " ")

    /** Every production Kotlin source under `core/`, `features/`, and `apps/`. */
    val sources: List<Source> by lazy {
        listOf(File(repoRoot, "core"), File(repoRoot, "features"), File(repoRoot, "apps"))
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
            .filterNot { it.path.contains("/build/") }
            .filterNot { it.path.contains("/.claude/worktrees/") }
            .filterNot { it.path.contains("/bin/") }
            // Test sources name these types in fixtures and KDoc, including the guards' own.
            .filterNot { Regex("""/src/[^/]*[Tt]est/""").containsMatchIn(it.path) }
            .map { Source(it, it.readText()) }
    }

    val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return@lazy dir
            dir = dir.parentFile
        }
        fail("no settings.gradle.kts above ${File("").absolutePath}; cannot locate the repo root")
    }
}
