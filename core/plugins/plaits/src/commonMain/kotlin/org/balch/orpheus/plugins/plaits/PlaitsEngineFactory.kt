package org.balch.orpheus.plugins.plaits

/**
 * Factory for creating [PlaitsEngine] instances by ID.
 * Each call returns an independent instance with its own state.
 */
fun interface PlaitsEngineFactory {
    fun create(id: PlaitsEngineId): PlaitsEngine
}
