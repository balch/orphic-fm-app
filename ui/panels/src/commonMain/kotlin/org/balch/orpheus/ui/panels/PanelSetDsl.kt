package org.balch.orpheus.core.panels

import org.balch.orpheus.core.features.PanelId

@DslMarker
annotation class PanelSetDsl

/**
 * Builder for configuring an individual panel within a panel set.
 */
@PanelSetDsl
class PanelConfigBuilder(val panelId: PanelId) {
    var weight: Float? = null
}

/**
 * Builder for constructing a [PanelSet] via DSL.
 */
@PanelSetDsl
class PanelSetBuilder(private val name: String) {
    private val configs = mutableListOf<PanelConfig>()

    /** Add a single panel as expanded (visible + open). */
    fun expand(panelId: PanelId) {
        configs += PanelConfig(panelId = panelId.id, expanded = true)
    }

    /** Add a panel as expanded with additional configuration. */
    fun expand(panelId: PanelId, block: PanelConfigBuilder.() -> Unit) {
        val builder = PanelConfigBuilder(panelId).apply(block)
        configs += PanelConfig(
            panelId = panelId.id,
            expanded = true,
            weight = builder.weight,
        )
    }

    /** Add a single panel as collapsed (visible as collapsed strip). */
    fun collapse(panelId: PanelId) {
        configs += PanelConfig(panelId = panelId.id, expanded = false)
    }

    /** Add a panel as collapsed with additional configuration. */
    fun collapse(panelId: PanelId, block: PanelConfigBuilder.() -> Unit) {
        val builder = PanelConfigBuilder(panelId).apply(block)
        configs += PanelConfig(
            panelId = panelId.id,
            expanded = false,
            weight = builder.weight,
        )
    }

    fun build(isFactory: Boolean = false): PanelSet = PanelSet(
        name = name,
        panels = configs.toList(),
        isFactory = isFactory,
    )
}

/**
 * DSL entry point for building a [PanelSet].
 *
 * ```
 * panelSet("Effects Studio") {
 *     expand(DELAY, REVERB, DISTORTION)
 *     expand(WARPS) { weight = 2f }
 *     collapse(PRESETS)
 * }
 * ```
 */
fun panelSet(name: String, block: PanelSetBuilder.() -> Unit): PanelSet =
    PanelSetBuilder(name).apply(block).build()

/** Build a factory panel set (non-deletable). */
fun factoryPanelSet(name: String, block: PanelSetBuilder.() -> Unit): PanelSet =
    PanelSetBuilder(name).apply(block).build(isFactory = true)
