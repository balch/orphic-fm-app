package org.balch.orpheus.features.pulsar.vibes

/**
 * Runtime policy for [VibeCatalog.curate] — the highest [VibeStatus] tier this build shows.
 * [catalogLevel] feeds `curate`'s `visibleThrough` and is CUMULATIVE-LEFT: LIVE shows only live,
 * WIP shows live+wip, SHELF shows live+wip+shelf.
 *
 * The build-type truth lives at the APP layer, not here: AGP 9 KMP library targets compile a
 * single variant, so this module can never know debug-vs-release at compile time. Instead each
 * platform source set of THIS module contributes the policy to the AppScope graph (picked up by
 * every app that generates a graph — DJ and Orpheus alike):
 *
 * - Android (`VibeCatalogPolicyProvider` androidMain): `ApplicationInfo.FLAG_DEBUGGABLE` — WIP on
 *   the debuggable `debug` build, else LIVE. `debugRelease` (initWith(release), R8) is NOT
 *   debuggable, so WIP vibes stay hidden on shipping-like builds.
 * - Desktop JVM (jvmMain): driven by `-Pcatalog=live|wip|shelf` (default `live`) surfaced as the
 *   `-Dcatalog` system property — the desktop app is the ear-test harness, so raise the tier when
 *   you want to audition works-in-progress or shelved vibes.
 * - iOS (iosMain): WIP on `kotlin.native.Platform.isDebugBinary` binaries, else LIVE.
 * - WASM (wasmJsMain): always LIVE — the public site never shows works-in-progress.
 */
data class VibeCatalogPolicy(val catalogLevel: VibeStatus = VibeStatus.LIVE)
