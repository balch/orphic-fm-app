package org.balch.orpheus.djapp.di

import org.balch.orpheus.features.pulsar.bench.MixBenchController

// Nothing to do here: the meters stay silent (NoMeterTap) and the process ends with the engine.
internal actual fun stopOnAppExit(controller: MixBenchController) {}
