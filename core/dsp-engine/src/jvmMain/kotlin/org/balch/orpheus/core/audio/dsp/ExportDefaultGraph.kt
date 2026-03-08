package org.balch.orpheus.core.audio.dsp

import java.io.File

/**
 * Serializes the production ODWG graph descriptor to a binary file.
 * Used by the `exportDefaultGraph` Gradle task to generate test data
 * for C++ unit tests.
 *
 * Usage: pass the output file path as the first argument.
 */
fun main(args: Array<String>) {
    val outputPath = args.firstOrNull()
        ?: error("Usage: ExportDefaultGraph <output-path>")

    val bytes = buildDefaultWiringGraph()
    File(outputPath).writeBytes(bytes)
    println("Wrote ${bytes.size} bytes to $outputPath")
}
