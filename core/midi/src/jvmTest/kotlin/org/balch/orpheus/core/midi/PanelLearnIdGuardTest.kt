package org.balch.orpheus.core.midi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A learn id with no colon never reaches a port, and a learned control then does nothing. Panels
 * take the id from the port's symbol, so a string literal here is a control that can't be learned.
 */
class PanelLearnIdGuardTest {
    private val repoRoot = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private val sources = listOf("features", "apps").flatMap { dir ->
        File(repoRoot, dir).walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it.extension == "kt" && "/src/commonMain/" in it.path }
            .toList()
    }

    private val learnIdLiteral = Regex("""\b(?:controlId|triggerId)\s*=\s*"([^"]+)"""")

    @Test
    fun panelsLearnPortKeys() {
        val literals = sources.flatMap { file ->
            learnIdLiteral.findAll(file.readText()).map { "${file.name}: ${it.groupValues[1]}" }
        }
        assertEquals(emptyList(), literals)
    }

    @Test
    fun theScanFindsThePanels() {
        val names = sources.map { it.name }
        for (panel in listOf("BassPanel.kt", "DrumsPanel.kt", "ResonatorPanel.kt")) {
            assertTrue(panel in names, "$panel not found under $repoRoot")
        }
    }
}
