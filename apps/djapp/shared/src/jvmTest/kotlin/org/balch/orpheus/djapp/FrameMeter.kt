package org.balch.orpheus.djapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.setObserver
import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.jetbrains.skia.Image
import java.lang.management.ManagementFactory

/**
 * Counts every composable scope that runs, so any recomposition at all shows up. It counts each
 * restart group started, a child that then skips included, and a derivedStateOf re-check.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
internal class ScopeCounter : CompositionObserver {
    var entered = 0
        private set

    /** Whether [ObserveScopes] got hold of the composition: false means the counts prove nothing. */
    var observed = false

    override fun onBeginComposition(composition: ObservableComposition) = Unit
    override fun onScopeEnter(scope: RecomposeScope) { entered++ }
    override fun onReadInScope(scope: RecomposeScope, value: Any) = Unit
    override fun onScopeExit(scope: RecomposeScope) = Unit
    override fun onEndComposition(composition: ObservableComposition) = Unit
    override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) = Unit
    override fun onScopeDisposed(scope: RecomposeScope) = Unit
}

/** Reports every scope of this composition, and its subcompositions, to [counter]. Call it first in a scene's content. */
@OptIn(ExperimentalComposeRuntimeApi::class)
@Composable
internal fun ObserveScopes(counter: ScopeCounter) {
    val composition = currentComposer.composition
    DisposableEffect(composition) {
        val handle = composition.setObserver(counter)
        counter.observed = handle != null
        onDispose { handle?.dispose() }
    }
}

/** Runs before a frame; a fun interface, so the frame index is never boxed. It must not allocate. */
internal fun interface BeforeFrame {
    fun run(frame: Int)
}

private val NoStep = BeforeFrame { }

/**
 * Steps [scene] one 16ms frame at a time from [startMs] (about a second in: frame loops that use a
 * zero timestamp as unset never start on 0), and measures what the frames allocate on this thread.
 * With a [scheduler], virtual time moves with the frames, so a `delay` passes in step with them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FrameMeter(
    val scene: ImageComposeScene,
    startMs: Long = 1_000L,
    private val scheduler: TestCoroutineScheduler? = null,
    val scopes: ScopeCounter? = null,
) {
    init {
        checkAllocationCounting()
    }

    var nowMs = startMs
        private set

    /** Frames rendered so far; the index [BeforeFrame] receives. */
    var frames = 0
        private set

    fun frame(before: BeforeFrame = NoStep) = render(before).close()

    fun frames(count: Int, before: BeforeFrame = NoStep) = repeat(count) { frame(before) }

    /** Renders one frame as [frame] does and returns its pixels, encoded: for checking that a scene still moves. */
    fun image(before: BeforeFrame = NoStep): ByteArray = render(before).use { checkNotNull(it.encodeToData()).bytes }

    private fun render(before: BeforeFrame): Image {
        before.run(frames)
        Snapshot.sendApplyNotifications()
        nowMs += FrameMs
        scheduler?.let {
            it.advanceTimeBy(FrameMs)
            it.runCurrent()
        }
        return scene.render(nowMs * 1_000_000).also { frames++ }
    }

    /** Bytes allocated per frame over [count] frames, after [warmup] frames and a GC. */
    fun bytesPerFrame(count: Int = 240, warmup: Int = 60, before: BeforeFrame = NoStep): Long {
        frames(warmup, before)
        System.gc()
        val start = allocatedBytes()
        frames(count, before)
        return (allocatedBytes() - start) / count
    }

    /**
     * Bytes per frame over only those of the next [count] frames the scene asked for, for scenes
     * whose frames differ (the zip's pass against its rest): null if it asked for none.
     */
    fun bytesPerRequestedFrame(count: Int, before: BeforeFrame = NoStep): Long? {
        System.gc()
        var bytes = 0L
        var requested = 0
        repeat(count) {
            val asked = scene.hasInvalidations()
            val start = allocatedBytes()
            frame(before)
            if (asked) {
                bytes += allocatedBytes() - start
                requested++
            }
        }
        return if (requested == 0) null else bytes / requested
    }

    /** Scopes recomposed per frame over [count] frames: exact, as a fraction only when they are uneven. */
    fun scopesPerFrame(count: Int = 120, before: BeforeFrame = NoStep): Double {
        val counter = checkNotNull(scopes) { "no ScopeCounter on this meter" }
        check(counter.observed) { "the composition could not be observed, so the count proves nothing" }
        val start = counter.entered
        frames(count, before)
        return (counter.entered - start).toDouble() / count
    }

    fun close() = scene.close()

    companion object {
        const val FrameMs = 16L
        private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

        /** Unsupported or disabled, the count reads -1 every time and every byte budget passes on nothing. */
        fun checkAllocationCounting() {
            check(threads.isThreadAllocatedMemorySupported) { "this JVM cannot count a thread's allocations" }
            check(threads.isThreadAllocatedMemoryEnabled) { "thread allocation counting is disabled" }
        }

        /** What this thread has allocated so far, in bytes. */
        fun allocatedBytes(): Long = threads.currentThreadAllocatedBytes
    }
}
