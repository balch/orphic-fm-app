package org.balch.orpheus.tools.vibecodegen

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class RunCodegenTest {

    private val fixtureJson = """
        {
          "name": "Fixture Vibe",
          "bpm": 100.0,
          "rootNote": "C",
          "scaleType": "MINOR",
          "genre": {
            "swingAmount": 0.0,
            "ghostProbability": 0.0,
            "noteRangeLow": 36,
            "noteRangeHigh": 72,
            "rhythmDensity": 0.5
          },
          "tracks": [
            {"engineEdm": {"engineId": "BD"}, "engineSpace": {"engineId": "BD"}},
            {"engineEdm": {"engineId": "SD"}, "engineSpace": {"engineId": "SD"}},
            {"engineEdm": {"engineId": "HH"}, "engineSpace": {"engineId": "HH"}},
            {"engineEdm": {"engineId": "PD"}, "engineSpace": {"engineId": "PD"}},
            {"engineEdm": {"engineId": "CHD"}, "engineSpace": {"engineId": "CHD"}},
            {"engineEdm": {"engineId": "STR"}, "engineSpace": {"engineId": "STR"}},
            {"engineEdm": {"engineId": "GRN"}, "engineSpace": {"engineId": "GRN"}},
            {"engineEdm": {"engineId": "NSE"}, "engineSpace": {"engineId": "NSE"}}
          ]
        }
    """.trimIndent()

    @Test
    fun `runCodegen decodes JSON and writes a real provider file`() {
        val tempDir = Files.createTempDirectory("vibe-codegen-test").toFile()
        try {
            val jsonFile = tempDir.resolve("fixture.json")
            jsonFile.writeText(fixtureJson)

            val outFile = runCodegen(
                jsonPath = jsonFile.absolutePath,
                className = "FixtureVibe",
                outDir = tempDir.absolutePath,
            )

            assertTrue(outFile.exists(), "expected ${outFile.absolutePath} to exist")
            val content = outFile.readText()
            assertTrue(content.contains("class FixtureVibe : VibeProvider"), content)
            assertTrue(content.contains("override val name: String = \"Fixture Vibe\""), content)
            assertTrue(content.contains("override val vibe: Vibe by lazy {"), content)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
