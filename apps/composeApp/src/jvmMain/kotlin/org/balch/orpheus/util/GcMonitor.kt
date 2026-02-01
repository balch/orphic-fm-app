package org.balch.orpheus.util

import com.diamondedge.logging.logging
import com.sun.management.GarbageCollectionNotificationInfo
import java.lang.management.ManagementFactory
import javax.management.NotificationEmitter
import javax.management.NotificationListener
import javax.management.openmbean.CompositeData

object GcMonitor {
    private val logger = logging("GcMonitor")

    fun install() {
        if (System.getProperty("orpheus.debug.gc") != "true") return

        logger.info { "Initializing GC Monitor..." }
        val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
        for (gcBean in gcBeans) {
            val emitter = gcBean as NotificationEmitter
            val listener = NotificationListener { notification, _ ->
                if (notification.type == GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) {
                    val info = GarbageCollectionNotificationInfo.from(notification.userData as CompositeData)
                    val gcInfo = info.gcInfo
                    val duration = gcInfo.duration
                    val action = info.gcAction
                    val cause = info.gcCause
                    val name = info.gcName
                    
                    // Log to standard out for visibility
                    logger.info { " [GC] $name: $action ($cause) took ${duration}ms" }
                    
                    // Optional: Print memory stats if it was a long pause
                    if (duration > 10) {
                         val before = gcInfo.memoryUsageBeforeGc
                         val after = gcInfo.memoryUsageAfterGc
                         // Sum up heap usage
                         // MemoryUsage is implicitly typed, access .used
                         val usedBefore = before.values.sumOf { it.used } / 1024 / 1024
                         val usedAfter = after.values.sumOf { it.used } / 1024 / 1024
                         logger.info { "      Heap: ${usedBefore}MB -> ${usedAfter}MB" }
                    }
                }
            }
            emitter.addNotificationListener(listener, null, null)
        }
    }
}
