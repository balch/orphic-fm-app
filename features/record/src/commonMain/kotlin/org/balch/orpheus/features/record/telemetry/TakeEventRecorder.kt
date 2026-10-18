package org.balch.orpheus.features.record.telemetry

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.balch.orpheus.core.controller.MidiNoteEvent
import org.balch.orpheus.core.plugin.symbols.DELAY_URI
import org.balch.orpheus.core.plugin.symbols.DISTORTION_URI
import org.balch.orpheus.core.plugin.symbols.REVERB_URI
import org.balch.orpheus.core.plugin.symbols.VIBRATO_URI
import org.balch.orpheus.core.plugin.symbols.VOICE_URI

/**
 * Collects timestamped [TakeEvent]s between [start] and [stop]. Calls made while inactive are
 * ignored, which also drops replayed values from `replay = 1` flows. Thread-safe: callers may
 * feed it from different dispatchers.
 */
class TakeEventRecorder(private val nowMs: () -> Long) {

    private class KeyState(var lastKeptMs: Long, var held: Control? = null)

    private val lock = SynchronizedObject()
    private val events = mutableListOf<TakeEvent>()
    private val controlState = mutableMapOf<String, KeyState>()
    private var startMs = 0L
    private var active = false

    val isActive: Boolean
        get() = synchronized(lock) { active }

    fun start(): Unit = synchronized(lock) {
        events.clear()
        controlState.clear()
        startMs = nowMs()
        active = true
    }

    fun gateOn(voice: Int, midiNote: Float): Unit = synchronized(lock) {
        if (!active) return@synchronized
        events += GateOn(elapsedSec(nowMs()), voice, midiNote)
    }

    fun gateOff(voice: Int): Unit = synchronized(lock) {
        if (!active) return@synchronized
        events += GateOff(elapsedSec(nowMs()), voice)
    }

    /** Keeps only [RECORDED_URIS] keys, at most one event per key per [CONTROL_MIN_INTERVAL_MS]. */
    fun control(key: String, value: Float): Unit = synchronized(lock) {
        if (!active || !isRecordedKey(key)) return@synchronized
        val now = nowMs()
        val event = Control(elapsedSec(now), key, value)
        val state = controlState[key]
        if (state == null || now - state.lastKeptMs >= CONTROL_MIN_INTERVAL_MS) {
            events += event
            controlState[key] = KeyState(lastKeptMs = now)
        } else {
            state.held = event
        }
    }

    fun midiNote(event: MidiNoteEvent): Unit = synchronized(lock) {
        if (!active) return@synchronized
        val t = elapsedSec(nowMs())
        events += if (event.on) {
            MidiNoteOn(t, event.note, event.velocity, event.voice)
        } else {
            MidiNoteOff(t, event.note, event.voice)
        }
    }

    /** Flushes held-back controls and returns the events sorted by time. */
    fun stop(): List<TakeEvent> = synchronized(lock) {
        if (!active) return@synchronized emptyList()
        active = false
        controlState.values.forEach { state -> state.held?.let { events += it } }
        controlState.clear()
        events.sortedBy { it.t }
    }

    private fun elapsedSec(atMs: Long): Float = (atMs - startMs) / 1000f

    private fun isRecordedKey(key: String): Boolean =
        RECORDED_URIS.any { key.startsWith("$it:") }

    companion object {
        val RECORDED_URIS: Set<String> =
            setOf(VOICE_URI, DELAY_URI, REVERB_URI, DISTORTION_URI, VIBRATO_URI)
        const val CONTROL_MIN_INTERVAL_MS = 50L
    }
}
