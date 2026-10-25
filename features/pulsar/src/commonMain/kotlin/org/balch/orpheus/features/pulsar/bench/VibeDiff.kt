package org.balch.orpheus.features.pulsar.bench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.balch.orpheus.features.pulsar.models.Vibe

/** Same config as `FileMixChangeStore.vibeJson` and `CaptureWriter`, so trees and hashes agree. */
val vibeTreeJson: Json = Json { encodeDefaults = true }

/** One changed leaf. [path] is `a.b[2].c`; an absent side is [JsonNull]. */
@Serializable
data class VibeFieldChange(val path: String, val old: JsonElement, val new: JsonElement)

fun Vibe.toTree(): JsonObject =
    vibeTreeJson.encodeToJsonElement(Vibe.serializer(), this) as JsonObject

/** Throws when the tree no longer satisfies the model's `init` checks. */
fun JsonObject.toVibe(): Vibe = vibeTreeJson.decodeFromJsonElement(Vibe.serializer(), this)

fun diffVibe(from: Vibe, to: Vibe): List<VibeFieldChange> =
    diffTree(from.toTree(), to.toTree()).sortedBy { it.path }

fun diffTree(from: JsonElement, to: JsonElement, at: String = ""): List<VibeFieldChange> {
    if (from == to) return emptyList()
    val whole = listOf(VibeFieldChange(at, from, to))
    if (isAtomicPath(at)) return whole
    return when {
        // A different polymorphic subtype shares no fields, so replace it whole.
        from is JsonObject && to is JsonObject && from["type"] != to["type"] -> whole
        from is JsonObject && to is JsonObject ->
            (from.keys + to.keys).sorted().flatMap { key ->
                diffTree(from[key] ?: JsonNull, to[key] ?: JsonNull, if (at.isEmpty()) key else "$at.$key")
            }
        from is JsonArray && to is JsonArray && from.size == to.size && !(from.isPrimitives() && to.isPrimitives()) ->
            from.indices.flatMap { diffTree(from[it], to[it], "$at[$it]") }
        else -> whole
    }
}

private fun JsonArray.isPrimitives() = all { it is JsonPrimitive }

private sealed interface Step {
    data class Key(val name: String) : Step
    data class Index(val i: Int) : Step
}

private val INDEX = Regex("""\[(\d+)]""")

private fun parsePath(path: String): List<Step> {
    val steps = mutableListOf<Step>()
    for (part in path.split('.')) {
        val bracket = part.indexOf('[')
        val key = if (bracket < 0) part else part.substring(0, bracket)
        if (key.isNotEmpty()) steps += Step.Key(key)
        if (bracket >= 0) {
            INDEX.findAll(part.substring(bracket)).forEach { steps += Step.Index(it.groupValues[1].toInt()) }
        }
    }
    return steps
}

/** The element at [path], or null when any step is absent. A present JSON null returns [JsonNull]. */
fun JsonElement.at(path: String): JsonElement? {
    var cur: JsonElement = this
    for (step in parsePath(path)) {
        cur = when (step) {
            is Step.Key -> (cur as? JsonObject)?.get(step.name) ?: return null
            is Step.Index -> (cur as? JsonArray)?.getOrNull(step.i) ?: return null
        }
    }
    return cur
}

/** Returns a copy with [value] at [path]. [JsonNull] on an object key removes the key. */
fun JsonElement.patch(path: String, value: JsonElement): JsonElement =
    patchSteps(this, parsePath(path), value)

private fun JsonElement.kind() = when (this) {
    is JsonObject -> "an object"
    is JsonArray -> "a list"
    is JsonNull -> "null"
    is JsonPrimitive -> if (isString) "a string" else "a number or flag"
}

// [at] is the path walked so far, so a refusal names the node's kind and place, never its contents.
private fun patchSteps(node: JsonElement?, steps: List<Step>, value: JsonElement, at: String = ""): JsonElement {
    if (steps.isEmpty()) return value
    val rest = steps.drop(1)
    return when (val step = steps.first()) {
        is Step.Key -> {
            check(node == null || node is JsonNull || node is JsonObject) {
                if (node is JsonArray) {
                    "'$at' is a list; use $at[${step.name}]"
                } else {
                    "'$at' is ${node!!.kind()}, so it has no '${step.name}'"
                }
            }
            val obj = node as? JsonObject ?: JsonObject(emptyMap())
            val child = obj[step.name]
            val here = if (at.isEmpty()) step.name else "$at.${step.name}"
            if (rest.isEmpty() && value is JsonNull) {
                JsonObject(obj - step.name)
            } else {
                JsonObject(obj + (step.name to patchSteps(child, rest, value, here)))
            }
        }
        is Step.Index -> {
            val arr = node as? JsonArray ?: error("'${at.ifEmpty { "the vibe" }}' is ${node?.kind() ?: "missing"}, not a list")
            require(step.i in arr.indices) { "Index ${step.i} out of range for array of ${arr.size}" }
            JsonArray(arr.toMutableList().also { it[step.i] = patchSteps(arr[step.i], rest, value, "$at[${step.i}]") })
        }
    }
}

/** Applies [changes] in order to the tree, then decodes. Throws if the result is not a valid vibe. */
fun Vibe.patched(changes: List<Pair<String, JsonElement>>): Vibe =
    changes.fold(toTree() as JsonElement) { tree, (path, value) -> tree.patch(path, value) }
        .let { it as JsonObject }
        .toVibe()

private val SECTION_PROGRESSION = Regex("""arrangement\.sections\[\d+]\.customProgression""")

/** Paths that diff and patch as one whole value. Primitive and resized arrays are handled in [diffTree]. */
fun isAtomicPath(path: String): Boolean =
    path == "lick" || path == "bassLine" || path == "band" || path == "genre.customProgression" ||
        SECTION_PROGRESSION.matches(path)

private val MIXER_PATTERNS = listOf(
    Regex("""tracks\[\d+]\.(engineEdm|engineSpace)\.(volume|reverbSend|delaySend)"""),
    Regex("""tracks\[\d+]\.pan"""),
    Regex("""energy|complexity|mood|space"""),
    Regex("""effects\.(delayTimeA|delayTimeB|delayFeedback)"""),
    Regex("""arrangement\.sections\[\d+]\.trackOverrides\.\d+\.(volume|reverbSend|delaySend)"""),
    Regex("""arrangement\.sections\[\d+]\.(macroOverrides|rampTo)\.(energy|complexity|mood|space)"""),
    Regex("""arrangement\.sections\[\d+]\.delay\.(timeA|timeB|feedback)"""),
)

/** True for paths the Mixer owns, which are never vibe rows. */
fun isMixerPath(path: String): Boolean = MIXER_PATTERNS.any { it.matches(path) }

/** A refusal message when [edited] changes the fixed structure of [file], else null. */
fun structureRefusal(file: Vibe, edited: Vibe): String? {
    if (edited.tracks.size != file.tracks.size || edited.tracks.size != 8) {
        return "An edit cannot change the track count (always 8)."
    }
    val before = file.arrangement?.sections.orEmpty()
    val after = edited.arrangement?.sections.orEmpty()
    if (before.size != after.size) {
        return "An edit cannot change the section count (${before.size} to ${after.size})."
    }
    before.indices.firstOrNull { before[it].name != after[it].name }?.let {
        return "An edit cannot rename section $it ('${before[it].name}' to '${after[it].name}')."
    }
    return null
}

private fun JsonElement.leafPaths(at: String = ""): List<String> = when (this) {
    is JsonObject -> flatMap { (k, v) -> v.leafPaths(if (at.isEmpty()) k else "$at.$k") }
    is JsonArray -> withIndex().flatMap { (i, v) -> v.leafPaths("$at[$i]") }
    else -> listOf(at)
}

private fun mixerDiffs(base: JsonObject, other: JsonObject): List<Triple<String, JsonElement, JsonElement>> =
    (base.leafPaths() + other.leafPaths()).distinct().filter(::isMixerPath).sorted().mapNotNull {
        val b = base.at(it) ?: JsonNull
        val o = other.at(it) ?: JsonNull
        if (b == o) null else Triple(it, b, o)
    }

/** Mixer-owned leaf paths whose value differs between [base] and [edited], even inside whole containers. */
fun mixerChanges(base: Vibe, edited: Vibe): List<String> =
    mixerDiffs(base.toTree(), edited.toTree()).map { it.first }

/** This vibe with every mixer-owned leaf reset to [base]'s value (key removed where base has none). */
fun Vibe.withMixerFieldsOf(base: Vibe): Vibe {
    val baseTree = base.toTree()
    val diffs = mixerDiffs(baseTree, toTree())
    var tree: JsonElement = toTree()
    for ((path, value, _) in diffs) tree = tree.patch(path, value)
    // Stripping can leave an object of only nulls where base holds null or nothing; restore the null.
    for ((path, _, _) in diffs) {
        val prefixes = path.indices.filter { path[it] == '.' || path[it] == '[' }.map { path.substring(0, it) }
        for (prefix in prefixes.reversed()) {
            val now = tree.at(prefix)
            if (now is JsonObject && now.values.all { it is JsonNull } && (baseTree.at(prefix) ?: JsonNull) is JsonNull) {
                tree = tree.patch(prefix, JsonNull)
            }
        }
    }
    return (tree as JsonObject).toVibe()
}
