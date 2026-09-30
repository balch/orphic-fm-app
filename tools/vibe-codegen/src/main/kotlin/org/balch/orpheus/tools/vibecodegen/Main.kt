package org.balch.orpheus.tools.vibecodegen

import org.balch.orpheus.features.ai.tools.decodeVibe
import org.balch.orpheus.features.ai.tools.vibeApplyJson
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val options = try {
        parseArgs(args)
    } catch (e: Exception) {
        System.err.println("Usage error: ${e.message}")
        exitProcess(1)
    }
    try {
        val outFile = runCodegen(options.jsonPath, options.className, options.nameConst, options.outDir)
        println("Wrote ${outFile.absolutePath}")
    } catch (e: Exception) {
        System.err.println("Could not generate ${options.className}: ${e.message}")
        exitProcess(1)
    }
}

/**
 * Decode [jsonPath] through the app's own lenient decoder and write `<className>.kt` into [outDir],
 * naming the vibe `VibeNames.[nameConst]`.
 */
fun runCodegen(jsonPath: String, className: String, nameConst: String, outDir: String): File {
    val jsonText = File(jsonPath).readText()
    val vibe = decodeVibe(vibeApplyJson, jsonText).getOrThrow()
    val fileSpec = generateVibeFile(vibe, className, nameConst)
    val outFile = File(outDir, "$className.kt")
    outFile.parentFile?.mkdirs()
    outFile.writeText(fileSpec.toString())
    return outFile
}

private data class Options(val jsonPath: String, val className: String, val nameConst: String, val outDir: String)

private fun parseArgs(args: Array<String>): Options {
    var jsonPath: String? = null
    var className: String? = null
    var nameConst: String? = null
    var outDir: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--class-name" -> { className = args[i + 1]; i += 2 }
            "--name-const" -> { nameConst = args[i + 1]; i += 2 }
            "--out-dir" -> { outDir = args[i + 1]; i += 2 }
            else -> { jsonPath = args[i]; i += 1 }
        }
    }
    return Options(
        jsonPath = requireNotNull(jsonPath) { "usage: <json-path> --class-name <Name> --name-const <CONST> --out-dir <path>" },
        className = requireNotNull(className) { "missing --class-name" },
        nameConst = requireNotNull(nameConst) { "missing --name-const" },
        outDir = requireNotNull(outDir) { "missing --out-dir" },
    )
}
