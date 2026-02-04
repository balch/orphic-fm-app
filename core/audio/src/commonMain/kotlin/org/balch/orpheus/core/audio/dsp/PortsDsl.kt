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
class FloatPort(override val symbol: Symbol) : PortDef<Float>() {
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
class IntPort(override val symbol: Symbol) : PortDef<Int>() {
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
class BoolPort(override val symbol: Symbol) : PortDef<Boolean>() {
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
 * Main DSL builder for port definitions.
 */
@PortsDsl
class PortsBuilder(private val startIndex: Int = 0) {
    private val _defs = mutableListOf<PortDef<*>>()
    
    val ports: List<ControlPort> 
        get() = _defs.mapIndexed { i, def -> def.toControlPort(startIndex + i) }
    
    /** Define a float port using type-safe enum symbol */
    fun float(sym: PortSymbol, init: FloatPort.() -> Unit) {
        _defs += FloatPort(sym.symbol).apply { 
            name = sym.displayName
            init()
        }
    }
    
    /** Define an int port using type-safe enum symbol */
    fun int(sym: PortSymbol, init: IntPort.() -> Unit) {
        _defs += IntPort(sym.symbol).apply {
            name = sym.displayName
            init()
        }
    }
    
    /** Define a boolean port using type-safe enum symbol */
    fun bool(sym: PortSymbol, init: BoolPort.() -> Unit) {
        _defs += BoolPort(sym.symbol).apply {
            name = sym.displayName  
            init()
        }
    }
    
    // Generic accessors
    fun getValue(symbol: Symbol): PortValue? = 
        _defs.find { it.symbol == symbol }?.getValue()
    
    fun getValue(sym: PortSymbol): PortValue? = getValue(sym.symbol)
    
    fun setValue(symbol: Symbol, value: PortValue): Boolean {
        val def = _defs.find { it.symbol == symbol } ?: return false
        def.setValue(value)
        return true
    }
    
    fun setValue(sym: PortSymbol, value: PortValue): Boolean = setValue(sym.symbol, value)
}

/** DSL entry point */
inline fun ports(startIndex: Int = 0, init: PortsBuilder.() -> Unit): PortsBuilder =
    PortsBuilder(startIndex).apply(init)
