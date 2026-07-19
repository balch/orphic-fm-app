package org.balch.orpheus.features.pulsar.vibes

import com.diamondedge.logging.logging
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.vibes.VibeCatalog.curate
import org.balch.orpheus.features.pulsar.vibes.VibeCatalog.vibeStatusFromArg

/**
 * Ship-readiness of a cataloged vibe.
 *
 * - [LIVE]  — green-lit: appears in the picker (and to the AI vibe tools) in catalog order.
 * - [WIP]   — in tuning: hidden on release-like builds, visible where [VibeCatalogPolicy]
 *   allows (debug Android/iOS builds and always on the desktop ear-test harness). Flip to
 *   [LIVE] when it passes its ear test. Replaces the comment-out-the-annotations pattern.
 * - [SHELF] — parked indefinitely: never shown on any build, but stays registered/compiling
 *   so the code doesn't rot and reviving it is a one-word flip.
 */
enum class VibeStatus { LIVE, WIP, SHELF }

/**
 * Curation metadata for one vibe. Deliberately tiny today.
 *
 * @param status ship-readiness gate.
 * @param tags free-form grouping attributes ("rock", "ambient", "riff", …). Not consumed by
 *   any UI yet — they exist so a future grouped/filterable picker (and the AI agent's vibe
 *   tools, which could bias suggestions by tag) have attributes to key on without a schema
 *   migration. `Vibe.album` remains the coarse grouping; tags are the fine one.
 */
data class CatalogEntry(
    val status: VibeStatus,
    val tags: List<String> = emptyList(),
)

/**
 * The master green-light map for Pulsar vibes.
 *
 * DI stays untouched: every `@ContributesIntoSet` [VibeProvider] in this package still
 * registers itself. This catalog decides what the app actually SHOWS — [curate] filters the
 * injected set down to LIVE entries and orders them by catalog position (which also makes the
 * FIRST entry the fresh-install default vibe).
 *
 * Authoring workflow:
 * 1. Drop a new `<Name>Vibe.kt` in this package (auto-registers via DI).
 * 2. Add its name here as [VibeStatus.WIP] — it stays out of the picker but compiles/ships in
 *    the codebase for tuning sessions (flip to LIVE locally while ear-testing).
 * 3. When it passes the ear test, flip to [VibeStatus.LIVE]. One-word diff, no comments.
 *
 * An unlisted-but-registered provider is treated as WIP (hidden) and logged — a new vibe can
 * never leak into a release because someone forgot the catalog line.
 *
 * ── Future hooks (deliberately not built yet) ──────────────────────────────────────────────
 *
 * SAVED VIBES (user- or AI-created, persisted on device):
 * - This catalog is the natural MERGE POINT: today [curate] sees only the DI set of built-in
 *   providers; a future `VibeRepository` would join `savedVibes: List<Vibe>` (each wrapped in
 *   a trivial VibeProvider) ahead of or behind the built-ins here, so the picker/AI see one
 *   uniform list. Keep the merge in one place — downstream code must never care whether a
 *   vibe is built-in or saved.
 * - `Vibe` is already `@Serializable` (see VibeSerializationTest and the AI's
 *   `pulsar_apply_vibe` tool, which decodes agent-emitted vibe JSON) — persistence is plain
 *   JSON via FeatureStatePersistence/DataStore or files; no new format work needed.
 * - The obvious capture point already exists: `VibeApplyTool` holds a fully-validated `Vibe`
 *   the moment the agent applies one — a "Save this vibe?" affordance would persist exactly
 *   that object.
 * - CAVEAT for that future: this catalog keys on DISPLAY NAME, which is fine for the curated
 *   built-in set but wrong for user content (renames, collisions). Saved vibes will need a
 *   stable id (uuid) on the persisted envelope; do NOT reuse `name` as the key there.
 * - CatalogEntry would likely grow `source: BUILT_IN | USER | AI` at that point so the UI can
 *   badge/group them.
 *
 * DEBUG-ONLY TIER VISIBILITY (BUILT): [curate] takes `visibleThrough` — a [VibeStatus] tier
 * fed by [VibeCatalogPolicy] and contributed per platform from this module's platform source
 * sets. It is CUMULATIVE-LEFT: a level shows its own tier plus every lower tier (LIVE < WIP <
 * SHELF by ordinal). LIVE shows only live; WIP shows live+wip; SHELF shows live+wip+shelf.
 * - Android: WIP on debuggable builds, else LIVE.
 * - Desktop JVM: driven by `-Pcatalog=live|wip|shelf` (default `live`) — see
 *   [vibeStatusFromArg] and the desktopApp build file's `-Dcatalog` jvmArg.
 * - iOS: WIP on debug binaries, else LIVE.
 * - WASM: always LIVE — the public site never shows works-in-progress.
 * Note WIP names are shown VERBATIM (no "· wip" suffix): the display name doubles as the lookup
 * key for saved-state restore and media-session ids (`applyVibeByName`), so decorating it would
 * break those lookups.
 */
object VibeCatalog {

    private val log = logging("VibeCatalog")

    /** Ordered master map: catalog position = picker order; first LIVE entry = default vibe. */
    val entries: Map<String, CatalogEntry> = linkedMapOf(
        "Bell Tolls" to CatalogEntry(VibeStatus.LIVE, tags = listOf("riff")),
        "Dog House" to CatalogEntry(VibeStatus.LIVE, tags = listOf("rock", "benchmark")),
        "Fire Sky .5f" to CatalogEntry(VibeStatus.LIVE, tags = listOf("og", "backup", "riff")),
        "Rust Belt" to CatalogEntry(VibeStatus.WIP, tags = listOf("rock", "riff", "swamp")),
        "Filter Funk" to CatalogEntry(VibeStatus.LIVE, tags = listOf("funk")),
        "Fire Sky" to CatalogEntry(VibeStatus.LIVE, tags = listOf("rock", "riff")),
        "Space & Drums" to CatalogEntry(VibeStatus.LIVE, tags = listOf("space")),
        "Techno Wobble" to CatalogEntry(VibeStatus.LIVE, tags = listOf("club")),
        "Velvet Leash" to CatalogEntry(VibeStatus.LIVE, tags = listOf("rock")),
        "Voltage Strut" to CatalogEntry(VibeStatus.LIVE, tags = listOf("funk")),
        "Lost In Space" to CatalogEntry(VibeStatus.LIVE, tags = listOf("ambient")),

// -----------------------------
        "Blues Burn" to CatalogEntry(VibeStatus.WIP, tags = listOf("rock", "riff")),
        "Fire Sky CX" to CatalogEntry(VibeStatus.WIP, tags = listOf("og", "backup", "riff")),
        "Fire Sky OG" to CatalogEntry(VibeStatus.WIP, tags = listOf("og", "backup", "riff")),
        "Rust Belt OG" to CatalogEntry(VibeStatus.WIP, tags = listOf("og", "backup", "riff")),
        "Black Cat" to CatalogEntry(VibeStatus.WIP, tags = listOf("blues", "riff", "soul")),
        "Black Cat OG" to CatalogEntry(VibeStatus.WIP, tags = listOf("og", "backup", "riff")),
        "Dust Groove" to CatalogEntry(VibeStatus.WIP, tags = listOf("lofi")),
        "Corner Office" to CatalogEntry(VibeStatus.WIP, tags = listOf("funk", "rock", "riff")),
        "Corner Office OG" to CatalogEntry(VibeStatus.WIP, tags = listOf("og", "backup", "riff")),
        // ── STEALTH: grooves and moods ──
        "Tremolo Tide" to CatalogEntry(VibeStatus.WIP, tags = listOf("surf")),
        "Army Stomp" to CatalogEntry(VibeStatus.WIP, tags = listOf("march")),
        "Swamp Swagger" to CatalogEntry(VibeStatus.WIP, tags = listOf("swamp")),
        "Blacktop Boogie" to CatalogEntry(VibeStatus.WIP, tags = listOf("boogie")),
        "Sixties Rebel" to CatalogEntry(VibeStatus.WIP, tags = listOf("garage")),
        "Garage Blitz" to CatalogEntry(VibeStatus.WIP, tags = listOf("garage")),
        "Mod Pioneer" to CatalogEntry(VibeStatus.WIP, tags = listOf("mod")),
        // ── Blitz family ──
        "Ascending Blitz" to CatalogEntry(VibeStatus.WIP, tags = listOf("blitz")),
        "Dark Blitz" to CatalogEntry(VibeStatus.WIP, tags = listOf("blitz")),
        "Floor Blitz" to CatalogEntry(VibeStatus.WIP, tags = listOf("blitz")),
        "Jazz Blitz" to CatalogEntry(VibeStatus.WIP, tags = listOf("blitz", "jazz")),
        // ── Club / ambient / time ──
        "Cosmic Techno" to CatalogEntry(VibeStatus.WIP, tags = listOf("club")),
        "Deep Space" to CatalogEntry(VibeStatus.WIP, tags = listOf("ambient")),
        "TimeZone" to CatalogEntry(VibeStatus.WIP, tags = listOf("time")),
        "UTC" to CatalogEntry(VibeStatus.WIP, tags = listOf("time")),
        // ── Comp Lab: comping-style demos ──
        "Comp Pad" to CatalogEntry(VibeStatus.WIP, tags = listOf("comp")),
        "Comp Rock" to CatalogEntry(VibeStatus.WIP, tags = listOf("comp")),
        "Comp Funk" to CatalogEntry(VibeStatus.WIP, tags = listOf("comp")),
        "Comp Blues" to CatalogEntry(VibeStatus.WIP, tags = listOf("comp")),
        "Comp Jazz" to CatalogEntry(VibeStatus.WIP, tags = listOf("comp")),
        "Comp Gospel" to CatalogEntry(VibeStatus.WIP, tags = listOf("comp")),
        "Comp Reggae" to CatalogEntry(VibeStatus.WIP, tags = listOf("comp")),
        "Comp Ska" to CatalogEntry(VibeStatus.WIP, tags = listOf("comp")),
        "Vanished Skyline" to CatalogEntry(VibeStatus.WIP, tags = listOf("ai")),
        "Kaleidoscope Drift" to CatalogEntry(VibeStatus.WIP, tags = listOf("ai", "opus 4.8")),
    )

    /**
     * Filter + order the injected provider set through the catalog.
     *
     * Touches only [VibeProvider.name] — never forces a lazy `Vibe` body to construct, so the
     * deferred-materialization behavior of the picker is preserved.
     *
     * @param visibleThrough from [VibeCatalogPolicy] — the highest tier to show, CUMULATIVE-LEFT:
     *   an entry is included iff `entry.status.ordinal <= visibleThrough.ordinal` (LIVE always;
     *   WIP when the level is WIP or higher; SHELF only when the level is SHELF). Defaults to
     *   [VibeStatus.LIVE]. (Future: saved-vibe providers merge here — see the class KDoc hooks.)
     * @param entries overridable for tests; production always uses the master map.
     */
    fun curate(
        providers: Set<VibeProvider>,
        visibleThrough: VibeStatus = VibeStatus.LIVE,
        entries: Map<String, CatalogEntry> = this.entries,
    ): List<VibeProvider> {
        val byName = providers.associateBy { it.name }

        // Registered but not cataloged = hidden. Loud log: this is either a forgotten catalog
        // line for a new vibe, or a display-name drift between the provider and the catalog.
        val unlisted = providers.map { it.name }.filterNot { it in entries }
        if (unlisted.isNotEmpty()) {
            log.warn { "Uncataloged vibes hidden from picker (add VibeCatalog entries): $unlisted" }
        }

        val curated = entries.mapNotNull { (name, entry) ->
            val provider = byName[name]
            when {
                provider == null -> {
                    // Cataloged but not registered — benched code or a stale entry. Quiet:
                    // this is expected while a vibe is parked out of DI entirely.
                    log.debug { "Catalog entry '$name' has no registered provider" }
                    null
                }
                entry.status.ordinal <= visibleThrough.ordinal -> provider
                else -> null  // tier above the visible cutoff (e.g. WIP/SHELF on a live build)
            }
        }

        // The picker (and PulsarViewModel's default-vibe `.first()`) must never see an empty
        // list. If curation matched nothing — catastrophic name drift, or a test graph built
        // from stub providers — fail open to the uncurated set rather than brick playback.
        if (curated.isEmpty()) {
            log.error { "VibeCatalog matched no registered providers — falling back to the uncurated set" }
            return providers.sortedBy { it.name }
        }
        return curated
    }

    /**
     * Parse a catalog-tier argument (e.g. the desktop `-Pcatalog`/`-Dcatalog` value) into a
     * [VibeStatus] cutoff for [curate]'s `visibleThrough`. Case-insensitive: "wip" -> [VibeStatus.WIP],
     * "shelf" -> [VibeStatus.SHELF]; null, "live", or anything unrecognized -> [VibeStatus.LIVE].
     * Robust to typos by design — an unknown value falls back to the safe live-only default.
     */
    fun vibeStatusFromArg(arg: String?): VibeStatus = when (arg?.trim()?.lowercase()) {
        "wip" -> VibeStatus.WIP
        "shelf" -> VibeStatus.SHELF
        else -> VibeStatus.LIVE
    }
}
