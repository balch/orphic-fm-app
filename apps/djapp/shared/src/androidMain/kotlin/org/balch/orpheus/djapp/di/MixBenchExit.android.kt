package org.balch.orpheus.djapp.di

import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.StemRecorder

// Nothing to do here: the process ends with the engine, so no exit hook is needed.
internal actual fun stopOnAppExit(controller: MixBenchController) {}

// A capture dies with the process and the engine, so no exit hook is needed.
internal actual fun closeOnAppExit(recorder: StemRecorder) {}
