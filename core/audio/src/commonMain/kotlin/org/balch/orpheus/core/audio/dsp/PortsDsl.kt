package org.balch.orpheus.core.audio.dsp

/**
 * Marks DSL scope to prevent accidental access to outer receivers.
 */
@DslMarker
annotation class PortsDsl

/**
 * Interface for port symbols - each plugin defines its own enum implementing this.
 * Provides compile-time safety and auto-completion.
 */
interface PortSymbol {
    val symbol: Symbol
    val displayName: String get() = symbol.replaceFirstChar { it.uppercase() }
    
    /** Create qualified symbol "pluginUri:symbol" for use with PortRegistry */
    fun qualifiedSymbol(pluginUri: String): String = "$pluginUri:$symbol"
}

/**
 * Base class for port definitions with embedded getter/setter.
 */
@PortsDsl
sealed class PortDef<T> {
    abstract val symbol: Symbol
    abstract val name: String
    abstract fun toControlPort(index: Int): ControlPort
    abstract fun getValue(): PortValue
    abstract fun setValue(value: PortValue)
}

/**
 * Float port builder with receiver-based configuration.
 */
@PortsDsl
class FloatPortBuilder(override val symbol: Symbol) : PortDef<Float>() {
    override var name: String = symbol.replaceFirstChar { it.uppercase() }
    var default: Float = 0.5f
    var min: Float = 0f
    var max: Float = 1f
    var logarithmic: Boolean = false
    var units: String? = null
    
    private var _getter: (() -> Float)? = null
    private var _setter: ((Float) -> Unit)? = null
    
    /** Define how to read the current value */
    fun get(block: () -> Float) { _getter = block }
    
    /** Define how to apply a new value */
    fun set(block: (Float) -> Unit) { _setter = block }
    
    override fun toControlPort(index: Int) = ControlPort(
        index, symbol, name, PortType.FLOAT, default, min, max,
        isLogarithmic = logarithmic, units = units
    )
    
    override fun getValue() = PortValue.FloatValue(_getter?.invoke() ?: default)
    override fun setValue(value: PortValue) { _setter?.invoke(value.asFloat()) }
}

/**
 * Int port builder with receiver-based configuration.
 */
@PortsDsl
class IntPortBuilder(override val symbol: Symbol) : PortDef<Int>() {
    override var name: String = symbol.replaceFirstChar { it.uppercase() }
    var default: Int = 0
    var min: Int = 0
    var max: Int = 100
    var options: List<String>? = null  // Enum labels
    
    private var _getter: (() -> Int)? = null
    private var _setter: ((Int) -> Unit)? = null
    
    fun get(block: () -> Int) { _getter = block }
    fun set(block: (Int) -> Unit) { _setter = block }
    
    override fun toControlPort(index: Int) = ControlPort(
        index, symbol, name, PortType.INT, 
        default.toFloat(), min.toFloat(), max.toFloat(),
        enumLabels = options
    )
    
    override fun getValue() = PortValue.IntValue(_getter?.invoke() ?: default)
    override fun setValue(value: PortValue) { _setter?.invoke(value.asInt()) }
}

/**
 * Boolean port builder with receiver-based configuration.
 */
@PortsDsl
class BoolPortBuilder(override val symbol: Symbol) : PortDef<Boolean>() {
    override var name: String = symbol.replaceFirstChar { it.uppercase() }
    var default: Boolean = false
    
    private var _getter: (() -> Boolean)? = null
    private var _setter: ((Boolean) -> Unit)? = null
    
    fun get(block: () -> Boolean) { _getter = block }
    fun set(block: (Boolean) -> Unit) { _setter = block }
    
    override fun toControlPort(index: Int) = ControlPort(
        index, symbol, name, PortType.BOOLEAN,
        if (default) 1f else 0f, 0f, 1f
    )
    
    override fun getValue() = PortValue.BoolValue(_getter?.invoke() ?: default)
    override fun setValue(value: PortValue) { _setter?.invoke(value.asBoolean()) }
}

/**
 * Builder for audio port definitions.
 */
@PortsDsl
class AudioPortBuilder {
    var index: Int = 0
    var symbol: String = ""
    var name: String = ""
    var isInput: Boolean = true
    
    fun toAudioPort(): AudioPort = AudioPort(index, symbol, name, isInput)
}

/**
 * Builder for control port type selection.
 * Allows syntax: controlPort(Symbol) { floatType { ... } }
 */
@PortsDsl
class ControlPortTypeBuilder(private val sym: PortSymbol) {
    internal var portDef: PortDef<*>? = null
    
    /** Define a float type control port */
    fun floatType(init: FloatPortBuilder.() -> Unit) {
        val s = sym.symbol
        val d = sym.displayName
        portDef = FloatPortBuilder(s).apply {
            name = d
            init()
        }
    }
    
    /** Define an int type control port */
    fun intType(init: IntPortBuilder.() -> Unit) {
        val s = sym.symbol
        val d = sym.displayName
        portDef = IntPortBuilder(s).apply {
            name = d
            init()
        }
    }
    
    /** Define a boolean type control port */
    fun boolType(init: BoolPortBuilder.() -> Unit) {
        val s = sym.symbol
        val d = sym.displayName
        portDef = BoolPortBuilder(s).apply {
            name = d
            init()
        }
    }
}

/**
 * Builder for control port type selection using raw string symbol.
 */
@PortsDsl
class ControlPortTypeBuilderRaw(private val symbol: String) {
    internal var portDef: PortDef<*>? = null
    
    /** Define a float type control port */
    fun floatType(init: FloatPortBuilder.() -> Unit) {
        portDef = FloatPortBuilder(symbol).apply { init() }
    }
    
    /** Define an int type control port */
    fun intType(init: IntPortBuilder.() -> Unit) {
        portDef = IntPortBuilder(symbol).apply { init() }
    }
    
    /** Define a boolean type control port */
    fun boolType(init: BoolPortBuilder.() -> Unit) {
        portDef = BoolPortBuilder(symbol).apply { init() }
    }
}

/**
 * Main DSL builder for port definitions.
 */
@PortsDsl
class PortsBuilder(private val startIndex: Int = 0) {
    private val _controlDefs = mutableListOf<PortDef<*>>()
    private val _audioDefs = mutableListOf<AudioPortBuilder>()
    
    val controlPorts: List<ControlPort> 
        get() = _controlDefs.mapIndexed { i, def -> def.toControlPort(startIndex + i) }
    
    val audioPorts: List<AudioPort>
        get() = _audioDefs.map { it.toAudioPort() }
    
    val ports: List<Port>
        get() = audioPorts + controlPorts
    
    /** Define a control port using type-safe enum symbol with nested type */
    fun controlPort(sym: PortSymbol, init: ControlPortTypeBuilder.() -> Unit) {
        val builder = ControlPortTypeBuilder(sym).apply(init)
        builder.portDef?.let { _controlDefs += it }
    }
    
    /** Define a control port using raw string symbol with nested type */
    fun controlPort(symbol: String, init: ControlPortTypeBuilderRaw.() -> Unit) {
        val builder = ControlPortTypeBuilderRaw(symbol).apply(init)
        builder.portDef?.let { _controlDefs += it }
    }
    
    /** Define an audio port */
    fun audioPort(init: AudioPortBuilder.() -> Unit) {
        _audioDefs += AudioPortBuilder().apply(init)
    }
    
    
    // Generic accessors
    fun getValue(symbol: Symbol): PortValue? = 
        _controlDefs.find { it.symbol == symbol }?.getValue()
    
    fun getValue(sym: PortSymbol): PortValue? = getValue(sym.symbol)
    
    fun setValue(symbol: Symbol, value: PortValue): Boolean {
        val def = _controlDefs.find { it.symbol == symbol } ?: return false
        def.setValue(value)
        return true
    }
    
    fun setValue(sym: PortSymbol, value: PortValue): Boolean = setValue(sym.symbol, value)
}

/** DSL entry point */
inline fun ports(startIndex: Int = 0, init: PortsBuilder.() -> Unit): PortsBuilder =
    PortsBuilder(startIndex).apply(init)
