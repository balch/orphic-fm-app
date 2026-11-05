package org.balch.orpheus.features.drum

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DrumTriggerPortTest {
    /** The panel state only asks whether a native engine exists, so everything else can stay blank. */
    private val synthEngine = Proxy.newProxyInstance(
        SynthEngine::class.java.classLoader,
        arrayOf(SynthEngine::class.java),
    ) { _, method, _ -> if (method.returnType == java.lang.Boolean.TYPE) false else null } as SynthEngine

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Default
        override val io = Dispatchers.Default
        override val default = Dispatchers.Default
        override val unconfined = Dispatchers.Unconfined
    }

    private val writes = mutableListOf<Pair<PluginControlId, Boolean>>()
    private val controller = SynthController().apply {
        setDelegates(
            setter = { id, value -> writes += id to value.asBoolean(); true },
            getter = { PortValue.BoolValue(false) },
        )
    }
    private val scope = FeatureCoroutineScope()
    private val viewModel = DrumViewModel(synthEngine, controller, dispatchers, scope)

    @AfterTest
    fun stopTheScope() = scope.cancel()

    @Test
    fun padsPressAndReleaseTheirPorts() {
        val actions = viewModel.actions
        actions.startBdTrigger()
        actions.stopBdTrigger()
        actions.startSdTrigger()
        actions.stopSdTrigger()
        actions.startHhTrigger()
        actions.stopHhTrigger()
        val expected = listOf(DrumSymbol.BD_TRIGGER, DrumSymbol.SD_TRIGGER, DrumSymbol.HH_TRIGGER)
            .flatMap { listOf(it.controlId to true, it.controlId to false) }
        assertEquals(expected, writes.filter { (id, _) -> "_trigger" in id.symbol && "_src" !in id.symbol })
    }

    @Test
    fun aMidiPressLightsThePad() = runBlocking {
        val subscribed = launch { viewModel.stateFlow.collect {} }
        val trigger = DrumSymbol.SD_TRIGGER.controlId
        controller.setPluginControl(trigger, PortValue.FloatValue(1f), ControlEventOrigin.MIDI)
        withTimeout(5_000) { viewModel.stateFlow.first { it.isSdActive } }
        controller.setPluginControl(trigger, PortValue.FloatValue(0f), ControlEventOrigin.MIDI)
        withTimeout(5_000) { viewModel.stateFlow.first { !it.isSdActive } }
        subscribed.cancel()
    }
}
