package org.balch.orpheus.core.controller

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue

/**
 * Origin of a control event.
 */
enum class ControlEventOrigin {
    MIDI,      // External MIDI controller or Keyboard
    UI,        // Manual UI interaction (Knobs/Sliders)
    SEQUENCER, // Internal Automation/Sequencer
    TIDAL,     // Tidal Cycles OSC input (future)
    AI,        // AI Agent Automation
    EVO,       // Audio Evolution Strategies
    MEDIAPIPE  // Camera-based gesture tracking
}

/**
 * Control event data for flow-based routing.
 */
data class ControlEvent(
    val controlId: String,
    val value: Float,
    val origin: ControlEventOrigin = ControlEventOrigin.MIDI
)

/**
 * Per-voice hold state change event.
 * Emitted by any source (UI, MediaPipe, AI) to synchronize hold state
 * across all consumers (VoiceViewModel, MediaPipeViewModel, etc.).
 */
data class HoldEvent(
    val voiceIndex: Int,
    val holding: Boolean,
    val origin: ControlEventOrigin = ControlEventOrigin.UI
)

/**
 * Generic controller for routing synth parameter events.
 * 
 * This is a protocol-agnostic event bus that ViewModels subscribe to.
 * Input handlers (MIDI, Tidal, etc.) emit events through this controller.
 * Internal producers (TweakSequencer, UI) also emit through here.
 * 
 * This separation allows multiple input sources without ViewModels
 * needing to know about specific protocols like MIDI or OSC.
 */
@SingleIn(AppScope::class)
class SynthController @Inject constructor() {

    // Private mutable flows
    private val _onPulseStart = MutableSharedFlow<Int>(
        replay = 1,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    private val _onPulseEnd = MutableSharedFlow<Int>(
        replay = 1,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    private val _onControlChange = MutableSharedFlow<ControlEvent>(
        replay = 1,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    private val _onBendChange = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _onHoldChange = MutableSharedFlow<HoldEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Public read-only flows for ViewModels to subscribe
    val onPulseStart: Flow<Int> = _onPulseStart.asSharedFlow()
    val onPulseEnd: Flow<Int> = _onPulseEnd.asSharedFlow()
    val onControlChange: Flow<ControlEvent> = _onControlChange.asSharedFlow()
    val onBendChange: Flow<Float> = _onBendChange.asSharedFlow()
    val onHoldChange: Flow<HoldEvent> = _onHoldChange.asSharedFlow()

    // ═══════════════════════════════════════════════════════════
    // PLUGIN PORT ROUTING
    // ═══════════════════════════════════════════════════════════

    /**
     * Delegate for setting plugin port values.
     * Set once by DspSynthEngine during initialization via [setDelegates].
     */
    var pluginPortSetter: ((PluginControlId, PortValue) -> Boolean)? = null
        private set

    /**
     * Delegate for getting plugin port values.
     * Set once by DspSynthEngine during initialization via [setDelegates].
     */
    var pluginPortGetter: ((PluginControlId) -> PortValue?)? = null
        private set

    /**
     * Initialize the engine delegates. Must be called exactly once during setup.
     */
    fun setDelegates(
        setter: (PluginControlId, PortValue) -> Boolean,
        getter: (PluginControlId) -> PortValue?
    ) {
        check(pluginPortSetter == null && pluginPortGetter == null) { "Delegates already set" }
        pluginPortSetter = setter
        pluginPortGetter = getter
    }

    /**
     * Lazy StateFlow map for plugin control values.
     * Created on first access, seeded from engine.
     */
    private val _controlFlows = mutableMapOf<PluginControlId, InterceptingMutableStateFlow>()

    /**
     * Get a StateFlow for observing a plugin control value.
     * 
     * The flow is created lazily on first access and seeded with
     * the current value from the engine. Subsequent calls return
     * the same flow instance.
     * 
     * @param id The plugin control identifier (uri + symbol)
     * @return StateFlow of the control value
     */
    fun controlFlow(id: PluginControlId): MutableStateFlow<PortValue> {
        return _controlFlows.getOrPut(id) {
            // Seed from engine on first access
            val initialValue = pluginPortGetter?.invoke(id) ?: PortValue.FloatValue(0.5f)
            InterceptingMutableStateFlow(id, initialValue)
        }
    }

    /**
     * Internal implementation of MutableStateFlow that intercepts writes
     * and forwards them to the synth engine via the pluginPortSetter delegate.
     */
    @OptIn(ExperimentalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
    private inner class InterceptingMutableStateFlow(
        private val id: PluginControlId,
        initialValue: PortValue
    ) : MutableStateFlow<PortValue> {
        private val flow = MutableStateFlow(initialValue)

        /**
         * Update the flow value from the engine without triggering pluginPortSetter.
         * Used after preset restore where the plugin already has the value.
         */
        fun updateFromEngine(v: PortValue) {
            flow.value = v
        }

        /** Forward a value change to the synth engine and emit a legacy control event. */
        private fun onValueChanged(v: PortValue, origin: ControlEventOrigin = ControlEventOrigin.UI) {
            pluginPortSetter?.invoke(id, v)
            emitControlChange(id.key, v.asFloat(), origin)
        }

        /** Set value with an explicit origin (used by [setPluginControl]). */
        fun setValueWithOrigin(v: PortValue, origin: ControlEventOrigin) {
            flow.value = v
            onValueChanged(v, origin)
        }

        override var value: PortValue
            get() = flow.value
            set(v) {
                flow.value = v
                onValueChanged(v)
            }

        override val replayCache: List<PortValue> get() = flow.replayCache
        override val subscriptionCount: StateFlow<Int> get() = flow.subscriptionCount

        override suspend fun collect(collector: FlowCollector<PortValue>): Nothing {
            flow.collect(collector)
        }

        override fun compareAndSet(expect: PortValue, update: PortValue): Boolean {
            val result = flow.compareAndSet(expect, update)
            if (result) onValueChanged(update)
            return result
        }

        override fun tryEmit(value: PortValue): Boolean {
            val result = flow.tryEmit(value)
            if (result) onValueChanged(value)
            return result
        }

        override suspend fun emit(value: PortValue) {
            flow.emit(value)
            onValueChanged(value)
        }

        @ExperimentalCoroutinesApi
        override fun resetReplayCache() {
            flow.resetReplayCache()
        }
    }

    /**
     * Re-read all active control flows from the engine.
     *
     * Called after preset restore to sync StateFlows with
     * engine state that was set directly via PortRegistry.
     */
    fun refreshControlFlows() {
        val snapshot = _controlFlows.entries.toList()
        for ((id, flow) in snapshot) {
            val engineValue = pluginPortGetter?.invoke(id) ?: continue
            flow.updateFromEngine(engineValue)
        }
    }

    /**
     * Set a plugin control value using the unified PluginControlId.
     *
     * This is the preferred way for ViewModels to set plugin parameters.
     * It emits a control event (for MIDI mapping and UI sync),
     * updates the StateFlow, AND sets the plugin port value via the delegate.
     *
     * @param id The plugin control identifier (uri + symbol)
     * @param value The port value to set
     * @param origin The source of this change
     * @return true if the port was set successfully
     */
    fun setPluginControl(
        id: PluginControlId,
        value: PortValue,
        origin: ControlEventOrigin = ControlEventOrigin.UI
    ): Boolean {
        // Update the StateFlow if it exists (this handles engine update and emission)
        val flow = _controlFlows[id]
        if (flow != null) {
            flow.setValueWithOrigin(value, origin)
            return true
        }
        
        // No flow exists yet, update engine directly
        emitControlChange(id.key, value.asFloat(), origin)
        return pluginPortSetter?.invoke(id, value) ?: false
    }

    /**
     * Get a plugin control value using the unified PluginControlId.
     * 
     * @param id The plugin control identifier (uri + symbol)
     * @return The current port value, or null if not found
     */
    fun getPluginControl(id: PluginControlId): PortValue? {
        return pluginPortGetter?.invoke(id)
    }

    /**
     * Set a plugin control value by its string key (format "pluginUri:symbol").
     *
     * Used by MidiInputHandler to bridge MIDI CC values into the typed plugin control system.
     *
     * @param key The control key in "uri:symbol" format
     * @param value The port value to set
     * @param origin The source of this change
     * @return true if the key was parsed and the port was set successfully
     */
    fun setPluginControlByKey(key: String, value: PortValue, origin: ControlEventOrigin): Boolean {
        val id = PluginControlId.parse(key) ?: return false
        return setPluginControl(id, value, origin)
    }

    // ═══════════════════════════════════════════════════════════
    // LEGACY CONTROL EVENT METHODS
    // ═══════════════════════════════════════════════════════════

    /**
     * Emit a pulse start event for a voice.
     * Called by input handlers when a note-on is received.
     */
    fun emitPulseStart(voiceIndex: Int) {
        _onPulseStart.tryEmit(voiceIndex)
    }

    /**
     * Emit a pulse end event for a voice.
     * Called by input handlers when a note-off is received.
     */
    fun emitPulseEnd(voiceIndex: Int) {
        _onPulseEnd.tryEmit(voiceIndex)
    }

    /**
     * Emit a control change event.
     * Called by input handlers, UI controls, and sequencer.
     */
    fun emitControlChange(
        controlId: String, 
        value: Float, 
        origin: ControlEventOrigin = ControlEventOrigin.UI
    ) {
        val event = ControlEvent(controlId, value, origin)
        _onControlChange.tryEmit(event)
    }

    /**
     * Emit a bend change event.
     * Called by AI agents and other sources to control the pitch bender.
     * @param amount Bend amount from -1 (full down) to +1 (full up), 0 = center/neutral
     */
    fun emitBendChange(amount: Float) {
        _onBendChange.tryEmit(amount.coerceIn(-1f, 1f))
    }

    /**
     * Emit a per-voice hold state change.
     * Called by VoiceViewModel (UI), MediaPipeViewModel (gesture), or AI agents.
     * All consumers listen to [onHoldChange] and update their local state.
     */
    fun emitHoldChange(voiceIndex: Int, holding: Boolean, origin: ControlEventOrigin = ControlEventOrigin.UI) {
        _onHoldChange.tryEmit(HoldEvent(voiceIndex, holding, origin))
    }
}

