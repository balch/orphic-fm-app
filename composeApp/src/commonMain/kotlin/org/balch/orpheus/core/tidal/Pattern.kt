package org.balch.orpheus.core.tidal

/**
 * Represents a time span in the pattern cycle.
 * Tidal uses rational time where 1 cycle = 1 bar.
 * 
 * @param start Start time in cycles (0.0 = beginning of cycle)
 * @param end End time in cycles
 */
data class Arc(
    val start: Double,
    val end: Double
) {
    val duration: Double get() = end - start
    
    /**
     * Get the part of this arc that falls within the query arc.
     */
    fun sect(other: Arc): Arc? {
        val s = maxOf(start, other.start)
        val e = minOf(end, other.end)
        return if (s < e) Arc(s, e) else null
    }
    
    /**
     * Shift the arc forward in time.
     */
    fun shift(amount: Double): Arc = Arc(start + amount, end + amount)
    
    companion object {
        val UNIT = Arc(0.0, 1.0)
    }
}

/**
 * An event in a pattern, occurring within a time span.
 * 
 * @param whole The complete event span (null for continuous events)
 * @param part The portion of the event that's active in this query
 * @param value The event's value
 */
data class Event<T>(
    val whole: Arc?,
    val part: Arc,
    val value: T
) {
    /**
     * Returns true if this event has an onset during the query arc.
     */
    fun hasOnset(): Boolean = whole != null && whole.start == part.start
    
    /**
     * Map the event's value.
     */
    fun <R> fmap(f: (T) -> R): Event<R> = Event(whole, part, f(value))
    
    /**
     * Shift the event in time.
     */
    fun shift(amount: Double): Event<T> = Event(
        whole?.shift(amount),
        part.shift(amount),
        value
    )
}

/**
 * A Pattern represents a function from time to events.
 * This is the core Tidal abstraction - patterns are infinite and cyclic.
 * 
 * @param query Function that returns events within the given time arc
 */
class Pattern<T>(
    val query: (Arc) -> List<Event<T>>
) {
    companion object {
        /**
         * Create a pattern from a constant value.
         * The value is present for the entire cycle.
         */
        fun <T> pure(value: T): Pattern<T> = Pattern { arc ->
            // Return one event per whole cycle within the query arc
            val startCycle = kotlin.math.floor(arc.start).toInt()
            val endCycle = kotlin.math.ceil(arc.end).toInt()
            (startCycle until endCycle).mapNotNull { cycle ->
                val whole = Arc(cycle.toDouble(), (cycle + 1).toDouble())
                whole.sect(arc)?.let { part ->
                    Event(whole, part, value)
                }
            }
        }

        /**
         * Silence - a pattern with no events.
         */
        fun <T> silence(): Pattern<T> = Pattern { emptyList() }
        
        /**
         * Create a pattern from a list of values, distributed evenly across one cycle.
         */
        fun <T> fastcat(vararg patterns: Pattern<T>): Pattern<T> = fastcat(patterns.toList())
        
        /**
         * Fast concatenation - crams all patterns into one cycle.
         * 
         * This is implemented as slowcat().fast(n), following Strudel's approach.
         * This preserves cycle information for nested slowcat patterns (e.g., <0 2 4>),
         * ensuring they alternate correctly even when nested inside a sequence.
         */
        fun <T> fastcat(patterns: List<Pattern<T>>): Pattern<T> {
            if (patterns.isEmpty()) return silence()
            if (patterns.size == 1) return patterns[0]
            
            // Key insight from Strudel: fastcat = slowcat(...pats).fast(n)
            // This preserves cycle information for nested patterns!
            return slowcat(patterns).fast(patterns.size.toDouble())
        }
        
        /**
         * Alias for fastcat - concatenate patterns sequentially within one cycle.
         */
        fun <T> cat(vararg patterns: Pattern<T>): Pattern<T> = fastcat(*patterns)
        
        /**
         * Slow concatenation - each pattern gets a full cycle.
         * 
         * Implementation follows Strudel's slowcat which:
         * 1. Uses absolute cycle number to pick pattern index
         * 2. Calculates offset to ensure constituent pattern cycles aren't skipped
         * 3. Shifts query time back and result time forward
         */
        fun <T> slowcat(vararg patterns: Pattern<T>): Pattern<T> = slowcat(patterns.toList())
        
        fun <T> slowcat(patterns: List<Pattern<T>>): Pattern<T> {
            if (patterns.isEmpty()) return silence()
            if (patterns.size == 1) return patterns[0]
            
            val n = patterns.size
            
            return Pattern { arc ->
                // Split the query into individual cycles first
                val startCycle = kotlin.math.floor(arc.start).toInt()
                val endCycle = kotlin.math.ceil(arc.end).toInt()
                
                (startCycle until endCycle).flatMap { cycle ->
                    val cycleArc = Arc(cycle.toDouble(), (cycle + 1).toDouble())
                    val intersection = cycleArc.sect(arc) ?: return@flatMap emptyList()
                    
                    // Use absolute cycle number to select pattern (like Strudel's span.begin.sam())
                    val patternIndex = ((cycle % n) + n) % n
                    val pattern = patterns[patternIndex]
                    
                    // Calculate offset: this ensures that constituent pattern cycles aren't skipped.
                    // For example if 3 patterns are slowcat-ed, the 4th cycle of the result should
                    // be the 2nd (rather than 4th) cycle from the first pattern.
                    // offset = floor(cycle) - floor(cycle / n)
                    val offset = cycle - (cycle / n)
                    
                    // Query the pattern with time shifted back by offset
                    val shiftedArc = Arc(intersection.start - offset, intersection.end - offset)
                    
                    pattern.query(shiftedArc).map { event ->
                        // Shift the result times forward by offset
                        event.shift(offset.toDouble())
                    }
                }
            }
        }
        
        /**
         * Stack patterns on top of each other (simultaneous).
         */
        fun <T> stack(vararg patterns: Pattern<T>): Pattern<T> = stack(patterns.toList())
        
        fun <T> stack(patterns: List<Pattern<T>>): Pattern<T> = Pattern { arc ->
            patterns.flatMap { it.query(arc) }
        }
        
        /**
         * Time-weighted concatenation - like fastcat but with proportional timing.
         * 
         * Each pair is (weight, pattern). Time is distributed proportionally:
         * e.g., [(2, a), (1, b)] means 'a' gets 2/3 of the cycle, 'b' gets 1/3.
         * 
         * This is Strudel's `timeCat` function - essential for the elongation (@) operator.
         */
        fun <T> timeCat(weighted: List<Pair<Double, Pattern<T>>>): Pattern<T> {
            if (weighted.isEmpty()) return silence()
            if (weighted.size == 1) return weighted[0].second
            
            val totalWeight = weighted.sumOf { it.first }
            if (totalWeight <= 0) return silence()
            
            return Pattern { arc ->
                val events = mutableListOf<Event<T>>()
                
                // Calculate time boundaries for each pattern
                var position = 0.0
                for ((weight, pattern) in weighted) {
                    val fraction = weight / totalWeight
                    val patternStart = position
                    val patternEnd = position + fraction
                    position = patternEnd
                    
                    // For each cycle in the query
                    val startCycle = kotlin.math.floor(arc.start).toInt()
                    val endCycle = kotlin.math.ceil(arc.end).toInt()
                    
                    for (cycle in startCycle until endCycle) {
                        // Map the pattern's slice to absolute time
                        val absoluteStart = cycle + patternStart
                        val absoluteEnd = cycle + patternEnd
                        
                        // Check if this segment intersects with the query
                        if (absoluteEnd <= arc.start || absoluteStart >= arc.end) continue
                        
                        // Query the inner pattern in normalized time [0, 1)
                        // We need to scale time: pattern time / fraction = real time
                        val queryStart = ((arc.start - absoluteStart).coerceAtLeast(0.0)) / fraction
                        val queryEnd = ((arc.end - absoluteStart).coerceAtMost(fraction)) / fraction
                        
                        if (queryEnd <= queryStart) continue
                        
                        val innerEvents = pattern.query(Arc(queryStart, queryEnd))
                        
                        // Map events back to absolute time
                        for (event in innerEvents) {
                            val mappedWhole = event.whole?.let { w ->
                                Arc(
                                    absoluteStart + w.start * fraction,
                                    absoluteStart + w.end * fraction
                                )
                            }
                            val mappedPart = Arc(
                                absoluteStart + event.part.start * fraction,
                                absoluteStart + event.part.end * fraction
                            )
                            events.add(Event(mappedWhole, mappedPart, event.value))
                        }
                    }
                }
                
                events
            }
        }
    }
    
    /**
     * Map a function over the pattern's events.
     */
    fun <R> fmap(f: (T) -> R): Pattern<R> = Pattern { arc ->
        query(arc).map { event -> event.fmap(f) }
    }
    
    /**
     * Speed up the pattern by a factor.
     */
    fun fast(factor: Double): Pattern<T> = Pattern { arc ->
        val scaledArc = Arc(arc.start * factor, arc.end * factor)
        query(scaledArc).map { event ->
            Event(
                event.whole?.let { Arc(it.start / factor, it.end / factor) },
                Arc(event.part.start / factor, event.part.end / factor),
                event.value
            )
        }
    }
    
    /**
     * Slow down the pattern by a factor.
     */
    fun slow(factor: Double): Pattern<T> = fast(1.0 / factor)
    
    /**
     * Apply a function to every nth cycle.
     */
    fun every(n: Int, f: (Pattern<T>) -> Pattern<T>): Pattern<T> {
        val transformed = f(this)
        return Pattern { arc ->
            val startCycle = kotlin.math.floor(arc.start).toInt()
            val endCycle = kotlin.math.ceil(arc.end).toInt()
            
            (startCycle until endCycle).flatMap { cycle ->
                val cycleArc = Arc(cycle.toDouble(), (cycle + 1).toDouble())
                val intersection = cycleArc.sect(arc)
                if (intersection != null) {
                    val pattern = if (cycle % n == 0) transformed else this
                    pattern.query(intersection)
                } else {
                    emptyList()
                }
            }
        }
    }
    
    /**
     * Shift the pattern in time (rotate).
     */
    fun late(amount: Double): Pattern<T> = Pattern { arc ->
        query(arc.shift(-amount)).map { it.shift(amount) }
    }
    
    /**
     * Shift the pattern backward in time.
     */
    fun early(amount: Double): Pattern<T> = late(-amount)
    
    /**
     * Reverse the pattern within each cycle.
     */
    fun rev(): Pattern<T> = Pattern { arc ->
        val startCycle = kotlin.math.floor(arc.start).toInt()
        val endCycle = kotlin.math.ceil(arc.end).toInt()
        
        (startCycle until endCycle).flatMap { cycle ->
            val cycleArc = Arc(cycle.toDouble(), (cycle + 1).toDouble())
            cycleArc.sect(arc)?.let { intersection ->
                // Mirror the query arc within the cycle
                val mirroredStart = cycle + 1 - (intersection.end - cycle)
                val mirroredEnd = cycle + 1 - (intersection.start - cycle)
                val mirroredArc = Arc(mirroredStart, mirroredEnd)
                
                query(mirroredArc).map { event ->
                    // Mirror event times
                    Event(
                        event.whole?.let { whole ->
                            Arc(cycle + 1 - whole.end + cycle, cycle + 1 - whole.start + cycle)
                        },
                        Arc(
                            cycle + 1 - event.part.end + cycle,
                            cycle + 1 - event.part.start + cycle
                        ),
                        event.value
                    )
                }
            } ?: emptyList()
        }
    }
    
    /**
     * Filter events where the predicate is true.
     */
    fun filterValues(predicate: (T) -> Boolean): Pattern<T> = Pattern { arc ->
        query(arc).filter { predicate(it.value) }
    }
    
    /**
     * Only keep events that have an onset.
     */
    fun filterOnsets(): Pattern<T> = Pattern { arc ->
        query(arc).filter { it.hasOnset() }
    }
}

// Extension functions for fluent API
infix fun <T> Pattern<T>.fast(factor: Double): Pattern<T> = this.fast(factor)
infix fun <T> Pattern<T>.fast(factor: Int): Pattern<T> = this.fast(factor.toDouble())
infix fun <T> Pattern<T>.slow(factor: Double): Pattern<T> = this.slow(factor)
infix fun <T> Pattern<T>.slow(factor: Int): Pattern<T> = this.slow(factor.toDouble())
