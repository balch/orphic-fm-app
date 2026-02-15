package org.balch.orpheus.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import com.diamondedge.logging.logging
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.input.KeyBinding

private val log = logging("SynthKeyActions")

/**
 * Collects [KeyBinding]s from all provided features' [SynthFeature.keyBindings].
 * Returns a map of Key to list of actionable bindings, supporting multiple bindings
 * per key (e.g. shift vs non-shift variants). The handler uses [KeyBinding.requiresShift]
 * and [KeyBinding.eventType] to select the correct binding at dispatch time.
 */
@Composable
fun rememberSynthKeyActions(
    features: Set<SynthFeature<*, *>>,
): Map<Key, List<KeyBinding>> = remember(features) {
    buildMap<Key, MutableList<KeyBinding>> {
        for (feature in features) {
            for (binding in feature.keyBindings) {
                if (binding.action == null) continue
                val list = getOrPut(binding.key) { mutableListOf() }

                // Warn on collision: two bindings for the same key + shift combo
                val conflict = list.any { it.requiresShift == binding.requiresShift }
                if (conflict) {
                    log.warn {
                        "Key binding collision: ${binding.label} (requiresShift=${binding.requiresShift}) " +
                            "conflicts with existing binding for same key+shift combo"
                    }
                }
                list.add(binding)
            }
        }
    }
}
