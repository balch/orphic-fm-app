package org.balch.orpheus.core.features

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that `run()` reaches the startup features, that roots were already built by injection before
 * it was called, and -- the point of putting the flag on the key -- that skipped features are never
 * constructed.
 */
class StartupInitializerTest {

    private class CountingRoot {
        companion object { var constructed = 0 }
        init { constructed++ }
    }

    private interface EagerFake : SynthFeature<Unit, Unit>
    private interface LazyFake : SynthFeature<Unit, Unit>

    private class FakeFeature : SynthFeature<Unit, Unit> {
        override val stateFlow: StateFlow<Unit> = MutableStateFlow(Unit)
        override val actions: Unit = Unit
        override val synthControl = SynthFeature.SynthControl.Empty
    }

    private class FakeFeatureGraph(
        override val featureCollection: FeatureCollection,
    ) : FeatureGraph {
        override val featureCoroutineScope: FeatureCoroutineScope
            get() = error("not used by StartupInitializer")
    }

    private class FakeFactory(private val collection: FeatureCollection) : FeatureGraph.Factory {
        var creates = 0
        override fun create(): FeatureGraph {
            creates++
            return FakeFeatureGraph(collection)
        }
    }

    /** One startup feature, one lazy, each counting its own construction. */
    private class Fixture {
        var eagerBuilds = 0
        var lazyBuilds = 0
        val collection = FeatureCollection(
            mapOf(
                SynthFeatureKey(EagerFake::class, startup = true) to
                    { eagerBuilds++; FakeFeature() },
                SynthFeatureKey(LazyFake::class) to
                    { lazyBuilds++; FakeFeature() },
            )
        )
    }

    @Test
    fun runBuildsOnlyTheStartupFeatures() {
        val fixture = Fixture()
        val factory = FakeFactory(fixture.collection)

        StartupInitializer(roots = emptySet(), holder = FeatureGraphHolder(factory)).run()

        assertEquals(1, fixture.eagerBuilds, "startup = true feature must be built")
        assertEquals(
            0,
            fixture.lazyBuilds,
            "flag is read off the key, so an unflagged provider must never be invoked",
        )
        assertEquals(1, factory.creates, "the child graph must be built exactly once")
    }

    @Test
    fun appRootsAreConstructedByInjectionNotByRun() {
        CountingRoot.constructed = 0
        val fixture = Fixture()
        val holder = FeatureGraphHolder(FakeFactory(fixture.collection))

        // Building the set is what constructs the roots; Metro does this on injection.
        val initializer = StartupInitializer(roots = setOf(CountingRoot()), holder = holder)

        assertEquals(1, CountingRoot.constructed, "root constructed when the set was built")
        assertEquals(0, fixture.eagerBuilds, "no feature built before run()")

        initializer.run()
        assertTrue(fixture.eagerBuilds == 1)
    }

    @Test
    fun getFeatureStillResolvesByInterface() {
        val fixture = Fixture()
        // Lookups go through a derived index now. Resolving one must not build the other.
        fixture.collection.getFeature(LazyFake::class)

        assertEquals(1, fixture.lazyBuilds)
        assertEquals(0, fixture.eagerBuilds)
    }
}
