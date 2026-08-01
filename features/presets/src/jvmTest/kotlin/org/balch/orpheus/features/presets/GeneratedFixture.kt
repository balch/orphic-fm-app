package org.balch.orpheus.features.presets

import java.io.File
import kotlin.test.fail

/**
 * Guard for files that are generated from Kotlin but checked in for the C++ test
 * harness to `#include`.
 *
 * A plain `./gradlew jvmTest` **verifies** that the checked-in fixture still matches
 * what the generator produces and fails if it drifted. It never writes to the working
 * tree, so an unrelated build can't leave spurious diffs on someone's branch.
 *
 * Regeneration is explicit:
 * ```
 * ./gradlew :features:presets:exportPresets
 * ./gradlew jvmTest -Pexport-fixtures
 * ```
 */
internal object GeneratedFixture {

    /** Set by the build for [regenerateHint] tasks and for `-Pexport-fixtures`. */
    private val writing: Boolean = System.getProperty(WRITE_PROPERTY) == "true"

    const val WRITE_PROPERTY: String = "orpheus.fixtures.write"

    /**
     * Writes [generated] to [file] when regenerating, otherwise asserts the file on
     * disk is byte-identical to it.
     */
    fun sync(file: File, generated: String, regenerateHint: String) {
        if (writing) {
            file.parentFile?.mkdirs()
            file.writeText(generated)
            println("Wrote ${file.absolutePath}")
            return
        }

        if (!file.isFile) {
            fail(
                "Generated fixture is missing: ${file.absolutePath}\n" +
                    "Regenerate it with: $regenerateHint"
            )
        }

        val onDisk = file.readText()
        if (onDisk != generated) {
            fail(buildDriftMessage(file, expected = generated, actual = onDisk, regenerateHint))
        }
    }

    private fun buildDriftMessage(
        file: File,
        expected: String,
        actual: String,
        regenerateHint: String,
    ): String {
        val expectedLines = expected.lines()
        val actualLines = actual.lines()
        val drifted = (0 until maxOf(expectedLines.size, actualLines.size)).filter {
            expectedLines.getOrNull(it) != actualLines.getOrNull(it)
        }

        val detail = drifted.take(MAX_REPORTED_LINES).joinToString("\n") { i ->
            """
            |  line ${i + 1}
            |    checked in: ${actualLines.getOrNull(i) ?: "<end of file>"}
            |    generated:  ${expectedLines.getOrNull(i) ?: "<end of file>"}
            """.trimMargin()
        }
        val elided = (drifted.size - MAX_REPORTED_LINES)
            .takeIf { it > 0 }
            ?.let { "\n  ...and $it more differing line(s)" }
            .orEmpty()

        return """
        |${file.name} is out of sync with the preset it is generated from (${drifted.size} line(s) differ).
        |
        |$detail$elided
        |
        |This file is derived output. If the generated values are the ones that look wrong,
        |fix the source preset JSON in composeResources/files/presets/ — editing the header
        |only hides the drift until the next regeneration.
        |
        |To accept the generated values: $regenerateHint
        """.trimMargin()
    }

    private const val MAX_REPORTED_LINES = 8
}
