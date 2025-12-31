package org.balch.orpheus.core.tidal

/**
 * A robust parser for TidalCycles mini-notation, ported from Strudel's PEG definition.
 * 
 * Supports:
 * - Sequencing: "a b c"
 * - Stacking: "a, b"
 * - Polymeters: "{a, b}" and "{a, b}%4"
 * - Slowcat/Alternation: "<a b c>"
 * - Speed Modifiers: "*2", "/2", "fast 2", "slow 2"
 * - Euclidean Rhythms: "(3,8)"
 * - Polymetric Subdivision: "[a b]"
 * - Replication: "!3"
 * - Elongation: "@3"
 * - Degradation: "?"
 * - Rests: "~" or "-"
 */
object TidalParser {

    // === AST Definitions ===

    sealed class TidalNode {
        data class Atom(val value: String, val location: IntRange? = null) : TidalNode()
        data class Sequence(val children: List<TidalNode>) : TidalNode()
        data class Stack(val children: List<TidalNode>) : TidalNode()
        data class Poly(val children: List<TidalNode>, val stepsPerCycle: TidalNode? = null) : TidalNode()
        data class SlowCat(val children: List<TidalNode>) : TidalNode()
        
        data class Transform(
            val type: TransformType, 
            val node: TidalNode, 
            val params: List<TidalNode>
        ) : TidalNode()
    }

    enum class TransformType {
        Fast, Slow, Replicate, Elongate, Degrade, Euclid, Range
    }
    
    // === Parser Implementation ===

    class ParseException(message: String, val position: Int) : Exception("$message at index $position")

    private class MiniParser(val input: String) {
        var cursor = 0

        fun parse(): TidalNode {
            val node = parseStack()
            skipWhitespace()
            if (cursor < input.length) {
                // throw ParseException("Unexpected character '${input[cursor]}'", cursor)
                // Relaxed: Just ignore trailing garbage or allow comments? 
                // Strudel parser throws error. Let's throw.
                // Actually parseStack consumes comma-separated, so it consumes everything normally.
                // Except if there is trailing garbage.
                throw ParseException("Unexpected character '${input[cursor]}'", cursor)
            }
            return node
        }

        private fun parseStack(): TidalNode {
            val children = mutableListOf<TidalNode>()
            do {
                children.add(parseSequence())
            } while (consume(','))
            
            return if (children.size == 1) children[0] else TidalNode.Stack(children)
        }

        private fun parseSequence(): TidalNode {
            val children = mutableListOf<TidalNode>()
            while (cursor < input.length) {
                skipWhitespace()
                // Stop at special characters that end a sequence context
                if (cursor < input.length && input[cursor] in ",]}>()") break
                
                children.add(parseSliceWithModifiers())
            }
            
            return if (children.size == 1) children[0] else TidalNode.Sequence(children)
        }

        private fun parseSliceWithModifiers(): TidalNode {
            var node = parseSlice()
            
            // Apply postfix modifiers
            while (true) {
                skipWhitespace()
                if (cursor >= input.length) break
                
                val char = input[cursor]
                when (char) {
                    '*' -> {
                        consume()
                        val factor = parseNumberNode() ?: TidalNode.Atom("2")
                        node = TidalNode.Transform(TransformType.Fast, node, listOf(factor))
                    }
                    '/' -> {
                        consume()
                        val factor = parseNumberNode() ?: TidalNode.Atom("2")
                        node = TidalNode.Transform(TransformType.Slow, node, listOf(factor))
                    }
                    '!' -> {
                        consume()
                        val factor = parseNumberNode() ?: TidalNode.Atom("1") 
                        node = TidalNode.Transform(TransformType.Replicate, node, listOf(factor))
                    }
                    '@' -> {
                        consume()
                        val factor = parseNumberNode() ?: TidalNode.Atom("1")
                        node = TidalNode.Transform(TransformType.Elongate, node, listOf(factor))
                    }
                    '?' -> {
                        consume()
                        val chance = parseNumberNode() ?: TidalNode.Atom("0.5")
                        node = TidalNode.Transform(TransformType.Degrade, node, listOf(chance))
                    }
                    '(' -> {
                        // Euclidean: (k, n, r)
                        consume() // (
                        val k = parseSequence() 
                        expect(',')
                        val n = parseSequence()
                        var r: TidalNode? = null
                        if (consume(',')) {
                            r = parseSequence()
                        }
                        expect(')')
                        node = TidalNode.Transform(TransformType.Euclid, node, listOfNotNull(k, n, r))
                    }
                    '.' -> {
                        // Range: 0..3 becomes sequence [0, 1, 2, 3]
                        // Check for ".." (two dots)
                        if (cursor + 1 < input.length && input[cursor + 1] == '.') {
                            consume() // first .
                            consume() // second .
                            val endNode = parseSlice()
                            node = TidalNode.Transform(TransformType.Range, node, listOf(endNode))
                        } else {
                            return node
                        }
                    }
                    else -> return node
                }
            }
            return node
        }

        private fun parseSlice(): TidalNode {
            skipWhitespace()
            if (cursor >= input.length) return TidalNode.Atom("~") 

            val char = input[cursor]
            return when (char) {
                '[' -> {
                    consume()
                    val node = parseStack() 
                    expect(']')
                    node
                }
                '<' -> {
                    consume()
                    val catChildren = mutableListOf<TidalNode>()
                    while(cursor < input.length) {
                         skipWhitespace()
                         if (cursor < input.length && input[cursor] == '>') break
                         catChildren.add(parseSliceWithModifiers())
                    }
                    expect('>')
                    TidalNode.SlowCat(catChildren)
                }
                '{' -> {
                    consume()
                    val children = mutableListOf<TidalNode>()
                    do {
                        children.add(parseSequence())
                    } while (consume(','))
                    expect('}')
                    var steps: TidalNode? = null
                    if (consume('%')) {
                        steps = parseSliceWithModifiers()
                    }
                    TidalNode.Poly(children, steps)
                }
                '~', '-', '.' -> {
                    consume()
                    TidalNode.Atom("~")
                }
                else -> parseAtom()
            }
        }

        private fun parseAtom(): TidalNode {
            val start = cursor
            // Allow numbers, letters, quotes
            // Handle '.' specially - it could be:
            // 1. Part of a float (e.g., 0.5) - include it if followed by digit
            // 2. Range operator (..) - stop parsing here
            // 3. Rest (.) - stop parsing here
            while (cursor < input.length) {
                val c = input[cursor]
                if (c.isWhitespace() || c in ",[]{}<>*!/@?()") break
                
                // Special handling for '.'
                if (c == '.') {
                    // Check if this is a decimal point (previous char is digit, next char is digit)
                    val prevIsDigit = cursor > start && input[cursor - 1].isDigit()
                    val nextIsDigit = cursor + 1 < input.length && input[cursor + 1].isDigit()
                    
                    if (prevIsDigit && nextIsDigit) {
                        // This is a decimal point, include it
                        cursor++
                        continue
                    }
                    // Otherwise, stop - it's either a range (..) or rest (.)
                    break
                }
                cursor++
            }
            if (start == cursor) {
                throw ParseException("Expected atom", cursor)
            }
            val value = input.substring(start, cursor)
            return TidalNode.Atom(value, start until cursor)
        }

        private fun parseNumberNode(): TidalNode? {
             val start = cursor
             if (cursor >= input.length) return null
             // Heuristic: check if next char is digit? 
             // Or just try parsedSlice.
             // If we are at `* [1 2]`, `parseSlice` handles it.
             return parseSlice()
        }
        
        // Helpers
        private fun skipWhitespace() {
            while (cursor < input.length && input[cursor].isWhitespace()) cursor++
        }
        
        private fun consume(char: Char): Boolean {
            skipWhitespace()
            if (cursor < input.length && input[cursor] == char) {
                cursor++
                return true
            }
            return false
        }
        
        private fun consume() {
            if (cursor < input.length) cursor++
        }
        
        private fun expect(char: Char) {
            if (!consume(char)) {
                throw ParseException("Expected '$char'", cursor)
            }
        }
    }
    
    // === Compiler Implementation ===
    
    fun <T> compile(node: TidalNode, mapper: (TidalNode.Atom) -> Pattern<T>): Pattern<T> {
        return when (node) {
            is TidalNode.Atom -> {
                if (node.value == "~" || node.value == "-") Pattern.silence()
                else mapper(node)
            }
            is TidalNode.Sequence -> {
                // Check if any children have elongation weights
                val hasWeights = node.children.any { 
                    it is TidalNode.Transform && it.type == TransformType.Elongate 
                }
                
                if (hasWeights) {
                    // Use timeCat for weighted sequences
                    val weighted = node.children.map { child ->
                        val (weight, innerNode) = extractWeight(child)
                        weight to compile(innerNode, mapper)
                    }
                    Pattern.timeCat(weighted)
                } else {
                    val patterns = node.children.map { compile(it, mapper) }
                    Pattern.fastcat(patterns)
                }
            }
            is TidalNode.Stack -> {
                val patterns = node.children.map { compile(it, mapper) }
                Pattern.stack(patterns)
            }
            is TidalNode.SlowCat -> {
                val patterns = node.children.map { compile(it, mapper) }
                Pattern.slowcat(patterns)
            }
            is TidalNode.Poly -> {
                 val patterns = node.children.map { compile(it, mapper) }
                 Pattern.stack(patterns)
            }
            is TidalNode.Transform -> {
                // Note: params are read directly from node.params by getFactor(), not compiled
                // This avoids incorrectly validating numeric params (like euclid steps) as voice indices 
                
                fun getFactor(): Double {
                     val p0 = node.params.firstOrNull()
                     if (p0 is TidalNode.Atom) return p0.value.toDoubleOrNull() ?: 1.0
                     return 1.0
                }

                fun getIntParam(index: Int, default: Int): Int {
                    val p = node.params.getOrNull(index)
                    if (p is TidalNode.Atom) return p.value.toIntOrNull() ?: default
                    return default
                }
                
                when (node.type) {
                    TransformType.Fast -> {
                        val inner = compile(node.node, mapper)
                        inner.fast(getFactor())
                    }
                    TransformType.Slow -> {
                        val inner = compile(node.node, mapper)
                        inner.slow(getFactor())
                    }
                    TransformType.Replicate -> {
                        val inner = compile(node.node, mapper)
                        val count = getFactor().toInt()
                        Pattern.fastcat(List(count) { inner })
                    }
                    TransformType.Elongate -> {
                        // When Elongate is seen at compile time (not as child of Sequence),
                        // it means the parent didn't handle it - just compile the inner pattern
                        compile(node.node, mapper)
                    }
                    TransformType.Euclid -> {
                        val inner = compile(node.node, mapper)
                        val k = getFactor().toInt()
                        val n = getIntParam(1, 8)
                        applyEuclid(inner, k, n)
                    }
                    TransformType.Range -> {
                        // Range: expand N..M to fastcat(N, N+1, ..., M)
                        val startNode = node.node
                        val endNode = node.params.firstOrNull()
                        
                        if (startNode is TidalNode.Atom && endNode is TidalNode.Atom) {
                            val startVal = startNode.value.toIntOrNull()
                            val endVal = endNode.value.toIntOrNull()
                            
                            if (startVal != null && endVal != null) {
                                val range = if (startVal <= endVal) {
                                    (startVal..endVal).toList()
                                } else {
                                    (startVal downTo endVal).toList()
                                }
                                val patterns = range.map { v ->
                                    val atomNode = TidalNode.Atom(v.toString(), startNode.location)
                                    mapper(atomNode)
                                }
                                Pattern.fastcat(patterns)
                            } else {
                                // Non-numeric range - just compile the start
                                compile(startNode, mapper)
                            }
                        } else {
                            // Complex range - compile start 
                            compile(startNode, mapper)
                        }
                    }
                    TransformType.Degrade -> compile(node.node, mapper)
                }
            }
        }
    }
    
    /**
     * Extract weight from a node that may be wrapped in Elongate transform.
     * Returns (weight, unwrapped node).
     */
    private fun extractWeight(node: TidalNode): Pair<Double, TidalNode> {
        return if (node is TidalNode.Transform && node.type == TransformType.Elongate) {
            val weight = (node.params.firstOrNull() as? TidalNode.Atom)?.value?.toDoubleOrNull() ?: 1.0
            weight to node.node
        } else {
            1.0 to node
        }
    }
    
    // === Bjorklund Implementation ===
    
    private fun <T> applyEuclid(pat: Pattern<T>, k: Int, n: Int): Pattern<T> {
        val pulses = bjorklund(k, n)
        val segments = pulses.map { active ->
            if (active) pat else Pattern.silence()
        }
        return Pattern.fastcat(segments)
    }

    private fun bjorklund(k: Int, n: Int): List<Boolean> {
        if (n == 0) return emptyList()
        val steps = k.coerceIn(0, n)
        val bitmap = BooleanArray(n)
        var bucket = 0
        for (i in 0 until n) {
            bucket += k
            if (bucket >= n) {
                bucket -= n
                bitmap[i] = true
            } else {
                bitmap[i] = false
            }
        }
        return bitmap.toList()
    }

    // === Public API ===

    sealed class ParseResult<T> {
        data class Success<T>(val pattern: Pattern<T>) : ParseResult<T>()
        data class Failure<T>(val message: String) : ParseResult<T>()
    }

    fun parseGates(input: String): ParseResult<TidalEvent> {
        return try {
            val node = MiniParser(input).parse()
            val pattern: Pattern<TidalEvent> = compile(node) { atom ->
                val idx = atom.value.toIntOrNull() ?: throw IllegalArgumentException("Invalid index: ${atom.value}")
                // Accept 1-based input (1-12) and convert to 0-based (0-7)
                if (idx !in 1..12) throw IllegalArgumentException("Voice index must be 1-12, got: $idx")
                val zeroBasedIdx = idx - 1
                var event: TidalEvent = TidalEvent.Gate(zeroBasedIdx, true)
                atom.location?.let { loc ->
                    event = event.withLocation(SourceLocation(loc.first, loc.last + 1))
                }
                Pattern.pure(event)
            }
            ParseResult.Success(pattern)
        } catch (e: Exception) {
            ParseResult.Failure(e.message ?: "Unknown error")
        }
    }
    
    fun parseFloats(
        input: String,
        mapper: (Float) -> TidalEvent
    ): ParseResult<TidalEvent> {
        return try {
            val node = MiniParser(input).parse()
            val pattern: Pattern<TidalEvent> = compile(node) { atom ->
                val value = atom.value.toFloatOrNull() ?: throw IllegalArgumentException("Invalid float: ${atom.value}")
                Pattern.pure(mapper(value))
            }
            ParseResult.Success(pattern)
        } catch (e: Exception) {
            ParseResult.Failure(e.message ?: "Unknown parse error")
        }
    }

    fun parseSounds(input: String): ParseResult<TidalEvent> {
        return try {
            val node = MiniParser(input).parse()
            val pattern: Pattern<TidalEvent> = compile(node) { atom ->
                val cleaned = atom.value.replace("\"", "")
                var event: TidalEvent = TidalEvent.Sample(cleaned)
                atom.location?.let { loc ->
                     event = event.withLocation(SourceLocation(loc.first, loc.last + 1))
                }
                Pattern.pure(event)
            }
            ParseResult.Success(pattern)
        } catch (e: Exception) {
            ParseResult.Failure(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Parse a note pattern (e.g., "c3 d#4 e2") into a pattern of Note events.
     * Note names: c, d, e, f, g, a, b with optional # or b for accidentals
     * Octave numbers: 0-9 (c4 = middle C = MIDI 60)
     */
    fun parseNotes(input: String): ParseResult<TidalEvent> {
        return try {
            val node = MiniParser(input).parse()
            val pattern: Pattern<TidalEvent> = compile(node) { atom ->
                val midiNote = noteNameToMidi(atom.value)
                var event: TidalEvent = TidalEvent.Note(midiNote)
                atom.location?.let { loc ->
                    event = event.withLocation(SourceLocation(loc.first, loc.last + 1))
                }
                Pattern.pure(event)
            }
            ParseResult.Success(pattern)
        } catch (e: Exception) {
            ParseResult.Failure(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Convert note name to MIDI number.
     * c4 = middle C = MIDI 60
     */
    private fun noteNameToMidi(note: String): Int {
        // Handle silence/rest
        if (note == "~" || note == "-") return 0
        
        // Support raw MIDI numbers
        val rawMidi = note.toIntOrNull()
        if (rawMidi != null) {
            return rawMidi
        }
        
        // Support # for sharp, b or - for flat (e-3 = eb3 = E-flat)
        val match = Regex("^([a-gA-G])([#b-]?)(\\d)$").find(note)
            ?: throw IllegalArgumentException("Invalid note: $note (use format like c3, c#4, db5, e-3)")
        
        val (noteName, accidental, octaveStr) = match.destructured
        val octave = octaveStr.toInt()
        
        val baseNote = when (noteName.lowercase()) {
            "c" -> 0
            "d" -> 2
            "e" -> 4
            "f" -> 5
            "g" -> 7
            "a" -> 9
            "b" -> 11
            else -> throw IllegalArgumentException("Invalid note: $note")
        }
        
        val modifier = when (accidental) {
            "#" -> 1
            "b", "-" -> -1  // Both 'b' and '-' mean flat
            else -> 0
        }
        
        // MIDI: c4 = 60, so octave 4 at C = 60
        return (octave + 1) * 12 + baseNote + modifier
    }
    
    // === AST Introspection ===
    
    /**
     * Result of parsing that includes both the AST and compiled pattern.
     */
    data class ParseResultWithAst<T>(
        val ast: TidalNode,
        val pattern: Pattern<T>
    )
    
    /**
     * Parse input string and return both the AST and compiled pattern.
     * Useful for REPL introspection and visualization.
     */
    fun <T> parseWithAst(input: String, mapper: (TidalNode.Atom) -> Pattern<T>): Result<ParseResultWithAst<T>> {
        return try {
            val ast = MiniParser(input).parse()
            val pattern = compile(ast, mapper)
            Result.success(ParseResultWithAst(ast, pattern))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Parse input and return just the AST (no compilation).
     * Useful for syntax inspection and debugging.
     */
    fun parseToAst(input: String): Result<TidalNode> {
        return try {
            Result.success(MiniParser(input).parse())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Pretty-print the AST as a tree structure.
     * Useful for debugging and visualization.
     */
    fun prettyPrintAst(node: TidalNode, indent: String = ""): String {
        val childIndent = "$indent  "
        return when (node) {
            is TidalNode.Atom -> {
                val locStr = node.location?.let { " @${it.first}..${it.last}" } ?: ""
                "${indent}Atom(\"${node.value}\"$locStr)"
            }
            is TidalNode.Sequence -> {
                val children = node.children.joinToString("\n") { prettyPrintAst(it, childIndent) }
                "${indent}Sequence[\n$children\n${indent}]"
            }
            is TidalNode.Stack -> {
                val children = node.children.joinToString("\n") { prettyPrintAst(it, childIndent) }
                "${indent}Stack(\n$children\n${indent})"
            }
            is TidalNode.SlowCat -> {
                val children = node.children.joinToString("\n") { prettyPrintAst(it, childIndent) }
                "${indent}SlowCat<\n$children\n${indent}>"
            }
            is TidalNode.Poly -> {
                val children = node.children.joinToString("\n") { prettyPrintAst(it, childIndent) }
                val stepsStr = node.stepsPerCycle?.let { "\n${childIndent}steps=${prettyPrintAst(it, "")}" } ?: ""
                "${indent}Poly{\n$children$stepsStr\n${indent}}"
            }
            is TidalNode.Transform -> {
                val inner = prettyPrintAst(node.node, childIndent)
                val params = node.params.joinToString(", ") { prettyPrintAst(it, "") }
                "${indent}${node.type}($params) ->\n$inner"
            }
        }
    }
}

// Extension functions for easy pattern creation from strings
fun String.gates(): Pattern<TidalEvent> = TidalParser.parseGates(this).let {
    when (it) {
        is TidalParser.ParseResult.Success -> it.pattern
        is TidalParser.ParseResult.Failure -> Pattern.silence()
    }
}

fun String.tune(voiceIndex: Int): Pattern<TidalEvent> = 
    TidalParser.parseFloats(this) { TidalEvent.VoiceTune(voiceIndex, it) }.let {
        when (it) {
            is TidalParser.ParseResult.Success -> it.pattern
            is TidalParser.ParseResult.Failure -> Pattern.silence()
        }
    }

fun String.delayTime(delayIndex: Int): Pattern<TidalEvent> = 
    TidalParser.parseFloats(this) { TidalEvent.DelayTime(delayIndex, it) }.let {
        when (it) {
            is TidalParser.ParseResult.Success -> it.pattern
            is TidalParser.ParseResult.Failure -> Pattern.silence()
        }
    }

fun String.lfoFreq(lfoIndex: Int): Pattern<TidalEvent> = 
    TidalParser.parseFloats(this) { TidalEvent.LfoFreq(lfoIndex, it) }.let {
        when (it) {
            is TidalParser.ParseResult.Success -> it.pattern
            is TidalParser.ParseResult.Failure -> Pattern.silence()
        }
    }
