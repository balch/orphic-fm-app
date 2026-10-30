package org.balch.orpheus.plugins.duolfo

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.LegacyModSource
import org.balch.orpheus.core.audio.patch.PATCH_CELLS
import org.balch.orpheus.core.audio.patch.PATCH_URI
import org.balch.orpheus.core.audio.patch.PatchDest
import org.balch.orpheus.core.audio.patch.PatchSource
import org.balch.orpheus.core.audio.patch.VoiceSlot
import org.balch.orpheus.core.audio.patch.legacyDuoPatch
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ./gradlew :core:plugins:voice:jvmTest --tests '*VoicePluginPatchTest*' */
class VoicePluginPatchTest {

    private val engine = RecordingAudioEngine()
    private val plugin = VoicePlugin(engine)

    private fun patchWrites() = engine.writes.filter { it.uri == PATCH_URI }

    @Test
    fun setPatchSendsCellsOnPatchUri() {
        val p = DuoPatch().connect(PatchSource.LFO_A, PatchDest.MORPH, amount = 0.5f)
        engine.writes.clear()
        plugin.setPatch(1, p)

        assertEquals(p, plugin.patches.value[1])
        val writes = patchWrites().associate { it.symbol to it.value }
        assertEquals(0.5f, writes["p1_0_1_2"])
        assertEquals(0.5f, writes["p1_1_1_2"])
        assertTrue(engine.writes.all { it.uri == PATCH_URI })
    }

    @Test
    fun amountChangeSendsOnlyChangedCells() {
        val p = DuoPatch().connect(PatchSource.LFO_A90, PatchDest.PITCH, VoiceSlot.BOTH, 0.25f)
        plugin.setPatch(0, p)
        engine.writes.clear()

        plugin.setPatch(0, p.setAmount(PatchSource.LFO_A90, PatchDest.PITCH, VoiceSlot.BOTH, 0.3f))

        val writes = patchWrites()
        assertEquals(2, writes.size)
        assertEquals(setOf("p0_0_3_4", "p0_1_3_4"), writes.map { it.symbol }.toSet())
        assertTrue(writes.all { it.value == 0.3f })
    }

    @Test
    fun resyncSendsAllCells() {
        engine.writes.clear()
        plugin.resyncPatches()
        assertEquals(PATCH_CELLS * 6, patchWrites().size)
    }

    @Test
    fun engineToDxParksHarmonics() {
        plugin.setPortValue(VoiceSymbol.duoEngine(0).symbol, PortValue.IntValue(OrpheusEngineId.VA.id))
        plugin.setPatch(0, legacyDuoPatch(LegacyModSource.LFO, onDx = false, duoIndex = 0))
        engine.writes.clear()

        plugin.setPortValue(VoiceSymbol.duoEngine(0).symbol, PortValue.IntValue(OrpheusEngineId.DX.id))

        val harmonics = plugin.patches.value[0].cables.single { it.dest == PatchDest.HARMONICS }
        assertEquals(PatchSource.LFO_B, harmonics.source)
        assertTrue(harmonics.parked)
        val writes = patchWrites().associate { it.symbol to it.value }
        // LFO_B is source 2, HARMONICS is dest 0.
        assertEquals(0f, writes["p0_0_2_0"])
        assertEquals(0f, writes["p0_1_2_0"])
        assertEquals(2, writes.size)
    }

    @Test
    fun applyLegacySourceReplacesPatch() {
        plugin.setPortValue(VoiceSymbol.duoEngine(3).symbol, PortValue.IntValue(OrpheusEngineId.DX.id))
        plugin.applyLegacySource(3, LegacyModSource.LFO)
        assertEquals(legacyDuoPatch(LegacyModSource.LFO, onDx = true, duoIndex = 3), plugin.patches.value[3])
        assertEquals(OrpheusEngineId.DX, plugin.engineOf(3))
    }
}

private data class PortWrite(val uri: String, val symbol: String, val value: Float)

private class RecordingAudioEngine : AudioEngine {
    val writes = mutableListOf<PortWrite>()
    override fun start() {}
    override fun stop() {}
    override val isRunning = false
    override val sampleRate = 48_000
    override fun getCpuLoad() = 0f
    override fun getCurrentTime() = 0.0
    override fun setPort(uri: String, symbol: String, value: Float) {
        writes += PortWrite(uri, symbol, value)
    }
}
