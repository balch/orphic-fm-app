package org.balch.orpheus.worker

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo

/**
 * Metro module providing dependencies that are normally supplied
 * by OrpheusModule in composeApp but are missing in the Worker context.
 *
 * The Worker does not have MIDI, UI, or Compose — only the DSP engine
 * and its transitive dependencies.
 *
 * Note: DispatcherProvider is now contributed via @ContributesBinding on
 * DefaultDispatcherProvider in core:foundation.
 */
@ContributesTo(AppScope::class)
interface WorkerModule
