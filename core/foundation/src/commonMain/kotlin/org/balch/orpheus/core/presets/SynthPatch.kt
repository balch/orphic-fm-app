package org.balch.orpheus.core.presets

/**
 * Interface for pluggable synthesizer patches (factory presets).
 * Implementations are injected into the set of available factory presets.
 * 
 * Similar to [org.balch.orpheus.ui.viz.Visualization], these are DI-injected
 * via @ContributesIntoSet to ensure cross-platform availability without
 * relying on file system access.
 */
interface SynthPatch {
    /** Unique identifier for this patch */
    val id: String
    
    /** Display name for the UI */
    val name: String
    
    /** The preset data containing all synth parameters */
    val preset: SynthPreset
    
    /** Whether this is a factory preset (true) or user-created (false) */
    val isFactory: Boolean get() = true
}
