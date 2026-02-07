package org.balch.orpheus.core.controller

import kotlinx.coroutines.flow.MutableStateFlow
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.PortValue.BoolValue
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.PortValue.IntValue

/** Creates a `(Float) -> Unit` setter lambda that wraps into [FloatValue]. */
fun MutableStateFlow<PortValue>.floatSetter(): (Float) -> Unit = { value = FloatValue(it) }

/** Creates a `(Boolean) -> Unit` setter lambda that wraps into [BoolValue]. */
fun MutableStateFlow<PortValue>.boolSetter(): (Boolean) -> Unit = { value = BoolValue(it) }

/** Creates an `(Int) -> Unit` setter lambda that wraps into [IntValue]. */
fun MutableStateFlow<PortValue>.intSetter(): (Int) -> Unit = { value = IntValue(it) }

/** Creates a setter lambda for any [Enum] type, storing its ordinal as [IntValue]. */
fun <E : Enum<E>> MutableStateFlow<PortValue>.enumSetter(): (E) -> Unit = { value = IntValue(it.ordinal) }
