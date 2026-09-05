package org.balch.orpheus.core.gestures

import com.diamondedge.logging.KmLog
import kotlin.concurrent.Volatile

/**
 * Opt-in gate for the per-frame diagnostics in [AslSignClassifier] and [GestureInterpreter].
 *
 * Both run once per camera frame per tracked hand, so leaving their logging on costs a
 * formatted string and a log line ~8x/second. A log level alone can't gate this: KmLogging's
 * inline level checks read a global flag that is ORed across every installed logger, and the
 * default PlatformLogger(FixedLogLevel(true)) leaves all of them true.
 */
object GestureLogging {
    /** Set true from app or debug code to trace gesture recognition. */
    @Volatile
    var frameLogging: Boolean = false
}

/** Per-frame diagnostic log. Inline, so the message is never built while [GestureLogging.frameLogging] is off. */
inline fun KmLog.frameDebug(msg: () -> Any?) {
    if (GestureLogging.frameLogging) debug(msg)
}
