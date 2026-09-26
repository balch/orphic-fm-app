package org.balch.orpheus.features.pulsar.playback

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.playback.SkipDirection
import org.balch.orpheus.core.playback.SkipHandler
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarSession
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one owner of "change the playing vibe": buttons, swipe, arrow keys, media keys and widgets
 * (via [SkipHandler]), list picks (via [PulsarSession.requestVibe]) and the song advancer
 * ([advance]). One transition at a time; a newer request cancels the one in flight.
 *
 * Steps start from the in-flight target, so two presses of ▶ inside one transition land two vibes on.
 * ▶ during an advance is the exception: it lands on the advance's own target, as the chrome names it.
 *
 * Each accepted change is announced on [PulsarSession.vibeMoves] as its transition starts.
 */
@SingleIn(AppScope::class)
@Inject
class VibeNavigator(
    // Lazy: PlaybackController -> SkipHandler? (this) -> PulsarFeature would otherwise be a DI cycle.
    pulsarFeatureProvider: () -> PulsarFeature,
    private val pulsarSession: PulsarSession,
    private val transitionRunner: PulsarTransitionRunner,
    private val transitionPreferences: TransitionPreferences,
    scope: AppCoroutineScope,
) : SkipHandler {
    private val log = logging("VibeNavigator")
    private val pulsarFeature: PulsarFeature by lazy(pulsarFeatureProvider)

    private sealed interface Command {
        /** Short log label; a Pick's whole Vibe is too big to interpolate. */
        val label: String
        val origin: VibeMoveOrigin

        class User(val request: VibeRequest) : Command {
            override val label: String
                get() = when (request) {
                    is VibeRequest.Pick -> "pick ${request.vibe.name}"
                    VibeRequest.Next -> "next"
                    VibeRequest.Previous -> "previous"
                    is VibeRequest.DomeSwipe -> if (request.next) "swipe next" else "swipe previous"
                }
            override val origin: VibeMoveOrigin
                get() = if (request is VibeRequest.DomeSwipe) VibeMoveOrigin.DomeSwipe else VibeMoveOrigin.Request
        }

        /** [from] is the vibe the advancer computed [name] from; the advance is void once it stops playing. */
        class Advance(
            val from: String,
            val name: String,
            val spec: TransitionSpec,
            val done: CompletableDeferred<Unit>,
        ) : Command {
            override val label: String get() = "advance $from -> $name"
            override val origin: VibeMoveOrigin get() = VibeMoveOrigin.Advance
        }
    }

    private class Move(val target: String, val spec: TransitionSpec, val kind: VibeMoveKind, val apply: () -> Unit)

    private val commands = Channel<Command>(Channel.UNLIMITED)
    @Volatile private var inFlight: Job? = null
    @Volatile private var inFlightIsUser = false
    // Where the running or last-cancelled transition was heading; null once one completes.
    @Volatile private var pendingTarget: String? = null
    // Whether a user request set [pendingTarget]. An advance's target is not where ▶ starts: the
    // chrome still names it "Up next", so ▶ steps from the playing vibe and lands on it.
    @Volatile private var pendingIsUser = false

    /** True while a transition runs. Only a cheap pre-check for the advancer; the loop decides. */
    val isBusy: Boolean get() = inFlight?.isActive == true

    init {
        scope.launch {
            for (command in commands) {
                if (command is Command.Advance && isOverruled(command)) {
                    command.done.complete(Unit)
                    continue
                }
                inFlight?.cancelAndJoin()
                inFlightIsUser = command is Command.User
                // UNDISPATCHED enters run()'s finally before anything can cancel it, so an
                // Advance's caller is always released.
                inFlight = launch(start = CoroutineStart.UNDISPATCHED) { run(command) }
            }
        }
        scope.launch {
            pulsarSession.vibeRequests.collect { commands.trySend(Command.User(it)) }
        }
    }

    override fun onSkip(direction: SkipDirection) {
        request(if (direction == SkipDirection.NEXT) VibeRequest.Next else VibeRequest.Previous)
    }

    fun request(request: VibeRequest) {
        commands.trySend(Command.User(request))
    }

    /**
     * Suspends until the advance finishes, a newer request cancels it, or it is dropped because a
     * user request is in flight or [from] is no longer playing.
     */
    suspend fun advance(from: String, name: String, spec: TransitionSpec) {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Advance(from, name, spec, done))
        done.await()
    }

    // User wins. Decided here, where commands are serialized: the advancer's isBusy check can
    // read false between a user request being queued and its job starting.
    private fun isOverruled(advance: Command.Advance): Boolean {
        val userInFlight = inFlightIsUser && inFlight?.isActive == true
        val playing = runCatching { pulsarFeature.vibeFlow.value.name }.getOrNull()
        val overruled = userInFlight || playing != advance.from
        if (overruled) log.info { "${advance.label} dropped (user in flight=$userInFlight, playing $playing)" }
        return overruled
    }

    private suspend fun run(command: Command) {
        try {
            val move = resolve(command) ?: return
            log.info { "${command.label} -> ${move.target} (${move.spec.style})" }
            pendingTarget = move.target
            pendingIsUser = command is Command.User
            pulsarSession.announceMove(VibeMove(move.kind, command.origin))
            transitionRunner.runTransition(move.spec) { move.apply() }
            pendingTarget = null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Contained here: escaping would fail the loop and strand every later request.
            log.warn(t) { "${command.label} failed" }
            pendingTarget = null
        } finally {
            (command as? Command.Advance)?.done?.complete(Unit)
        }
    }

    private fun resolve(command: Command): Move? {
        val feature = pulsarFeature
        val current = feature.vibeFlow.value
        val spec = transitionPreferences.defaultFlow.value
        return when (command) {
            is Command.Advance -> Move(command.name, command.spec, VibeMoveKind.Next) { feature.applyVibeByName(command.name) }
            is Command.User -> when (val request = command.request) {
                is VibeRequest.Pick -> Move(request.vibe.name, spec, VibeMoveKind.Pick) { feature.applyVibe(request.vibe) }
                VibeRequest.Next -> stepOn(feature, current, spec)
                VibeRequest.Previous -> stepBack(feature, current, spec)
                is VibeRequest.DomeSwipe ->
                    if (request.next) stepOn(feature, current, spec) else stepBack(feature, current, spec)
            }
        }
    }

    private fun stepOn(feature: PulsarFeature, current: Vibe, spec: TransitionSpec): Move? =
        step(feature, (if (pendingIsUser) pendingTarget else null) ?: current.name, 1, spec)

    // ◀ steps back from any in-flight target: during an advance that replays the playing vibe, the
    // restart the chrome names that late in a song.
    private fun stepBack(feature: PulsarFeature, current: Vibe, spec: TransitionSpec): Move? =
        if (pendingTarget == null && shouldRestartOnPrevious(pulsarSession.progressFlow.value)) {
            Move(current.name, spec, VibeMoveKind.Restart) { feature.applyVibe(current) }
        } else {
            step(feature, pendingTarget ?: current.name, -1, spec)
        }

    private fun step(feature: PulsarFeature, from: String, step: Int, spec: TransitionSpec): Move? {
        val target = neighborVibe(feature.vibeNames, from, step) ?: return null
        val kind = if (step > 0) VibeMoveKind.Next else VibeMoveKind.Previous
        return Move(target, spec, kind) { feature.applyVibeByName(target) }
    }
}
