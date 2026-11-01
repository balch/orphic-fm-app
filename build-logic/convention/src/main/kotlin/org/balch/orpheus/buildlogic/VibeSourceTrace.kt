package org.balch.orpheus.buildlogic

/**
 * Regex-only trace of which pulsar vibe sources a build can drop (every vibe they declare has a
 * status its [VibeSourceTrace.Cut] leaves out). Pure text in, text out so it stays configuration-cache safe.
 */
object VibeSourceTrace {
    data class Result(
        val removable: Set<String>,
        val mixed: Map<String, Set<String>>,
        val vibeFiles: Int,
        val names: Int,
    )

    private val blockComment = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val lineComment = Regex("//[^\\n]*")
    private val catalogEntry =
        Regex("VibeNames\\.(\\w+)\\s+to\\s+CatalogEntry\\(\\s*VibeStatus\\.(\\w+)")
    private val catalogEntryCall = Regex("(\\bclass\\s+)?\\bCatalogEntry\\s*\\(")
    private val providerContribution = Regex(
        "@ContributesIntoSet\\s*\\([^)]*\\bbinding\\s*=\\s*binding\\s*<\\s*VibeProvider\\s*>\\s*\\(",
    )
    private val vibeName = Regex("\\bval\\s+name\\s*(?::\\s*\\w+\\s*)?=\\s*VibeNames\\.(\\w+)")
    private val topLevel = Regex(
        "^(?:@\\w+(?:\\([^)]*\\))?\\s+)*" +
            "((?:(?:public|internal|private|protected|data|sealed|abstract|open|enum|annotation|value|" +
            "inline|fun|const|lateinit|expect|actual|suspend|tailrec|operator|infix|external)\\s+)*)" +
            "(?:class|interface|object|typealias|val|var|fun)\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?]*\\.)?(\\w+)",
        RegexOption.MULTILINE,
    )

    // Not string-literal aware: a "//" inside a string drops the rest of that line.
    private fun stripComments(source: String) =
        lineComment.replace(blockComment.replace(source, ""), "")

    /** VibeNames constant to status name, from lines like `VibeNames.X to CatalogEntry(VibeStatus.LIVE, ...)`. */
    fun catalogStatuses(catalogText: String): Map<String, String> =
        catalogEntry.findAll(stripComments(catalogText)).associate { it.groupValues[1] to it.groupValues[2] }

    /** `CatalogEntry(` calls, minus the class declaration; must match [catalogStatuses] or a form went unparsed. */
    fun catalogEntryCalls(catalogText: String): Int =
        catalogEntryCall.findAll(stripComments(catalogText)).count { it.groupValues[1].isEmpty() }

    /** `@ContributesIntoSet(..., binding = binding<VibeProvider>())` annotations, comments excluded. */
    fun providerContributions(source: String): Int =
        providerContribution.findAll(stripComments(source)).count()

    fun vibeNamesIn(source: String): Set<String> =
        vibeName.findAll(stripComments(source)).map { it.groupValues[1] }.toSet()

    /**
     * The catalog statuses one build compiles. An OG release ([liveOnly]) keeps only LIVE, any other
     * build keeps every tier, and EXCLUSIVE vibes need [exclusive] on every build.
     */
    data class Cut(val liveOnly: Boolean, val exclusive: Boolean) {
        fun keeps(status: String?): Boolean = when (status) {
            "LIVE" -> true
            "EXCLUSIVE" -> exclusive
            else -> !liveOnly
        }

        val dropsAnything: Boolean get() = liveOnly || !exclusive

        companion object {
            val OG_RELEASE = Cut(liveOnly = true, exclusive = false)

            /** Every cut that drops a file; Guard 1 checks each one. */
            val dropping = listOf(
                OG_RELEASE,
                Cut(liveOnly = true, exclusive = true),
                Cut(liveOnly = false, exclusive = false),
            )
        }
    }

    /** Keys are paths, values file text. A name missing from [statuses] counts as non-LIVE. */
    fun removable(files: Map<String, String>, statuses: Map<String, String>, cut: Cut = Cut.OG_RELEASE): Result {
        val removable = linkedSetOf<String>()
        val mixed = linkedMapOf<String, Set<String>>()
        var vibeFiles = 0
        var nameCount = 0
        for ((path, text) in files) {
            val names = vibeNamesIn(text)
            if (names.isEmpty()) continue
            vibeFiles++
            nameCount += names.size
            val (kept, hidden) = names.partition { cut.keeps(statuses[it]) }
            when {
                kept.isEmpty() -> removable += path
                hidden.isNotEmpty() -> mixed[path] = hidden.toSet()
            }
        }
        return Result(removable, mixed, vibeFiles, nameCount)
    }

    /** Names of unindented non-private class/interface/object/typealias/val/var/fun declarations. */
    fun topLevelDeclarations(source: String): Set<String> =
        topLevel.findAll(stripComments(source))
            .filterNot { Regex("\\bprivate\\b").containsMatchIn(it.groupValues[1]) }
            .map { it.groupValues[2] }.toSet()

    /** Which of [declarations] appear as whole words in [source], comments excluded. */
    fun referencesTo(declarations: Set<String>, source: String): Set<String> {
        val code = stripComments(source)
        return declarations.filterTo(linkedSetOf()) { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(code) }
    }
}
