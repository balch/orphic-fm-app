package org.balch.orpheus.core.features

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.di.StartupRoot

/**
 * The one startup hook every entry point calls.
 *
 * [roots] resolves during construction, so every `@StartupRoot` exists before [run] builds the
 * `startup = true` features. Ordering falls out of that, not a priority field. Roots do NOT stay in
 * `AppScope` though -- `PulsarSongAdvancer` injects `PulsarFeature`, so building them already
 * creates the child graph.
 *
 * The drain lives here, NOT in `FeatureGraphHolder`'s `by lazy`: `SynchronizedLazyImpl` re-enters
 * on the same thread and recurses without bound. **Do not move it.**
 *
 * No try/catch, deliberately: a root that throws is a bug, and a loud trace beats a degraded boot.
 */
@SingleIn(AppScope::class)
@Inject
class StartupInitializer(
    @StartupRoot private val roots: Set<Any>,
    private val holder: FeatureGraphHolder,
) {
    private val log = logging("StartupInitializer")

    fun run() {
        // roots are already constructed; reading size is just the record of how many.
        val features = holder.featureGraph.featureCollection.startupFeatures
        log.info { "startup init: ${roots.size} app roots, ${features.size} features" }
    }
}
