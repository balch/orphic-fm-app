package org.balch.orpheus.features.timer

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

@SingleIn(AppScope::class)
@Inject
@ContributesBinding(AppScope::class, binding = binding<TimerWidgetNotifier>())
class WasmTimerWidgetNotifier : TimerWidgetNotifier by NoOpTimerWidgetNotifier()
