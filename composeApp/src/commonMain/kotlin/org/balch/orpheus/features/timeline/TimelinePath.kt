package org.balch.orpheus.features.timeline

/**
 * A single point on a timeline automation path.
 *
 * @param time Normalized position on timeline (0.0 = start, 1.0 = end)
 * @param value Normalized parameter value (0.0 to 1.0)
 */
data class TimelinePoint(
    val time: Float,
    val value: Float
)

/**
 * A complete automation path consisting of ordered points.
 *
 * @param points Ordered list of points from start to end
 * @param isComplete True if path has been finalized (connected to end)
 */
data class TimelinePath(
    val points: List<TimelinePoint> = emptyList(),
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
    fun withPointAdded(point: TimelinePoint): TimelinePath {
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
    fun withPointsRemovedAfter(time: Float): TimelinePath {
        return copy(
            points = points.filter { it.time <= time },
            isComplete = false
        )
    }

    /**
     * Complete the path by extending to the end with the last value.
     */
    fun completed(endValue: Float? = null): TimelinePath {
        if (points.isEmpty()) return this

        val lastPoint = points.last()
        val finalValue = endValue ?: lastPoint.value

        // If already at the end, just mark complete
        if (lastPoint.time >= 1.0f) {
            return copy(isComplete = true)
        }

        // Add end point
        return copy(
            points = points + TimelinePoint(time = 1.0f, value = finalValue),
            isComplete = true
        )
    }

    /**
     * Start a new path from the beginning, connecting to the touch point.
     */
    fun startedAt(point: TimelinePoint): TimelinePath {
        val startPoint = TimelinePoint(time = 0f, value = point.value)
        return copy(
            points = listOf(startPoint, point),
            isComplete = false
        )
    }
}
