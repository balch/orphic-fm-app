package org.balch.orpheus.core.audio.dsp

import java.io.File
import kotlin.test.fail

/**
 * Guard for binaries that are generated from Kotlin but checked in for the C++ test
 * harness to load.
 *
 * A plain `./gradlew jvmTest` **verifies** that the checked-in fixture still matches
 * what the generator produces and fails if it drifted. It never writes to the working
 * tree, so an unrelated build can't leave spurious diffs on someone's branch.
 *
 * Regeneration is explicit:
 * ```
 * ./gradlew :core:dsp-engine:exportOdwg
 * ./gradlew jvmTest -Pexport-fixtures
 * ```
 */
internal object GeneratedFixture {

    const val WRITE_PROPERTY: String = "orpheus.fixtures.write"

    /** Set by the build for [regenerateHint] tasks and for `-Pexport-fixtures`. */
    private val writing: Boolean = System.getProperty(WRITE_PROPERTY) == "true"

    /**
     * Writes [generated] to [file] when regenerating, otherwise asserts the file on
     * disk is byte-identical to it.
     */
    fun sync(file: File, generated: ByteArray, regenerateHint: String) {
        if (writing) {
            file.parentFile?.mkdirs()
            file.writeBytes(generated)
            println("Wrote ${generated.size} bytes to ${file.absolutePath}")
            return
        }

        if (!file.isFile) {
            fail(
                "Generated fixture is missing: ${file.absolutePath}\n" +
                    "Regenerate it with: $regenerateHint"
            )
        }

        val onDisk = file.readBytes()
        if (!onDisk.contentEquals(generated)) {
            fail(
                """
                |${file.name} is out of sync with the wiring graph it is generated from.
                |
                |  checked in: ${onDisk.size} bytes
                |  generated:  ${generated.size} bytes
                |  ${describeFirstDifference(onDisk, generated)}
                |
                |This file is derived output. If the graph changed on purpose, regenerate it
                |and commit the result: $regenerateHint
                """.trimMargin()
            )
        }
    }

    private fun describeFirstDifference(onDisk: ByteArray, generated: ByteArray): String {
        val offset = (0 until minOf(onDisk.size, generated.size))
            .firstOrNull { onDisk[it] != generated[it] }
            ?: return "identical up to the length of the shorter file"
        return "first differing byte at offset $offset " +
            "(checked in 0x%02X, generated 0x%02X)".format(onDisk[offset], generated[offset])
    }
}
