package org.balch.orpheus.core.audio.dsp

// ── Unit type constants (must match C++ orpheus_graph.h) ────────────────────

const val UNIT_TRIANGLE_OSC = 0
const val UNIT_SQUARE_OSC = 1
const val UNIT_MULTIPLY = 2
const val UNIT_ADD = 3
const val UNIT_MULTIPLY_ADD = 4
const val UNIT_ENVELOPE = 5
const val UNIT_LINEAR_RAMP = 6
const val UNIT_PASS_THROUGH = 7
const val UNIT_PEAK_FOLLOWER = 8
const val UNIT_HARD_CLIP = 9
const val UNIT_LIMITER = 10
const val UNIT_PLAITS = 11
const val UNIT_CLOUDS = 12
const val UNIT_RINGS = 13
const val UNIT_WARPS = 14
const val UNIT_DELAY_LINE = 15
const val UNIT_REVERB = 16
const val UNIT_MASTER_OUT = 17

// ── Output port constants ───────────────────────────────────────────────────

const val OPORT_OUT = 0
const val OPORT_OUT_RIGHT = 1
const val OPORT_AUX = 2

// ── Input port constants ────────────────────────────────────────────────────

const val IPORT_INPUT = 0
const val IPORT_INPUT_A = 1
const val IPORT_INPUT_B = 2
const val IPORT_INPUT_C = 3
const val IPORT_FREQUENCY = 4
const val IPORT_AMPLITUDE = 5
const val IPORT_GATE = 6
const val IPORT_TIME = 7
const val IPORT_DRIVE = 8
const val IPORT_TRIGGER = 9

// ── Param key constants ─────────────────────────────────────────────────────

const val PARAM_FREQUENCY = 0
const val PARAM_AMPLITUDE = 1
const val PARAM_ATTACK = 2
const val PARAM_DECAY = 3
const val PARAM_SUSTAIN = 4
const val PARAM_RELEASE = 5
const val PARAM_TIME = 6
const val PARAM_HALF_LIFE = 7
const val PARAM_MAX_DELAY = 8
const val PARAM_DRIVE = 9
const val PARAM_INPUT_A = 10
const val PARAM_INPUT_B = 11
const val PARAM_INPUT_C = 12

// ── DSL types ───────────────────────────────────────────────────────────────

/**
 * Reference to a unit in the graph, returned by builder methods.
 */
class UnitRef internal constructor(
    internal val id: Int,
    internal val type: Int,
) {
    // Output port accessors
    val out get() = PortRef(id, OPORT_OUT, isOutput = true)
    val outRight get() = PortRef(id, OPORT_OUT_RIGHT, isOutput = true)
    val aux get() = PortRef(id, OPORT_AUX, isOutput = true)

    // Input port accessors
    val input get() = PortRef(id, IPORT_INPUT, isOutput = false)
    val inputA get() = PortRef(id, IPORT_INPUT_A, isOutput = false)
    val inputB get() = PortRef(id, IPORT_INPUT_B, isOutput = false)
    val inputC get() = PortRef(id, IPORT_INPUT_C, isOutput = false)
    val frequency get() = PortRef(id, IPORT_FREQUENCY, isOutput = false)
    val amplitude get() = PortRef(id, IPORT_AMPLITUDE, isOutput = false)
    val gate get() = PortRef(id, IPORT_GATE, isOutput = false)
    val time get() = PortRef(id, IPORT_TIME, isOutput = false)
    val drive get() = PortRef(id, IPORT_DRIVE, isOutput = false)
    val trigger get() = PortRef(id, IPORT_TRIGGER, isOutput = false)
}

/**
 * Reference to a specific port on a unit.
 */
data class PortRef(
    val unitId: Int,
    val port: Int,
    val isOutput: Boolean,
)

/**
 * Builder for setting initial param values on a unit.
 */
class UnitParamBuilder internal constructor() {
    internal val params = mutableListOf<Pair<Int, Float>>()

    infix fun Int.set(value: Float) {
        params.add(this to value)
    }

    // Named setters for common params
    var frequency: Float
        get() = error("Write-only")
        set(v) { params.add(PARAM_FREQUENCY to v) }
    var amplitude: Float
        get() = error("Write-only")
        set(v) { params.add(PARAM_AMPLITUDE to v) }
    var attack: Float
        get() = error("Write-only")
        set(v) { params.add(PARAM_ATTACK to v) }
    var decay: Float
        get() = error("Write-only")
        set(v) { params.add(PARAM_DECAY to v) }
    var sustain: Float
        get() = error("Write-only")
        set(v) { params.add(PARAM_SUSTAIN to v) }
    var release: Float
        get() = error("Write-only")
        set(v) { params.add(PARAM_RELEASE to v) }
    var time: Float
        get() = error("Write-only")
        set(v) { params.add(PARAM_TIME to v) }
    var halfLife: Float
        get() = error("Write-only")
        set(v) { params.add(PARAM_HALF_LIFE to v) }
    var maxDelay: Float
        get() = error("Write-only")
        set(v) { params.add(PARAM_MAX_DELAY to v) }
    var driveAmount: Float
        get() = error("Write-only")
        set(v) { params.add(PARAM_DRIVE to v) }
    var inputA: Float
        get() = error("Write-only")
        set(v) { params.add(PARAM_INPUT_A to v) }
    var inputB: Float
        get() = error("Write-only")
        set(v) { params.add(PARAM_INPUT_B to v) }
    var inputC: Float
        get() = error("Write-only")
        set(v) { params.add(PARAM_INPUT_C to v) }
}

/**
 * Connection between an output port and an input port.
 */
data class Connection(
    val srcUnitId: Int,
    val srcPort: Int,
    val dstUnitId: Int,
    val dstPort: Int,
)

/**
 * Port map entry: maps (URI hash, symbol hash) -> (unit_id, port).
 */
data class PortMapEntry(
    val uriHash: Int,
    val symbolHash: Int,
    val unitId: Int,
    val port: Int,
)

/**
 * Builder for port map entries.
 */
class PortMapBuilder internal constructor(
    private val unitLookup: (String) -> Int,
) {
    internal val entries = mutableListOf<PortMapEntry>()

    fun map(uri: String, symbol: String, unitName: String, port: Int) {
        entries.add(
            PortMapEntry(
                uriHash = hash16(uri),
                symbolHash = hash16(symbol),
                unitId = unitLookup(unitName),
                port = port,
            )
        )
    }
}

/**
 * Main builder for the wiring graph DSL.
 */
class WiringGraphBuilder {
    private data class UnitDesc(
        val type: Int,
        val id: Int,
        val name: String,
        val params: List<Pair<Int, Float>>,
    )

    private val units = mutableListOf<UnitDesc>()
    private val connections = mutableListOf<Connection>()
    private val portMapEntries = mutableListOf<PortMapEntry>()
    private val nameToId = mutableMapOf<String, Int>()

    private fun addUnit(
        type: Int,
        name: String,
        init: (UnitParamBuilder.() -> Unit)? = null,
    ): UnitRef {
        val id = units.size
        val params = if (init != null) {
            UnitParamBuilder().apply(init).params
        } else {
            emptyList()
        }
        units.add(UnitDesc(type, id, name, params))
        nameToId[name] = id
        return UnitRef(id, type)
    }

    // ── Unit factory methods ────────────────────────────────────────────────

    fun triangleOsc(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_TRIANGLE_OSC, name, init)

    fun squareOsc(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_SQUARE_OSC, name, init)

    fun multiply(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_MULTIPLY, name, init)

    fun add(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_ADD, name, init)

    fun multiplyAdd(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_MULTIPLY_ADD, name, init)

    fun envelope(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_ENVELOPE, name, init)

    fun linearRamp(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_LINEAR_RAMP, name, init)

    fun passThrough(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_PASS_THROUGH, name, init)

    fun peakFollower(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_PEAK_FOLLOWER, name, init)

    fun hardClip(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_HARD_CLIP, name, init)

    fun limiter(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_LIMITER, name, init)

    fun plaits(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_PLAITS, name, init)

    fun clouds(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_CLOUDS, name, init)

    fun rings(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_RINGS, name, init)

    fun warps(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_WARPS, name, init)

    fun delayLine(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_DELAY_LINE, name, init)

    fun reverb(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_REVERB, name, init)

    fun masterOut(name: String, init: (UnitParamBuilder.() -> Unit)? = null) =
        addUnit(UNIT_MASTER_OUT, name, init)

    // ── Connection wiring ───────────────────────────────────────────────────

    infix fun PortRef.to(other: PortRef) {
        require(this.isOutput) { "Source must be an output port" }
        require(!other.isOutput) { "Destination must be an input port" }
        connections.add(Connection(this.unitId, this.port, other.unitId, other.port))
    }

    // ── Port map ────────────────────────────────────────────────────────────

    fun portMap(init: PortMapBuilder.() -> Unit) {
        val builder = PortMapBuilder { name ->
            nameToId[name] ?: error("Unknown unit: $name")
        }
        builder.init()
        portMapEntries.addAll(builder.entries)
    }

    // ── Serialization to ODWG binary format ─────────────────────────────────

    fun serialize(): ByteArray {
        val totalParams = units.sumOf { it.params.size }
        val unitSectionSize = units.sumOf { 6 + it.params.size * 6 }
        val connSectionSize = connections.size * 8
        val portMapSectionSize = 2 + portMapEntries.size * 8
        val totalSize = 12 + unitSectionSize + connSectionSize + portMapSectionSize

        val buf = ByteArray(totalSize)
        var pos = 0

        fun writeU16(v: Int) {
            buf[pos++] = (v and 0xFF).toByte()
            buf[pos++] = ((v shr 8) and 0xFF).toByte()
        }

        fun writeF32(v: Float) {
            val bits = v.toRawBits()
            buf[pos++] = (bits and 0xFF).toByte()
            buf[pos++] = ((bits shr 8) and 0xFF).toByte()
            buf[pos++] = ((bits shr 16) and 0xFF).toByte()
            buf[pos++] = ((bits shr 24) and 0xFF).toByte()
        }

        // Header (12 bytes)
        buf[pos++] = 'O'.code.toByte()
        buf[pos++] = 'D'.code.toByte()
        buf[pos++] = 'W'.code.toByte()
        buf[pos++] = 'G'.code.toByte()
        writeU16(1) // version
        writeU16(units.size)
        writeU16(connections.size)
        writeU16(totalParams)

        // Units section
        for (u in units) {
            writeU16(u.type)
            writeU16(u.id)
            writeU16(u.params.size)
            for ((key, value) in u.params) {
                writeU16(key)
                writeF32(value)
            }
        }

        // Connections section (8 bytes each)
        for (c in connections) {
            writeU16(c.srcUnitId)
            writeU16(c.srcPort)
            writeU16(c.dstUnitId)
            writeU16(c.dstPort)
        }

        // Port map section
        writeU16(portMapEntries.size)
        for (e in portMapEntries) {
            writeU16(e.uriHash)
            writeU16(e.symbolHash)
            writeU16(e.unitId)
            writeU16(e.port)
        }

        return buf
    }
}

// ── Top-level DSL entry point ───────────────────────────────────────────────

/**
 * Build a wiring graph and serialize it to the ODWG binary format
 * consumed by the C++ graph runtime.
 *
 * Example usage:
 * ```kotlin
 * val bytes = wiringGraph {
 *     val osc = triangleOsc("osc1") { frequency = 440f }
 *     val env = envelope("env1") {
 *         attack = 0.01f
 *         decay = 0.1f
 *         sustain = 0.7f
 *         release = 0.3f
 *     }
 *     val vca = multiply("vca")
 *     val out = masterOut("master")
 *
 *     osc.out to vca.inputA
 *     env.out to vca.inputB
 *     vca.out to out.input
 *
 *     portMap {
 *         map("osc", "freq", "osc1", IPORT_FREQUENCY)
 *         map("env", "gate", "env1", IPORT_GATE)
 *     }
 * }
 * ```
 */
fun wiringGraph(init: WiringGraphBuilder.() -> Unit): ByteArray =
    WiringGraphBuilder().apply(init).serialize()

// ── Hash function ───────────────────────────────────────────────────────────

/**
 * 16-bit hash matching the C++ `hash16()` function in `orpheus_graph.cpp`.
 *
 * Both implementations use: `h = h * 31 + char_value` with 16-bit truncation.
 * The Kotlin version applies `and 0xFFFF` after each step to match
 * `uint16_t` overflow behavior in C++.
 */
fun hash16(str: String): Int {
    var h = 0
    for (c in str) {
        h = ((h * 31) + c.code) and 0xFFFF
    }
    return h
}
