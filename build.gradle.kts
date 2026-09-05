plugins {
    // Core plugins needed across modules
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.androidTest) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.metro) apply false
    alias(libs.plugins.buildkonfig) apply false
    alias(libs.plugins.versions.plugin)
}
// Fails if any PUBLIC module depends on a module from the private repo.
//
// The private app modules live in their own repository, cloned into a gitignored `noop/`
// (see .gitignore and settings.gradle.kts). Dependencies may only point INWARD: private
// modules depend on shared ones, never the reverse. Nothing enforced that on its own --
// with the clone present a backwards dependency compiles perfectly, and breaks only for
// someone who cloned the public repo alone, which is everybody else. This is the mirror
// of :apps:baton:shared's checkGplIsolation, and is a no-op when the clone is absent
// because the private projects are not in the build at all.
tasks.register("checkPrivateIsolation") {
    group = "verification"
    description = "Fails if a public module depends on a module from the private repo."

    // Resolved at configuration time: Project objects are not configuration-cache safe,
    // so reduce to plain strings before doLast captures anything.
    val privateRoot = layout.projectDirectory.dir("noop").asFile
    val privatePaths = allprojects
        .filter { it.projectDir.startsWith(privateRoot) }
        .map { it.path }
        .toSet()
    // Only real dependency buckets. Tooling configurations (the versions plugin's
    // dependencyUpdatesAggregation, KMP's swiftPM lockfile metadata) aggregate EVERY
    // project in the build by design, so they name every private module and mean nothing.
    val declaredBucket = Regex("(implementation|api|compileOnly|runtimeOnly)$", RegexOption.IGNORE_CASE)
    val offenders = allprojects
        .filterNot { it.projectDir.startsWith(privateRoot) }
        .flatMap { consumer ->
            consumer.configurations
                .filter { declaredBucket.containsMatchIn(it.name) }
                .flatMap { cfg ->
                    cfg.dependencies
                        .filterIsInstance<ProjectDependency>()
                        .map { it.path }
                        .filter { it in privatePaths }
                        .map { "${consumer.path} -> $it (${cfg.name})" }
                }
        }
        .distinct()
        .sorted()

    doLast {
        if (privatePaths.isEmpty()) {
            logger.lifecycle("checkPrivateIsolation: no private modules in this build; nothing to check.")
            return@doLast
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Public modules must not depend on the private repo's modules.\n" +
                    offenders.joinToString("\n") { "  $it" } +
                    "\n\nMove the shared part into a public module, or move the consumer into the private repo.",
            )
        }
        logger.lifecycle(
            "checkPrivateIsolation: OK — ${privatePaths.size} private module(s) present, no public module depends on them.",
        )
    }
}
