package org.balch.orpheus.features.timer

import com.diamondedge.logging.logging

actual class WakeLockManager actual constructor() {
    private val log = logging("WakeLockManager")
    private var caffeinateProcess: Process? = null

    actual fun acquire() {
        if (caffeinateProcess != null) return
        try {
            val os = System.getProperty("os.name").lowercase()
            when {
                os.contains("mac") -> {
                    caffeinateProcess = ProcessBuilder("caffeinate", "-i")
                        .redirectErrorStream(true).start()
                    log.debug { "WakeLock acquired via caffeinate" }
                }
                os.contains("linux") -> {
                    caffeinateProcess = ProcessBuilder(
                        "systemd-inhibit", "--what=idle", "--who=Orpheus",
                        "--why=Sleep timer active", "sleep", "infinity"
                    ).redirectErrorStream(true).start()
                    log.debug { "WakeLock acquired via systemd-inhibit" }
                }
                os.contains("windows") -> {
                    caffeinateProcess = ProcessBuilder(
                        "powershell", "-Command",
                        "[System.Runtime.InteropServices.Marshal]::SetThreadExecutionState(0x80000003)"
                    ).redirectErrorStream(true).start()
                    log.debug { "WakeLock acquired via SetThreadExecutionState" }
                }
                else -> log.warn { "WakeLock not supported on $os" }
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to acquire wake lock" }
        }
    }

    actual fun release() {
        caffeinateProcess?.let {
            it.destroyForcibly()
            caffeinateProcess = null
            log.debug { "WakeLock released" }
        }
    }
}
