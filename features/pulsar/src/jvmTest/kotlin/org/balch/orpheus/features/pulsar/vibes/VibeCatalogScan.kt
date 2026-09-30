package org.balch.orpheus.features.pulsar.vibes

import org.balch.orpheus.features.pulsar.models.VibeProvider
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.assertTrue

/**
 * Discovers every shipped [VibeProvider] off the compiled classpath, for whole-catalog
 * authoring guards.
 *
 * A hand-maintained list enforces nothing about the vibe someone adds tomorrow, which is
 * the only vibe a guard needed to catch. Filenames are no better: `CompLabVibe.kt` declares
 * eight providers and no class named `CompLabVibe`. Compiled class files are ground truth.
 *
 * Constructing every provider also runs every `Vibe.init` and `EvolutionTension.init`
 * require for the whole catalog — most vibes are never otherwise force-instantiated.
 */
internal object VibeCatalogScan {

    private const val PACKAGE_PATH = "org/balch/orpheus/features/pulsar/vibes"
    private const val PACKAGE_NAME = "org.balch.orpheus.features.pulsar.vibes"

    /**
     * Sanity floor for the scan. Well below the real count (47 providers as of this writing)
     * so ordinary curation never trips it, but high enough that a scan returning nothing
     * fails loudly instead of passing vacuously.
     */
    const val MIN_EXPECTED_PROVIDERS = 40

    private fun discoverClasses(subpackages: Boolean): List<Class<*>> {
        val loader = javaClass.classLoader
        val roots = loader.getResources(PACKAGE_PATH).toList()
            .filter { it.protocol == "file" }
            .map { File(it.toURI()) }
            .filter { it.isDirectory }

        assertTrue(
            roots.isNotEmpty(),
            "found no classpath directory for $PACKAGE_PATH, so nothing could be scanned. If " +
                "the build started packaging this module's classes into a jar, teach this scan " +
                "to read jar entries. Do NOT let it pass with an empty scan.",
        )

        return roots
            .flatMap { root ->
                val files = if (subpackages) root.walkTopDown().toList() else root.listFiles().orEmpty().toList()
                files
                    .filter { it.isFile && it.name.endsWith(".class") && !it.name.contains('$') }
                    .map { PACKAGE_NAME + "." + it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.') }
            }
            .distinct()
            .sorted()
            .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
            .filter { VibeProvider::class.java.isAssignableFrom(it) }
            .filter { !it.isInterface && !Modifier.isAbstract(it.modifiers) }
    }

    /**
     * Every shipped provider, constructed. Fails rather than silently skipping any. [subpackages]
     * adds the vibes in packages below this one (`classical`), which the older sweeps never covered.
     */
    fun allProviders(subpackages: Boolean = false): List<VibeProvider> {
        val classes = discoverClasses(subpackages)

        // A provider we cannot construct is a provider we cannot check.
        val unconstructable = classes.filter { runCatching { it.getDeclaredConstructor() }.isFailure }
        assertTrue(
            unconstructable.isEmpty(),
            "these VibeProviders have no no-arg constructor, so this sweep silently skips " +
                "them: ${unconstructable.map { it.simpleName }}. Give the scan a way to build " +
                "them, or it stops covering them.",
        )

        val providers = classes.map {
            it.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as VibeProvider
        }
        assertTrue(
            providers.size >= MIN_EXPECTED_PROVIDERS,
            "classpath scan found only ${providers.size} VibeProviders (expected at least " +
                "$MIN_EXPECTED_PROVIDERS). Discovery is broken, so the guard is not checking anything.",
        )
        return providers
    }
}
