package org.balch.orpheus.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph

/**
 * WASM concrete graph. Declared in wasmJsMain so Metro can see the wasmJsMain contributions
 * (`WasmRepositoryModule`, `WasmNativeAudioEngine`, `WasmDispatcherProvider`, `WasmTtsGenerator`,
 * the WASM `VibeCatalogPolicyProvider`). Common members, including the eager Pulsar roots, are
 * inherited from [OrpheusGraph]; only the factory is declared here.
 */
@DependencyGraph(AppScope::class)
interface OrpheusGraphWasm : OrpheusGraph {

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(): OrpheusGraphWasm
    }
}
