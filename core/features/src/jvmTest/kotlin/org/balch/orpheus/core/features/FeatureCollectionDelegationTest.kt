package org.balch.orpheus.core.features

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [FeatureCollection] is a pure index: it resolves through the multibinding and adds nothing.
 *
 * This replaces `FeatureCollectionConcurrencyTest`, which pinned the behaviour of a cache and a
 * lock that no longer exist. That suite proved a real bug at the time -- holding the collection's
 * lock across `provider()` while a Metro `DoubleCheck` was taken in the opposite order elsewhere
 * deadlocked startup. The fix taken here removes the collection's memoization entirely rather than
 * removing one of the two locks, so the cycle cannot form: there is only one lock layer left, and
 * it is Metro's.
 *
 * Single-instance therefore comes from `@SingleIn(FeatureScope::class)` on each ViewModel, which
 * `FeatureScopeGuardTest` enforces. It is deliberately *not* asserted here: these tests use plain
 * lambdas as providers, so they can only pin what this class itself does, which is delegate.
 */
class FeatureCollectionDelegationTest {

    private interface FakeFeature : SynthFeature<Unit, Unit>

    private class FakeFeatureImpl : FakeFeature {
        override val stateFlow: StateFlow<Unit> = MutableStateFlow(Unit)
        override val actions: Unit = Unit
        override val synthControl = SynthFeature.SynthControl.Empty
    }

    private fun collectionOf(
        provider: () -> SynthFeature<*, *>,
    ) = FeatureCollection(
        mapOf<KClass<out SynthFeature<*, *>>, () -> SynthFeature<*, *>>(
            FakeFeature::class to provider,
        ),
    )

    @Test
    fun getFeatureReturnsWhateverTheProviderReturns() {
        val instance = FakeFeatureImpl()
        val collection = collectionOf { instance }

        assertSame(instance, collection.getFeature(FakeFeature::class))
    }

    /**
     * The delegation contract, stated as a test so re-adding a cache fails.
     *
     * A non-memoizing provider must yield distinct instances. In production every provider *is*
     * memoizing, because the ViewModel is `@SingleIn(FeatureScope::class)` and Metro holds it in a
     * provider field -- so this asserts that the singleton-ness comes from there and not from here.
     */
    @Test
    fun collectionDoesNotMemoize() {
        val builds = AtomicInteger(0)
        val collection = collectionOf {
            builds.incrementAndGet()
            FakeFeatureImpl()
        }

        val first = collection.getFeature(FakeFeature::class)
        val second = collection.getFeature(FakeFeature::class)

        assertEquals(
            2,
            builds.get(),
            "FeatureCollection memoized a result. It must not: a cache here is a second " +
                "memoization layer over instances Metro already owns, and its lock -- held across " +
                "arbitrary ViewModel construction -- is what used to deadlock startup. If a " +
                "feature is being built twice in production, the missing piece is " +
                "@SingleIn(FeatureScope::class) on that ViewModel.",
        )
        assertTrue(first !== second, "a non-memoizing provider must yield distinct instances")
    }

    /**
     * No lock anywhere on the resolve path.
     *
     * Eight threads resolve through one collection while the provider is slow. With a lock held
     * across `provider()` this serializes; with none it does not. The assertion is only that they
     * all finish promptly -- enough to catch a reintroduced lock, and it cannot hang CI.
     */
    @Test
    fun concurrentResolvesDoNotSerializeOrDeadlock() {
        val inProvider = CountDownLatch(8)
        val release = CountDownLatch(1)
        val collection = collectionOf {
            inProvider.countDown()
            // Every thread must be inside the provider at once. Under a lock, only one ever is,
            // the latch never reaches zero, and this times out.
            check(release.await(5, TimeUnit.SECONDS)) { "release latch never fired" }
            FakeFeatureImpl()
        }

        val done = CountDownLatch(8)
        repeat(8) { n ->
            Thread({
                try {
                    collection.getFeature(FakeFeature::class)
                } finally {
                    done.countDown()
                }
            }, "resolver-$n").apply { isDaemon = true }.start()
        }

        assertTrue(
            inProvider.await(5, TimeUnit.SECONDS),
            "not all 8 threads reached the provider concurrently, so something is serializing " +
                "resolution. FeatureCollection must not take a lock -- see its KDoc.",
        )
        release.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS), "threads did not finish after release")
    }

    /**
     * [FeatureCollection.allFeatures] must not hold a monitor across `provider()` either.
     *
     * The default SYNCHRONIZED lazy would: its lock is held for the whole initializer, which
     * constructs every feature, recreating the lock-across-arbitrary-construction shape that
     * used to deadlock startup. PUBLICATION lets racing readers each run the initializer
     * unlocked, and the first published result wins, so two threads must be able to sit inside
     * the provider at the same time.
     */
    @Test
    fun allFeaturesDoesNotHoldALockAcrossProviders() {
        val inProvider = CountDownLatch(2)
        val release = CountDownLatch(1)
        val collection = collectionOf {
            inProvider.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "release latch never fired" }
            FakeFeatureImpl()
        }

        val done = CountDownLatch(2)
        repeat(2) { n ->
            Thread({
                try {
                    collection.allFeatures
                } finally {
                    done.countDown()
                }
            }, "enumerator-$n").apply { isDaemon = true }.start()
        }

        assertTrue(
            inProvider.await(5, TimeUnit.SECONDS),
            "two allFeatures readers could not run the initializer concurrently, so the lazy is " +
                "holding a monitor across provider() again. Keep LazyThreadSafetyMode.PUBLICATION.",
        )
        release.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS), "threads did not finish after release")
    }
}
