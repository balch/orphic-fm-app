package org.balch.orpheus.core.audio.dsp

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DspStaleCheckTest {
    private val dspDir: File = Files.createTempDirectory("dsp-stale").toFile()
    private val built = LocalDateTime.of(2026, 9, 30, 21, 17, 7)
    private val stamp = "built=2026-09-30T21:17:15 git=abc src=2026-09-30T21:17:07(a.cpp) config=Release"

    @AfterTest
    fun cleanup() {
        dspDir.deleteRecursively()
    }

    private fun source(path: String, at: LocalDateTime) = dspDir.resolve(path).apply {
        parentFile.mkdirs()
        writeText("")
        setLastModified(at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }

    @Test
    fun sourceNewerThanStampWarns() {
        source("src/a.cpp", built)
        source("src/chaos/b.cpp", built.plusSeconds(5))
        val warning = assertNotNull(DspStaleCheck.staleWarning(stamp, dspDir))
        assertTrue("b.cpp" in warning)
    }

    @Test
    fun sourceAsOldAsStampIsFresh() {
        source("src/a.cpp", built)
        source("desktop/jni_bridge_desktop.cpp", built.minusSeconds(60))
        assertNull(DspStaleCheck.staleWarning(stamp, dspDir))
    }

    @Test
    fun filesOutsideTheStampedSetAreIgnored() {
        source("src/a.cpp", built)
        source("test/test_main.cpp", built.plusSeconds(60))
        source("desktop/build-nogrids/gen.cpp", built.plusSeconds(60))
        assertNull(DspStaleCheck.staleWarning(stamp, dspDir))
    }

    @Test
    fun stampWithoutSourceTimeIsSkipped() {
        source("src/a.cpp", built.plusSeconds(60))
        assertNull(DspStaleCheck.staleWarning("built=2026-09-30T21:17:15 git=abc", dspDir))
    }
}
