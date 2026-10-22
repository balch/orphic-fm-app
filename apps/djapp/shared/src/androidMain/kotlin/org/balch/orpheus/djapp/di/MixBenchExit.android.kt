package org.balch.orpheus.djapp.di

import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.StemRecorder

// Nothing to do here: the meters stay silent (NoMeterTap) and the process ends with the engine.
internal actual fun stopOnAppExit(controller: MixBenchController) {}

// No stem ring here (NoStemTap), so no capture can outlive the engine.
internal actual fun closeOnAppExit(recorder: StemRecorder) {}
