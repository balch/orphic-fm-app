package org.balch.orpheus.core.di

/**
 * Scope for `FeaturePanel` contributions consumed by the orpheus app's
 * collapsible-header UI. Separate from [FeatureScope] so apps that don't
 * render the header (djapp) aren't forced to materialize a `Set<FeaturePanel>`.
 *
 * Panel registrations annotate `@ContributesIntoSet(HeaderPanelScope::class, ...)`;
 * only orpheus assembles a graph at this scope (via `HeaderPanelGraph`).
 */
abstract class HeaderPanelScope private constructor()
