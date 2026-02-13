package org.balch.orpheus.core.presets

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.plugin.PortValue

/**
 * New dynamic synth preset structure using generic port values.
 * Replacing the legacy SynthPresetV1.
 */
@Serializable
data class SynthPreset(
    val name: String,
    val bpm: Float = 120f,
    val portValues: Map<String, PortValue> = emptyMap(),
    val createdAt: Long = Clock.System.now().toEpochMilliseconds()
) {
    fun getFloat(key: String, default: Float = 0f): Float =
        (portValues[key] as? PortValue.FloatValue)?.value ?: default

    fun getInt(key: String, default: Int = 0): Int =
        (portValues[key] as? PortValue.IntValue)?.value ?: default

    fun getBool(key: String, default: Boolean = false): Boolean =
        (portValues[key] as? PortValue.BoolValue)?.value ?: default

    fun getString(key: String, default: String = ""): String =
        (portValues[key] as? PortValue.StringValue)?.value ?: default
}
