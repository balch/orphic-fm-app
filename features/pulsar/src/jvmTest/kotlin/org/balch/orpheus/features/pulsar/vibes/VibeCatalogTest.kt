package org.balch.orpheus.features.pulsar.vibes

import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VibeCatalogTest {

    /** Provider whose vibe body must never be touched — curation is name-only by contract. */
    private class LazyGuardProvider(override val name: String) : VibeProvider {
        override val vibe: Vibe
            get() = error("curate() must not force Vibe construction (touched '$name')")
    }

    @Test
    fun curateFiltersToLiveEntriesInCatalogOrder() {
        val names = VibeCatalog.entries.keys.toList()
        val providers = names.map { LazyGuardProvider(it) }.toSet<VibeProvider>()
        val curated = VibeCatalog.curate(providers)

        val expected = VibeCatalog.entries
            .filterValues { it.status == VibeStatus.LIVE }
            .keys.toList()
        assertEquals(expected, curated.map { it.name }, "curated order must equal catalog order")
    }

    @Test
    fun wipAndUnlistedProvidersAreHiddenByDefault() {
        val providers = setOf<VibeProvider>(
            LazyGuardProvider("Dog House"),          // LIVE in catalog
            LazyGuardProvider("Corner Office"),      // WIP in catalog
            LazyGuardProvider("Totally Unknown"),    // not cataloged
        )
        val curated = VibeCatalog.curate(providers)
        assertEquals(listOf("Dog House"), curated.map { it.name })
    }

    @Test
    fun wipProvidersAppearWhenPolicyShowsThem() {
        // visibleThrough = WIP = debug builds / the desktop ear-test harness at `-Pcatalog=wip`.
        val providers = setOf<VibeProvider>(
            LazyGuardProvider("Dog House"),
            LazyGuardProvider("Corner Office"),
        )
        val curated = VibeCatalog.curate(providers, visibleThrough = VibeStatus.WIP)
        assertEquals(setOf("Dog House", "Corner Office"), curated.map { it.name }.toSet())
    }

    @Test
    fun shelfProvidersNeverAppearBelowShelfLevel() {
        val entries = linkedMapOf(
            "Alive" to CatalogEntry(VibeStatus.LIVE),
            "Parked" to CatalogEntry(VibeStatus.SHELF),
        )
        val providers = setOf<VibeProvider>(
            LazyGuardProvider("Alive"),
            LazyGuardProvider("Parked"),
        )
        // WIP level still excludes SHELF — only SHELF level would surface it.
        val curated = VibeCatalog.curate(providers, visibleThrough = VibeStatus.WIP, entries = entries)
        assertEquals(listOf("Alive"), curated.map { it.name })
    }

    @Test
    fun visibleThroughIsCumulativeLeftAcrossTiers() {
        // Ordered so curated order is deterministic and tiers are interleaved by ordinal.
        val entries = linkedMapOf(
            "Live One" to CatalogEntry(VibeStatus.LIVE),
            "Wip One" to CatalogEntry(VibeStatus.WIP),
            "Shelf One" to CatalogEntry(VibeStatus.SHELF),
        )
        val providers = setOf<VibeProvider>(
            LazyGuardProvider("Live One"),
            LazyGuardProvider("Wip One"),
            LazyGuardProvider("Shelf One"),
        )

        // LIVE (default) shows only live entries.
        assertEquals(
            listOf("Live One"),
            VibeCatalog.curate(providers, entries = entries).map { it.name },
        )
        // WIP shows live + wip, in catalog order.
        assertEquals(
            listOf("Live One", "Wip One"),
            VibeCatalog.curate(providers, visibleThrough = VibeStatus.WIP, entries = entries).map { it.name },
        )
        // SHELF shows live + wip + shelf.
        assertEquals(
            listOf("Live One", "Wip One", "Shelf One"),
            VibeCatalog.curate(providers, visibleThrough = VibeStatus.SHELF, entries = entries).map { it.name },
        )
    }

    @Test
    fun vibeStatusFromArgParsesTiersAndDefaultsLive() {
        assertEquals(VibeStatus.WIP, VibeCatalog.vibeStatusFromArg("wip"))
        assertEquals(VibeStatus.WIP, VibeCatalog.vibeStatusFromArg("WIP"))
        assertEquals(VibeStatus.SHELF, VibeCatalog.vibeStatusFromArg("Shelf"))
        assertEquals(VibeStatus.LIVE, VibeCatalog.vibeStatusFromArg("live"))
        assertEquals(VibeStatus.LIVE, VibeCatalog.vibeStatusFromArg(null))
        assertEquals(VibeStatus.LIVE, VibeCatalog.vibeStatusFromArg("wpi")) // typo -> safe default
    }

    @Test
    fun catalogNeverConstructsVibes() {
        // LazyGuardProvider throws on vibe access; surviving curate() proves laziness.
        val providers = VibeCatalog.entries.keys.map { LazyGuardProvider(it) }.toSet<VibeProvider>()
        assertTrue(VibeCatalog.curate(providers).isNotEmpty())
    }

    @Test
    fun curationNeverReturnsEmpty() {
        // Catastrophic name drift (or a stub-only test graph) must fail open to the
        // uncurated set — PulsarViewModel takes `.first()` and must never brick.
        val strangers = setOf<VibeProvider>(
            LazyGuardProvider("Zeta Unknown"),
            LazyGuardProvider("Alpha Unknown"),
        )
        val curated = VibeCatalog.curate(strangers)
        assertEquals(listOf("Alpha Unknown", "Zeta Unknown"), curated.map { it.name })
    }
}
