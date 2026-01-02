package org.balch.orpheus.features.tweaker

/**
 * A single point on a sequencer automation path.
 *
 * @param time Normalized position on sequencer (0.0 = start, 1.0 = end)
 * @param value Normalized parameter value (0.0 to 1.0)
 */
data class SequencerPoint(
    val time: Float,
    val value: Float
)

/**
 * A complete automation path consisting of ordered points.
 *
 * @param points Ordered list of points from start to end
 * @param isComplete True if path has been finalized (connected to end)
 */
data class SequencerPath(
    val points: List<SequencerPoint> = emptyList(),
    val isComplete: Boolean = false
) {
    /**
     * Get the interpolated value at a given time position.
     * Uses linear interpolation between points.
     *
     * @param time Normalized time position (0.0 to 1.0)
     * @return Interpolated value, or null if path is empty
     */
    fun valueAt(time: Float): Float? {
        if (points.isEmpty()) return null

        // Before first point, return first value
        val firstPoint = points.first()
        if (time <= firstPoint.time) return firstPoint.value

        // After last point, return last value
        val lastPoint = points.last()
        if (time >= lastPoint.time) return lastPoint.value

        // Find surrounding points and interpolate
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            if (time >= p1.time && time <= p2.time) {
                val t = (time - p1.time) / (p2.time - p1.time)
                return p1.value + t * (p2.value - p1.value)
            }
        }

        return lastPoint.value
    }

    /**
     * Create a new path with a point added at the correct position.
     * If the point's time is before existing points, those points are removed.
     */
    fun withPointAdded(point: SequencerPoint): SequencerPath {
        // Remove any points at or after this time
        val filtered = points.filter { it.time < point.time }
        return copy(
            points = filtered + point,
            isComplete = false
        )
    }

    /**
     * Remove all points after the given time.
     */
    fun withPointsRemovedAfter(time: Float): SequencerPath {
        return copy(
            points = points.filter { it.time <= time },
            isComplete = false
        )
    }

    /**
     * Complete the path by extending to the end with the last value.
     */
    fun completed(endValue: Float? = null): SequencerPath {
        if (points.isEmpty()) return this

        val lastPoint = points.last()
        val finalValue = endValue ?: lastPoint.value

        // If already at the end, just mark complete
        if (lastPoint.time >= 1.0f) {
            return copy(isComplete = true)
        }

        // Add end point
        return copy(
            points = points + SequencerPoint(time = 1.0f, value = finalValue),
            isComplete = true
        )
    }

    /**
     * Start a new path from the beginning, connecting to the touch point.
     */
    fun startedAt(point: SequencerPoint): SequencerPath {
        val startPoint = SequencerPoint(time = 0f, value = point.value)
        return copy(
            points = listOf(startPoint, point),
            isComplete = false
        )
    }

    /**
     * Applies a simple smoothing algorithm to the path to reduce jaggedness
     * while retaining the overall shape.
     */
    fun smoothed(): SequencerPath {
        if (points.size < 3) return this

        val newPoints = ArrayList<SequencerPoint>(points.size)
        newPoints.add(points.first()) // Keep start anchor

        // 3-point moving average for inner points
        // We iterate from 1 to size-2
        for (i in 1 until points.size - 1) {
            val prev = points[i - 1]
            val curr = points[i]
            val next = points[i + 1]

            // Average the values, but keep the time of the current point
            // This assumes points are relatively evenly spaced in time or close enough
            // that time-shifting isn't desired.
            val smoothedValue = (prev.value + curr.value + next.value) / 3f
            newPoints.add(curr.copy(value = smoothedValue))
        }

        newPoints.add(points.last()) // Keep end anchor

        return copy(points = newPoints)
    }
}
