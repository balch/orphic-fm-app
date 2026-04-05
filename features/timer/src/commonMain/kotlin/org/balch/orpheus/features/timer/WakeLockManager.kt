package org.balch.orpheus.features.timer

expect class WakeLockManager() {
    fun acquire()
    fun release()
}
