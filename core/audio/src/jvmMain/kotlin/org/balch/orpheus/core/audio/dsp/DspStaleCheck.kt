package org.balch.orpheus.core.audio.dsp

import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Compares the loaded library's build stamp (`src=`, its newest source mtime) with the C++ tree
 * on disk. Only meaningful on dev runs, where `orpheus.dsp.sourceDir` is set.
 */
internal object DspStaleCheck {
    const val SOURCE_DIR_PROPERTY = "orpheus.dsp.sourceDir"

    private val stampSource = Regex("""\bsrc=(\S+?)\(""")

    /** A warning when [dspDir] holds a source newer than the one [stamp] was built from, else null. */
    fun staleWarning(stamp: String, dspDir: File): String? {
        val builtFrom = stampSource.find(stamp)?.groupValues?.get(1) ?: return null
        val builtFromSec = runCatching {
            LocalDateTime.parse(builtFrom).atZone(ZoneId.systemDefault()).toEpochSecond()
        }.getOrNull() ?: return null
        val newest = newestSource(dspDir) ?: return null
        // The stamp has second precision.
        if (newest.lastModified() / 1000 <= builtFromSec) return null
        val edited = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(newest.lastModified()), ZoneId.systemDefault()
        ).withNano(0)
        return "DSP library is STALE: built from sources as of $builtFrom, but ${newest.name} " +
            "changed at $edited. Rerun buildDesktopNative."
    }

    // Same file set as liborpheus_dsp/cmake/build_stamp.cmake, so the two can't disagree.
    private fun newestSource(dspDir: File): File? {
        val recursive = listOf("src", "include").flatMap { dir ->
            dspDir.resolve(dir).walk().filter { it.isFile }.toList()
        }
        val desktop = dspDir.resolve("desktop").listFiles { f ->
            f.isFile && f.extension in setOf("cpp", "h", "mm")
        }.orEmpty().toList()
        val cmake = listOf(dspDir.resolve("CMakeLists.txt")).filter { it.isFile }
        return (recursive + desktop + cmake).maxByOrNull { it.lastModified() }
    }
}
