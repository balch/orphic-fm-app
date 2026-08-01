package org.balch.orpheus.features.pulsar

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.viz.ARRANGEMENT_STATE_UNKNOWN
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.features.pulsar.models.Vibe

/**
 * `AppScope` home for [vibeFlow] and [arrangementStateFlow], the two [PulsarFeature] members
 * AppScope classes need without forcing [PulsarViewModel] (FeatureScope) into existence.
 *
 * `PulsarSongEnding`/`PulsarMetadataProducer` used `() -> PulsarFeature` lazy providers to read
 * these, which deferred the DI cycle rather than removing it — a compile-time graph error turned
 * into a runtime ordering requirement. This session depends on nothing that depends back on a
 * feature, so both take it as a plain constructor parameter.
 *
 * Not here, and not an oversight: `vibeList`/`vibeNames`/`applyVibeByName` resolve through a
 * `Set<VibeProvider>` multibinding contributed at `FeatureScope`, which Metro's
 * parent-sees-child-doesn't rule puts out of reach. `applyVibe` stays on the ViewModel; this
 * session mirrors the resulting vibe, it does not compute it.
 */
@SingleIn(AppScope::class)
@Inject
class PulsarSession(
    private val synthEngine: SynthEngine,
    private val scope: AppCoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
) {
    private val log = logging("PulsarSession")

    // Null until PulsarViewModel pushes the first real vibe. This class cannot see the
    // FeatureScope Set<VibeProvider> the real one is curated from, and a stand-in placeholder
    // would leak to whichever AppScope consumer reads first. Consumers filterNotNull instead.
    private val _vibeFlow = MutableStateFlow<Vibe?>(null)
    val vibeFlow: StateFlow<Vibe?> = _vibeFlow.asStateFlow()

    /** Called by [PulsarViewModel] (its constructor, and every `applyVibe`) to keep this session's [vibeFlow] current. */
    fun updateVibe(vibe: Vibe) {
        _vibeFlow.value = vibe
    }

    private val _arrangementState = MutableStateFlow(ARRANGEMENT_STATE_UNKNOWN)
    val arrangementStateFlow: StateFlow<PulsarArrangementState> = _arrangementState.asStateFlow()

    init {
        // Always-on enrichment of the C++ arrangement state, moved here from PulsarViewModel.
        // Runs without a UI subscription so the ViewModel's section-BPM collectors and AppScope
        // consumers (Android Auto media session) stay current with no panel rendered.
        scope.launch(dispatcherProvider.io) {
            synthEngine.pulsarArrangementStateFlow
                .filterNotNull()
                .map { state ->
                    // Enrich with band member names if band solos are configured
                    val vibe = vibeFlow.value
                    val section = vibe?.arrangement?.sections?.getOrNull(state.sectionIndex)
                    val memberNames = vibe?.band?.members?.map { it.name }
                    val enriched = if (memberNames != null) {
                        state.copy(
                            bandSolo = true,
                            bandMemberNames = memberNames,
                        )
                    } else state

                    val sectionName = section?.name ?: "section-${state.sectionIndex}"
                    if (enriched.soloActive && enriched.soloTrack >= 0) {
                        val name = if (enriched.bandSolo) {
                            enriched.bandMemberNames.getOrElse(enriched.soloTrack) { "?" }
                        } else {
                            PULSAR_TRACK_NAMES.getOrElse(enriched.soloTrack) { "?" }
                        }
                        log.debug { "Solo active: $name section=$sectionName" }
                    } else {
                        log.debug { "Section: $sectionName bar=${state.barsElapsed}/${state.barsTotal}" }
                    }
                    enriched
                }
                .collect { _arrangementState.value = it }
        }
    }
}
