package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Manages audio-rate automation players and their wiring.
 */
@SingleIn(AppScope::class)
class DspAutomationManager @Inject constructor(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) {
    private val log = logging("DspAutomationManager")

    private data class AutomationSetup(
        val player: AutomationPlayer,
        val scaler: MultiplyAdd,
        val targets: List<AudioInput>,
        val restoreManualValue: () -> Unit,
        val prepareForAutomation: () -> Unit = {}
    )
    
    private val automationSetups = mutableMapOf<String, AutomationSetup>()
    private val activeAutomations = mutableSetOf<String>()

    /**
     * Registers a new automation setup.
     */
    fun setupAutomation(
        id: String,
        targets: List<AudioInput>,
        scale: Double,
        offset: Double,
        restoreManualValue: () -> Unit,
        prepareForAutomation: () -> Unit = {}
    ) {
        val player = dspFactory.createAutomationPlayer()
        val scaler = dspFactory.createMultiplyAdd()
        scaler.inputB.set(scale)
        scaler.inputC.set(offset)
        player.output.connect(scaler.inputA)
        
        val setup = AutomationSetup(player, scaler, targets, restoreManualValue, prepareForAutomation)
        automationSetups[id] = setup
        audioEngine.addUnit(player)
        audioEngine.addUnit(scaler)
    }
    
    /**
     * Registers a custom automation setup (e.g. for mix controls with dual scalers).
     * The secondary scaler/setup must be manually wired or registered separately 
     * if it needs to be controlled.
     * 
     * For complex scenarios like Split/Mix, register the primary controller here,
     * and ensuring the player is shared/wired correctly.
     */
    fun registerCustomAutomation(
        id: String,
        setup: AutomationSetupWrapper
    ) {
        // Wrapper to bridging to internal data class
        automationSetups[id] = AutomationSetup(
            setup.player,
            setup.scaler,
            setup.targets,
            setup.restoreManualValue,
            setup.prepareForAutomation
        )
    }
    
    // Helper for external custom setups
    data class AutomationSetupWrapper(
        val player: AutomationPlayer,
        val scaler: MultiplyAdd,
        val targets: List<AudioInput>,
        val restoreManualValue: () -> Unit,
        val prepareForAutomation: () -> Unit = {}
    )

    fun setParameterAutomation(controlId: String, times: FloatArray, values: FloatArray, count: Int, duration: Float, mode: Int) {
        val setup = automationSetups[controlId]
        if (setup == null) {
            log.debug { "Automation NOT FOUND: $controlId" }
            return
        }
        
        if (controlId.startsWith("voice_freq")) {
            log.debug { "Automation: $controlId, first value=${values.firstOrNull()} Hz, targets=${setup.targets.size}" }
        }
        
        // Handling secondary setups (mix/dry/clean)
        val secondarySetup = when (controlId) {
            "delay_mix" -> automationSetups["delay_mix_dry"]
            "distortion_mix" -> automationSetups["distortion_mix_clean"]
            else -> null
        }
        
        // Prepare automation (e.g. zero out manual values)
        setup.prepareForAutomation()
        secondarySetup?.prepareForAutomation?.invoke()
        
        setup.targets.forEach { it.disconnectAll() }
        secondarySetup?.targets?.forEach { it.disconnectAll() }
        
        // Connect scalers
        when (controlId) {
            "delay_mix" -> {
                // Hardcoded knowledge of split mix: 
                // Primary is Wet, Secondary is Dry.
                // Both share the SAME player (handled in SynthEngine init or wiring).
                // Here we just ensure connections.
                setup.targets.forEach { setup.scaler.output.connect(it) }
                secondarySetup?.let { sec ->
                    sec.targets.forEach { sec.scaler.output.connect(it) }
                }
            }
            "distortion_mix" -> {
                 setup.targets.forEach { setup.scaler.output.connect(it) }
                 secondarySetup?.let { sec ->
                    sec.targets.forEach { sec.scaler.output.connect(it) }
                 }
            }
            else -> setup.targets.forEach { setup.scaler.output.connect(it) }
        }
        
        setup.player.setPath(times, values, count)
        setup.player.setDuration(duration)
        setup.player.setMode(mode)
        setup.player.play()
        activeAutomations.add(controlId)
    }

    fun clearParameterAutomation(controlId: String) {
        val setup = automationSetups[controlId] ?: return
        setup.player.stop()
        setup.targets.forEach { it.disconnectAll() }
        
        // Disconnect secondary targets (mix/dry/clean)
        val secondarySetup = when (controlId) {
            "delay_mix" -> automationSetups["delay_mix_dry"]
            "distortion_mix" -> automationSetups["distortion_mix_clean"]
            else -> null
        }
        secondarySetup?.targets?.forEach { it.disconnectAll() }
        
        setup.restoreManualValue()
        // We don't need to restore secondary manually usually, 
        // as the primary restore often covers the high level setter which updates both.
        // e.g. setDelayMix() updates both wet and dry.
        
        activeAutomations.remove(controlId)
    }
}
