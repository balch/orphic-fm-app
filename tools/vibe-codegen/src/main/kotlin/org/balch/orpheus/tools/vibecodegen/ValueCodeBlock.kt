package org.balch.orpheus.tools.vibecodegen

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.asClassName
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Reflectively renders any value from the Vibe schema as a Kotlin source literal.
 * Walks the real decoded object graph (not a hand-maintained mirror of the schema),
 * so it never goes stale as the schema gains fields.
 */
fun valueToCodeBlock(value: Any?): CodeBlock = when {
    value == null -> CodeBlock.of("null")
    value is Boolean || value is Int -> CodeBlock.of("%L", value)
    value is Float -> CodeBlock.of("%Lf", value)
    value is String -> CodeBlock.of("%S", value)
    value is IntRange -> CodeBlock.of("%L..%L", value.first, value.last)
    value is Enum<*> -> CodeBlock.of("%T.%L", value::class.asClassName(), value.name)
    value is List<*> -> listCodeBlock(value)
    value is Map<*, *> -> mapCodeBlock(value)
    else -> dataClassCodeBlock(value)
}

private fun listCodeBlock(list: List<*>): CodeBlock {
    if (list.isEmpty()) return CodeBlock.of("emptyList()")
    val builder = CodeBlock.builder().add("listOf(\n").indent()
    list.forEach { element ->
        builder.add(valueToCodeBlock(element))
        builder.add(",\n")
    }
    return builder.unindent().add(")").build()
}

private fun mapCodeBlock(map: Map<*, *>): CodeBlock {
    if (map.isEmpty()) return CodeBlock.of("emptyMap()")
    val builder = CodeBlock.builder().add("mapOf(\n").indent()
    map.forEach { (key, mapValue) ->
        builder.add(valueToCodeBlock(key))
        builder.add(" to ")
        builder.add(valueToCodeBlock(mapValue))
        builder.add(",\n")
    }
    return builder.unindent().add(")").build()
}

/**
 * Handles every non-primitive case in the schema: plain data classes (e.g. [OrpheusEngine]),
 * sealed object singletons (e.g. `LickMode.None`), and sealed data-class subtypes
 * (e.g. `TrackRole.Melodic(...)`) — all via the runtime KClass, so a new field or a new sealed
 * subtype never needs a matching branch added here.
 */
private fun dataClassCodeBlock(value: Any): CodeBlock {
    val kClass = value::class
    if (kClass.objectInstance != null) {
        return CodeBlock.of("%T", kClass.asClassName())
    }
    val constructor = kClass.primaryConstructor
        ?: error("${kClass.qualifiedName} has no primary constructor and is not an object")
    val properties = kClass.memberProperties.associateBy { it.name }
    val builder = CodeBlock.builder().add("%T(\n", kClass.asClassName()).indent()
    constructor.parameters.forEach { parameter ->
        val name = parameter.name
            ?: error("${kClass.qualifiedName} constructor parameter has no name")
        val property = properties[name]
            ?: error("${kClass.qualifiedName} has no property matching constructor parameter '$name'")
        builder.add("%L = ", name)
        builder.add(valueToCodeBlock(property.call(value)))
        builder.add(",\n")
    }
    return builder.unindent().add(")").build()
}
