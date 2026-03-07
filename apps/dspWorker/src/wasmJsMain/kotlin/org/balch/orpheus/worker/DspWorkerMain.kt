@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.balch.orpheus.worker

import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main() {
    val graph = createGraphFactory<WorkerGraph.Factory>().create()
    val dispatcher = CommandDispatcher(graph.synthEngine)

    jsInstallCommandHandler { cmd, data ->
        dispatcher.dispatch(cmd, data)
    }

    // Monitoring bridge: periodically send CPU load to main thread
    val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    monitorScope.launch {
        while (true) {
            delay(200) // 5Hz monitoring rate — minimize event loop contention
            jsPostMonitorData(graph.audioEngine.getCpuLoad())
        }
    }

    jsPostReady()
}

private fun jsInstallCommandHandler(handler: (Int, JsAny) -> Unit): Unit =
    js("self.onmessage = function(e) { handler(e.data.cmd | 0, e.data) }")
