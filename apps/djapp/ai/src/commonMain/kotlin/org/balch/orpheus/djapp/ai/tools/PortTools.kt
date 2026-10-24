package org.balch.orpheus.djapp.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.djapp.ai.collab.CollabEvents
import org.balch.orpheus.djapp.ai.collab.CollabMixer
import org.balch.orpheus.djapp.ai.collab.CollabResult
import org.balch.orpheus.djapp.ai.collab.MixControl
import org.balch.orpheus.core.plugin.ControlPort
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortType
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.features.pulsar.bench.Macro

@LLMDescription("Arguments for ports_list.")
@Serializable
data class PortsListArgs(
    @property:LLMDescription("Glob on the port key uri:symbol, * and ? wildcards, ignoring case (e.g. *pulsar:track_3_*). Without a wildcard it matches any key containing the text. Omit for all ports.")
    val filter: String? = null,
    @property:LLMDescription("Most ports to return.")
    val limit: Int = 200,
)

@LLMDescription("One engine control port.")
@Serializable
data class PortInfo(
    @property:LLMDescription("uri:symbol, the key port_get and port_set take.")
    val key: String,
    val name: String,
    @property:LLMDescription("FLOAT, INT or BOOLEAN (0 or 1).")
    val type: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val units: String? = null,
    @property:LLMDescription("Labels for an INT port's choices.")
    val enumLabels: List<String>? = null,
    @property:LLMDescription("Current value, or null when the engine has none yet.")
    val value: Float? = null,
)

@LLMDescription("Ports matching the filter, sorted by key.")
@Serializable
data class PortsListResult(
    val ok: Boolean,
    val message: String,
    @property:LLMDescription("How many ports matched; more than ports.size when limit cut the list.")
    val matched: Int = 0,
    val ports: List<PortInfo> = emptyList(),
)

@LLMDescription("Arguments for port_get.")
@Serializable
data class PortGetArgs(
    @property:LLMDescription("Port key uri:symbol from ports_list.")
    val key: String,
)

@LLMDescription("One port and its value.")
@Serializable
data class PortGetResult(
    val ok: Boolean,
    val message: String,
    val port: PortInfo? = null,
)

@LLMDescription("Arguments for port_set.")
@Serializable
data class PortSetArgs(
    @property:LLMDescription("Port key uri:symbol from ports_list.")
    val key: String,
    @property:LLMDescription("New value; clamped to the port's min..max. INT rounds; BOOLEAN is 1 at 0.5 or more.")
    val value: Float,
)

@LLMDescription("What port_set wrote.")
@Serializable
data class PortSetResult(
    val ok: Boolean,
    val message: String,
    val key: String = "",
    @property:LLMDescription("The value written, after clamping and rounding.")
    val value: Float? = null,
    val clamped: Boolean = false,
    @property:LLMDescription("Always false: a mix capture never includes raw port writes.")
    val captured: Boolean = false,
    @property:LLMDescription("The mix tool that would capture this change, when there is one.")
    val hint: String? = null,
    @property:LLMDescription(REFUSAL_FIELD)
    val refusal: String? = null,
)

private fun ControlPort.info(key: String, value: PortValue?) = PortInfo(
    key = key,
    name = name,
    type = type.name,
    min = min,
    max = max,
    default = default,
    units = units,
    enumLabels = enumLabels,
    value = value?.asFloat(),
)

/** A glob with * and ?, ignoring case; plain text matches as a substring. */
internal fun portFilter(filter: String?): (String) -> Boolean {
    val f = filter?.trim().orEmpty()
    if (f.isEmpty()) return { true }
    if ('*' !in f && '?' !in f) return { it.contains(f, ignoreCase = true) }
    val pattern = f.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } }
    val regex = Regex(pattern, RegexOption.IGNORE_CASE)
    return { regex.matches(it) }
}

private fun MutableMap<PluginControlId, MixControl>.block(first: PulsarSymbol, control: (Int) -> MixControl) {
    for (t in 0 until 8) put(PulsarSymbol.entries[first.ordinal + t].controlId, control(t))
}

/** The mixer control each Pulsar level, pan, send and macro port writes over (both voice slots); mix_set captures these. */
private val CAPTURED_PORT_CONTROLS: Map<PluginControlId, MixControl> = buildMap {
    block(PulsarSymbol.TRACK_0_VOLUME, MixControl::Level)
    block(PulsarSymbol.TRACK_0_VOLUME_SPACE, MixControl::Level)
    block(PulsarSymbol.TRACK_0_PAN, MixControl::Pan)
    block(PulsarSymbol.TRACK_0_REVERB_SEND_TRACK) { MixControl.Send(it, reverb = true) }
    block(PulsarSymbol.TRACK_0_REVERB_SEND_SPACE) { MixControl.Send(it, reverb = true) }
    block(PulsarSymbol.TRACK_0_DELAY_SEND) { MixControl.Send(it, reverb = false) }
    block(PulsarSymbol.TRACK_0_DELAY_SEND_SPACE) { MixControl.Send(it, reverb = false) }
    put(PulsarSymbol.ENERGY.controlId, MixControl.MacroKnob(Macro.ENERGY))
    put(PulsarSymbol.COMPLEXITY.controlId, MixControl.MacroKnob(Macro.COMPLEXITY))
    put(PulsarSymbol.MOOD.controlId, MixControl.MacroKnob(Macro.MOOD))
    put(PulsarSymbol.SPACE.controlId, MixControl.MacroKnob(Macro.SPACE))
}

/** Every port a mixer control rides on: also the audition gain (the strip's level) and the section pan glide. */
private val MIX_PORT_CONTROLS: Map<PluginControlId, MixControl> = CAPTURED_PORT_CONTROLS + buildMap {
    block(PulsarSymbol.TRACK_0_AUDITION_GAIN, MixControl::Level)
    block(PulsarSymbol.TRACK_0_PAN_GLIDE, MixControl::Pan)
}

/** The mixer control a write to [id] changes, or null for a port outside the mix. */
internal fun mixControl(id: PluginControlId): MixControl? = MIX_PORT_CONTROLS[id]

/** Names the mix tool that captures a change to [id], or null when none does. */
internal fun mixHint(id: PluginControlId): String? = when (val c = CAPTURED_PORT_CONTROLS[id]) {
    is MixControl.MacroKnob -> "use mix_set field=macro macro=${c.macro.name.lowercase()} to keep this"
    is MixControl.Level -> "use mix_set field=level_db track=${c.track} to keep this"
    is MixControl.Pan -> "use mix_set field=pan track=${c.track} to keep this"
    is MixControl.Send -> "use mix_set field=${if (c.reverb) "reverb" else "delay"} track=${c.track} to keep this"
    else -> null
}

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class PortsListTool(
    private val portRegistry: PortRegistry,
    private val synthController: SynthController,
) : ToolProvider {
    override val tool: Tool<PortsListArgs, PortsListResult> by lazy {
        object : Tool<PortsListArgs, PortsListResult>(
            argsType = typeToken<PortsListArgs>(),
            resultType = typeToken<PortsListResult>(),
            name = "ports_list",
            description = """
                List the engine's control ports with their range, units and current value. Keys are uri:symbol;
                filter with a glob such as *pulsar:track_3_* (Pulsar tracks are 0-7).
            """.trimIndent(),
        ) {
            override suspend fun execute(args: PortsListArgs): PortsListResult = guarded({ PortsListResult(false, it) }) {
                if (args.limit < 1) badArg("limit must be at least 1")
                val match = portFilter(args.filter)
                val keys = portRegistry.controlPorts.keys.filter(match).sorted()
                val ports = keys.take(args.limit).map { key ->
                    val port = portRegistry.controlPorts.getValue(key).second
                    port.info(key, PluginControlId.parse(key)?.let(synthController::getPluginControl))
                }
                PortsListResult(
                    ok = true,
                    message = if (keys.isEmpty()) "No ports match \"${args.filter}\"." else "ok",
                    matched = keys.size,
                    ports = ports,
                )
            }
        }
    }
}

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class PortGetTool(
    private val portRegistry: PortRegistry,
    private val synthController: SynthController,
) : ToolProvider {
    override val tool: Tool<PortGetArgs, PortGetResult> by lazy {
        object : Tool<PortGetArgs, PortGetResult>(
            argsType = typeToken<PortGetArgs>(),
            resultType = typeToken<PortGetResult>(),
            name = "port_get",
            description = "Read one control port by its uri:symbol key (see ports_list): range, units and current value.",
        ) {
            override suspend fun execute(args: PortGetArgs): PortGetResult = guarded({ PortGetResult(false, it) }) {
                val key = args.key.trim()
                val port = portRegistry.controlPorts[key]?.second ?: badArg("unknown port \"$key\"; find keys with ports_list")
                PortGetResult(true, "ok", port.info(key, PluginControlId.parse(key)?.let(synthController::getPluginControl)))
            }
        }
    }
}

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class PortSetTool(
    private val portRegistry: PortRegistry,
    private val synthController: SynthController,
    private val collabMixer: CollabMixer,
    private val events: CollabEvents,
) : ToolProvider {
    override val tool: Tool<PortSetArgs, PortSetResult> by lazy {
        object : Tool<PortSetArgs, PortSetResult>(
            argsType = typeToken<PortSetArgs>(),
            resultType = typeToken<PortSetResult>(),
            name = "port_set",
            description = """
                Write one control port by its uri:symbol key (see ports_list); the value is clamped to its range.
                A mix capture never includes these writes, and a vibe load can overwrite them. For track level,
                pan, sends and the four macros use mix_set instead; writes to those ports follow mix_set's rules:
                $GATE_RULES
            """.trimIndent(),
        ) {
            override suspend fun execute(args: PortSetArgs): PortSetResult = guarded({ PortSetResult(false, it) }) {
                val key = args.key.trim()
                val port = portRegistry.controlPorts[key]?.second ?: badArg("unknown port \"$key\"; find keys with ports_list")
                val id = PluginControlId.parse(key) ?: badArg("bad key \"$key\"")
                if (!args.value.isFinite()) badArg("value must be a number")
                val clamped = args.value.coerceIn(port.min, port.max)
                val value = when (port.type) {
                    PortType.FLOAT -> PortValue.FloatValue(clamped)
                    PortType.INT -> PortValue.IntValue(clamped.roundToInt())
                    PortType.BOOLEAN -> PortValue.BoolValue(clamped >= 0.5f)
                }
                val control = mixControl(id)
                if (control == null) {
                    synthController.setPluginControl(id, value, ControlEventOrigin.AI)
                } else {
                    val edit = collabMixer.toolEdit(events, control) { synthController.setPluginControl(id, value, ControlEventOrigin.AI) }
                    if (edit is CollabResult.Refused) return@guarded PortSetResult(false, edit.reason, key, refusal = edit.refusal.name)
                }
                val hint = mixHint(id)
                PortSetResult(
                    ok = true,
                    message = if (hint != null) "Written, but not captured: $hint." else "Written; not captured.",
                    key = key,
                    value = value.asFloat(),
                    clamped = clamped != args.value,
                    captured = false,
                    hint = hint,
                )
            }
        }
    }
}
