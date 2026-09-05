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
// of the private repo's own GPL-isolation guard, and is a no-op when the clone is absent
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
    // Short-circuit before the scan, not inside doLast: with no clone present the result is
    // discarded anyway, and computing it realizes every configuration container across the
    // whole build (every KMP source-set bucket in ~76 projects) to build a list nobody reads.
    val offenders = if (privatePaths.isEmpty()) emptyList() else allprojects
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

// Registering the task above did not make it run: nothing depended on it, so a backwards
// dependency still reached origin/main unchallenged. Hang it off jvmTest, the entry point
// this repo already treats as "did I break anything" and the same one FeatureScopeGuardTest
// uses to enforce a structural invariant.
allprojects {
    tasks.matching { it.name == "jvmTest" }.configureEach {
        dependsOn(rootProject.tasks.named("checkPrivateIsolation"))
    }
}
